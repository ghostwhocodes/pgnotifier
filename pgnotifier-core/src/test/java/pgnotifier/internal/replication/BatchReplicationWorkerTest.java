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
import org.mockito.InOrder;
import pgnotifier.ChangeEvent;
import pgnotifier.ChangeHandler;
import pgnotifier.ErrorContext;
import pgnotifier.ErrorHandler;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorType;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.LsnPersistence;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.ProcessingDecision;
import pgnotifier.WorkerStopException;
import pgnotifier.SlotHealth;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BatchReplicationWorkerTest {

    @Test
    void processesEventAndCommitsLsnOnCommitDecision() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        // Provide two payloads so the worker can reach STOP on the second handler call.
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"value\"}")
                .thenReturn("{\"payload\":\"value2\"}")
                .thenReturn(null); // loop exits after STOP flag
        when(stream.lastReceiveLsn()).thenReturn(10L);

        ChangeHandler changeHandler = mock(ChangeHandler.class);
        when(changeHandler.onChange(any(ChangeEvent.class)))
                .thenReturn(ProcessingDecision.COMMIT) // first payload
                .thenReturn(ProcessingDecision.STOP);  // second payload stops loop

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                lsnPersistence);

        worker.start();

        InOrder inOrder = inOrder(stream);
        inOrder.verify(stream).readNonBlocking(0L, 0L);
        verify(stream, atLeastOnce()).markProcessed(10L);
        verify(changeHandler, times(2)).onChange(any(ChangeEvent.class));
        verify(errorHandler, never()).onError(any(ErrorContext.class));
        verify(lsnPersistence, atLeastOnce()).persist(eq(slotConfig), anyLong());
    }

    @Test
    void delegatesExceptionsToErrorHandler() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"value\"}")
                .thenReturn("{\"payload\":\"value2\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(20L);

        ChangeHandler changeHandler = mock(ChangeHandler.class);
        when(changeHandler.onChange(any(ChangeEvent.class)))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(ProcessingDecision.STOP);

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        when(errorHandler.onError(any(ErrorContext.class)))
                .thenAnswer(invocation -> ErrorHandlingDecision.dropAndContinue(invocation.getArgument(0)));
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                lsnPersistence);

        worker.start();

        verify(changeHandler, times(2)).onChange(any(ChangeEvent.class));
        verify(errorHandler).onError(any(ErrorContext.class));
        verify(stream).markProcessed(20L);
        verify(lsnPersistence, atLeastOnce()).persist(eq(slotConfig), anyLong());
    }

    @Test
    void retriesWithBackoffKeepsEventAndCommitsAfterSuccess() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"retry\"}")
                .thenReturn("{\"payload\":\"final\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(30L);
        when(stream.markProcessed(30L)).thenReturn(30L);

        ChangeHandler changeHandler = mock(ChangeHandler.class);
        when(changeHandler.onChange(any(ChangeEvent.class)))
                .thenThrow(new IllegalStateException("temporary"))
                .thenReturn(ProcessingDecision.COMMIT)
                .thenReturn(ProcessingDecision.STOP);

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        when(errorHandler.onError(any(ErrorContext.class)))
                .thenAnswer(invocation -> ErrorHandlingDecision.retryWithBackoff(
                        invocation.getArgument(0),
                        0L));
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                25L,
                lsnPersistence);

        worker.start();

        verify(changeHandler, times(3)).onChange(any(ChangeEvent.class));
        verify(errorHandler).onError(any(ErrorContext.class));
        verify(stream, times(1)).markProcessed(30L);
        verify(lsnPersistence).persist(slotConfig, 30L);
    }

    @Test
    void asyncQueueModeProcessesEventsAndCommitsLsn() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"queued\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(40L);
        when(stream.markProcessed(40L)).thenReturn(40L);

        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerRelease = new CountDownLatch(1);

        ChangeHandler changeHandler = mock(ChangeHandler.class);
        when(changeHandler.onChange(any(ChangeEvent.class)))
                .thenAnswer(invocation -> {
                    handlerStarted.countDown();
                    try {
                        handlerRelease.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ProcessingDecision.COMMIT;
                });

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);
        NotifierMetrics metrics = mock(NotifierMetrics.class);

        HandlerExecutionConfig handlerConfig = HandlerExecutionConfig.asyncQueue(
                1,
                10,
                HandlerExecutionConfig.QueueOverflowPolicy.BLOCK);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                lsnPersistence,
                handlerConfig,
                new SlotHealth(),
                metrics);

        Thread workerThread = new Thread(worker::start);
        workerThread.start();

        // Wait for the handler to be invoked on the worker thread.
        handlerStarted.await(1, TimeUnit.SECONDS);

        // Allow the handler to complete so the event can be committed.
        handlerRelease.countDown();

        worker.stop();
        workerThread.join(2000L);

        verify(changeHandler, times(1)).onChange(any(ChangeEvent.class));
        verify(errorHandler, never()).onError(any(ErrorContext.class));
        verify(stream, atLeastOnce()).markProcessed(40L);
        verify(lsnPersistence, atLeastOnce()).persist(eq(slotConfig), eq(40L));
        verify(metrics, atLeastOnce()).onHandlerQueueDepthUpdated(eq("slot"), anyInt(), eq(10));
        verify(metrics).onHandlerQueueDrainLatency(eq("slot"), anyLong());
        verify(metrics, atLeastOnce()).onLastPersistedLsnUpdated("slot", 40L);
        verify(metrics, atLeastOnce()).onReplicationLagBytesUpdated("slot", 0L);
    }

    @Test
    void asyncQueueModeAcknowledgesCompletedEventsUsingTheirOwnLsnsInOrder() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"first\"}")
                .thenReturn("{\"payload\":\"second\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(101L, 202L);
        when(stream.markProcessed(101L)).thenReturn(101L);
        when(stream.markProcessed(202L)).thenReturn(202L);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        ChangeHandler changeHandler = mock(ChangeHandler.class);
        when(changeHandler.onChange(any(ChangeEvent.class)))
                .thenAnswer(invocation -> {
                    ChangeEvent event = invocation.getArgument(0);
                    if ("{\"payload\":\"first\"}".equals(event.payload())) {
                        firstStarted.countDown();
                        allowFirstToFinish.await(1, TimeUnit.SECONDS);
                    } else if ("{\"payload\":\"second\"}".equals(event.payload())) {
                        secondFinished.countDown();
                    }
                    return ProcessingDecision.COMMIT;
                });

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        HandlerExecutionConfig handlerConfig = HandlerExecutionConfig.asyncQueue(
                2,
                10,
                HandlerExecutionConfig.QueueOverflowPolicy.BLOCK);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                lsnPersistence,
                handlerConfig,
                new SlotHealth(),
                pgnotifier.NotifierMetrics.noop());

        Thread workerThread = new Thread(worker::start);
        workerThread.start();

        firstStarted.await(1, TimeUnit.SECONDS);
        secondFinished.await(1, TimeUnit.SECONDS);
        allowFirstToFinish.countDown();

        worker.stop();
        workerThread.join(2000L);

        InOrder inOrder = inOrder(stream, lsnPersistence);
        inOrder.verify(stream).markProcessed(101L);
        inOrder.verify(lsnPersistence).persist(slotConfig, 101L);
        inOrder.verify(stream).markProcessed(202L);
        inOrder.verify(lsnPersistence).persist(slotConfig, 202L);
    }

    @Test
    void asyncQueueModeDoesNotAdvanceLaterLsnWhenEarlierEventRetriesDuringStop() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"first\"}")
                .thenReturn("{\"payload\":\"second\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(701L, 802L);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToRetry = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        ChangeHandler changeHandler = event -> {
            if ("{\"payload\":\"first\"}".equals(event.payload())) {
                firstStarted.countDown();
                try {
                    allowFirstToRetry.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ProcessingDecision.RETRY;
                }
                return ProcessingDecision.RETRY;
            }

            secondFinished.countDown();
            return ProcessingDecision.COMMIT;
        };

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        HandlerExecutionConfig handlerConfig = HandlerExecutionConfig.asyncQueue(
                2,
                10,
                HandlerExecutionConfig.QueueOverflowPolicy.BLOCK);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                5L,
                0L,
                0L,
                lsnPersistence,
                handlerConfig,
                new SlotHealth(),
                pgnotifier.NotifierMetrics.noop());

        Thread workerThread = new Thread(worker::start);
        workerThread.setDaemon(true);
        workerThread.start();

        try {
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(1, TimeUnit.SECONDS)).isTrue();
            worker.stop();
            allowFirstToRetry.countDown();
            workerThread.join(2000L);

            assertThat(workerThread.isAlive()).isFalse();
            verify(stream, never()).markProcessed(anyLong());
            verify(lsnPersistence, never()).persist(eq(slotConfig), anyLong());
            verify(errorHandler, never()).onError(any(ErrorContext.class));
        } finally {
            allowFirstToRetry.countDown();
            worker.stop();
            workerThread.join(1000L);
        }
    }

    @Test
    void asyncQueueModeDefersReplicationFailureDropUntilQueuedEventsSettle() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        RuntimeException replicationFailure = new IllegalStateException("read failed");
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"first\"}")
                .thenThrow(replicationFailure)
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(901L);
        when(stream.markProcessed(901L)).thenReturn(901L);

        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerCommit = new CountDownLatch(1);
        CountDownLatch replicationFailureHandled = new CountDownLatch(1);
        CountDownLatch streamHeadDropAttempted = new CountDownLatch(1);
        CountDownLatch firstPersisted = new CountDownLatch(1);
        CountDownLatch streamHeadPersisted = new CountDownLatch(1);

        when(stream.markProcessed()).thenAnswer(invocation -> {
            streamHeadDropAttempted.countDown();
            return 950L;
        });

        ChangeHandler changeHandler = event -> {
            handlerStarted.countDown();
            try {
                allowHandlerCommit.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ProcessingDecision.RETRY;
            }
            return ProcessingDecision.COMMIT;
        };

        ErrorHandler errorHandler = context -> {
            assertThat(context.origin()).isEqualTo(ErrorOrigin.REPLICATION);
            assertThat(context.exception()).isSameAs(replicationFailure);
            replicationFailureHandled.countDown();
            return ErrorHandlingDecision.dropAndContinue(context);
        };

        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);
        doAnswer(invocation -> {
            firstPersisted.countDown();
            return null;
        }).when(lsnPersistence).persist(slotConfig, 901L);
        doAnswer(invocation -> {
            streamHeadPersisted.countDown();
            return null;
        }).when(lsnPersistence).persist(slotConfig, 950L);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                5L,
                0L,
                0L,
                lsnPersistence,
                HandlerExecutionConfig.asyncQueue(1, 10, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK),
                new SlotHealth(),
                pgnotifier.NotifierMetrics.noop());

        Thread workerThread = new Thread(worker::start);
        workerThread.setDaemon(true);
        workerThread.start();

        try {
            assertThat(handlerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(replicationFailureHandled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(streamHeadDropAttempted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            allowHandlerCommit.countDown();
            assertThat(firstPersisted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(streamHeadPersisted.await(1, TimeUnit.SECONDS)).isTrue();

            worker.stop();
            workerThread.join(2000L);

            assertThat(workerThread.isAlive()).isFalse();
            InOrder inOrder = inOrder(stream, lsnPersistence);
            inOrder.verify(stream).markProcessed(901L);
            inOrder.verify(lsnPersistence).persist(slotConfig, 901L);
            inOrder.verify(stream).markProcessed();
            inOrder.verify(lsnPersistence).persist(slotConfig, 950L);
        } finally {
            allowHandlerCommit.countDown();
            worker.stop();
            workerThread.join(1000L);
        }
    }

    @Test
    void stopCancelsInlineRetryLoop() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"retry\"}")
                .thenReturn(null);
        when(stream.lastReceiveLsn()).thenReturn(60L);

        CountDownLatch retried = new CountDownLatch(2);
        AtomicBoolean allowCleanupStop = new AtomicBoolean();

        ChangeHandler changeHandler = ignored -> {
            retried.countDown();
            return allowCleanupStop.get()
                    ? ProcessingDecision.STOP
                    : ProcessingDecision.RETRY;
        };

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot"),
                changeHandler,
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                5L,
                0L,
                5L,
                LsnPersistence.noop());

        Thread workerThread = new Thread(worker::start);
        workerThread.setDaemon(true);
        workerThread.start();

        assertThat(retried.await(1, TimeUnit.SECONDS)).isTrue();

        try {
            worker.stop();
            workerThread.join(1000L);

            assertThat(workerThread.isAlive()).isFalse();
            verify(stream, never()).markProcessed(anyLong());
        } finally {
            allowCleanupStop.set(true);
            worker.stop();
            workerThread.join(1000L);
        }
    }

    @Test
    void asyncQueueModeCanStartAgainAfterCleanStop() throws Exception {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream firstStream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        ReplicationStream secondStream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream())
                .thenReturn(firstStream)
                .thenReturn(secondStream);
        when(firstStream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"first\"}")
                .thenReturn(null);
        when(firstStream.lastReceiveLsn()).thenReturn(61L);
        when(firstStream.markProcessed(61L)).thenReturn(61L);
        when(secondStream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"second\"}")
                .thenReturn(null);
        when(secondStream.lastReceiveLsn()).thenReturn(62L);
        when(secondStream.markProcessed(62L)).thenReturn(62L);

        CountDownLatch firstPersisted = new CountDownLatch(1);
        CountDownLatch secondPersisted = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();

        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig(
                "user",
                "password",
                "jdbc:postgresql://localhost/test",
                "slot");
        LsnPersistence persistence = mock(LsnPersistence.class);
        doAnswer(invocation -> {
            firstPersisted.countDown();
            return null;
        }).when(persistence).persist(eq(slotConfig), eq(61L));
        doAnswer(invocation -> {
            secondPersisted.countDown();
            return null;
        }).when(persistence).persist(eq(slotConfig), eq(62L));

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                event -> {
                    handled.incrementAndGet();
                    return ProcessingDecision.COMMIT;
                },
                context -> ErrorHandlingDecision.dropAndContinue(context),
                pgnotifier.ChangeEventDecoder.raw(),
                5L,
                0L,
                0L,
                persistence,
                HandlerExecutionConfig.asyncQueue(1, 4, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK),
                new SlotHealth(),
                NotifierMetrics.noop());

        Thread firstRun = new Thread(worker::start);
        firstRun.start();
        assertThat(firstPersisted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.stop();
        firstRun.join(1000L);
        assertThat(firstRun.isAlive()).isFalse();

        Thread secondRun = new Thread(worker::start);
        secondRun.start();

        try {
            assertThat(secondPersisted.await(1, TimeUnit.SECONDS)).isTrue();
            worker.stop();
            secondRun.join(1000L);

            assertThat(secondRun.isAlive()).isFalse();
            assertThat(handled).hasValue(2);
            verify(secondStream, atLeastOnce()).readNonBlocking(5L, 0L);
            verify(secondStream).markProcessed(62L);
        } finally {
            worker.stop();
            secondRun.join(1000L);
        }
    }

    @Test
    void queueFullWithFatalPolicySignalsWorkerStop() {
        ReplicationSlot slot = mock(ReplicationSlot.class, withSettings().withoutAnnotations());
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        when(slot.newReplicationStream()).thenReturn(stream);
        when(stream.readNonBlocking(anyLong(), anyLong()))
                .thenReturn("{\"payload\":\"one\"}")
                .thenReturn("{\"payload\":\"two\"}");
        when(stream.lastReceiveLsn()).thenReturn(50L);

        CountDownLatch handlerBlock = new CountDownLatch(1);

        ChangeHandler changeHandler = event -> {
            try {
                handlerBlock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ProcessingDecision.COMMIT;
        };

        ErrorHandler errorHandler = mock(ErrorHandler.class);
        PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
        LsnPersistence lsnPersistence = mock(LsnPersistence.class);

        HandlerExecutionConfig handlerConfig = HandlerExecutionConfig.asyncQueue(
                1,
                1,
                HandlerExecutionConfig.QueueOverflowPolicy.FATAL);

        BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                changeHandler,
                errorHandler,
                pgnotifier.ChangeEventDecoder.raw(),
                0L,
                0L,
                0L,
                lsnPersistence,
                handlerConfig,
                new SlotHealth(),
                pgnotifier.NotifierMetrics.noop());

        try {
            worker.start();
        } catch (WorkerStopException e) {
            // expected: queue full with FATAL policy should stop the worker
        }
    }
}
