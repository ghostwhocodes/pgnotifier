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

import org.jspecify.annotations.Nullable;
import pgnotifier.util.config.Configuration;
import pgnotifier.util.config.ConfigurationSource;
import pgnotifier.util.config.MapConfigurationSource;
import pgnotifier.util.config.PropertiesConfigurationSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds command-line configuration sources for PgNotifier command adapters.
 * <p>
 * Command adapters collect option values, then pass them here so property keys,
 * override precedence and map assembly stay in one place.
 */
public final class CommandConfiguration {

    public static final String DEFAULT_CONFIG_PATH = "pgnotifier.properties";

    private final String configPath;
    private final Map<String, String> overrides;

    private CommandConfiguration(final String configPath, final Map<String, String> overrides) {
        this.configPath = Objects.requireNonNull(configPath);
        this.overrides = Map.copyOf(overrides);
    }

    public static Builder builder(final @Nullable String configPath) {
        return new Builder(configPath);
    }

    public Configuration configuration() {
        final List<ConfigurationSource> sources = new ArrayList<>();
        sources.add(new PropertiesConfigurationSource(this.configPath));
        if (!this.overrides.isEmpty()) {
            sources.add(new MapConfigurationSource(this.overrides));
        }
        return new Configuration(sources);
    }

    public PgNotifierProperties properties() {
        return new PgNotifierProperties(configuration());
    }

    public Map<String, String> overrides() {
        return this.overrides;
    }

    public static final class Builder {

        private final String configPath;
        private final Map<String, String> overrides = new LinkedHashMap<>();

        private Builder(final @Nullable String configPath) {
            this.configPath = configPath != null ? configPath : DEFAULT_CONFIG_PATH;
        }

        public Builder connectionOverrides(
                final @Nullable String database,
                final @Nullable String username,
                final @Nullable String password,
                final @Nullable String slotName) {

            put(PgNotifierProperties.DATABASE_KEY, database);
            put(PgNotifierProperties.USERNAME_KEY, username);
            put(PgNotifierProperties.PASSWORD_KEY, password);
            put(PgNotifierProperties.SLOT_NAME_KEY, slotName);
            return this;
        }

        public Builder processOverrides(
                final @Nullable Integer poolSize,
                final @Nullable Integer errorBackoffSeconds,
                final @Nullable Integer shutdownTimeoutSeconds,
                final @Nullable Integer sleepMillis,
                final @Nullable Integer throttleMillis) {

            put(PgNotifierProperties.POOL_SIZE_KEY, poolSize);
            put(PgNotifierProperties.ERROR_BACKOFF_SECONDS_KEY, errorBackoffSeconds);
            put(PgNotifierProperties.SHUTDOWN_TIMEOUT_SECONDS_KEY, shutdownTimeoutSeconds);
            put(PgNotifierProperties.SLEEP_MILLIS_KEY, sleepMillis);
            put(PgNotifierProperties.THROTTLE_MILLIS_KEY, throttleMillis);
            return this;
        }

        public Builder pluginOverrides(
                final @Nullable Integer statusIntervalSeconds,
                final @Nullable String includeTables) {

            put(PgNotifierProperties.STATUS_INTERVAL_SECONDS_KEY, statusIntervalSeconds);
            put(PgNotifierProperties.INCLUDE_TABLES_KEY, includeTables);
            return this;
        }

        public Builder handlerOverrides(
                final @Nullable String handlerMode,
                final @Nullable Integer handlerWorkerThreads,
                final @Nullable Integer handlerQueueCapacity,
                final @Nullable String handlerQueueOverflowPolicy) {

            put(PgNotifierProperties.HANDLER_MODE_KEY, handlerMode);
            put(PgNotifierProperties.HANDLER_WORKER_THREADS_KEY, handlerWorkerThreads);
            put(PgNotifierProperties.HANDLER_QUEUE_CAPACITY_KEY, handlerQueueCapacity);
            put(PgNotifierProperties.HANDLER_QUEUE_OVERFLOW_POLICY_KEY, handlerQueueOverflowPolicy);
            return this;
        }

        public CommandConfiguration build() {
            return new CommandConfiguration(this.configPath, this.overrides);
        }

        private void put(final String key, final @Nullable String value) {
            if (value != null) {
                this.overrides.put(key, value);
            }
        }

        private void put(final String key, final @Nullable Integer value) {
            if (value != null) {
                this.overrides.put(key, value.toString());
            }
        }

    }

}
