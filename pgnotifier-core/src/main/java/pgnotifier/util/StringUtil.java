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

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * String-related utility methods used throughout the project.
 *
 * @author Nos Doughty
 */
public class StringUtil {

    /**
     * Parses the given string as an integer, returning a default value if the input
     * is {@code null} or not a valid integer representation.
     *
     * @param input        string to parse, may be {@code null}
     * @param defaultValue value to return when parsing fails
     * @return parsed integer or {@code defaultValue} when input is invalid
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * int port = StringUtil.convertIntegerString("5432", 5433); // 5432
     * int fallback = StringUtil.convertIntegerString("invalid", 5433); // 5433
     * }</pre>
     */
    public static int convertIntegerString(final @Nullable String input, final int defaultValue) {
        return Optional.ofNullable(input)
                .filter(str -> str.matches("-?\\d+"))
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }

    /**
     * Converts the remaining bytes of the given {@link ByteBuffer} into a UTF-8 string.
     * <p>
     * The buffer is duplicated internally so the original position and limit are preserved.
     *
     * @param buffer byte buffer whose remaining bytes should be converted, may be {@code null}
     * @return decoded UTF-8 string, or {@code null} if {@code buffer} is {@code null}
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * ByteBuffer buffer = StandardCharsets.UTF_8.encode("hello");
     * String text = StringUtil.toStringFrom(buffer); // "hello"
     * }</pre>
     */
    public static @Nullable String toStringFrom(final @Nullable ByteBuffer buffer) {

        if (buffer == null) return null;

        // Work on a duplicate so we don't disturb the caller's position/limit.
        final ByteBuffer copy = buffer.duplicate();

        if (!copy.hasRemaining()) return "";

        final byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);

        // Logical decoding output is UTF-8 JSON; use an explicit charset.
        return new String(bytes, StandardCharsets.UTF_8);

    }

}
