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

import org.junit.jupiter.api.Test;
import pgnotifier.util.LatchedProcess;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgNotifierTest {

    @Test
    void startAndStopDelegateToAllSlotRuntimes() {
        LatchedProcess firstProcess = mock(LatchedProcess.class);
        LatchedProcess secondProcess = mock(LatchedProcess.class);
        PgNotifier notifier = new PgNotifier(List.of(
                runtime("slot1", new SlotHealth(), firstProcess),
                runtime("slot2", new SlotHealth(), secondProcess)));

        notifier.start();
        verify(firstProcess).start();
        verify(secondProcess).start();

        notifier.stop();
        verify(firstProcess).stop();
        verify(secondProcess).stop();
    }

    @Test
    void healthAggregatesSlotRuntimeSnapshots() {
        LatchedProcess process = mock(LatchedProcess.class);
        when(process.state()).thenReturn(LatchedProcess.State.RUNNING);
        when(process.isRunning()).thenReturn(true);
        when(process.lastFailureEpochMillis()).thenReturn(null);

        SlotHealth slotHealth = new SlotHealth();
        slotHealth.markSuccessfulEvent(123L);
        slotHealth.recordFailureStreak(2);
        slotHealth.recordLastPersistedLsn(456L);
        slotHealth.recordHandlerQueueDepth(3, 10);
        slotHealth.recordObservedReplicationLagBytes(7L);

        PgNotifier notifier = new PgNotifier(List.of(runtime("slot1", slotHealth, process)));

        List<PgNotifier.SlotHealthSnapshot> health = notifier.health();

        assertThat(health).hasSize(1);
        PgNotifier.SlotHealthSnapshot snapshot = health.getFirst();
        assertThat(snapshot.slot()).isEqualTo("slot1");
        assertThat(snapshot.state()).isEqualTo(ProcessState.RUNNING);
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.failureCount()).isEqualTo(2);
        assertThat(snapshot.lastSuccessfulEventEpochMillis()).isEqualTo(123L);
        assertThat(snapshot.lastPersistedLsn()).isEqualTo(456L);
        assertThat(snapshot.handlerQueueDepth()).isEqualTo(3);
        assertThat(snapshot.handlerQueueCapacity()).isEqualTo(10);
        assertThat(snapshot.observedReplicationLagBytes()).isEqualTo(7L);
        assertThat(snapshot.lastWorkerFailure()).isNull();
        assertThat(snapshot.lastProcessFailure()).isNull();
    }

    private static SlotRuntime runtime(
            final String slotName,
            final SlotHealth slotHealth,
            final LatchedProcess process) {

        return new SlotRuntime(
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", slotName),
                slotHealth,
                process);
    }

}
