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

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Terminal-based dashboard for monitoring logical replication events in near real time.
 * <p>
 * Uses Lanterna to render a minimal "top"-style interface showing the total number of
 * processed events.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * java -jar pgnotifier-top.jar \
 *   pgnotifier-top \
 *   --database jdbc:postgresql://localhost/postgres \
 *   --username user \
 *   --password secret \
 *   --slotname demo_slot
 * }</pre>
 *
 * @author Nos Doughty
 */
@CommandLine.Command(
        name = "pgnotifier-top",
        mixinStandardHelpOptions = true,
        description = "Simple logical replication top-style dashboard"
)
public class TopCli implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(TopCli.class);
    private static final long METRICS_REFRESH_MILLIS = 5_000L;

    @CommandLine.Option(
            names = {"-c", "--config"},
            description = "Path to properties file (default: ${DEFAULT-VALUE})",
            defaultValue = CommandConfiguration.DEFAULT_CONFIG_PATH
    )
    @Nullable String configPath;

    @CommandLine.Option(names = "--database", description = "Override JDBC URL")
    @Nullable String database;

    @CommandLine.Option(names = "--username", description = "Override database username")
    @Nullable String username;

    @CommandLine.Option(names = "--password", description = "Override database password")
    @Nullable String password;

    @CommandLine.Option(names = "--slotname", description = "Override replication slot name")
    @Nullable String slotname;

    private final Stats stats = new Stats();

    /**
     * Executes the CLI command and runs the dashboard UI loop.
     *
     * @return process exit code
     */
    @Override
    public Integer call() throws Exception {

        final PgNotifierProperties properties = CommandConfiguration.builder(configPath)
                .connectionOverrides(database, username, password, slotname)
                .build()
                .properties();

        final ChangeHandler changeHandler = this::handleChange;
        final ErrorHandler errorHandler = this::handleError;

        final PgNotifier notifier = new PgNotifierBuilder()
                .addSlot(properties.getSlotConfig())
                .processConfig(properties.getProcessConfig())
                .pluginConfig(properties.getPluginConfig())
                .handlerExecutionConfig(properties.getHandlerExecutionConfig())
                .changeHandler(changeHandler)
                .errorHandler(errorHandler)
                .build()
                .start();

        @Nullable Connection metricsConnection = null;
        try {
            metricsConnection = openMetricsConnection(properties);
        } catch (Exception e) {
            logger.warn("Failed to establish metrics connection; pg_stat_* metrics will be unavailable", e);
        }

        try {
            runUiLoop(metricsConnection);
        } finally {
            notifier.stop();
            if (metricsConnection != null) {
                try {
                    metricsConnection.close();
                } catch (Exception e) {
                    logger.warn("Failed to close metrics connection", e);
                }
            }
        }

        return 0;
    }

    private ProcessingDecision handleChange(final ChangeEvent event) {
        stats.increment(event);
        return ProcessingDecision.COMMIT;
    }

    private ErrorHandlingDecision handleError(final ErrorContext context) {
        logger.error("Error while processing event", context.exception());
        // count it but keep going
        stats.increment(context.event());
        return ErrorHandlingDecision.dropAndContinue(context);
    }

    private Connection openMetricsConnection(final PgNotifierProperties properties) throws SQLException {

        final PgNotifier.SlotConfig slotConfig = properties.getSlotConfig();

        return DriverManager.getConnection(
                slotConfig.database(),
                slotConfig.username(),
                slotConfig.password());

    }

    private Map<String, Long> fetchTableMetrics(final @Nullable Connection connection) {

        final Map<String, Long> rowsByTable = new LinkedHashMap<>();

        if (connection == null) {
            return rowsByTable;
        }

        final String sql = """
                SELECT relname AS table_name, n_live_tup
                FROM pg_stat_user_tables
                ORDER BY n_live_tup DESC
                LIMIT 5
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) {
                rowsByTable.put(rs.getString("table_name"), rs.getLong("n_live_tup"));
            }

        } catch (Exception e) {
            logger.warn("Failed to query pg_stat_user_tables for metrics", e);
        }

        return rowsByTable;
    }

    private void runUiLoop(final @Nullable Connection metricsConnection) throws IOException {

        final DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory();
        try (var terminal = terminalFactory.createTerminal();
             Screen screen = new com.googlecode.lanterna.screen.TerminalScreen(terminal)) {

            screen.startScreen();
            screen.setCursorPosition(null);

            boolean running = true;

            long lastRateSampleMillis = 0L;
            long lastRateSampleTotal = 0L;
            long lastMetricsRefreshMillis = 0L;
            Map<String, Long> latestTableRows = Map.of();

            while (running) {
                final long now = System.currentTimeMillis();

                final TerminalSize size = screen.doResizeIfNecessary();
                if (size != null) {
                    screen.clear();
                }

                final TextGraphics g = screen.newTextGraphics();

                g.putString(0, 0, "pgnotifier-top");
                g.putString(0, 2, "Press 'q' to quit");
                final long totalEvents = stats.getTotalEvents();

                double eventsPerSecond = 0.0d;
                if (lastRateSampleMillis > 0L && now > lastRateSampleMillis) {
                    final long deltaEvents = totalEvents - lastRateSampleTotal;
                    final long deltaMillis = now - lastRateSampleMillis;
                    if (deltaMillis > 0L && deltaEvents >= 0L) {
                        eventsPerSecond = (deltaEvents * 1000.0d) / deltaMillis;
                    }
                }
                lastRateSampleMillis = now;
                lastRateSampleTotal = totalEvents;

                final long idleSeconds = stats.getIdleSeconds(now);

                g.putString(0, 4, "Total events: " + totalEvents);
                g.putString(0, 5, String.format("Events/sec (approx): %.2f", eventsPerSecond));
                g.putString(0, 6, "Idle seconds: " + idleSeconds);

                if (metricsConnection != null && now - lastMetricsRefreshMillis >= METRICS_REFRESH_MILLIS) {
                    latestTableRows = fetchTableMetrics(metricsConnection);
                    lastMetricsRefreshMillis = now;
                }

                int row = 8;

                if (!latestTableRows.isEmpty()) {
                    g.putString(0, row++, "Table rows (pg_stat_user_tables):");
                    for (Map.Entry<String, Long> entry : latestTableRows.entrySet()) {
                        if (row >= screen.getTerminalSize().getRows()) {
                            break;
                        }
                        g.putString(2, row++, entry.getKey() + ": " + entry.getValue());
                    }
                    row++;
                }

                final Map<String, Long> tableCounts = stats.snapshotTableCounts();
                if (!tableCounts.isEmpty() && row < screen.getTerminalSize().getRows()) {
                    g.putString(0, row++, "Events by table:");
                    final List<Map.Entry<String, Long>> sortedTables = new ArrayList<>(tableCounts.entrySet());
                    sortedTables.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

                    int printedTables = 0;
                    for (Map.Entry<String, Long> entry : sortedTables) {
                        if (printedTables >= 5 || row >= screen.getTerminalSize().getRows()) {
                            break;
                        }
                        g.putString(2, row++, entry.getKey() + ": " + entry.getValue());
                        printedTables++;
                    }
                    row++;
                }

                final Map<String, Long> opCounts = stats.snapshotOperationCounts();
                if (!opCounts.isEmpty() && row < screen.getTerminalSize().getRows()) {
                    g.putString(0, row++, "Events by operation:");
                    final List<Map.Entry<String, Long>> sortedOps = new ArrayList<>(opCounts.entrySet());
                    sortedOps.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

                    for (Map.Entry<String, Long> entry : sortedOps) {
                        if (row >= screen.getTerminalSize().getRows()) {
                            break;
                        }
                        g.putString(2, row++, entry.getKey() + ": " + entry.getValue());
                    }
                }

                screen.refresh();

                KeyStroke key = screen.pollInput();
                if (key != null) {
                    if (key.getKeyType() == KeyType.Character && (key.getCharacter() == 'q' || key.getCharacter() == 'Q')) {
                        running = false;
                    } else if (key.getKeyType() == KeyType.EOF) {
                        running = false;
                    }
                }

                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }

            screen.stopScreen();
        }

    }

    /**
     * Standard Java entry point for launching the dashboard CLI.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        final int exitCode = new CommandLine(new TopCli()).execute(args);
        System.exit(exitCode);
    }

}
