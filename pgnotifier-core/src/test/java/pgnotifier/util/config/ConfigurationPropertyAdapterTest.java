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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationPropertyAdapterTest {

    @Test
    void prefersLaterSources() {
        ConfigurationSource lower = key -> key.equals("name") ? "lower" : null;
        ConfigurationSource higher = key -> key.equals("name") ? "higher" : null;

        Configuration.PropertyAdapter adapter = new Configuration.PropertyAdapter(List.of(lower, higher));

        assertThat(adapter.getAsString("name")).isEqualTo("higher");
    }

    @Test
    void providesTypedAccessorsWithDefaultsAndClamping() {
        ConfigurationSource source = key -> switch (key) {
            case "flag" -> "true";
            case "int" -> "10";
            default -> null;
        };

        Configuration.PropertyAdapter adapter = new Configuration.PropertyAdapter(List.of(source));

        assertThat(adapter.getAsBoolean("flag")).isTrue();
        assertThat(adapter.getAsInt("int", 5)).isEqualTo(10);
        assertThat(adapter.getAsInt("missing", 7)).isEqualTo(7);
        assertThat(adapter.getAsInt("int", 0, 5, 8)).isEqualTo(8); // clamped to max
        assertThat(adapter.getAsInt("int", 0, 12)).isEqualTo(12); // clamped to min
    }

    @Test
    void getAsStringThrowsWhenMissing() {
        Configuration.PropertyAdapter adapter = new Configuration.PropertyAdapter(List.of(key -> null));

        assertThatThrownBy(() -> adapter.getAsString("absent"))
                .isInstanceOf(NullPointerException.class);
    }
}
