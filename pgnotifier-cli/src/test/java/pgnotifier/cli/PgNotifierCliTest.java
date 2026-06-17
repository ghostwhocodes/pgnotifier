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

package pgnotifier.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class PgNotifierCliTest {

    @Test
    void parsesConnectionOverrides() {
        PgNotifierCli cli = new PgNotifierCli();
        new CommandLine(cli).parseArgs(
                "--database", "jdbc:postgresql://localhost/db",
                "--username", "user",
                "--password", "pass",
                "--slotname", "slot");

        assertThat(cli.database).isEqualTo("jdbc:postgresql://localhost/db");
        assertThat(cli.username).isEqualTo("user");
        assertThat(cli.password).isEqualTo("pass");
        assertThat(cli.slotname).isEqualTo("slot");
    }

    @Test
    void parsesTuningFlags() {
        PgNotifierCli cli = new PgNotifierCli();
        new CommandLine(cli).parseArgs(
                "--poolsize", "3",
                "--error-backoff-seconds", "15",
                "--shutdown-timeout-seconds", "20",
                "--sleep-millis", "500",
                "--throttle-millis", "200",
                "--status-interval-seconds", "30",
                "--include-tables", "public.table1,public.table2");

        assertThat(cli.poolSize).isEqualTo(3);
        assertThat(cli.errorBackoffSeconds).isEqualTo(15);
        assertThat(cli.shutdownTimeoutSeconds).isEqualTo(20);
        assertThat(cli.sleepMillis).isEqualTo(500);
        assertThat(cli.throttleMillis).isEqualTo(200);
        assertThat(cli.statusIntervalSeconds).isEqualTo(30);
        assertThat(cli.includeTables).isEqualTo("public.table1,public.table2");
    }

    @Test
    void parsesHandlerExecutionFlags() {
        PgNotifierCli cli = new PgNotifierCli();
        new CommandLine(cli).parseArgs(
                "--handler-mode", "ASYNC_QUEUE",
                "--handler-worker-threads", "4",
                "--handler-queue-capacity", "100",
                "--handler-queue-overflow-policy", "DROP_NEWEST");

        assertThat(cli.handlerMode).isEqualTo("ASYNC_QUEUE");
        assertThat(cli.handlerWorkerThreads).isEqualTo(4);
        assertThat(cli.handlerQueueCapacity).isEqualTo(100);
        assertThat(cli.handlerQueueOverflowPolicy).isEqualTo("DROP_NEWEST");
    }

    @Test
    void parsesSummaryAndLimitFlags() {
        PgNotifierCli cli = new PgNotifierCli();
        new CommandLine(cli).parseArgs(
                "--summary",
                "--summary-only",
                "--summary-interval-events", "5",
                "--max-events", "10",
                "--once",
                "--print-table",
                "--filter-table", "mytable");

        assertThat(cli.summary).isTrue();
        assertThat(cli.summaryOnly).isTrue();
        assertThat(cli.summaryIntervalEvents).isEqualTo(5L);
        assertThat(cli.maxEvents).isEqualTo(10L);
        assertThat(cli.once).isTrue();
        assertThat(cli.printTable).isTrue();
        assertThat(cli.filterTable).isEqualTo("mytable");
    }
}
