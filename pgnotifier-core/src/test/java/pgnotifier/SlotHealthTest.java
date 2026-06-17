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

import static org.assertj.core.api.Assertions.assertThat;

class SlotHealthTest {

    @Test
    void recordsLastSuccessfulEventAndFailure() {
        SlotHealth health = new SlotHealth();

        long now = System.currentTimeMillis();
        health.markSuccessfulEvent(now);
        assertThat(health.lastSuccessfulEventEpochMillis()).isEqualTo(now);

        RuntimeException failure = new RuntimeException("boom");
        health.recordWorkerFailure(new ErrorContext(failure, null, ErrorOrigin.REPLICATION, ErrorType.TRANSIENT));
        assertThat(health.lastWorkerFailureEpochMillis()).isNotNull();
        assertThat(health.lastWorkerFailureRecovered()).isFalse();
        assertThat(health.lastWorkerFailureOrigin()).isEqualTo(ErrorOrigin.REPLICATION);
        assertThat(health.lastWorkerFailureType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(health.lastWorkerFailureClassName()).isEqualTo(RuntimeException.class.getName());
        assertThat(health.lastWorkerFailureMessage()).isEqualTo("boom");

        health.markWorkerFailureRecovered();
        assertThat(health.lastWorkerFailureRecovered()).isTrue();

        health.recordFailure(null);
        assertThat(health.lastWorkerFailureEpochMillis()).isNull();
        assertThat(health.lastWorkerFailureRecovered()).isFalse();
        assertThat(health.lastWorkerFailureOrigin()).isNull();
        assertThat(health.lastWorkerFailureType()).isNull();
        assertThat(health.lastWorkerFailureClassName()).isNull();
        assertThat(health.lastWorkerFailureMessage()).isNull();
    }

    @Test
    void recordsSafeObservabilityFields() {
        SlotHealth health = new SlotHealth();

        assertThat(health.lastPersistedLsn()).isNull();
        assertThat(health.handlerQueueDepth()).isZero();
        assertThat(health.handlerQueueCapacity()).isZero();
        assertThat(health.observedReplicationLagBytes()).isNull();

        health.recordLastPersistedLsn(100L);
        health.recordHandlerQueueDepth(3, 10);
        health.recordObservedReplicationLagBytes(25L);

        assertThat(health.lastPersistedLsn()).isEqualTo(100L);
        assertThat(health.handlerQueueDepth()).isEqualTo(3);
        assertThat(health.handlerQueueCapacity()).isEqualTo(10);
        assertThat(health.observedReplicationLagBytes()).isEqualTo(25L);
    }
}
