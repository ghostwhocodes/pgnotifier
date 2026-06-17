/*
 * Copyright 2016-2026 Nos Doughty
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pgnotifier.internal.replication;

import org.jspecify.annotations.Nullable;
import pgnotifier.ChangeEvent;
import pgnotifier.LsnPersistence;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.SlotHealth;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns event settlement for a single replication worker.
 * <p>
 * Settlement is the point where an event outcome becomes durable: the stream is
 * acknowledged, the acknowledged LSN is persisted, health is updated, and metrics
 * describe the same LSN.
 */
final class EventSettlement {

    private final PgNotifier.SlotConfig slotConfig;
    private final LsnPersistence lsnPersistence;
    private final SlotHealth slotHealth;
    private final NotifierMetrics metrics;
    private final AtomicLong lastReceivedLsn = new AtomicLong();
    private final ConcurrentHashMap<Long, AsyncEvent> asyncEvents = new ConcurrentHashMap<>();

    private volatile long lastSettledAsyncSequence = -1L;

    EventSettlement(
            final PgNotifier.SlotConfig slotConfig,
            final LsnPersistence lsnPersistence,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        this.slotConfig = Objects.requireNonNull(slotConfig);
        this.lsnPersistence = Objects.requireNonNull(lsnPersistence);
        this.slotHealth = Objects.requireNonNull(slotHealth);
        this.metrics = Objects.requireNonNull(metrics);

    }

    void recordReceivedLsn(final long lsn) {
        this.lastReceivedLsn.accumulateAndGet(lsn, Math::max);
    }

    void settleHandledEvent(final ReplicationStream stream, final ChangeEvent event) {
        final long processedLsn = acknowledgeEvent(stream, event);
        persistAcknowledgedLsn(processedLsn);
        recordSuccessfulEvent();
    }

    void settleFailedEventDrop(final ReplicationStream stream, final @Nullable ChangeEvent event) {
        if (event != null) {
            final long processedLsn = acknowledgeEvent(stream, event);
            persistAcknowledgedLsn(processedLsn);
        }
        this.slotHealth.markWorkerFailureRecovered();
    }

    void settleStreamHeadDrop(final ReplicationStream stream) {
        final long processedLsn = stream.markProcessed();
        persistAcknowledgedLsn(processedLsn);
        this.slotHealth.markWorkerFailureRecovered();
    }

    void leaveUnadvancedForRetry() {
        // RETRY intentionally leaves the LSN unacknowledged so PostgreSQL can redeliver it.
    }

    AsyncEvent beginAsyncEvent(final long sequence, final ChangeEvent event) {
        final AsyncEvent asyncEvent = new AsyncEvent(sequence, event);
        final AsyncEvent existing = this.asyncEvents.putIfAbsent(sequence, asyncEvent);
        if (existing != null) {
            throw new IllegalStateException("Duplicate async event sequence: " + sequence);
        }
        return asyncEvent;
    }

    boolean hasPendingAsyncEvents() {
        return !this.asyncEvents.isEmpty();
    }

    void resetAsyncSettlement(final long lastSettledSequence) {
        this.asyncEvents.clear();
        this.lastSettledAsyncSequence = lastSettledSequence;
    }

    void abandonAsyncSettlement(final long lastSettledSequence) {
        resetAsyncSettlement(lastSettledSequence);
    }

    boolean allPendingAsyncEventsCompleted() {
        for (AsyncEvent asyncEvent : this.asyncEvents.values()) {
            if (!asyncEvent.completed()) {
                return false;
            }
        }
        return true;
    }

    boolean asyncSettlementBlockedByNotAdvanced() {
        final AsyncEvent nextEvent = this.asyncEvents.get(this.lastSettledAsyncSequence + 1L);
        return nextEvent != null
                && nextEvent.completed()
                && nextEvent.outcome() == AsyncOutcome.NOT_ADVANCED;
    }

    void settleCompletedAsyncEvents(final ReplicationStream stream, final Runnable afterEachSettlement) {
        long nextSequence = this.lastSettledAsyncSequence + 1L;

        while (true) {
            final AsyncEvent asyncEvent = this.asyncEvents.get(nextSequence);
            if (asyncEvent == null || !asyncEvent.completed()) {
                break;
            }

            if (!settleCompletedAsyncEvent(stream, asyncEvent)) {
                break;
            }

            this.asyncEvents.remove(nextSequence);
            this.lastSettledAsyncSequence = nextSequence;
            afterEachSettlement.run();
            nextSequence++;
        }
    }

    private boolean settleCompletedAsyncEvent(final ReplicationStream stream, final AsyncEvent asyncEvent) {
        return switch (asyncEvent.outcome()) {
            case HANDLED -> {
                settleHandledEvent(stream, asyncEvent.event());
                yield true;
            }
            case FAILED_EVENT_DROPPED -> {
                settleFailedEventDrop(stream, asyncEvent.event());
                yield true;
            }
            case QUEUE_DROPPED -> {
                settleQueueDroppedEvent(stream, asyncEvent.event());
                yield true;
            }
            case NOT_ADVANCED -> {
                leaveUnadvancedForRetry();
                yield false;
            }
        };
    }

    private void settleQueueDroppedEvent(final ReplicationStream stream, final ChangeEvent event) {
        final long processedLsn = acknowledgeEvent(stream, event);
        persistAcknowledgedLsn(processedLsn);
    }

    private long acknowledgeEvent(final ReplicationStream stream, final ChangeEvent event) {
        return stream.markProcessed(event.lsn());
    }

    private void persistAcknowledgedLsn(final long lsn) {
        this.lsnPersistence.persist(this.slotConfig, lsn);
        this.slotHealth.recordLastPersistedLsn(lsn);
        this.metrics.onLastPersistedLsnUpdated(this.slotConfig.slotname(), lsn);

        final long lagBytes = Math.max(0L, this.lastReceivedLsn.get() - lsn);
        this.slotHealth.recordObservedReplicationLagBytes(lagBytes);
        this.metrics.onReplicationLagBytesUpdated(this.slotConfig.slotname(), lagBytes);
    }

    private void recordSuccessfulEvent() {
        final long now = System.currentTimeMillis();
        this.slotHealth.markSuccessfulEvent(now);
        this.metrics.onEventProcessed(this.slotConfig.slotname());
        this.metrics.onFailureStreakUpdated(this.slotConfig.slotname(), 0);
        this.metrics.onLastEventTimeUpdated(this.slotConfig.slotname(), now);
    }

    static final class AsyncEvent {

        private final long sequence;
        private final ChangeEvent event;
        private volatile boolean completed;
        private volatile @Nullable AsyncOutcome outcome;

        private AsyncEvent(final long sequence, final ChangeEvent event) {
            this.sequence = sequence;
            this.event = Objects.requireNonNull(event);
        }

        long sequence() {
            return this.sequence;
        }

        ChangeEvent event() {
            return this.event;
        }

        void handled() {
            complete(AsyncOutcome.HANDLED);
        }

        void failedEventDropped() {
            complete(AsyncOutcome.FAILED_EVENT_DROPPED);
        }

        void queueDropped() {
            complete(AsyncOutcome.QUEUE_DROPPED);
        }

        void notAdvanced() {
            complete(AsyncOutcome.NOT_ADVANCED);
        }

        private void complete(final AsyncOutcome outcome) {
            this.outcome = outcome;
            this.completed = true;
        }

        private boolean completed() {
            return this.completed;
        }

        private AsyncOutcome outcome() {
            return Objects.requireNonNull(this.outcome, "Completed async event has no settlement outcome");
        }

    }

    private enum AsyncOutcome {
        HANDLED,
        FAILED_EVENT_DROPPED,
        QUEUE_DROPPED,
        NOT_ADVANCED
    }

}
