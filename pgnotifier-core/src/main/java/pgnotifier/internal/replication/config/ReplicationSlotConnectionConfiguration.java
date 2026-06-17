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

package pgnotifier.internal.replication.config;

import org.jspecify.annotations.Nullable;
import org.postgresql.PGProperty;

import java.util.Properties;
import java.util.function.Consumer;

/**
 * Configuration of JDBC connection properties for replication-related connections.
 *
 * @author Nos Doughty
 */
public class ReplicationSlotConnectionConfiguration {

    /**
     * Mutable connection properties bean.
     */
    public static class ConnectionProperties extends AbstractConnectionProperties {

        public @Nullable String getUsername() {
            return username;
        }

        public ConnectionProperties setUsername(final @Nullable String username) {
            this.username = username;
            return this;
        }

        public @Nullable String getPassword() {
            return password;
        }

        public ConnectionProperties setPassword(final @Nullable String password) {
            this.password = password;
            return this;
        }

    }

    /**
     * Builder for {@link ReplicationSlotConnectionConfiguration} instances.
     */
    public static class ConnectionPropertiesBuilder extends AbstractConnectionProperties {

        public static ConnectionPropertiesBuilder connectionProperties() {
            return new ConnectionPropertiesBuilder();
        }

        public ReplicationSlotConnectionConfiguration toConfig() {
            return new ReplicationSlotConnectionConfiguration(this);
        }

        public ConnectionPropertiesBuilder setUsername(final @Nullable String username) {
            this.username = username;
            return this;
        }

        public ConnectionPropertiesBuilder setPassword(final @Nullable String password) {
            this.password = password;
            return this;
        }

    }

    private static class AbstractConnectionProperties {

        @Nullable String username = null;
        @Nullable String password = null;

    }

    private final AbstractConnectionProperties properties;

    public ReplicationSlotConnectionConfiguration(final AbstractConnectionProperties properties) {
        this.properties = properties;
    }

    private void configureQueryConnection(final Properties properties) {

        PGProperty.USER.set(properties, this.properties.username);
        PGProperty.PASSWORD.set(properties, this.properties.password);

        PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");

    }

    private void configureReplicationConnection(final Properties properties) {

        this.configureQueryConnection(properties);

        PGProperty.REPLICATION.set(properties, "database");
        PGProperty.PREFER_QUERY_MODE.set(properties, "simple");

    }

    /**
     * Returns a consumer that applies query-related properties to a {@link Properties} instance.
     */
    public Consumer<Properties> configureQueryConnection() {

        return this::configureQueryConnection;

    }

    /**
     * Returns a consumer that applies replication-related properties to a {@link Properties} instance.
     */
    public Consumer<Properties> configureReplicationConnection() {

        return this::configureReplicationConnection;

    }

}
