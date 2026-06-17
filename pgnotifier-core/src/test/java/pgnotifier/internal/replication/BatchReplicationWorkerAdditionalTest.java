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

import org.junit.jupiter.api.Test;
import pgnotifier.ChangeEvent;
import pgnotifier.ErrorContext;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorResolution;
import pgnotifier.ErrorType;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.LsnPersistence;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.ProcessingDecision;
import pgnotifier.SlotHealth;
import pgnotifier.WorkerStopException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BatchReplicationWorkerAdditionalTest {

    @Test
    void dropDecisionCommitsAndContinues() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"drop-me\"}")
                .thenReturn("{\"payload\":\"stop-me\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(55L);
        when(stream.markProcessed(55L)).thenReturn(55L);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig(),
                event -> event.payload() != null && event.payload().contains("drop")
                        ? ProcessingDecision.DROP
                        : ProcessingDecision.STOP,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                mock(LsnPersistence.class));

        worker.start();

        verify(stream, atLeastOnce()).markProcessed(55L);
    }

    @Test
    void inlineModePublishesPersistedLsnAndObservedLag() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        PgNotifier.SlotConfig slotConfig = slotConfig();
        LsnPersistence persistence = mock(LsnPersistence.class);
        SlotHealth slotHealth = new SlotHealth();
        NotifierMetrics metrics = mock(NotifierMetrics.class);

        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"commit\"}")
                .thenReturn("{\"payload\":\"stop\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(150L, 160L);
        when(stream.markProcessed(150L)).thenReturn(150L);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                event -> event.payload() != null && event.payload().contains("commit")
                        ? ProcessingDecision.COMMIT
                        : ProcessingDecision.STOP,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                persistence,
                HandlerExecutionConfig.inline(),
                slotHealth,
                metrics);

        worker.start();

        verify(persistence).persist(slotConfig, 150L);
        verify(metrics).onLastPersistedLsnUpdated("slot", 150L);
        verify(metrics).onReplicationLagBytesUpdated("slot", 0L);
        assertThat(slotHealth.lastPersistedLsn()).isEqualTo(150L);
        assertThat(slotHealth.observedReplicationLagBytes()).isEqualTo(0L);
    }

    @Test
    void replicationErrorDropAndContinueMarksProcessed() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        PgNotifier.SlotConfig slotConfig = slotConfig();
        CountDownLatch persisted = new CountDownLatch(1);
        SlotHealth slotHealth = new SlotHealth();
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("temporary replication failure"))
                .thenReturn(null);
        when(stream.markProcessed()).thenReturn(77L);

        LsnPersistence persistence = mock(LsnPersistence.class);
        doAnswer(invocation -> {
            persisted.countDown();
            return null;
        }).when(persistence).persist(eq(slotConfig), eq(77L));

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                event -> ProcessingDecision.COMMIT,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                persistence,
                HandlerExecutionConfig.inline(),
                slotHealth,
                NotifierMetrics.noop());

        Thread thread = new Thread(worker::start);
        thread.start();

        assertThat(persisted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.stop();
        thread.join(2000L);

        verify(stream).markProcessed();
        verify(persistence).persist(eq(slotConfig), eq(77L));
        assertThat(slotHealth.lastWorkerFailureEpochMillis()).isNotNull();
        assertThat(slotHealth.lastWorkerFailureRecovered()).isTrue();
        assertThat(slotHealth.lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.REPLICATION);
        assertThat(slotHealth.lastWorkerFailureType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(slotHealth.lastWorkerFailureClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(slotHealth.lastWorkerFailureMessage()).isEqualTo("temporary replication failure");
    }

    @Test
    void asyncQueueReplicationErrorRecordsStructuredFailureDetails() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        SlotHealth slotHealth = new SlotHealth();
        CountDownLatch persisted = new CountDownLatch(1);
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("async replication failure"))
                .thenReturn(null);
        when(stream.markProcessed()).thenReturn(88L);

        LsnPersistence persistence = mock(LsnPersistence.class);
        doAnswer(invocation -> {
            persisted.countDown();
            return null;
        }).when(persistence).persist(eq(slotConfig()), eq(88L));

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig(),
                event -> ProcessingDecision.COMMIT,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                persistence,
                HandlerExecutionConfig.asyncQueue(1, 4, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK),
                slotHealth,
                NotifierMetrics.noop());

        Thread thread = new Thread(worker::start);
        thread.start();

        assertThat(persisted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.stop();
        thread.join(2000L);

        assertThat(slotHealth.lastWorkerFailureEpochMillis()).isNotNull();
        assertThat(slotHealth.lastWorkerFailureRecovered()).isTrue();
        assertThat(slotHealth.lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.REPLICATION);
        assertThat(slotHealth.lastWorkerFailureType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(slotHealth.lastWorkerFailureClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(slotHealth.lastWorkerFailureMessage()).isEqualTo("async replication failure");
    }

    @Test
    void errorHandlerFailureFallsBackToPermanentStop() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"boom\"}");
        when(stream.lastReceiveLsn()).thenReturn(88L);

        SlotHealth slotHealth = new SlotHealth();

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig(),
                event -> {
                    throw new IllegalStateException("handler failed");
                },
                context -> {
                    throw new RuntimeException("error handler failed");
                },
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                LsnPersistence.noop(),
                HandlerExecutionConfig.inline(),
                slotHealth,
                NotifierMetrics.noop());

        assertThatThrownBy(worker::start)
                .isInstanceOf(WorkerStopException.class)
                .extracting(ex -> ((WorkerStopException) ex).decision().origin())
                .isEqualTo(ErrorOrigin.ERROR_HANDLER);

        assertThat(slotHealth.lastWorkerFailureEpochMillis()).isNotNull();
        assertThat(slotHealth.lastWorkerFailureRecovered()).isFalse();
        assertThat(slotHealth.lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.ERROR_HANDLER);
        assertThat(slotHealth.lastWorkerFailureType()).isEqualTo(ErrorType.PERMANENT);
        assertThat(slotHealth.lastWorkerFailureClassName()).isEqualTo(RuntimeException.class.getName());
        assertThat(slotHealth.lastWorkerFailureMessage()).isEqualTo("error handler failed");
    }

    @Test
    void asyncQueueDropNewestAcknowledgesDroppedEventWithoutCallingHandler() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"one\"}")
                .thenReturn("{\"payload\":\"two\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(99L);
        when(stream.markProcessed(99L)).thenReturn(99L);

        CountDownLatch enteredHandler = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig(),
                event -> {
                    enteredHandler.countDown();
                    try {
                        releaseHandler.await(1, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return ProcessingDecision.COMMIT;
                },
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                mock(LsnPersistence.class),
                HandlerExecutionConfig.asyncQueue(1, 1, HandlerExecutionConfig.QueueOverflowPolicy.DROP_NEWEST),
                new SlotHealth(),
                NotifierMetrics.noop());

        Thread thread = new Thread(worker::start);
        thread.start();
        enteredHandler.await(1, TimeUnit.SECONDS);
        releaseHandler.countDown();
        worker.stop();
        thread.join(2000L);

        verify(stream, atLeastOnce()).markProcessed(99L);
    }

    @Test
    void stopDecisionFromErrorHandlerStopsWorker() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"fail\"}");
        when(stream.lastReceiveLsn()).thenReturn(123L);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig(),
                event -> {
                    throw new IllegalArgumentException("handler fail");
                },
                context -> new ErrorHandlingDecision(
                        ErrorResolution.STOP_PROCESS,
                        ErrorType.PERMANENT,
                        ErrorOrigin.CHANGE_HANDLER,
                        0L),
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                LsnPersistence.noop());

        assertThatThrownBy(worker::start)
                .isInstanceOf(WorkerStopException.class)
                .extracting(ex -> ((WorkerStopException) ex).decision().resolution())
                .isEqualTo(ErrorResolution.STOP_PROCESS);

        verify(stream, never()).markProcessed(anyLong());
    }

    private static PgNotifier.SlotConfig slotConfig() {
        return new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
    }
}
