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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandConfigurationTest {

    @Test
    void assemblesPropertiesFromFileAndCommandOverrides() throws Exception {
        Path propertiesFile = Files.createTempFile("pgnotifier-command-", ".properties");
        Files.writeString(propertiesFile, """
                username=file_user
                password=file_password
                database=jdbc:postgresql://localhost/file
                slotname=file_slot
                poolsize=2
                errorbackoffseconds=12
                shutdowntimeoutseconds=30
                sleepmillis=100
                throttlemillis=50
                statusintervalseconds=20
                includetables=public.file_table
                handler.mode=INLINE
                """);

        CommandConfiguration commandConfiguration = CommandConfiguration.builder(propertiesFile.toString())
                .connectionOverrides(null, "cli_user", null, "cli_slot")
                .processOverrides(99, null, 0, 90_000, null)
                .pluginOverrides(0, "public.cli_table")
                .handlerOverrides("async_queue", 4, 100, "drop_newest")
                .build();

        PgNotifierProperties properties = commandConfiguration.properties();

        assertThat(commandConfiguration.overrides())
                .containsEntry(PgNotifierProperties.USERNAME_KEY, "cli_user")
                .containsEntry(PgNotifierProperties.SLOT_NAME_KEY, "cli_slot")
                .containsEntry(PgNotifierProperties.POOL_SIZE_KEY, "99")
                .containsEntry(PgNotifierProperties.SHUTDOWN_TIMEOUT_SECONDS_KEY, "0")
                .containsEntry(PgNotifierProperties.SLEEP_MILLIS_KEY, "90000")
                .containsEntry(PgNotifierProperties.STATUS_INTERVAL_SECONDS_KEY, "0")
                .containsEntry(PgNotifierProperties.INCLUDE_TABLES_KEY, "public.cli_table")
                .containsEntry(PgNotifierProperties.HANDLER_MODE_KEY, "async_queue")
                .containsEntry(PgNotifierProperties.HANDLER_QUEUE_OVERFLOW_POLICY_KEY, "drop_newest")
                .doesNotContainKey(PgNotifierProperties.PASSWORD_KEY);

        assertThat(properties.getSlotConfig()).isEqualTo(new PgNotifier.SlotConfig(
                "cli_user",
                "file_password",
                "jdbc:postgresql://localhost/file",
                "cli_slot"));
        assertThat(properties.getProcessConfig()).isEqualTo(new PgNotifier.ProcessConfig(
                PgNotifierProperties.MAX_POOL_SIZE,
                12,
                1,
                PgNotifierProperties.MAX_SLEEP_MILLIS,
                50L));
        assertThat(properties.getPluginConfig()).isEqualTo(new PgNotifier.PluginConfig(
                1,
                "public.cli_table"));
        assertThat(properties.getHandlerExecutionConfig()).isEqualTo(HandlerExecutionConfig.asyncQueue(
                4,
                100,
                HandlerExecutionConfig.QueueOverflowPolicy.DROP_NEWEST));
    }

}
