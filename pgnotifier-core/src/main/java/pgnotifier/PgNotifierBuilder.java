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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builder for {@link PgNotifier} instances.
 * <p>
 * This fluent builder lets you register one or more replication slots, configure worker behaviour,
 * and provide change/error handlers before building a {@link PgNotifier}.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * PgNotifier notifier = new PgNotifierBuilder()
 *         .addSlot("user", "password", "jdbc:postgresql://localhost/postgres", "demo_slot")
 *         .processConfig(new PgNotifier.ProcessConfig(
 *                 2,
 *                 5,
 *                 10,
 *                 PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
 *                 PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS))
 *         .changeHandler(event -> {
 *             // handle event
 *             return ProcessingDecision.COMMIT;
 *         })
 *         .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
 *         .build()
 *         .start();
 * }</pre>
 *
 * @author Nos Doughty
 */
public class PgNotifierBuilder {

    private final List<PgNotifier.SlotConfig> slots = new ArrayList<>();
    private PgNotifier.ProcessConfig processConfig = new PgNotifier.ProcessConfig(
            1,
            10,
            10,
            PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
            PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS);
    private HandlerExecutionConfig handlerExecutionConfig = HandlerExecutionConfig.inline();
    private PgNotifier.PluginConfig pluginConfig = PgNotifier.PluginConfig.defaults();
    private RestartPolicy restartPolicy = RestartPolicy.fixed(processConfig.errorBackoffSeconds());
    private boolean restartPolicyOverridden = false;
    private @Nullable ChangeHandler changeHandler;
    private @Nullable ErrorHandler errorHandler;
    private LsnPersistence lsnPersistence = LsnPersistence.noop();
    private NotifierMetrics metrics = NotifierMetrics.noop();
    private SlotRuntime.Factory slotRuntimeFactory = SlotRuntime::create;

    /**
     * Adds a fully configured slot definition.
     *
     * @param slotConfig slot configuration containing credentials and slot metadata
     * @return this builder for fluent chaining
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * PgNotifier.SlotConfig slot = new PgNotifier.SlotConfig(
     *         "user", "password", "jdbc:postgresql://localhost/postgres", "demo_slot");
     * PgNotifierBuilder builder = new PgNotifierBuilder().addSlot(slot);
     * }</pre>
     */
    public PgNotifierBuilder addSlot(final PgNotifier.SlotConfig slotConfig) {
        this.slots.add(Objects.requireNonNull(slotConfig));
        return this;
    }

    /**
     * Convenience overload to add a slot from individual parameters.
     *
     * @param username database username
     * @param password database password
     * @param database JDBC URL for the database
     * @param slotname logical replication slot name
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder addSlot(
            final String username,
            final String password,
            final String database,
            final String slotname) {

        return addSlot(new PgNotifier.SlotConfig(username, password, database, slotname));
    }

    /**
     * Overrides the default process configuration.
     *
     * @param processConfig processing configuration including pool size and backoff
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder processConfig(final PgNotifier.ProcessConfig processConfig) {
        this.processConfig = Objects.requireNonNull(processConfig);
        if (!restartPolicyOverridden) {
            this.restartPolicy = RestartPolicy.fixed(processConfig.errorBackoffSeconds());
        }
        return this;
    }

    /**
     * Configures how {@link ChangeHandler} invocations are executed.
     * <p>
     * When using an asynchronous queue-based configuration, the replication thread
     * only reads from the logical stream, decodes {@link ChangeEvent}s and enqueues
     * them into a bounded internal queue. One or more worker threads drain the queue
     * and invoke user handlers.
     *
     * @param handlerExecutionConfig handler execution configuration
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder handlerExecutionConfig(final HandlerExecutionConfig handlerExecutionConfig) {
        this.handlerExecutionConfig = Objects.requireNonNull(handlerExecutionConfig);
        return this;
    }

    /**
     * Configures handlers to run inline on the replication thread.
     * <p>
     * This is the simplest mode and is suitable when handlers are fast and
     * you do not expect them to block the replication loop for long periods.
     *
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder inlineHandlers() {
        this.handlerExecutionConfig = HandlerExecutionConfig.inline();
        return this;
    }

    /**
     * Configures handlers to run asynchronously using a bounded queue and worker pool.
     * <p>
     * The replication thread only reads from the logical stream, decodes events, and
     * enqueues them into an internal queue. One or more worker threads drain the queue
     * and invoke the user {@link ChangeHandler}s.
     * <p>
     * This overload uses {@link HandlerExecutionConfig.QueueOverflowPolicy#BLOCK} when
     * the queue is full.
     *
     * @param workerThreads number of handler worker threads
     * @param queueCapacity bounded capacity of the handler queue
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder asyncHandlerQueue(final int workerThreads, final int queueCapacity) {
        return asyncHandlerQueue(workerThreads, queueCapacity, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK);
    }

    /**
     * Configures handlers to run asynchronously using a bounded queue and worker pool with
     * a configurable overflow policy.
     *
     * @param workerThreads       number of handler worker threads
     * @param queueCapacity       bounded capacity of the handler queue
     * @param queueOverflowPolicy policy applied when the queue is full
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder asyncHandlerQueue(
            final int workerThreads,
            final int queueCapacity,
            final HandlerExecutionConfig.QueueOverflowPolicy queueOverflowPolicy) {
        this.handlerExecutionConfig = HandlerExecutionConfig.asyncQueue(
                workerThreads,
                queueCapacity,
                Objects.requireNonNull(queueOverflowPolicy));
        return this;
    }

    /**
     * Overrides the default wal2json plugin configuration.
     *
     * @param pluginConfig wal2json plugin configuration
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder pluginConfig(final PgNotifier.PluginConfig pluginConfig) {
        this.pluginConfig = Objects.requireNonNull(pluginConfig);
        return this;
    }

    /**
     * Registers an LSN persistence adapter used to resume from a persisted position.
     *
     * @param lsnPersistence persistence implementation
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder lsnPersistence(final LsnPersistence lsnPersistence) {
        this.lsnPersistence = Objects.requireNonNull(lsnPersistence);
        return this;
    }

    /**
     * Configures an optional {@link NotifierMetrics} implementation used to emit
     * counters and gauges for events, failures and restarts.
     *
     * @param metrics metrics bridge implementation
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder metrics(final NotifierMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }

    /**
     * Overrides the restart policy used when a worker fails.
     *
     * @param restartPolicy restart policy to apply
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder restartPolicy(final RestartPolicy restartPolicy) {
        this.restartPolicy = Objects.requireNonNull(restartPolicy);
        this.restartPolicyOverridden = true;
        return this;
    }

    /**
     * Configures a fixed restart policy.
     *
     * @param backoffSeconds delay between restarts
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder fixedRestartPolicy(final long backoffSeconds) {
        return restartPolicy(RestartPolicy.fixed(backoffSeconds));
    }

    /**
     * Configures an exponential backoff restart policy with an optional jitter.
     *
     * @param initialBackoffSeconds initial backoff in seconds
     * @param maxBackoffSeconds     maximum backoff cap
     * @param jitter                apply jitter when true
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder exponentialRestartPolicy(
            final long initialBackoffSeconds,
            final long maxBackoffSeconds,
            final boolean jitter) {
        return restartPolicy(RestartPolicy.exponential(initialBackoffSeconds, maxBackoffSeconds, jitter));
    }

    /**
     * Configures the notifier to never restart on failure.
     *
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder neverRestart() {
        return restartPolicy(RestartPolicy.never());
    }

    /**
     * Registers the mandatory {@link ChangeHandler}.
     *
     * @param changeHandler callback invoked for each decoded change
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder changeHandler(final ChangeHandler changeHandler) {
        this.changeHandler = Objects.requireNonNull(changeHandler);
        return this;
    }

    /**
     * Registers the mandatory {@link ErrorHandler}.
     *
     * @param errorHandler callback invoked when processing a change throws
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder errorHandler(final ErrorHandler errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler);
        return this;
    }

    /**
     * Configures the {@link ErrorHandler} using a built-in {@link ErrorHandlingStrategy}.
     * <p>
     * This keeps simple strategies (such as "drop transient failures, stop on permanent failures")
     * discoverable without requiring callers to implement {@link ErrorHandler} themselves.
     *
     * @param strategy error handling strategy to apply
     * @return this builder for fluent chaining
     */
    public PgNotifierBuilder errorHandlingStrategy(final ErrorHandlingStrategy strategy) {
        Objects.requireNonNull(strategy, "ErrorHandlingStrategy must be provided");
        return errorHandler(strategy.toErrorHandler());
    }

    PgNotifierBuilder slotRuntimeFactory(final SlotRuntime.Factory slotRuntimeFactory) {
        this.slotRuntimeFactory = Objects.requireNonNull(slotRuntimeFactory);
        return this;
    }

    /**
     * Validates configuration and creates a {@link PgNotifier}.
     *
     * @return a configured {@link PgNotifier} ready to {@link PgNotifier#start() start}
     * @throws IllegalStateException if no slots were configured or handlers are missing
     */
    public PgNotifier build() {

        if (slots.isEmpty()) {
            throw new IllegalStateException("At least one SlotConfig must be provided");
        }

        Objects.requireNonNull(changeHandler, "ChangeHandler must be provided");
        Objects.requireNonNull(errorHandler, "ErrorHandler must be provided");

        final List<SlotRuntime> slotRuntimes = new ArrayList<>(this.slots.size());
        for (PgNotifier.SlotConfig slot : this.slots) {
            slotRuntimes.add(this.slotRuntimeFactory.create(new SlotRuntime.Config(
                    slot,
                    this.processConfig,
                    this.pluginConfig,
                    changeHandler,
                    errorHandler,
                    this.lsnPersistence,
                    this.restartPolicy,
                    this.handlerExecutionConfig,
                    this.metrics)));
        }

        return new PgNotifier(slotRuntimes);
    }

}
