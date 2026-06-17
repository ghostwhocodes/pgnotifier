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

package pgnotifier.internal.replication;

import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgnotifier.util.ExecutionUtil;
import pgnotifier.util.JdbcUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.Properties;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Handles the setup required to get a valid logical replication slot from PostgreSQL.
 * <p>
 * The slot transparently validates and recreates JDBC connections as needed, and exposes
 * a method to open a {@link ReplicationStream}. Not thread-safe; should be used from a
 * single worker thread.
 *
 * @author Nos Doughty
 */
public class ReplicationSlot implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationSlot.class);

    private final String url;
    private final String slot;

    private final Consumer<Properties> queryConnConfigurer;
    private final Consumer<Properties> replicationConnConfigurer;
    private final Consumer<ChainedLogicalCreateSlotBuilder> replicationSlotConfigurer;
    private final Consumer<ChainedLogicalStreamBuilder> replicationStreamConfigurer;
    private final Supplier<Optional<Long>> startingLsnSupplier;

    private transient @Nullable Connection queryConn;
    private transient @Nullable Connection replicationConn;

    /**
     * Creates a new replication slot wrapper.
     *
     * @param url                     JDBC URL of the PostgreSQL database
     * @param slot                    replication slot name
     * @param queryConnConfigurer     configurer for the query connection properties
     * @param replicationConnConfigurer configurer for the replication connection properties
     * @param replicationSlotConfigurer configurer for slot creation
     * @param replicationStreamConfigurer configurer for stream creation
     * @param startingLsnSupplier optional provider for the exact PostgreSQL stream start position
     */
    public ReplicationSlot(
            final String url,
            final String slot,
            final Consumer<Properties> queryConnConfigurer,
            final Consumer<Properties> replicationConnConfigurer,
            final Consumer<ChainedLogicalCreateSlotBuilder> replicationSlotConfigurer,
            final Consumer<ChainedLogicalStreamBuilder> replicationStreamConfigurer,
            final Supplier<Optional<Long>> startingLsnSupplier) {

        this.url = Objects.requireNonNull(url);

        this.slot = Objects.requireNonNull(slot);

        this.queryConnConfigurer = Objects.requireNonNull(queryConnConfigurer);

        this.replicationConnConfigurer = Objects.requireNonNull(replicationConnConfigurer);

        this.replicationSlotConfigurer = Objects.requireNonNull(replicationSlotConfigurer);

        this.replicationStreamConfigurer = Objects.requireNonNull(replicationStreamConfigurer);

        this.startingLsnSupplier = Objects.requireNonNull(startingLsnSupplier);

    }

    private Connection queryConnection() {

        this.validateConnections();

        return Objects.requireNonNull(queryConn);

    }

    private Connection replicationConnection() {

        this.validateConnections();

        return Objects.requireNonNull(this.replicationConn);

    }

    private void validateConnections() {

        logger.debug("event=replication_slot_validate_connections slot={} url={}", this.slot, this.url);

        // no need to do anything special to close partial connections, it will re-run the above and clean up
        ExecutionUtil.execute(() -> {

            final boolean queryInvalid = (queryConn == null) || !queryConn.isValid(5);
            final boolean replicationInvalid = (replicationConn == null) || !replicationConn.isValid(5);

            if (queryInvalid || replicationInvalid) {

                logger.info("event=replication_slot_connections_invalid slot={} url={}", slot, url);
                this.close();

                logger.info("event=replication_slot_create_query_connection slot={} url={}", slot, url);
                final Properties queryProps = new Properties();
                this.queryConnConfigurer.accept(queryProps);
                this.queryConn = DriverManager.getConnection(this.url, queryProps);

                logger.info("event=replication_slot_create_replication_connection slot={} url={}", slot, url);
                final Properties replicationProps = new Properties();
                this.replicationConnConfigurer.accept(replicationProps);
                this.replicationConn = DriverManager.getConnection(this.url, replicationProps);

            }

        });

    }

    private boolean validateSlot() {

        logger.info("event=replication_slot_check_existing slot={} url={}", this.slot, this.url);

        return Boolean.TRUE.equals(JdbcUtil.query(
                this.queryConnection(),
                "SELECT 1 from pg_replication_slots WHERE slot_name = ?",
                (stmt) -> ExecutionUtil.execute(() -> stmt.setString(1, this.slot)),
                resultSet -> Boolean.TRUE.equals(ExecutionUtil.execute(resultSet::next))));

    }

    private void createReplicationSlot() {

        logger.info("event=replication_slot_create slot={} url={}", this.slot, this.url);

        ExecutionUtil.execute(() -> {

            final ChainedLogicalCreateSlotBuilder builder = this
                    .replicationConnection()
                    .unwrap(PGConnection.class)
                    .getReplicationAPI()
                    .createReplicationSlot()
                    .logical();

            logger.info("event=replication_slot_configure slot={} url={}", this.slot, this.url);

            this.replicationSlotConfigurer.accept(builder.withSlotName(this.slot));

            builder.make();

        });

    }

    private PGReplicationStream createReplicationStream() {

        logger.info("event=replication_stream_open slot={} url={}", this.slot, this.url);

        return Objects.requireNonNull(ExecutionUtil.execute(() -> {

            final ChainedLogicalStreamBuilder builder = this
                    .replicationConnection()
                    .unwrap(PGConnection.class)
                    .getReplicationAPI()
                    .replicationStream()
                    .logical();

            logger.info("event=replication_stream_configure slot={} url={}", this.slot, this.url);

            this.replicationStreamConfigurer.accept(builder.withSlotName(this.slot));

            this.startingLsnSupplier.get()
                    .filter(lsn -> lsn > 0L)
                    // PostgreSQL owns start-position semantics; pgnotifier passes the configured LSN through.
                    .ifPresent(lsn -> builder.withStartPosition(LogSequenceNumber.valueOf(lsn)));

            return builder.start();

        }));

    }

    public ReplicationStream newReplicationStream() throws RuntimeException {

        if (!this.validateSlot()) this.createReplicationSlot();

        return new ReplicationStream(this.createReplicationStream());

    }


    @Override
    public void close() throws RuntimeException {

        if (queryConn != null) {
            final Connection currentQueryConn = queryConn;
            logger.info("event=replication_slot_close_query_connection slot={} url={}", this.slot, this.url);
            ExecutionUtil.execute(
                    currentQueryConn::close,
                    (e) -> logger.error("event=replication_slot_close_query_connection_failed slot={} url={}",
                                        this.slot, this.url, e));
            queryConn = null;
        }

        if (replicationConn != null) {
            final Connection currentReplicationConn = replicationConn;
            logger.info("event=replication_slot_close_replication_connection slot={} url={}", this.slot, this.url);
            ExecutionUtil.execute(
                    currentReplicationConn::close,
                    (e) -> logger.error("event=replication_slot_close_replication_connection_failed slot={} url={}",
                                        this.slot, this.url, e));
            replicationConn = null;
        }

    }

}
