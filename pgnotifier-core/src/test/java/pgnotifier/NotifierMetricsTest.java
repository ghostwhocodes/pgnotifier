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

import static org.assertj.core.api.Assertions.assertThatCode;

class NotifierMetricsTest {

    @Test
    void noopMetricsAreSafeToInvoke() {
        NotifierMetrics metrics = NotifierMetrics.noop();

        assertThatCode(() -> {
            metrics.onEventProcessed("slot");
            metrics.onHandlerFailure("slot", new RuntimeException("boom"));
            metrics.onReplicationFailure("slot", new RuntimeException("boom"));
            metrics.onProcessRestart("slot", 2);
            metrics.onLastEventTimeUpdated("slot", 42L);
            metrics.onFailureStreakUpdated("slot", 2);
            metrics.onHandlerQueueDepthUpdated("slot", 3, 10);
            metrics.onHandlerQueueDrainLatency("slot", 12L);
            metrics.onLastPersistedLsnUpdated("slot", 100L);
            metrics.onReplicationLagBytesUpdated("slot", 25L);
        }).doesNotThrowAnyException();
    }
}
