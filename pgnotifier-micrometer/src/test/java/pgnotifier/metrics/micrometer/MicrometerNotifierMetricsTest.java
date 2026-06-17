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

package pgnotifier.metrics.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerNotifierMetricsTest {

    @Test
    void publishesRestartCountersAndFailureStreakGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerNotifierMetrics metrics = new MicrometerNotifierMetrics(registry);

        metrics.onProcessRestart("slot-a", 1);
        metrics.onProcessRestart("slot-a", 2);
        metrics.onFailureStreakUpdated("slot-a", 3);

        assertThat(registry.get("pgnotifier_restarts_total")
                           .tag("slot", "slot-a")
                           .counter()
                           .count()).isEqualTo(2.0d);
        assertThat(registry.get("pgnotifier_failure_streak")
                           .tag("slot", "slot-a")
                           .gauge()
                           .value()).isEqualTo(3.0d);
    }

    @Test
    void publishesQueueLsnAndLagMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerNotifierMetrics metrics = new MicrometerNotifierMetrics(registry);

        metrics.onHandlerQueueDepthUpdated("slot-a", 4, 10);
        metrics.onHandlerQueueDrainLatency("slot-a", 12L);
        metrics.onLastPersistedLsnUpdated("slot-a", 100L);
        metrics.onReplicationLagBytesUpdated("slot-a", 25L);

        assertThat(registry.get("pgnotifier_handler_queue_depth")
                           .tag("slot", "slot-a")
                           .gauge()
                           .value()).isEqualTo(4.0d);
        assertThat(registry.get("pgnotifier_handler_queue_capacity")
                           .tag("slot", "slot-a")
                           .gauge()
                           .value()).isEqualTo(10.0d);
        assertThat(registry.get("pgnotifier_handler_queue_drain_latency_millis")
                           .tag("slot", "slot-a")
                           .summary()
                           .totalAmount()).isEqualTo(12.0d);
        assertThat(registry.get("pgnotifier_last_persisted_lsn")
                           .tag("slot", "slot-a")
                           .gauge()
                           .value()).isEqualTo(100.0d);
        assertThat(registry.get("pgnotifier_replication_lag_bytes")
                           .tag("slot", "slot-a")
                           .gauge()
                           .value()).isEqualTo(25.0d);
    }
}
