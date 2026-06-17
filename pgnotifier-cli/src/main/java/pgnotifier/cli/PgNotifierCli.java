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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgnotifier.ChangeEvent;
import pgnotifier.ChangeHandler;
import pgnotifier.ErrorContext;
import pgnotifier.ErrorHandler;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.PgNotifier;
import pgnotifier.PgNotifierBuilder;
import pgnotifier.ProcessingDecision;
import pgnotifier.config.CommandConfiguration;
import pgnotifier.config.PgNotifierProperties;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * Picocli-based command-line interface for {@link PgNotifier}.
 * <p>
 * This CLI tails PostgreSQL logical replication changes and pretty-prints them as JSON,
 * with options for table filtering, summary statistics and dry-run connectivity checks.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * java -jar pgnotifier-cli.jar \
 *   pgnotifier \
 *   --database jdbc:postgresql://localhost/postgres \
 *   --username user \
 *   --password secret \
 *   --slotname demo_slot \
 *   --summary
 * }</pre>
 *
 * @author Nos Doughty
 */
@Command(
        name = "pgnotifier",
        mixinStandardHelpOptions = true,
        description = "Tail PostgreSQL logical replication changes and pretty-print them as JSON."
)
public class PgNotifierCli implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(PgNotifierCli.class);

    @Option(
            names = {"-c", "--config"},
            description = "Path to properties file (default: ${DEFAULT-VALUE})",
            defaultValue = CommandConfiguration.DEFAULT_CONFIG_PATH
    )
    @Nullable String configPath;

    @Option(names = "--database", description = "Override JDBC URL")
    @Nullable String database;

    @Option(names = "--username", description = "Override database username")
    @Nullable String username;

    @Option(names = "--password", description = "Override database password")
    @Nullable String password;

    @Option(names = "--slotname", description = "Override replication slot name")
    @Nullable String slotname;

    @Option(names = "--poolsize", description = "Worker thread pool size")
    @Nullable Integer poolSize;

    @Option(names = "--error-backoff-seconds", description = "Seconds to wait before restarting worker on error")
    @Nullable Integer errorBackoffSeconds;

    @Option(names = "--shutdown-timeout-seconds", description = "Seconds to wait for graceful shutdown")
    @Nullable Integer shutdownTimeoutSeconds;

    @Option(names = "--sleep-millis", description = "Sleep when no work is available (ms)")
    @Nullable Integer sleepMillis;

    @Option(names = "--throttle-millis", description = "Sleep when work was done (ms)")
    @Nullable Integer throttleMillis;

    @Option(names = "--status-interval-seconds", description = "Status interval seconds for replication stream")
    @Nullable Integer statusIntervalSeconds;

    @Option(names = "--include-tables", description = "wal2json add-tables option value")
    @Nullable String includeTables;

    @Option(
            names = "--handler-mode",
            description = "Handler execution mode: INLINE or ASYNC_QUEUE"
    )
    @Nullable String handlerMode;

    @Option(
            names = "--handler-worker-threads",
            description = "Number of handler worker threads when using ASYNC_QUEUE mode"
    )
    @Nullable Integer handlerWorkerThreads;

    @Option(
            names = "--handler-queue-capacity",
            description = "Capacity of the internal handler queue when using ASYNC_QUEUE mode"
    )
    @Nullable Integer handlerQueueCapacity;

    @Option(
            names = "--handler-queue-overflow-policy",
            description = "Handler queue overflow policy: BLOCK, DROP_OLDEST, DROP_NEWEST, FATAL"
    )
    @Nullable String handlerQueueOverflowPolicy;

    @Option(
            names = "--print-table",
            description = "Include table name prefix when printing events"
    )
    boolean printTable;

    @Option(
            names = "--filter-table",
            description = "Only print events for this table name"
    )
    @Nullable String filterTable;

    @Option(
            names = "--summary",
            description = "Collect and print summary stats periodically and on shutdown"
    )
    boolean summary;

    @Option(
            names = "--summary-only",
            description = "Only print summary stats; suppress per-event output"
    )
    boolean summaryOnly;

    @Option(
            names = "--summary-interval-events",
            description = "Print summary every N events (0 = only on shutdown)",
            defaultValue = "0"
    )
    long summaryIntervalEvents;

    @Option(
            names = "--max-events",
            description = "Exit after processing this many events"
    )
    @Nullable Long maxEvents;

    @Option(
            names = "--once",
            description = "Exit after a single event (shortcut for --max-events=1)"
    )
    boolean once;

    @Option(
            names = "--dry-run",
            description = "Validate configuration and database connectivity, then exit"
    )
    boolean dryRun;

    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong totalEvents = new AtomicLong();
    private final ConcurrentMap<String, LongAdder> tableCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> opCounts = new ConcurrentHashMap<>();

    private volatile @Nullable PgNotifier notifier;
    private volatile @Nullable CountDownLatch shutdownLatch;

    /**
     * Executes the CLI command using the configured options.
     *
     * @return process exit code
     * @throws Exception in case of unrecoverable failures
     */
    @Override
    public Integer call() throws Exception {

        final PgNotifierProperties properties = CommandConfiguration.builder(configPath)
                .connectionOverrides(database, username, password, slotname)
                .processOverrides(poolSize, errorBackoffSeconds, shutdownTimeoutSeconds, sleepMillis, throttleMillis)
                .pluginOverrides(statusIntervalSeconds, includeTables)
                .handlerOverrides(handlerMode, handlerWorkerThreads, handlerQueueCapacity, handlerQueueOverflowPolicy)
                .build()
                .properties();

        if (once && maxEvents == null) {
            maxEvents = 1L;
        }

        if (dryRun) {
            return performDryRun(properties);
        }

        final ChangeHandler changeHandler = this::handleChange;
        final ErrorHandler errorHandler = this::handleError;

        this.shutdownLatch = new CountDownLatch(1);

        this.notifier = new PgNotifierBuilder()
                .addSlot(properties.getSlotConfig())
                .processConfig(properties.getProcessConfig())
                .pluginConfig(properties.getPluginConfig())
                .handlerExecutionConfig(properties.getHandlerExecutionConfig())
                .changeHandler(changeHandler)
                .errorHandler(errorHandler)
                .build();

        this.notifier.start();

        // keep process alive until interrupted (Ctrl+C) or max-events reached
        try {
            this.shutdownLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (summary) {
            printSummary(true);
        }

        return 0;
    }

    private ProcessingDecision handleChange(final ChangeEvent event) {

        if (filterTable != null) {
            final String table = event.table();
            if (table == null || !filterTable.equals(table)) {
                return ProcessingDecision.DROP;
            }
        }

        final String payload = event.payload();
        if (payload == null) {
            return ProcessingDecision.DROP;
        }

        String output = payload;
        try {
            final JsonNode node = mapper.readTree(payload);
            output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            logger.debug("Payload is not valid JSON, printing raw payload instead", e);
        }

        incrementStats(event);

        if (!summaryOnly) {
            if (printTable && event.table() != null) {
                System.out.println(event.table() + ": " + output);
            } else {
                System.out.println(output);
            }
        }

        maybeStopAfterMaxEvents();

        return ProcessingDecision.COMMIT;
    }

    private ErrorHandlingDecision handleError(final ErrorContext context) {

        final ChangeEvent event = context.event();
        final String payload = event != null ? event.payload() : null;
        logger.error("Error while processing payload: {}", payload, context.exception());
        // For a debug CLI we drop the offending event but keep running.
        if (event != null) {
            incrementStats(event);
            maybeStopAfterMaxEvents();
        }
        return ErrorHandlingDecision.dropAndContinue(context);
    }

    private void incrementStats(final ChangeEvent event) {

        final long total = totalEvents.incrementAndGet();

        final String table = event.table();
        if (table != null) {
            tableCounts.computeIfAbsent(table, t -> new LongAdder()).increment();
        }

        final String op = event.operation();
        if (op != null) {
            opCounts.computeIfAbsent(op, o -> new LongAdder()).increment();
        }

        if (summary && summaryIntervalEvents > 0 && total % summaryIntervalEvents == 0) {
            printSummary(false);
        }

    }

    private void maybeStopAfterMaxEvents() {

        if (maxEvents != null && maxEvents > 0 && totalEvents.get() >= maxEvents) {

            final PgNotifier current = this.notifier;
            final CountDownLatch latch = this.shutdownLatch;

            if (current != null) {
                current.stop();
            }
            if (latch != null) {
                latch.countDown();
            }
        }

    }

    private void printSummary(final boolean isFinal) {

        System.out.println();
        System.out.println(isFinal ? "Final summary:" : "Summary:");
        System.out.println("Total events: " + totalEvents.get());

        if (!tableCounts.isEmpty()) {
            System.out.println("By table:");
            tableCounts.forEach((table, count) ->
                    System.out.println("  " + table + ": " + count.longValue()));
        }

        if (!opCounts.isEmpty()) {
            System.out.println("By operation:");
            opCounts.forEach((op, count) ->
                    System.out.println("  " + op + ": " + count.longValue()));
        }

        System.out.println();
    }

    private Integer performDryRun(final PgNotifierProperties properties) {

        final PgNotifier.SlotConfig slotConfig = properties.getSlotConfig();

        try (Connection connection = DriverManager.getConnection(
                slotConfig.database(),
                slotConfig.username(),
                slotConfig.password())) {

            logger.debug("Connected to database using driver {}", connection.getMetaData().getDriverName());
            logger.info("Successfully connected to database {}", slotConfig.database());
            return 0;

        } catch (Exception e) {

            logger.error("Failed to connect to database {} as {}", slotConfig.database(), slotConfig.username(), e);
            return 1;

        }

    }

    /**
     * Standard Java entry point for launching the CLI.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        final int exitCode = new CommandLine(new PgNotifierCli()).execute(args);
        System.exit(exitCode);
    }

}
