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
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.PgNotifier;
import pgnotifier.util.config.Configuration;

import java.util.Locale;
import java.util.Objects;

/**
 * Convenience wrapper that maps {@link Configuration} properties into {@link PgNotifier} configs.
 *
 * @author Nos Doughty
 */
public class PgNotifierProperties {

    public static final String USERNAME_KEY = "username";
    public static final String PASSWORD_KEY = "password";
    public static final String DATABASE_KEY = "database";
    public static final String SLOT_NAME_KEY = "slotname";
    public static final String POOL_SIZE_KEY = "poolsize";
    public static final String ERROR_BACKOFF_SECONDS_KEY = "errorbackoffseconds";
    public static final String SHUTDOWN_TIMEOUT_SECONDS_KEY = "shutdowntimeoutseconds";
    public static final String SLEEP_MILLIS_KEY = "sleepmillis";
    public static final String THROTTLE_MILLIS_KEY = "throttlemillis";
    public static final String STATUS_INTERVAL_SECONDS_KEY = "statusintervalseconds";
    public static final String INCLUDE_TABLES_KEY = "includetables";
    public static final String HANDLER_MODE_KEY = "handler.mode";
    public static final String HANDLER_WORKER_THREADS_KEY = "handler.worker.threads";
    public static final String HANDLER_QUEUE_CAPACITY_KEY = "handler.queue.capacity";
    public static final String HANDLER_QUEUE_OVERFLOW_POLICY_KEY = "handler.queue.overflow.policy";

    public static final int DEFAULT_POOL_SIZE = 1;
    public static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_ERROR_BACKOFF_SECONDS = 10;
    public static final int DEFAULT_STATUS_INTERVAL_SECONDS = 10;
    public static final int DEFAULT_HANDLER_WORKER_THREADS = 1;
    public static final int DEFAULT_HANDLER_QUEUE_CAPACITY = 1000;

    // Upper bounds for clamped configuration values
    public static final int MAX_POOL_SIZE = 64;
    public static final int MAX_ERROR_BACKOFF_SECONDS = 3600;         // 1 hour
    public static final int MAX_SHUTDOWN_TIMEOUT_SECONDS = 600;       // 10 minutes
    public static final int MAX_SLEEP_MILLIS = 60_000;                // 60 seconds
    public static final int MAX_THROTTLE_MILLIS = 60_000;             // 60 seconds
    public static final int MAX_STATUS_INTERVAL_SECONDS = 3600;       // 1 hour

    private final Configuration config;

    /**
     * Creates a new properties adapter.
     *
     * @param config configuration to read values from
     */
    public PgNotifierProperties(Configuration config) {
        this.config = config;
    }

    public PgNotifier.SlotConfig getSlotConfig() {
        return this.config.configure(properties -> new PgNotifier.SlotConfig(
                properties.getAsString(USERNAME_KEY),
                properties.getAsString(PASSWORD_KEY),
                properties.getAsString(DATABASE_KEY),
                properties.getAsString(SLOT_NAME_KEY)));
    }

    public PgNotifier.ProcessConfig getProcessConfig() {
        return this.config.configure(properties -> new PgNotifier.ProcessConfig(
                properties.getAsInt(POOL_SIZE_KEY, DEFAULT_POOL_SIZE, 1, MAX_POOL_SIZE),
                properties.getAsInt(
                        ERROR_BACKOFF_SECONDS_KEY,
                        DEFAULT_ERROR_BACKOFF_SECONDS,
                        1,
                        MAX_ERROR_BACKOFF_SECONDS),
                properties.getAsInt(
                        SHUTDOWN_TIMEOUT_SECONDS_KEY,
                        DEFAULT_SHUTDOWN_TIMEOUT_SECONDS,
                        1,
                        MAX_SHUTDOWN_TIMEOUT_SECONDS),
                properties.getAsInt(SLEEP_MILLIS_KEY,
                        (int) PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS, 1, MAX_SLEEP_MILLIS),
                properties.getAsInt(THROTTLE_MILLIS_KEY,
                        (int) PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS, 1, MAX_THROTTLE_MILLIS)));
    }

    public PgNotifier.PluginConfig getPluginConfig() {
        return this.config.configure(properties -> new PgNotifier.PluginConfig(
                properties.getAsInt(
                        STATUS_INTERVAL_SECONDS_KEY,
                        DEFAULT_STATUS_INTERVAL_SECONDS,
                        1,
                        MAX_STATUS_INTERVAL_SECONDS),
                properties.getAsString(INCLUDE_TABLES_KEY, null)));
    }

    /**
     * Returns the handler execution configuration derived from properties.
     * <p>
     * Supported keys:
     * <ul>
     *     <li>{@code handler.mode} – {@code INLINE} (default) or {@code ASYNC_QUEUE}</li>
     *     <li>{@code handler.queue.capacity} – bounded queue capacity when using {@code ASYNC_QUEUE}</li>
     *     <li>{@code handler.queue.overflow.policy} – {@code BLOCK}, {@code DROP_OLDEST}, {@code DROP_NEWEST}, {@code FATAL}</li>
     *     <li>{@code handler.worker.threads} – number of handler worker threads for {@code ASYNC_QUEUE}</li>
     * </ul>
     */
    public HandlerExecutionConfig getHandlerExecutionConfig() {
        return this.config.configure(properties -> {
            final String modeRaw = Objects.requireNonNullElse(
                    properties.getAsString(HANDLER_MODE_KEY, HandlerExecutionConfig.Mode.INLINE.name()),
                    HandlerExecutionConfig.Mode.INLINE.name());
            final HandlerExecutionConfig.Mode mode =
                    HandlerExecutionConfig.Mode.valueOf(modeRaw.toUpperCase(Locale.ROOT));

            if (mode == HandlerExecutionConfig.Mode.INLINE) {
                return HandlerExecutionConfig.inline();
            }

            final int workerThreads = properties.getAsInt(
                    HANDLER_WORKER_THREADS_KEY,
                    DEFAULT_HANDLER_WORKER_THREADS,
                    1,
                    MAX_POOL_SIZE);

            final int queueCapacity = properties.getAsInt(
                    HANDLER_QUEUE_CAPACITY_KEY,
                    DEFAULT_HANDLER_QUEUE_CAPACITY,
                    1,
                    Integer.MAX_VALUE);

            final String policyRaw = Objects.requireNonNullElse(properties.getAsString(
                    HANDLER_QUEUE_OVERFLOW_POLICY_KEY,
                    HandlerExecutionConfig.QueueOverflowPolicy.BLOCK.name()),
                    HandlerExecutionConfig.QueueOverflowPolicy.BLOCK.name());
            final HandlerExecutionConfig.QueueOverflowPolicy policy =
                    HandlerExecutionConfig.QueueOverflowPolicy.valueOf(policyRaw.toUpperCase(Locale.ROOT));

            return HandlerExecutionConfig.asyncQueue(workerThreads, queueCapacity, policy);
        });
    }

}
