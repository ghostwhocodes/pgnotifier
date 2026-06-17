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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pgnotifier.internal.replication.BatchReplicationWorker;
import pgnotifier.internal.replication.ReplicationSlot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LogicalReplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("royratcliffe/postgres-wal2json:13")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("pgnotifier_test")
                    .withUsername("pgnotifier")
                    .withPassword("pgnotifier")
                    .withCommand(
                            "postgres",
                            "-c", "wal_level=logical",
                            "-c", "max_replication_slots=10",
                            "-c", "max_wal_senders=10");

    @Test
    void batchWorkerConsumesWal2JsonChangesPersistsExactLsnsAndResumes() throws Exception {
        final String slotName = "slot_" + UUID.randomUUID().toString().replace("-", "");
        final String firstValue = "integration-first-" + UUID.randomUUID();
        final String secondValue = "integration-second-" + UUID.randomUUID();

        prepareTable();

        final PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig(
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                POSTGRES.getJdbcUrl(),
                slotName);
        final TrackingLsnPersistence persistence = new TrackingLsnPersistence();

        final CopyOnWriteArrayList<ChangeEvent> firstRunEvents = new CopyOnWriteArrayList<>();
        consumeUntilValue(slotConfig, persistence, firstValue, firstRunEvents, Optional.empty());

        final List<ChangeEvent> firstMatchingEvents = firstRunEvents.stream()
                .filter(event -> payloadContains(event, firstValue))
                .toList();
        assertThat(firstMatchingEvents).hasSize(1);
        final ChangeEvent firstEvent = firstMatchingEvents.getFirst();
        assertThat(firstEvent.schema()).isEqualTo("public");
        assertThat(firstEvent.table()).isEqualTo("wal_test");
        assertThat(firstEvent.operation()).isEqualTo("insert");
        assertThat(firstEvent.payload()).contains(firstValue);
        assertThat(persistence.persistedLsns()).containsExactlyElementsOf(
                firstRunEvents.stream().map(ChangeEvent::lsn).toList());
        assertThat(persistence.lastPersistedLsn()).isEqualTo(firstEvent.lsn());

        final long resumeLsn = persistence.lastPersistedLsn();
        final CopyOnWriteArrayList<ChangeEvent> secondRunEvents = new CopyOnWriteArrayList<>();
        consumeUntilValue(slotConfig, persistence, secondValue, secondRunEvents, Optional.of(resumeLsn));

        assertThat(secondRunEvents)
                .noneMatch(event -> payloadContains(event, firstValue))
                .anyMatch(event -> payloadContains(event, secondValue));
        assertThat(persistence.lastPersistedLsn()).isGreaterThan(resumeLsn);
    }

    private void consumeUntilValue(
            final PgNotifier.SlotConfig slotConfig,
            final TrackingLsnPersistence persistence,
            final String insertedValue,
            final CopyOnWriteArrayList<ChangeEvent> seenEvents,
            final Optional<Long> startingLsn) throws Exception {

        persistence.setStartingLsn(startingLsn);

        final CountDownLatch sawInsertedRow = new CountDownLatch(1);
        final ReplicationSlot slot = new ReplicationFactory().newReplicationSlot(
                slotConfig,
                PgNotifier.PluginConfig.defaults(),
                persistence);

        final BatchReplicationWorker worker = new BatchReplicationWorker(
                slot,
                slotConfig,
                event -> {
                    seenEvents.add(event);
                    if (payloadContains(event, insertedValue)) {
                        sawInsertedRow.countDown();
                    }
                    return ProcessingDecision.COMMIT;
                },
                context -> ErrorHandlingDecision.stopProcess(context),
                new Wal2JsonChangeEventDecoder(),
                10L,
                10L,
                10L,
                persistence);

        final Thread workerThread = new Thread(worker::start, "logical-replication-worker");

        try {
            workerThread.start();
            waitForSlotToBecomeActive(slotConfig.slotname(), Duration.ofSeconds(10));
            insertRow(insertedValue);

            assertThat(sawInsertedRow.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.stop();
            workerThread.join(5000L);
            slot.close();
        }
    }

    private static boolean payloadContains(final ChangeEvent event, final String value) {
        final String payload = event.payload();
        return payload != null && payload.contains(value);
    }

    private void prepareTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS wal_test");
            statement.execute("CREATE TABLE wal_test (id SERIAL PRIMARY KEY, data text)");
        }
    }

    private void insertRow(final String value) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO wal_test (data) VALUES (?)")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private void waitForSlotToBecomeActive(final String slotName, final Duration timeout) throws Exception {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (isSlotActive(slotName)) {
                return;
            }
            Thread.sleep(100L);
        }

        throw new AssertionError("Timed out waiting for replication slot to become active: " + slotName);
    }

    private boolean isSlotActive(final String slotName) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT active FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, slotName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static final class TrackingLsnPersistence implements LsnPersistence {

        private final AtomicLong lastPersistedLsn = new AtomicLong();
        private final CopyOnWriteArrayList<Long> persistedLsns = new CopyOnWriteArrayList<>();
        private volatile Optional<Long> startingLsn = Optional.empty();

        @Override
        public void persist(final PgNotifier.SlotConfig slotConfig, final long lsn) {
            this.persistedLsns.add(lsn);
            this.lastPersistedLsn.set(lsn);
        }

        @Override
        public Optional<Long> startingLsn(final PgNotifier.SlotConfig slotConfig) {
            return this.startingLsn;
        }

        void setStartingLsn(final Optional<Long> startingLsn) {
            this.startingLsn = startingLsn;
        }

        long lastPersistedLsn() {
            return this.lastPersistedLsn.get();
        }

        List<Long> persistedLsns() {
            return List.copyOf(this.persistedLsns);
        }
    }
}
