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

import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;
import pgnotifier.internal.replication.BatchReplicationWorker;
import pgnotifier.internal.replication.ReplicationSlot;
import pgnotifier.internal.replication.ReplicationStream;
import pgnotifier.util.LatchedProcess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotRuntimeTest {

    @Test
    void createWiresSlotWorkerProcessListenerHealthAndMetrics() {
        ReplicationSlot slot = mock(ReplicationSlot.class);
        List<PgNotifier.SlotConfig> capturedSlots = new ArrayList<>();
        List<LsnPersistence> capturedPersistence = new ArrayList<>();
        List<Integer> capturedPoolSizes = new ArrayList<>();
        List<RestartPolicy> capturedRestartPolicies = new ArrayList<>();
        List<Integer> capturedShutdownTimeouts = new ArrayList<>();
        List<LatchedProcess.ProcessWorker> capturedWorkers = new ArrayList<>();
        RecordingMetrics metrics = new RecordingMetrics();
        RestartPolicy restartPolicy = RestartPolicy.fixed(12L);
        LsnPersistence persistence = LsnPersistence.noop();

        SlotRuntime.Dependencies dependencies = new SlotRuntime.Dependencies(
                (slotConfig, pluginConfig, lsnPersistence) -> {
                    capturedSlots.add(slotConfig);
                    capturedPersistence.add(lsnPersistence);
                    return slot;
                },
                poolSize -> {
                    capturedPoolSizes.add(poolSize);
                    return MoreExecutors.newDirectExecutorService();
                },
                (policy, shutdownTimeoutSeconds, executor, worker, processListener) -> {
                    capturedRestartPolicies.add(policy);
                    capturedShutdownTimeouts.add(shutdownTimeoutSeconds);
                    capturedWorkers.add(worker);
                    processListener.onFailure(3, new RuntimeException("boom"), true, 12L);

                    LatchedProcess process = mock(LatchedProcess.class);
                    when(process.state()).thenReturn(LatchedProcess.State.BACKING_OFF);
                    when(process.isRunning()).thenReturn(true);
                    when(process.lastFailureEpochMillis()).thenReturn(123L);
                    when(process.lastFailureRecovered()).thenReturn(false);
                    when(process.lastFailureClassName()).thenReturn(RuntimeException.class.getName());
                    when(process.lastFailureMessage()).thenReturn("boom");
                    return process;
                });

        SlotRuntime runtime = SlotRuntime.create(config("slot1", persistence, restartPolicy, metrics), dependencies);

        assertThat(capturedSlots)
                .hasSize(1)
                .extracting(PgNotifier.SlotConfig::slotname)
                .containsExactly("slot1");
        assertThat(capturedPersistence).containsExactly(persistence);
        assertThat(capturedPoolSizes).containsExactly(2);
        assertThat(capturedRestartPolicies).containsExactly(restartPolicy);
        assertThat(capturedShutdownTimeouts).containsExactly(8);
        assertThat(capturedWorkers).hasSize(1);
        assertThat(capturedWorkers.getFirst()).isInstanceOf(BatchReplicationWorker.class);
        assertThat(metrics.failureStreakUpdates).containsExactly("slot1:0", "slot1:3");
        assertThat(metrics.processRestarts).containsExactly("slot1:3");

        PgNotifier.SlotHealthSnapshot snapshot = runtime.health();
        assertThat(snapshot.slot()).isEqualTo("slot1");
        assertThat(snapshot.state()).isEqualTo(ProcessState.BACKING_OFF);
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.failureCount()).isEqualTo(3);
        assertThat(snapshot.lastWorkerFailure()).isNull();
        assertThat(snapshot.lastProcessFailure()).isEqualTo(
                new PgNotifier.ProcessFailureSnapshot(123L, false, RuntimeException.class.getName(), "boom"));
    }

    @Test
    void startAndStopDelegateToProcessAndResetFailureHealth() {
        LatchedProcess process = mock(LatchedProcess.class);
        when(process.state()).thenReturn(LatchedProcess.State.RUNNING);
        SlotHealth slotHealth = new SlotHealth();
        slotHealth.recordFailureStreak(5);
        SlotRuntime runtime = new SlotRuntime(
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot1"),
                slotHealth,
                process);

        runtime.start();

        verify(process).start();
        assertThat(runtime.health().failureCount()).isZero();

        slotHealth.recordFailureStreak(4);
        runtime.stop();

        verify(process).stop();
        assertThat(runtime.health().failureCount()).isZero();
    }

    @Test
    void healthClearsFailureCountForStoppedProcesses() {
        LatchedProcess process = mock(LatchedProcess.class);
        when(process.state()).thenReturn(LatchedProcess.State.STOPPED);
        when(process.isRunning()).thenReturn(false);
        when(process.lastFailureEpochMillis()).thenReturn(null);
        SlotHealth slotHealth = new SlotHealth();
        slotHealth.recordFailureStreak(3);
        SlotRuntime runtime = new SlotRuntime(
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot1"),
                slotHealth,
                process);

        PgNotifier.SlotHealthSnapshot snapshot = runtime.health();

        assertThat(snapshot.state()).isEqualTo(ProcessState.STOPPED);
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.failureCount()).isZero();
        assertThat(snapshot.lastPersistedLsn()).isNull();
        assertThat(snapshot.handlerQueueDepth()).isZero();
        assertThat(snapshot.handlerQueueCapacity()).isZero();
        assertThat(snapshot.observedReplicationLagBytes()).isNull();
        assertThat(snapshot.lastWorkerFailure()).isNull();
        assertThat(snapshot.lastProcessFailure()).isNull();
    }

    @Test
    void healthExposesLastWorkerFailureDetailsSeparatelyFromProcessFailure() throws Exception {
        List<LatchedProcess.ProcessWorker> workers = new ArrayList<>();

        ReplicationSlot slot = mock(ReplicationSlot.class);
        ReplicationStream stream = mock(ReplicationStream.class);
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"first\"}")
                .thenReturn("{\"payload\":\"second\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(22L);
        when(stream.markProcessed(22L)).thenReturn(22L);

        SlotRuntime.Dependencies dependencies = new SlotRuntime.Dependencies(
                (slotConfig, pluginConfig, lsnPersistence) -> slot,
                poolSize -> MoreExecutors.newDirectExecutorService(),
                (restartPolicy, shutdownTimeoutSeconds, executor, worker, processListener) -> {
                    workers.add(worker);
                    LatchedProcess process = mock(LatchedProcess.class);
                    when(process.state()).thenReturn(LatchedProcess.State.STOPPED);
                    when(process.isRunning()).thenReturn(false);
                    when(process.lastFailureEpochMillis()).thenReturn(null);
                    return process;
                });

        SlotRuntime runtime = SlotRuntime.create(
                new SlotRuntime.Config(
                        new PgNotifier.SlotConfig("u", "p", "jdbc", "slot1"),
                        new PgNotifier.ProcessConfig(1, 10, 10,
                                PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
                                PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS),
                        PgNotifier.PluginConfig.defaults(),
                        event -> {
                            String payload = event.payload();
                            if (payload != null && payload.contains("first")) {
                                throw new IllegalStateException("handler failed");
                            }
                            return ProcessingDecision.STOP;
                        },
                        context -> ErrorHandlingDecision.dropAndContinue(context),
                        LsnPersistence.noop(),
                        RestartPolicy.fixed(10),
                        HandlerExecutionConfig.inline(),
                        NotifierMetrics.noop()),
                dependencies);

        assertThat(workers).hasSize(1);
        workers.getFirst().start();

        PgNotifier.SlotHealthSnapshot snapshot = runtime.health();
        assertThat(snapshot.lastProcessFailure()).isNull();
        assertThat(snapshot.lastPersistedLsn()).isEqualTo(22L);
        assertThat(snapshot.observedReplicationLagBytes()).isEqualTo(0L);
        assertThat(snapshot.lastWorkerFailure()).isNotNull();
        PgNotifier.WorkerFailureSnapshot workerFailure = Objects.requireNonNull(snapshot.lastWorkerFailure());
        assertThat(workerFailure.epochMillis()).isNotNull();
        assertThat(workerFailure.recovered()).isTrue();
        assertThat(workerFailure.origin()).isEqualTo(ErrorOrigin.CHANGE_HANDLER);
        assertThat(workerFailure.errorType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(workerFailure.className()).isEqualTo(IllegalStateException.class.getName());
        assertThat(workerFailure.message()).isEqualTo("handler failed");
    }

    private static SlotRuntime.Config config(
            final String slotName,
            final LsnPersistence persistence,
            final RestartPolicy restartPolicy,
            final NotifierMetrics metrics) {

        return new SlotRuntime.Config(
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", slotName),
                new PgNotifier.ProcessConfig(2, 12, 8, 50L, 25L),
                new PgNotifier.PluginConfig(15, "public.demo"),
                event -> ProcessingDecision.COMMIT,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                persistence,
                restartPolicy,
                HandlerExecutionConfig.inline(),
                metrics);
    }

    private static final class RecordingMetrics implements NotifierMetrics {

        private final List<String> failureStreakUpdates = new ArrayList<>();
        private final List<String> processRestarts = new ArrayList<>();

        @Override
        public void onFailureStreakUpdated(final String slotName, final int failureCount) {
            this.failureStreakUpdates.add(slotName + ":" + failureCount);
        }

        @Override
        public void onProcessRestart(final String slotName, final int failureCount) {
            this.processRestarts.add(slotName + ":" + failureCount);
        }

    }

}
