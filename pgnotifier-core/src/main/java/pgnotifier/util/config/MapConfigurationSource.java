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

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Simple ConfigurationSource backed by an in-memory map.
 */
public class MapConfigurationSource implements ConfigurationSource {

    private final Map<String, String> values;

    public MapConfigurationSource(final Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    @Override
    public @Nullable String getValue(final String key) {
        return this.values.get(key);
    }

}
