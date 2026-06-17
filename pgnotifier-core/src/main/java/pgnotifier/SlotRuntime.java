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
import pgnotifier.internal.replication.BatchReplicationWorker;
import pgnotifier.internal.replication.ReplicationSlot;
import pgnotifier.util.LatchedProcess;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class SlotRuntime {

    @FunctionalInterface
    interface Factory {

        SlotRuntime create(Config config);

    }

    @FunctionalInterface
    interface ReplicationSlotFactory {

        ReplicationSlot create(
                PgNotifier.SlotConfig config,
                PgNotifier.PluginConfig pluginConfig,
                LsnPersistence lsnPersistence);

    }

    @FunctionalInterface
    interface ExecutorServiceFactory {

        ExecutorService create(int poolSize);

    }

    @FunctionalInterface
    interface LatchedProcessFactory {

        LatchedProcess create(
                RestartPolicy restartPolicy,
                int shutdownTimeoutSeconds,
                ExecutorService executor,
                LatchedProcess.ProcessWorker worker,
                LatchedProcess.ProcessListener processListener);

    }

    record Config(
            PgNotifier.SlotConfig slotConfig,
            PgNotifier.ProcessConfig processConfig,
            PgNotifier.PluginConfig pluginConfig,
            ChangeHandler changeHandler,
            ErrorHandler errorHandler,
            LsnPersistence lsnPersistence,
            RestartPolicy restartPolicy,
            HandlerExecutionConfig handlerExecutionConfig,
            NotifierMetrics metrics) {

        Config {
            Objects.requireNonNull(slotConfig);
            Objects.requireNonNull(processConfig);
            Objects.requireNonNull(pluginConfig);
            Objects.requireNonNull(changeHandler);
            Objects.requireNonNull(errorHandler);
            Objects.requireNonNull(lsnPersistence);
            Objects.requireNonNull(restartPolicy);
            Objects.requireNonNull(handlerExecutionConfig);
            metrics = Objects.requireNonNullElse(metrics, NotifierMetrics.noop());
        }

    }

    record Dependencies(
            ReplicationSlotFactory replicationSlotFactory,
            ExecutorServiceFactory executorServiceFactory,
            LatchedProcessFactory latchedProcessFactory) {

        static Dependencies defaults() {
            return new Dependencies(
                    (config, pluginConfig, lsnPersistence) ->
                            new ReplicationFactory().newReplicationSlot(config, pluginConfig, lsnPersistence),
                    Executors::newFixedThreadPool,
                    LatchedProcess::new);
        }

        Dependencies {
            Objects.requireNonNull(replicationSlotFactory);
            Objects.requireNonNull(executorServiceFactory);
            Objects.requireNonNull(latchedProcessFactory);
        }

    }

    private final PgNotifier.SlotConfig slotConfig;
    private final SlotHealth slotHealth;
    private final LatchedProcess process;

    SlotRuntime(
            final PgNotifier.SlotConfig slotConfig,
            final SlotHealth slotHealth,
            final LatchedProcess process) {

        this.slotConfig = Objects.requireNonNull(slotConfig);
        this.slotHealth = Objects.requireNonNull(slotHealth);
        this.process = Objects.requireNonNull(process);

    }

    static SlotRuntime create(final Config config) {
        return create(config, Dependencies.defaults());
    }

    static SlotRuntime create(final Config config, final Dependencies dependencies) {

        Objects.requireNonNull(config);
        Objects.requireNonNull(dependencies);

        final PgNotifier.SlotConfig slotConfig = config.slotConfig();
        final PgNotifier.ProcessConfig processConfig = config.processConfig();
        final NotifierMetrics metrics = config.metrics();

        final ReplicationSlot slot = dependencies.replicationSlotFactory().create(
                slotConfig,
                config.pluginConfig(),
                config.lsnPersistence());

        final SlotHealth slotHealth = new SlotHealth();
        metrics.onFailureStreakUpdated(slotConfig.slotname(), 0);

        final BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                config.changeHandler(),
                config.errorHandler(),
                new Wal2JsonChangeEventDecoder(),
                processConfig.sleepMillis(),
                processConfig.throttleMillis(),
                TimeUnit.SECONDS.toMillis(processConfig.errorBackoffSeconds()),
                config.lsnPersistence(),
                config.handlerExecutionConfig(),
                slotHealth,
                metrics);

        final ExecutorService executor = dependencies.executorServiceFactory().create(processConfig.poolSize());

        final LatchedProcess.ProcessListener processListener = (failureCount, cause, willRestart, backoffSeconds) -> {
            slotHealth.recordFailureStreak(failureCount);
            metrics.onFailureStreakUpdated(slotConfig.slotname(), failureCount);
            if (willRestart) {
                metrics.onProcessRestart(slotConfig.slotname(), failureCount);
            }
        };

        final LatchedProcess process = dependencies.latchedProcessFactory().create(
                config.restartPolicy(),
                processConfig.shutdownTimeoutSeconds(),
                executor,
                worker,
                processListener);

        return new SlotRuntime(slotConfig, slotHealth, process);

    }

    void start() {
        this.slotHealth.recordFailureStreak(0);
        this.process.start();
    }

    void stop() {
        this.process.stop();
        this.slotHealth.recordFailureStreak(0);
    }

    PgNotifier.SlotHealthSnapshot health() {

        final LatchedProcess.State processState = this.process.state();
        final int failureCount = processState == LatchedProcess.State.STOPPED
                ? 0
                : this.slotHealth.failureStreak();

        // Never surface SlotConfig from health snapshots: it carries credentials and
        // these snapshots are routinely exported to logs, metrics bridges, and HTTP endpoints.
        return new PgNotifier.SlotHealthSnapshot(
                this.slotConfig.slotname(),
                processState(processState),
                this.process.isRunning(),
                failureCount,
                this.slotHealth.lastSuccessfulEventEpochMillis(),
                this.slotHealth.lastPersistedLsn(),
                this.slotHealth.handlerQueueDepth(),
                this.slotHealth.handlerQueueCapacity(),
                this.slotHealth.observedReplicationLagBytes(),
                workerFailureSnapshot(this.slotHealth),
                processFailureSnapshot(this.process));

    }

    private static PgNotifier.@Nullable WorkerFailureSnapshot workerFailureSnapshot(final SlotHealth slotHealth) {

        final String className = slotHealth.lastWorkerFailureClassName();
        final String message = slotHealth.lastWorkerFailureMessage();
        final Long epochMillis = slotHealth.lastWorkerFailureEpochMillis();
        final boolean recovered = slotHealth.lastWorkerFailureRecovered();
        final ErrorOrigin origin = slotHealth.lastWorkerFailureOrigin();
        final ErrorType errorType = slotHealth.lastWorkerFailureType();

        if (className == null && message == null && epochMillis == null && origin == null && errorType == null) {
            return null;
        }

        return new PgNotifier.WorkerFailureSnapshot(
                epochMillis,
                recovered,
                origin,
                errorType,
                className,
                message);

    }

    private static ProcessState processState(final LatchedProcess.State state) {
        return switch (state) {
            case STARTING -> ProcessState.STARTING;
            case RUNNING -> ProcessState.RUNNING;
            case BACKING_OFF -> ProcessState.BACKING_OFF;
            case STOPPING -> ProcessState.STOPPING;
            case STOPPED -> ProcessState.STOPPED;
            case FAILED -> ProcessState.FAILED;
        };
    }

    private static PgNotifier.@Nullable ProcessFailureSnapshot processFailureSnapshot(final LatchedProcess process) {

        final String className = process.lastFailureClassName();
        final String message = process.lastFailureMessage();
        final Long epochMillis = process.lastFailureEpochMillis();
        final boolean recovered = process.lastFailureRecovered();

        if (className == null && message == null && epochMillis == null) {
            return null;
        }

        return new PgNotifier.ProcessFailureSnapshot(epochMillis, recovered, className, message);

    }

}
