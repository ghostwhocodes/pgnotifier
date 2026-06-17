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

import org.jspecify.annotations.Nullable;

/**
 * Mutable health state for a single logical replication slot.
 * <p>
 * Instances are updated by workers and read via {@link PgNotifier}
 * health snapshots to drive HTTP health endpoints or readiness checks.
 *
 * @author Nos Doughty
 */
public class SlotHealth {

    private volatile int failureStreak;

    private volatile @Nullable Long lastSuccessfulEventEpochMillis;

    private volatile @Nullable Long lastWorkerFailureEpochMillis;

    private volatile boolean lastWorkerFailureRecovered;

    private volatile @Nullable ErrorOrigin lastWorkerFailureOrigin;

    private volatile @Nullable ErrorType lastWorkerFailureType;

    private volatile @Nullable String lastWorkerFailureClassName;

    private volatile @Nullable String lastWorkerFailureMessage;

    private volatile @Nullable Long lastPersistedLsn;

    private volatile int handlerQueueDepth;

    private volatile int handlerQueueCapacity;

    private volatile @Nullable Long observedReplicationLagBytes;

    /**
     * Records the timestamp of the last successfully processed event.
     *
     * @param epochMillis event completion time in epoch milliseconds
     */
    public void markSuccessfulEvent(final long epochMillis) {
        this.failureStreak = 0;
        this.lastSuccessfulEventEpochMillis = epochMillis;
        if (this.lastWorkerFailureEpochMillis != null) {
            this.lastWorkerFailureRecovered = true;
        }
    }

    /**
     * Records the current process failure streak for this slot.
     *
     * @param failureStreak consecutive process failures observed for the slot
     */
    public void recordFailureStreak(final int failureStreak) {
        this.failureStreak = Math.max(0, failureStreak);
    }

    /**
     * Records handler queue occupancy for asynchronous handler execution.
     *
     * @param depth    current queue depth
     * @param capacity configured queue capacity
     */
    public void recordHandlerQueueDepth(final int depth, final int capacity) {
        this.handlerQueueDepth = Math.max(0, depth);
        this.handlerQueueCapacity = Math.max(0, capacity);
    }

    /**
     * Records the last LSN persisted after event processing.
     *
     * @param lsn persisted LSN
     */
    public void recordLastPersistedLsn(final long lsn) {
        this.lastPersistedLsn = lsn;
    }

    /**
     * Records worker-observed lag between the last received and last persisted LSNs.
     *
     * @param lagBytes lag in bytes
     */
    public void recordObservedReplicationLagBytes(final long lagBytes) {
        this.observedReplicationLagBytes = Math.max(0L, lagBytes);
    }

    /**
     * Records the last worker-level failure cause in a safe, serialisable form.
     *
     * @param context the failure that occurred (must not be {@code null})
     */
    public void recordWorkerFailure(final ErrorContext context) {
        this.recordFailure(context.exception(), context.origin(), context.errorType());
    }

    /**
     * Records the last worker-level failure cause without structured origin/type metadata.
     *
     * @param cause the failure that occurred (may be {@code null} to clear)
     */
    public void recordFailure(final @Nullable Throwable cause) {
        this.recordFailure(cause, null, null);
    }

    private void recordFailure(
            final @Nullable Throwable cause,
            final @Nullable ErrorOrigin origin,
            final @Nullable ErrorType errorType) {

        if (cause == null) {
            this.lastWorkerFailureEpochMillis = null;
            this.lastWorkerFailureRecovered = false;
            this.lastWorkerFailureOrigin = null;
            this.lastWorkerFailureType = null;
            this.lastWorkerFailureClassName = null;
            this.lastWorkerFailureMessage = null;
        } else {
            this.lastWorkerFailureEpochMillis = System.currentTimeMillis();
            this.lastWorkerFailureRecovered = false;
            this.lastWorkerFailureOrigin = origin;
            this.lastWorkerFailureType = errorType;
            this.lastWorkerFailureClassName = cause.getClass().getName();
            this.lastWorkerFailureMessage = cause.getMessage();
        }
    }

    /**
     * Marks the last worker-level failure as recovered while keeping it available for diagnostics.
     */
    public void markWorkerFailureRecovered() {
        if (this.lastWorkerFailureEpochMillis != null) {
            this.lastWorkerFailureRecovered = true;
        }
    }

    /**
     * Timestamp of the last successfully processed event, if any.
     *
     * @return epoch milliseconds or {@code null} when no event has been processed yet
     */
    public @Nullable Long lastSuccessfulEventEpochMillis() {
        return lastSuccessfulEventEpochMillis;
    }

    /**
     * Current process failure streak for this slot.
     *
     * @return number of consecutive process failures observed for the slot
     */
    public int failureStreak() {
        return failureStreak;
    }

    /**
     * Timestamp of the last worker-level failure that occurred for this slot.
     *
     * @return epoch milliseconds or {@code null} if no worker-level failure has been recorded
     */
    public @Nullable Long lastWorkerFailureEpochMillis() {
        return lastWorkerFailureEpochMillis;
    }

    /**
     * Whether the last worker-level failure has subsequently recovered.
     *
     * @return {@code true} if the failure is historical rather than currently active
     */
    public boolean lastWorkerFailureRecovered() {
        return lastWorkerFailureRecovered;
    }

    /**
     * Origin of the last worker-level failure that occurred for this slot.
     *
     * @return worker failure origin or {@code null}
     */
    public @Nullable ErrorOrigin lastWorkerFailureOrigin() {
        return lastWorkerFailureOrigin;
    }

    /**
     * Classification of the last worker-level failure that occurred for this slot.
     *
     * @return worker failure type or {@code null}
     */
    public @Nullable ErrorType lastWorkerFailureType() {
        return lastWorkerFailureType;
    }

    /**
     * Class name of the last worker-level failure that occurred for this slot.
     *
     * @return fully qualified exception class name or {@code null}
     */
    public @Nullable String lastWorkerFailureClassName() {
        return lastWorkerFailureClassName;
    }

    /**
     * Message of the last worker-level failure that occurred for this slot.
     *
     * @return exception message or {@code null}
     */
    public @Nullable String lastWorkerFailureMessage() {
        return lastWorkerFailureMessage;
    }

    /**
     * Last persisted LSN, if any.
     *
     * @return LSN or {@code null} when no LSN has been persisted yet
     */
    public @Nullable Long lastPersistedLsn() {
        return lastPersistedLsn;
    }

    /**
     * Current async handler queue depth.
     *
     * @return number of queued events
     */
    public int handlerQueueDepth() {
        return handlerQueueDepth;
    }

    /**
     * Configured async handler queue capacity.
     *
     * @return queue capacity, or {@code 0} for inline handlers
     */
    public int handlerQueueCapacity() {
        return handlerQueueCapacity;
    }

    /**
     * Worker-observed replication lag in bytes.
     *
     * @return lag in bytes, or {@code null} when no LSN has been persisted yet
     */
    public @Nullable Long observedReplicationLagBytes() {
        return observedReplicationLagBytes;
    }

}
