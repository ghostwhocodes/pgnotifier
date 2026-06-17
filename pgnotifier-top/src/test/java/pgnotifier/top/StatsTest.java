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

package pgnotifier.top;

import org.junit.jupiter.api.Test;

import pgnotifier.ChangeEvent;

import static org.assertj.core.api.Assertions.assertThat;

class StatsTest {

    @Test
    void incrementIncreasesTotalEvents() {
        Stats stats = new Stats();

        assertThat(stats.getTotalEvents()).isZero();

        stats.increment(new ChangeEvent("payload", 1L, null, null, null));
        stats.increment(new ChangeEvent("payload", 2L, null, null, null));

        assertThat(stats.getTotalEvents()).isEqualTo(2L);
    }

    @Test
    void tracksPerTableAndPerOperationCounts() {
        Stats stats = new Stats();

        stats.increment(new ChangeEvent("payload", 1L, "public", "users", "insert"));
        stats.increment(new ChangeEvent("payload", 2L, "public", "users", "insert"));
        stats.increment(new ChangeEvent("payload", 3L, "public", "orders", "update"));

        assertThat(stats.snapshotTableCounts())
                .containsEntry("users", 2L)
                .containsEntry("orders", 1L);

        assertThat(stats.snapshotOperationCounts())
                .containsEntry("insert", 2L)
                .containsEntry("update", 1L);
    }

    @Test
    void idleSecondsIsZeroBeforeAnyEvents() {
        Stats stats = new Stats();

        long idle = stats.getIdleSeconds(System.currentTimeMillis());

        assertThat(idle).isZero();
    }

    @Test
    void idleSecondsReturnsWholeSecondsSinceLastEvent() throws InterruptedException {
        Stats stats = new Stats();

        stats.increment(new ChangeEvent("payload", 1L, null, null, null));

        Thread.sleep(20L);

        long idle = stats.getIdleSeconds(System.currentTimeMillis());

        assertThat(idle).isGreaterThanOrEqualTo(0L);
    }
}
