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

import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import pgnotifier.internal.replication.ReplicationSlot;
import pgnotifier.internal.replication.config.ReplicationSlotConnectionConfiguration;
import pgnotifier.internal.replication.config.ReplicationSlotJsonConfiguration;

import java.util.function.Consumer;

import static pgnotifier.internal.replication.config.ReplicationSlotConnectionConfiguration.ConnectionPropertiesBuilder.connectionProperties;
import static pgnotifier.internal.replication.config.ReplicationSlotJsonConfiguration.SlotPropertiesBuilder.jsonSlotProperties;

/**
 * Factory responsible for creating configured {@link pgnotifier.internal.replication.ReplicationSlot} instances.
 * <p>
 * The factory translates high-level {@link PgNotifier.SlotConfig} and {@link PgNotifier.PluginConfig}
 * values into concrete PostgreSQL replication and connection properties for wal2json.
 *
 * @author Nos Doughty
 */
final class ReplicationFactory {

    /**
     * Creates a new {@link ReplicationSlot} for the given configuration.
     *
     * @param config       slot credentials and database/slot identifiers
     * @param pluginConfig configuration for the wal2json logical decoding plugin
     * @param lsnPersistence persistence adapter used to seed and persist LSNs
     * @return a {@link ReplicationSlot} ready to open a {@link pgnotifier.internal.replication.ReplicationStream}
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * PgNotifier.SlotConfig slotConfig = new PgNotifier.SlotConfig(
     *         "user", "password", "jdbc:postgresql://localhost/postgres", "demo_slot");
     * PgNotifier.PluginConfig pluginConfig = PgNotifier.PluginConfig.defaults();
     *
     * ReplicationSlot slot = new ReplicationFactory().newReplicationSlot(slotConfig, pluginConfig);
     * }</pre>
     */
    ReplicationSlot newReplicationSlot(
            final PgNotifier.SlotConfig config,
            final PgNotifier.PluginConfig pluginConfig,
            final LsnPersistence lsnPersistence) {

        // get connection details
        final ReplicationSlotConnectionConfiguration connConfig = connectionProperties()
                .setUsername(config.username())
                .setPassword(config.password())
                .toConfig();

        // get slot details - json
        final ReplicationSlotJsonConfiguration jsonSlotConfig = jsonSlotProperties()
                .setIncludeLsn(true)
                .setIncludeXids(true)
                .setIncludeTimestamp(true)
                .setIncludeSchemas(true)
                .setStatusIntervalSeconds(pluginConfig.statusIntervalSeconds())
                .setIncludeTables(pluginConfig.includeTables())
                .toConfig();

        // select the slot/stream creation builders (wal2json only)
        final Consumer<ChainedLogicalCreateSlotBuilder> slotBuilder = jsonSlotConfig.slotConfigurer();

        final Consumer<ChainedLogicalStreamBuilder> streamBuilder = jsonSlotConfig.streamConfigurer();

        // create and return the replication slot
        return new ReplicationSlot(
                config.database(),
                config.slotname(),
                connConfig.configureQueryConnection(),
                connConfig.configureReplicationConnection(),
                slotBuilder,
                streamBuilder,
                () -> lsnPersistence.startingLsn(config));

    }

}
