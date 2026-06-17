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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesConfigurationSourceTest {

    @Test
    void loadsPropertiesFromFile() throws Exception {
        Path tempFile = Files.createTempFile("pgnotifier-props-", ".properties");
        Files.writeString(tempFile, "key=value\nflag=true\nnumber=5");

        PropertiesConfigurationSource source = new PropertiesConfigurationSource(tempFile.toString());

        assertThat(source.getValue("key")).isEqualTo("value");
        assertThat(source.getValue("flag")).isEqualTo("true");
        assertThat(source.getValue("missing")).isNull();
    }

    @Test
    void throwsWhenFileMissing() {
        String missing = "/tmp/" + UUID.randomUUID() + ".properties";

        assertThatThrownBy(() -> new PropertiesConfigurationSource(missing))
                .isInstanceOf(RuntimeException.class);
    }
}
