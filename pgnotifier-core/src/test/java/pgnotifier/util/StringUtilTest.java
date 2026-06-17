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

package pgnotifier.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StringUtil} helper methods.
 *
 * @author Nos Doughty
 */
class StringUtilTest {

    @Nested
    @DisplayName("toStringFrom(ByteBuffer)")
    class ToStringFrom {

        @Test
        void handlesNullBuffer() {
            assertThat(StringUtil.toStringFrom(null)).isNull();
        }

        @Test
        void readsOnlyRemainingBytes() {
            final byte[] bytes = "xxhello".getBytes(StandardCharsets.UTF_8);
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);

            buffer.position(2); // skip "xx"

            assertThat(StringUtil.toStringFrom(buffer)).isEqualTo("hello");
        }

        @Test
        void supportsDirectBuffers() {
            final ByteBuffer direct = ByteBuffer.allocateDirect(5);
            direct.put("world".getBytes(StandardCharsets.UTF_8));
            direct.flip();

            assertThat(StringUtil.toStringFrom(direct)).isEqualTo("world");
        }
    }

    @Nested
    @DisplayName("convertIntegerString")
    class ConvertIntegerString {

        @ParameterizedTest
        @NullSource
        void nullFallsBackToDefault(String input) {
            assertThat(StringUtil.convertIntegerString(input, 42)).isEqualTo(42);
        }

        @ParameterizedTest
        @CsvSource({
                "123, 0, 123",
                "-5,  10, -5",
                "0,   1,  0",
                "abc, 7,  7"
        })
        void parsesOrFallsBackToDefault(String input, int defaultValue, int expected) {
            assertThat(StringUtil.convertIntegerString(input, defaultValue)).isEqualTo(expected);
        }
    }
}
