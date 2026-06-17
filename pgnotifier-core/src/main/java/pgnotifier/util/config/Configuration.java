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

package pgnotifier.util.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import pgnotifier.util.StringUtil;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static java.lang.Boolean.parseBoolean;

/**
 * Aggregates one or more {@link ConfigurationSource} instances into a single configuration view.
 * <p>
 * Sources are consulted in reverse order so that later sources override earlier ones, making it
 * easy to layer defaults, file-based configuration and overrides.
 *
 * @author Nos Doughty
 */
public class Configuration {

    /**
     * Thin adapter used to access configuration values in a type-safe way.
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "The constructor stores an immutable defensive copy of the provided source list.")
    public record PropertyAdapter(List<ConfigurationSource> sources) {

        public PropertyAdapter(List<ConfigurationSource> sources) {
            this.sources = List.copyOf(sources).reversed();
        }

        private @Nullable String getValue(final String key) {

            // finds first non-null value from reversed order of sources
            for (ConfigurationSource source : this.sources) {
                final String value = source.getValue(key);
                if (value != null) return value;
            }
            ;

            return null;
        }

        /**
         * Get the value for the given key as a non-null string.
         *
         * @param key configuration key
         * @return value, never {@code null}
         * @throws NullPointerException if the key is not present in any source
         */
        public String getAsString(final String key) {
            final String value = getValue(key);
            return Objects.requireNonNull(value);
        }

        /**
         * Get the value for the given key as a string or return a default value.
         *
         * @param key          configuration key
         * @param defaultValue default value to return when the property is missing
         * @return configured value or {@code defaultValue}
         */
        public @Nullable String getAsString(final String key, final @Nullable String defaultValue) {
            final String value = getValue(key);
            return value != null ? value : defaultValue;
        }

        /**
         * Get the value for the given key as a boolean.
         *
         * @param key configuration key
         * @return {@code true} if the value parses as a boolean true, otherwise {@code false}
         */
        public boolean getAsBoolean(final String key) {
            return parseBoolean(getValue(key));
        }

        /**
         * Get the value for the given key as an integer, returning a default when parsing fails.
         */
        public int getAsInt(final String key, int defaultValue) {
            return StringUtil.convertIntegerString(
                    getValue(key),
                    defaultValue);
        }

        /**
         * Get the value for the given key as an integer, clamped between the provided bounds.
         *
         * @param key          configuration key
         * @param defaultValue default value to use when parsing fails
         * @param minValue     minimum allowed value (inclusive)
         * @param maxValue     maximum allowed value (inclusive)
         */
        public int getAsInt(final String key, int defaultValue, int minValue, int maxValue) {
            return Math.clamp(
                    StringUtil.convertIntegerString(getValue(key), defaultValue),
                    minValue, maxValue);
        }

        /**
         * Get the value for the given key as an integer, clamped to be at least {@code minValue}.
         * <p>
         * The upper bound defaults to {@link Integer#MAX_VALUE}.
         *
         * @param key          configuration key
         * @param defaultValue default value to use when parsing fails
         * @param minValue     minimum allowed value (inclusive)
         */
        public int getAsInt(final String key, int defaultValue, int minValue) {
            return getAsInt(key, defaultValue, minValue, Integer.MAX_VALUE);
        }

    }

    private final PropertyAdapter adapter;

    /**
     * Creates a configuration backed by the given sources.
     *
     * @param sources ordered list of sources; later sources override earlier ones
     */
    public Configuration(final List<ConfigurationSource> sources) {
        this.adapter = new PropertyAdapter(sources);
    }

    /**
     * Apply the given function to the internal {@link PropertyAdapter}.
     *
     * @param processor mapping function
     * @param <T>       result type
     * @return value returned by the mapping function
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * Configuration configuration = new Configuration(List.of(source));
     * String database = configuration.configure(p -> p.getAsString("database"));
     * }</pre>
     */
    public <T> T configure(final Function<PropertyAdapter, T> processor) {
        return processor.apply(this.adapter);
    }

}
