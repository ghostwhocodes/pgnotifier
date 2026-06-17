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

package pgnotifier;

/**
 * Pluggable metrics hook for {@link PgNotifier} and its workers.
 * <p>
 * Implementations can bridge to Micrometer, Dropwizard, or other metrics
 * libraries without the core module taking a hard dependency on them.
 * <p>
 * All methods have default no-op implementations so callers can safely
 * pass {@link #noop()} when metrics are not required.
 *
 * @author Nos Doughty
 */
public interface NotifierMetrics {

    /**
     * Called when a change event has been successfully processed for a slot.
     *
     * @param slotName logical replication slot name
     */
    default void onEventProcessed(final String slotName) {
        // noop
    }

    /**
     * Called when the change handler throws while processing an event.
     *
     * @param slotName logical replication slot name
     * @param cause    exception that was thrown
     */
    default void onHandlerFailure(final String slotName, final Throwable cause) {
        // noop
    }

    /**
     * Called when a replication error occurs (e.g. connection or protocol failures).
     *
     * @param slotName logical replication slot name
     * @param cause    exception that was thrown
     */
    default void onReplicationFailure(final String slotName, final Throwable cause) {
        // noop
    }

    /**
     * Called when a worker is scheduled to be restarted after a failure.
     * <p>
     * Implementations can use this to increment restart counters and update
     * failure streak gauges.
     *
     * @param slotName     logical replication slot name
     * @param failureCount number of consecutive failures so far
     */
    default void onProcessRestart(final String slotName, final int failureCount) {
        // noop
    }

    /**
     * Called when the last successful event time is updated for a slot.
     * <p>
     * Implementations can translate the epoch millisecond timestamp into
     * "time since last event" gauges.
     *
     * @param slotName          logical replication slot name
     * @param epochMilli        timestamp of the last successfully processed event
     */
    default void onLastEventTimeUpdated(final String slotName, final long epochMilli) {
        // noop
    }

    /**
     * Called when the current failure streak is updated for a slot.
     *
     * @param slotName     logical replication slot name
     * @param failureCount number of consecutive failures so far
     */
    default void onFailureStreakUpdated(final String slotName, final int failureCount) {
        // noop
    }

    /**
     * Called when the async handler queue depth changes.
     *
     * @param slotName logical replication slot name
     * @param depth    current number of queued events
     * @param capacity configured queue capacity
     */
    default void onHandlerQueueDepthUpdated(final String slotName, final int depth, final int capacity) {
        // noop
    }

    /**
     * Called when an async handler worker drains an event from the queue.
     *
     * @param slotName      logical replication slot name
     * @param latencyMillis elapsed milliseconds between enqueue and dequeue
     */
    default void onHandlerQueueDrainLatency(final String slotName, final long latencyMillis) {
        // noop
    }

    /**
     * Called after a processed LSN is successfully persisted.
     *
     * @param slotName logical replication slot name
     * @param lsn      last persisted LSN
     */
    default void onLastPersistedLsnUpdated(final String slotName, final long lsn) {
        // noop
    }

    /**
     * Called when worker-observed replication lag is recalculated.
     * <p>
     * The value is {@code max(0, lastReceivedLsn - lastPersistedLsn)}.
     *
     * @param slotName logical replication slot name
     * @param lagBytes worker-observed lag in bytes
     */
    default void onReplicationLagBytesUpdated(final String slotName, final long lagBytes) {
        // noop
    }

    /**
     * Returns a metrics implementation that performs no work.
     */
    static NotifierMetrics noop() {
        return NoopNotifierMetrics.INSTANCE;
    }

    /**
     * Trivial no-op implementation used by {@link #noop()}.
     */
    final class NoopNotifierMetrics implements NotifierMetrics {

        private static final NoopNotifierMetrics INSTANCE = new NoopNotifierMetrics();

        private NoopNotifierMetrics() {
        }

    }

}
