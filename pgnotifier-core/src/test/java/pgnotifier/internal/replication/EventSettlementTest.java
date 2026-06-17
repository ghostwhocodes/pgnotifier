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
import pgnotifier.ErrorContext;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorType;
import pgnotifier.LsnPersistence;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.SlotHealth;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class EventSettlementTest {

    @Test
    void handledEventAcknowledgesBeforePersistenceAndUpdatesHealthAndMetricsFromSettledLsn() {
        PgNotifier.SlotConfig slotConfig = slotConfig();
        LsnPersistence persistence = mock(LsnPersistence.class);
        SlotHealth health = new SlotHealth();
        NotifierMetrics metrics = mock(NotifierMetrics.class);
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        EventSettlement settlement = new EventSettlement(slotConfig, persistence, health, metrics);
        ChangeEvent event = event(90L);

        when(stream.markProcessed(90L)).thenReturn(90L);

        settlement.recordReceivedLsn(110L);
        settlement.settleHandledEvent(stream, event);

        InOrder inOrder = inOrder(stream, persistence);
        inOrder.verify(stream).markProcessed(90L);
        inOrder.verify(persistence).persist(slotConfig, 90L);

        assertThat(health.lastPersistedLsn()).isEqualTo(90L);
        assertThat(health.observedReplicationLagBytes()).isEqualTo(20L);
        assertThat(health.lastSuccessfulEventEpochMillis()).isNotNull();
        verify(metrics).onEventProcessed("slot");
        verify(metrics).onFailureStreakUpdated("slot", 0);
        verify(metrics).onLastEventTimeUpdated(eq("slot"), anyLong());
        verify(metrics).onLastPersistedLsnUpdated("slot", 90L);
        verify(metrics).onReplicationLagBytesUpdated("slot", 20L);
    }

    @Test
    void retryLeavesLsnUnadvanced() {
        LsnPersistence persistence = mock(LsnPersistence.class);
        SlotHealth health = new SlotHealth();
        NotifierMetrics metrics = mock(NotifierMetrics.class);
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        EventSettlement settlement = new EventSettlement(slotConfig(), persistence, health, metrics);

        settlement.leaveUnadvancedForRetry();

        verifyNoInteractions(stream, persistence, metrics);
        assertThat(health.lastPersistedLsn()).isNull();
        assertThat(health.observedReplicationLagBytes()).isNull();
        assertThat(health.lastSuccessfulEventEpochMillis()).isNull();
    }

    @Test
    void failedEventDropAcknowledgesPersistsAndMarksWorkerFailureRecovered() {
        PgNotifier.SlotConfig slotConfig = slotConfig();
        LsnPersistence persistence = mock(LsnPersistence.class);
        SlotHealth health = new SlotHealth();
        NotifierMetrics metrics = mock(NotifierMetrics.class);
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        EventSettlement settlement = new EventSettlement(slotConfig, persistence, health, metrics);
        ChangeEvent event = event(120L);

        when(stream.markProcessed(120L)).thenReturn(120L);
        health.recordWorkerFailure(new ErrorContext(
                new IllegalStateException("handler failed"),
                event,
                ErrorOrigin.CHANGE_HANDLER,
                ErrorType.TRANSIENT));

        settlement.recordReceivedLsn(130L);
        settlement.settleFailedEventDrop(stream, event);

        InOrder inOrder = inOrder(stream, persistence);
        inOrder.verify(stream).markProcessed(120L);
        inOrder.verify(persistence).persist(slotConfig, 120L);

        assertThat(health.lastWorkerFailureRecovered()).isTrue();
        assertThat(health.lastPersistedLsn()).isEqualTo(120L);
        assertThat(health.observedReplicationLagBytes()).isEqualTo(10L);
        verify(metrics).onLastPersistedLsnUpdated("slot", 120L);
        verify(metrics).onReplicationLagBytesUpdated("slot", 10L);
    }

    @Test
    void asyncSettlementAcknowledgesCompletedEventsInSequenceUsingEventLsns() {
        PgNotifier.SlotConfig slotConfig = slotConfig();
        LsnPersistence persistence = mock(LsnPersistence.class);
        SlotHealth health = new SlotHealth();
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        EventSettlement settlement = new EventSettlement(slotConfig, persistence, health, NotifierMetrics.noop());
        AtomicInteger settledEvents = new AtomicInteger();

        when(stream.markProcessed(101L)).thenReturn(101L);
        when(stream.markProcessed(202L)).thenReturn(202L);

        EventSettlement.AsyncEvent first = settlement.beginAsyncEvent(0L, event(101L));
        EventSettlement.AsyncEvent second = settlement.beginAsyncEvent(1L, event(202L));

        second.handled();
        settlement.settleCompletedAsyncEvents(stream, settledEvents::incrementAndGet);

        verify(stream, never()).markProcessed(anyLong());
        assertThat(settledEvents).hasValue(0);

        first.handled();
        settlement.settleCompletedAsyncEvents(stream, settledEvents::incrementAndGet);

        InOrder inOrder = inOrder(stream, persistence);
        inOrder.verify(stream).markProcessed(101L);
        inOrder.verify(persistence).persist(slotConfig, 101L);
        inOrder.verify(stream).markProcessed(202L);
        inOrder.verify(persistence).persist(slotConfig, 202L);
        assertThat(settledEvents).hasValue(2);
    }

    @Test
    void asyncNotAdvancedOutcomeBlocksLaterAcknowledgements() {
        LsnPersistence persistence = mock(LsnPersistence.class);
        ReplicationStream stream = mock(ReplicationStream.class, withSettings().withoutAnnotations());
        EventSettlement settlement = new EventSettlement(slotConfig(), persistence, new SlotHealth(), NotifierMetrics.noop());
        AtomicInteger settledEvents = new AtomicInteger();

        EventSettlement.AsyncEvent first = settlement.beginAsyncEvent(0L, event(101L));
        EventSettlement.AsyncEvent second = settlement.beginAsyncEvent(1L, event(202L));

        first.notAdvanced();
        second.handled();
        settlement.settleCompletedAsyncEvents(stream, settledEvents::incrementAndGet);

        verify(stream, never()).markProcessed(anyLong());
        verifyNoInteractions(persistence);
        assertThat(settledEvents).hasValue(0);
        assertThat(settlement.hasPendingAsyncEvents()).isTrue();
    }

    private static ChangeEvent event(final long lsn) {
        return new ChangeEvent("{\"payload\":\"value\"}", lsn, null, null, null);
    }

    private static PgNotifier.SlotConfig slotConfig() {
        return new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", "slot");
    }

}
