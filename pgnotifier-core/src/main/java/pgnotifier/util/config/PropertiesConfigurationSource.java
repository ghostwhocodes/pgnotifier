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

import java.io.FileInputStream;
import java.util.Properties;

import static pgnotifier.util.ExecutionUtil.execute;

/**
 * {@link ConfigurationSource} backed by a {@link Properties} file on disk.
 *
 * @author Nos Doughty
 */
public class PropertiesConfigurationSource implements ConfigurationSource {

    private final Properties properties = new Properties();

    /**
     * Loads properties from the given file.
     *
     * @param filename path to a standard Java properties file
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * ConfigurationSource source = new PropertiesConfigurationSource("pgnotifier.properties");
     * }</pre>
     */
    public PropertiesConfigurationSource(final String filename) {

        execute(() -> {
            try (FileInputStream fis = new FileInputStream(filename)) {
                this.properties.load(fis);
            }
        });

    }

    @Override public String getValue(String key) {
        return this.properties.getProperty(key);
    }

}
