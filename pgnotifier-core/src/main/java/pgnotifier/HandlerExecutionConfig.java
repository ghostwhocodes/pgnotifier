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

import java.util.Objects;

/**
 * Configuration for how {@link ChangeEvent} handler invocations are executed.
 * <p>
 * By default, handlers run inline on the replication thread. When configured
 * with {@link Mode#ASYNC_QUEUE}, the replication loop:
 * <ul>
 *     <li>Reads from the logical replication stream,</li>
 *     <li>Decodes {@link ChangeEvent}s,</li>
 *     <li>Enqueues them into a bounded internal queue.</li>
 * </ul>
 * One or more worker threads drain the queue and invoke user {@link ChangeHandler}s.
 *
 * @param mode                execution mode for handlers
 * @param workerThreads       number of worker threads when using {@link Mode#ASYNC_QUEUE}
 * @param queueCapacity       bounded capacity of the internal queue
 * @param queueOverflowPolicy policy applied when the queue is full
 */
public record HandlerExecutionConfig(
        Mode mode,
        int workerThreads,
        int queueCapacity,
        QueueOverflowPolicy queueOverflowPolicy) {

    /**
     * Execution mode for handler invocations.
     */
    public enum Mode {
        /**
         * Handlers run synchronously on the replication thread.
         */
        INLINE,

        /**
         * Handlers run asynchronously on a separate worker pool, fed by a bounded queue.
         */
        ASYNC_QUEUE
    }

    /**
     * Policy applied when the internal handler queue is full.
     */
    public enum QueueOverflowPolicy {
        /**
         * Block the replication thread until space becomes available.
         */
        BLOCK,

        /**
         * Drop the oldest queued event and enqueue the new one.
         */
        DROP_OLDEST,

        /**
         * Drop the newest event (the one being enqueued).
         */
        DROP_NEWEST,

        /**
         * Signal a fatal error and let the restart policy decide what happens next.
         */
        FATAL
    }

    private static final int DEFAULT_WORKER_THREADS = 1;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    public HandlerExecutionConfig {
        Objects.requireNonNull(mode, "Handler execution mode must be provided");
        Objects.requireNonNull(queueOverflowPolicy, "Queue overflow policy must be provided");

        if (mode == Mode.ASYNC_QUEUE) {
            if (workerThreads <= 0) {
                throw new IllegalArgumentException("workerThreads must be positive when using ASYNC_QUEUE mode");
            }
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException("queueCapacity must be positive when using ASYNC_QUEUE mode");
            }
        }
    }

    /**
     * Returns a configuration that executes handlers inline on the replication thread.
     */
    public static HandlerExecutionConfig inline() {
        return new HandlerExecutionConfig(
                Mode.INLINE,
                DEFAULT_WORKER_THREADS,
                DEFAULT_QUEUE_CAPACITY,
                QueueOverflowPolicy.BLOCK);
    }

    /**
     * Returns an asynchronous queue-based configuration with the given parameters.
     *
     * @param workerThreads       number of handler worker threads
     * @param queueCapacity       bounded queue capacity
     * @param queueOverflowPolicy behaviour when the queue is full
     */
    public static HandlerExecutionConfig asyncQueue(
            final int workerThreads,
            final int queueCapacity,
            final QueueOverflowPolicy queueOverflowPolicy) {

        return new HandlerExecutionConfig(
                Mode.ASYNC_QUEUE,
                workerThreads,
                queueCapacity,
                Objects.requireNonNull(queueOverflowPolicy));
    }

}

