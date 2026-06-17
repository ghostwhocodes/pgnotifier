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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import pgnotifier.internal.replication.ReplicationSlot;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ReplicationFactoryTest {

    private static final String JDBC_URL = "jdbc:pgnotifier-test://localhost/test";

    private RecordingDriver driver;

    @BeforeEach
    void registerDriver() throws SQLException {
        this.driver = new RecordingDriver();
        deregisterExistingTestDrivers();
        DriverManager.registerDriver(this.driver);
    }

    @AfterEach
    void deregisterDriver() throws SQLException {
        DriverManager.deregisterDriver(this.driver);
    }

    @Test
    void newReplicationSlotCreatesMissingSlotAndPassesStartingLsnThroughExactly() throws Exception {
        Connection queryConnection = mock(Connection.class, withSettings().withoutAnnotations());
        PreparedStatement statement = mock(PreparedStatement.class, withSettings().withoutAnnotations());
        ResultSet resultSet = mock(ResultSet.class, withSettings().withoutAnnotations());
        when(queryConnection.isValid(5)).thenReturn(true);
        when(queryConnection.prepareStatement("SELECT 1 from pg_replication_slots WHERE slot_name = ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Connection replicationConnection = mock(Connection.class, withSettings().withoutAnnotations());
        when(replicationConnection.isValid(5)).thenReturn(true);
        PGConnection pgConnection = mock(PGConnection.class, RETURNS_DEEP_STUBS);
        when(replicationConnection.unwrap(PGConnection.class)).thenReturn(pgConnection);

        ChainedLogicalCreateSlotBuilder createBuilder =
                mock(ChainedLogicalCreateSlotBuilder.class, withSettings().withoutAnnotations());
        when(createBuilder.withSlotName("demo_slot")).thenReturn(createBuilder);
        when(createBuilder.withOutputPlugin("wal2json")).thenReturn(createBuilder);
        when(pgConnection.getReplicationAPI().createReplicationSlot().logical()).thenReturn(createBuilder);

        ChainedLogicalStreamBuilder streamBuilder =
                mock(ChainedLogicalStreamBuilder.class, withSettings().withoutAnnotations());
        when(streamBuilder.withSlotName("demo_slot")).thenReturn(streamBuilder);
        when(streamBuilder.withStatusInterval(12, TimeUnit.SECONDS)).thenReturn(streamBuilder);
        when(streamBuilder.withSlotOption(anyString(), anyBoolean())).thenReturn(streamBuilder);
        when(streamBuilder.withSlotOption("add-tables", "public.users")).thenReturn(streamBuilder);
        when(streamBuilder.withStartPosition(LogSequenceNumber.valueOf(123L))).thenReturn(streamBuilder);
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(streamBuilder.start()).thenReturn(pgStream);
        when(pgConnection.getReplicationAPI().replicationStream().logical()).thenReturn(streamBuilder);

        this.driver.enqueue(queryConnection);
        this.driver.enqueue(replicationConnection);

        PgNotifier.SlotConfig slotConfig =
                new PgNotifier.SlotConfig("demo_user", "secret", JDBC_URL, "demo_slot");
        PgNotifier.PluginConfig pluginConfig = new PgNotifier.PluginConfig(12, "public.users");
        LsnPersistence lsnPersistence = new LsnPersistence() {
            @Override
            public Optional<Long> startingLsn(final PgNotifier.SlotConfig config) {
                return Optional.of(123L);
            }
        };

        ReplicationSlot slot = new ReplicationFactory().newReplicationSlot(slotConfig, pluginConfig, lsnPersistence);
        slot.newReplicationStream();
        slot.close();

        verify(statement).setString(1, "demo_slot");
        verify(createBuilder).withOutputPlugin("wal2json");
        verify(createBuilder).make();
        verify(streamBuilder).withStatusInterval(12, TimeUnit.SECONDS);
        verify(streamBuilder).withSlotOption("include-timestamp", true);
        verify(streamBuilder).withSlotOption("include-xids", true);
        verify(streamBuilder).withSlotOption("include-lsn", true);
        verify(streamBuilder).withSlotOption("include-schemas", true);
        verify(streamBuilder).withSlotOption("add-tables", "public.users");
        verify(streamBuilder).withStartPosition(LogSequenceNumber.valueOf(123L));
        verify(streamBuilder).start();
        verify(queryConnection).close();
        verify(replicationConnection).close();

        assertThat(this.driver.seenProperties()).hasSize(2);
        Properties queryProps = this.driver.seenProperties().getFirst();
        Properties replicationProps = this.driver.seenProperties().get(1);
        assertThat(queryProps.getProperty(PGProperty.USER.getName())).isEqualTo("demo_user");
        assertThat(queryProps.getProperty(PGProperty.PASSWORD.getName())).isEqualTo("secret");
        assertThat(queryProps.getProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName())).isEqualTo("9.4");
        assertThat(replicationProps.getProperty(PGProperty.REPLICATION.getName())).isEqualTo("database");
        assertThat(replicationProps.getProperty(PGProperty.PREFER_QUERY_MODE.getName())).isEqualTo("simple");
    }

    @Test
    void newReplicationSlotSkipsCreationWhenSlotAlreadyExistsAndNoStartingLsn() throws Exception {
        Connection queryConnection = mock(Connection.class, withSettings().withoutAnnotations());
        PreparedStatement statement = mock(PreparedStatement.class, withSettings().withoutAnnotations());
        ResultSet resultSet = mock(ResultSet.class, withSettings().withoutAnnotations());
        when(queryConnection.isValid(5)).thenReturn(true);
        when(queryConnection.prepareStatement("SELECT 1 from pg_replication_slots WHERE slot_name = ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        Connection replicationConnection = mock(Connection.class, withSettings().withoutAnnotations());
        when(replicationConnection.isValid(5)).thenReturn(true);
        PGConnection pgConnection = mock(PGConnection.class, RETURNS_DEEP_STUBS);
        when(replicationConnection.unwrap(PGConnection.class)).thenReturn(pgConnection);

        ChainedLogicalCreateSlotBuilder createBuilder =
                mock(ChainedLogicalCreateSlotBuilder.class, withSettings().withoutAnnotations());
        when(createBuilder.withSlotName("existing_slot")).thenReturn(createBuilder);
        when(pgConnection.getReplicationAPI().createReplicationSlot().logical()).thenReturn(createBuilder);

        ChainedLogicalStreamBuilder streamBuilder =
                mock(ChainedLogicalStreamBuilder.class, withSettings().withoutAnnotations());
        when(streamBuilder.withSlotName("existing_slot")).thenReturn(streamBuilder);
        when(streamBuilder.withStatusInterval(10, TimeUnit.SECONDS)).thenReturn(streamBuilder);
        when(streamBuilder.withSlotOption(anyString(), anyBoolean())).thenReturn(streamBuilder);
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(streamBuilder.start()).thenReturn(pgStream);
        when(pgConnection.getReplicationAPI().replicationStream().logical()).thenReturn(streamBuilder);

        this.driver.enqueue(queryConnection);
        this.driver.enqueue(replicationConnection);

        PgNotifier.SlotConfig slotConfig =
                new PgNotifier.SlotConfig("demo_user", "secret", JDBC_URL, "existing_slot");
        ReplicationSlot slot = new ReplicationFactory().newReplicationSlot(
                slotConfig,
                PgNotifier.PluginConfig.defaults(),
                LsnPersistence.noop());

        slot.newReplicationStream();

        verify(createBuilder, never()).make();
        verify(streamBuilder, never()).withStartPosition(LogSequenceNumber.valueOf(0L));
        verify(streamBuilder).start();
    }

    private static void deregisterExistingTestDrivers() throws SQLException {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver candidate = drivers.nextElement();
            if (candidate instanceof RecordingDriver) {
                DriverManager.deregisterDriver(candidate);
            }
        }
    }

    private static final class RecordingDriver implements Driver {

        private final Queue<Connection> connections = new ArrayDeque<>();
        private final List<Properties> seenProperties = new ArrayList<>();

        void enqueue(final Connection connection) {
            this.connections.add(connection);
        }

        List<Properties> seenProperties() {
            return this.seenProperties;
        }

        @Override
        public @Nullable Connection connect(final String url, final Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            Properties copy = new Properties();
            copy.putAll(info);
            this.seenProperties.add(copy);
            Connection next = this.connections.poll();
            if (next == null) {
                throw new SQLException("No connection configured for " + url);
            }
            return next;
        }

        @Override
        public boolean acceptsURL(final String url) {
            return JDBC_URL.equals(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
