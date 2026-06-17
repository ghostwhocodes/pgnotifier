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

import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static pgnotifier.internal.replication.config.ReplicationSlotConnectionConfiguration.ConnectionPropertiesBuilder.connectionProperties;

/**
 * Tests for {@link ReplicationSlotConnectionConfiguration} helpers.
 *
 * @author Nos Doughty
 */
class ReplicationSlotConnectionConfigurationTest {

    @Test
    void connectionPropertiesBeanSupportsAccessors() {
        ReplicationSlotConnectionConfiguration.ConnectionProperties properties =
                new ReplicationSlotConnectionConfiguration.ConnectionProperties()
                        .setUsername("demo")
                        .setPassword("secret");

        assertThat(properties.getUsername()).isEqualTo("demo");
        assertThat(properties.getPassword()).isEqualTo("secret");
    }

    @Test
    void configureQueryConnectionSetsCoreProperties() {

        final ReplicationSlotConnectionConfiguration config = connectionProperties()
                .setUsername("someuser")
                .setPassword("secret")
                .toConfig();

        final Properties props = new Properties();
        config.configureQueryConnection().accept(props);

        assertThat(props.getProperty(PGProperty.USER.getName())).isEqualTo("someuser");
        assertThat(props.getProperty(PGProperty.PASSWORD.getName())).isEqualTo("secret");
        assertThat(props.getProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName())).isEqualTo("9.4");

    }

    @Test
    void configureReplicationConnectionEnablesReplicationMode() {

        final ReplicationSlotConnectionConfiguration config = connectionProperties()
                .setUsername("repuser")
                .setPassword("reppass")
                .toConfig();

        final Properties props = new Properties();
        config.configureReplicationConnection().accept(props);

        assertThat(props.getProperty(PGProperty.USER.getName())).isEqualTo("repuser");
        assertThat(props.getProperty(PGProperty.PASSWORD.getName())).isEqualTo("reppass");
        assertThat(props.getProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName())).isEqualTo("9.4");

        assertThat(props.getProperty(PGProperty.REPLICATION.getName())).isEqualTo("database");
        assertThat(props.getProperty(PGProperty.PREFER_QUERY_MODE.getName())).isEqualTo("simple");

    }

}
