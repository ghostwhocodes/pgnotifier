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

package pgnotifier.config;

import org.junit.jupiter.api.Test;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.PgNotifier;
import pgnotifier.util.config.Configuration;
import pgnotifier.util.config.MapConfigurationSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

class PgNotifierPropertiesTest {

    @Test
    void mapsSlotProcessAndPluginConfiguration() {
        PgNotifierProperties properties = new PgNotifierProperties(new Configuration(List.of(
                new MapConfigurationSource(Map.ofEntries(
                        entry("username", "user"),
                        entry("password", "secret"),
                        entry("database", "jdbc:postgresql://localhost/postgres"),
                        entry("slotname", "demo_slot"),
                        entry("poolsize", "3"),
                        entry("errorbackoffseconds", "15"),
                        entry("shutdowntimeoutseconds", "20"),
                        entry("sleepmillis", "500"),
                        entry("throttlemillis", "200"),
                        entry("statusintervalseconds", "30"),
                        entry("includetables", "public.users"))))));

        assertThat(properties.getSlotConfig()).isEqualTo(new PgNotifier.SlotConfig(
                "user",
                "secret",
                "jdbc:postgresql://localhost/postgres",
                "demo_slot"));
        assertThat(properties.getProcessConfig()).isEqualTo(new PgNotifier.ProcessConfig(
                3,
                15,
                20,
                500L,
                200L));
        assertThat(properties.getPluginConfig()).isEqualTo(new PgNotifier.PluginConfig(
                30,
                "public.users"));
    }

    @Test
    void mapsAsyncHandlerConfiguration() {
        PgNotifierProperties properties = new PgNotifierProperties(new Configuration(List.of(
                new MapConfigurationSource(Map.of(
                        "handler.mode", "ASYNC_QUEUE",
                        "handler.worker.threads", "4",
                        "handler.queue.capacity", "100",
                        "handler.queue.overflow.policy", "DROP_OLDEST")))));

        assertThat(properties.getHandlerExecutionConfig()).isEqualTo(HandlerExecutionConfig.asyncQueue(
                4,
                100,
                HandlerExecutionConfig.QueueOverflowPolicy.DROP_OLDEST));
    }

    @Test
    void defaultsToInlineHandlerConfiguration() {
        PgNotifierProperties properties = new PgNotifierProperties(new Configuration(List.of(
                new MapConfigurationSource(Map.of()))));

        assertThat(properties.getHandlerExecutionConfig()).isEqualTo(HandlerExecutionConfig.inline());
    }
}
