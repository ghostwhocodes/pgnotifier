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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {

    private Configuration.PropertyAdapter adapterFor(Map<String, String> first, Map<String, String> second) {
        ConfigurationSource s1 = new MapConfigurationSource(first);
        ConfigurationSource s2 = new MapConfigurationSource(second);
        Configuration configuration = new Configuration(List.of(s1, s2));
        return configuration.configure(a -> a);
    }

    @Nested
    @DisplayName("getAsInt with default and bounds")
    class GetAsIntWithBounds {

        @Test
        void usesDefaultWhenMissing() {
            Configuration.PropertyAdapter adapter = adapterFor(Map.of(), Map.of());

            int value = adapter.getAsInt("missing", 10, 1, 100);

            assertThat(value).isEqualTo(10);
        }

        @Test
        void clampsBelowMinimum() {
            Configuration.PropertyAdapter adapter = adapterFor(Map.of("threads", "-5"), Map.of());

            int value = adapter.getAsInt("threads", 1, 1, 64);

            assertThat(value).isEqualTo(1);
        }

        @Test
        void clampsAboveMaximum() {
            Configuration.PropertyAdapter adapter = adapterFor(Map.of("threads", "128"), Map.of());

            int value = adapter.getAsInt("threads", 1, 1, 64);

            assertThat(value).isEqualTo(64);
        }
    }

    @Nested
    @DisplayName("source precedence")
    class SourcePrecedence {

        @Test
        void laterSourcesOverrideEarlierOnes() {
            Configuration.PropertyAdapter adapter = adapterFor(
                    Map.of("key", "fromFile"),
                    Map.of("key", "fromCli"));

            String value = adapter.getAsString("key");

            assertThat(value).isEqualTo("fromCli");
        }
    }
}

