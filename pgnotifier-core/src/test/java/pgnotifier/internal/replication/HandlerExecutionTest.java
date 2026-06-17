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
import pgnotifier.ChangeHandler;
import pgnotifier.ErrorHandler;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorType;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.LsnPersistence;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.ProcessingDecision;
import pgnotifier.SlotHealth;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class HandlerExecutionTest {

    @Test
    void inlineRetryLeavesEventUnadvancedUntilCommit() {
        Harness harness = Harness.create();
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        ChangeEvent event = event(300L);
        AtomicInteger attempts = new AtomicInteger();
        ChangeHandler changeHandler = ignored -> attempts.incrementAndGet() == 1
                ? ProcessingDecision.RETRY
                : ProcessingDecision.COMMIT;
        HandlerExecution execution = harness.execution(changeHandler, context -> ErrorHandlingDecision.stopProcess(context));

        when(stream.markProcessed(300L)).thenReturn(300L);

        HandlerExecution.Result result = execution.handleEvent(event, stream);

        assertThat(result).isEqualTo(HandlerExecution.Result.CONTINUE);
        assertThat(attempts).hasValue(2);
        verify(stream).markProcessed(300L);
        verify(harness.persistence()).persist(harness.slotConfig(), 300L);
    }

    @Test
    void inlineHandlerFailureDropUsesErrorHandlerAndRecoversFailure() {
        Harness harness = Harness.create();
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        ChangeEvent event = event(301L);
        RuntimeException failure = new IllegalStateException("handler failed");
        HandlerExecution execution = harness.execution(
                ignored -> {
                    throw failure;
                },
                context -> {
                    assertThat(context.event()).isSameAs(event);
                    assertThat(context.origin()).isEqualTo(ErrorOrigin.CHANGE_HANDLER);
                    assertThat(context.errorType()).isEqualTo(ErrorType.TRANSIENT);
                    return ErrorHandlingDecision.dropAndContinue(context);
                });

        when(stream.markProcessed(301L)).thenReturn(301L);

        HandlerExecution.Result result = execution.handleEvent(event, stream);

        assertThat(result).isEqualTo(HandlerExecution.Result.CONTINUE);
        verify(stream).markProcessed(301L);
        verify(harness.persistence()).persist(harness.slotConfig(), 301L);
        verify(harness.metrics()).onHandlerFailure("slot", failure);
        assertThat(harness.health().lastWorkerFailureRecovered()).isTrue();
        assertThat(harness.health().lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.CHANGE_HANDLER);
        assertThat(harness.health().lastWorkerFailureClassName()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void replicationFailureDropUsesErrorHandlerAndSettlesStreamHead() {
        Harness harness = Harness.create();
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        RuntimeException failure = new IllegalStateException("replication failed");
        HandlerExecution execution = harness.execution(
                ignored -> ProcessingDecision.COMMIT,
                context -> {
                    assertThat(context.event()).isNull();
                    assertThat(context.origin()).isEqualTo(ErrorOrigin.REPLICATION);
                    assertThat(context.errorType()).isEqualTo(ErrorType.TRANSIENT);
                    return ErrorHandlingDecision.dropAndContinue(context);
                });

        when(stream.markProcessed()).thenReturn(444L);

        execution.handleReplicationFailure(failure, stream);

        verify(stream).markProcessed();
        verify(harness.persistence()).persist(harness.slotConfig(), 444L);
        verify(harness.metrics()).onReplicationFailure("slot", failure);
        assertThat(harness.health().lastWorkerFailureRecovered()).isTrue();
        assertThat(harness.health().lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.REPLICATION);
        assertThat(harness.health().lastWorkerFailureClassName()).isEqualTo(IllegalStateException.class.getName());
    }

    private static ChangeEvent event(final long lsn) {
        return new ChangeEvent("{\"payload\":\"value\"}", lsn, null, null, null);
    }

    private record Harness(
            PgNotifier.SlotConfig slotConfig,
            LsnPersistence persistence,
            SlotHealth health,
            NotifierMetrics metrics) {

        private static Harness create() {
            return new Harness(
                    new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot"),
                    mock(LsnPersistence.class),
                    new SlotHealth(),
                    mock(NotifierMetrics.class));
        }

        private HandlerExecution execution(final ChangeHandler changeHandler, final ErrorHandler errorHandler) {
            EventSettlement settlement = new EventSettlement(this.slotConfig, this.persistence, this.health, this.metrics);
            return HandlerExecution.create(
                    this.slotConfig,
                    changeHandler,
                    errorHandler,
                    HandlerExecutionConfig.inline(),
                    0L,
                    0L,
                    settlement,
                    this.health,
                    this.metrics);
        }
    }

}
