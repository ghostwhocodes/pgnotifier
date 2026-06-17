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
import pgnotifier.util.ShutdownUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates one or more logical replication workers backed by PostgreSQL logical replication slots.
 * <p>
 * Instances are created via {@link PgNotifierBuilder}, configured with one or more {@link SlotConfig}
 * entries and handler callbacks. Once created, the notifier manages the lifecycle of the underlying
 * worker threads and shuts them down via a JVM shutdown hook.
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 * PgNotifier notifier = new PgNotifierBuilder()
 *         .addSlot("user", "password", "jdbc:postgresql://localhost/postgres", "demo_slot")
 *         .changeHandler(event -> {
 *             System.out.println("Received change: " + event.payload());
 *             return ProcessingDecision.COMMIT;
 *         })
 *         .errorHandler(context -> {
 *             context.exception().printStackTrace();
 *             return ErrorHandlingDecision.dropAndContinue(context);
 *         })
 *         .build()
 *         .start();
 *
 * // later, on shutdown
 * notifier.stop();
 * }</pre>
 *
 * @author Nos Doughty
 */
public class PgNotifier {

    public record SlotConfig(
            String username, String password,
            String database, String slotname) {}

    public record ProcessConfig(
            int poolSize,
            int errorBackoffSeconds,
            int shutdownTimeoutSeconds,
            long sleepMillis,
            long throttleMillis) {

        public static final long DEFAULT_SLEEP_MILLIS = 1000L;
        public static final long DEFAULT_THROTTLE_MILLIS = 100L;

    }

    public record PluginConfig(
            int statusIntervalSeconds,
            @Nullable String includeTables) {

        public static PluginConfig defaults() {
            return new PluginConfig(10, null);
        }

    }

    private final List<SlotRuntime> slotRuntimes;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();

    PgNotifier(final List<SlotRuntime> slotRuntimes) {
        this.slotRuntimes = List.copyOf(Objects.requireNonNull(slotRuntimes));

    }

    /**
     * Starts all configured replication workers in the background.
     *
     * @return this notifier instance for fluent chaining
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * PgNotifier notifier = new PgNotifierBuilder()
     *         .addSlot(slotConfig)
     *         .changeHandler(event -> ProcessingDecision.COMMIT)
     *         .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
     *         .build();
     *
     * notifier.start();
     * }</pre>
     */
    public PgNotifier start() {

        if (this.shutdownHookRegistered.compareAndSet(false, true)) {
            ShutdownUtil.addShutdownHook(this::stop);
        }

        for (SlotRuntime slotRuntime : this.slotRuntimes) {
            slotRuntime.start();
        }

        return this;

    }

    /**
     * Stops all running replication workers and waits for them to terminate.
     *
     * @return this notifier instance for fluent chaining
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * PgNotifier notifier = ...;
     * try {
     *     notifier.start();
     *     // application logic
     * } finally {
     *     notifier.stop();
     * }
     * }</pre>
     */
    public PgNotifier stop() {

        for (SlotRuntime slotRuntime : this.slotRuntimes) {
            slotRuntime.stop();
        }

        return this;

    }

    /**
     * Returns a snapshot of health information for all configured slots.
     *
     * @return immutable list of per-slot health snapshots
     */
    public List<SlotHealthSnapshot> health() {

        final List<SlotHealthSnapshot> snapshots = new ArrayList<>(this.slotRuntimes.size());

        for (SlotRuntime slotRuntime : this.slotRuntimes) {
            snapshots.add(slotRuntime.health());
        }

        return List.copyOf(snapshots);

    }

    /**
     * Immutable view of the last worker-level failure seen for a slot.
     *
     * @param epochMillis timestamp of the failure (epoch millis)
     * @param recovered   whether the failure has subsequently recovered and is now historical
     * @param origin      whether the failure came from replication, the change handler, or the error handler
     * @param errorType   transient/permanent classification
     * @param className   fully qualified exception class name
     * @param message     exception message
     */
    public record WorkerFailureSnapshot(
            @Nullable Long epochMillis,
            boolean recovered,
            @Nullable ErrorOrigin origin,
            @Nullable ErrorType errorType,
            @Nullable String className,
            @Nullable String message) {
    }

    /**
     * Immutable view of the last outer process failure seen by the lifecycle wrapper.
     *
     * @param epochMillis timestamp of the failure (epoch millis)
     * @param recovered   whether the process has subsequently recovered from that failure
     * @param className   fully qualified exception class name
     * @param message     exception message
     */
    public record ProcessFailureSnapshot(
            @Nullable Long epochMillis,
            boolean recovered,
            @Nullable String className,
            @Nullable String message) {
    }

    /**
     * Immutable view of health information for a single logical replication slot.
     *
     * @param slot                           logical replication slot name associated with the worker
     * @param state                          current lifecycle state of the worker process
     * @param running                        whether the worker process is running
     * @param failureCount                   current failure streak for the worker
     * @param lastSuccessfulEventEpochMillis timestamp of the last successfully processed event (epoch millis)
     * @param lastPersistedLsn               last persisted LSN after successful processing
     * @param handlerQueueDepth              current async handler queue depth
     * @param handlerQueueCapacity           configured async handler queue capacity
     * @param observedReplicationLagBytes    worker-observed lag as max(0, lastReceivedLsn - lastPersistedLsn)
     * @param lastWorkerFailure              last failure seen inside the replication worker
     * @param lastProcessFailure             last failure seen by the outer process lifecycle wrapper
     */
    public record SlotHealthSnapshot(
            String slot,
            ProcessState state,
            boolean running,
            int failureCount,
            @Nullable Long lastSuccessfulEventEpochMillis,
            @Nullable Long lastPersistedLsn,
            int handlerQueueDepth,
            int handlerQueueCapacity,
            @Nullable Long observedReplicationLagBytes,
            @Nullable WorkerFailureSnapshot lastWorkerFailure,
            @Nullable ProcessFailureSnapshot lastProcessFailure) {
    }

}
