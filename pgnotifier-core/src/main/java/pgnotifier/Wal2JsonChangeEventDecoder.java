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

package pgnotifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.postgresql.replication.LogSequenceNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Decoder for wal2json logical decoding output.
 * <p>
 * It extracts basic metadata (schema, table, operation) for the first change
 * in the {@code "change"} array, while preserving the full payload.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ChangeEventDecoder decoder = new Wal2JsonChangeEventDecoder();
 * String payload = """
 *   {"change":[{"kind":"insert","schema":"public","table":"users"}]}
 *   """;
 * ChangeEvent event = decoder.decode(payload, 100L);
 * String table = event.table();      // "users"
 * String operation = event.operation(); // "insert"
 * }</pre>
 *
 * @author Nos Doughty
 */
public class Wal2JsonChangeEventDecoder implements ChangeEventDecoder {

    private static final Logger logger = LoggerFactory.getLogger(Wal2JsonChangeEventDecoder.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ChangeEvent decode(final @Nullable String payload, final long lsn) {

        if (payload == null) {
            return new ChangeEvent(null, lsn, null, null, null);
        }

        try {

            final JsonNode root = mapper.readTree(payload);
            final JsonNode changes = root.path("change");

            if (changes.isArray() && changes.size() > 0) {

                final JsonNode first = changes.get(0);

                final String schema = textOrNull(first, "schema");
                final String table = textOrNull(first, "table");
                final String kind = textOrNull(first, "kind");
                final long eventLsn = nextLsnOrFallback(root, lsn);

                return new ChangeEvent(payload, eventLsn, schema, table, kind);

            }

        } catch (IOException e) {
            logger.debug("Failed to decode wal2json payload, returning raw event", e);
        }

        // fallback to raw event if structure is not as expected
        return new ChangeEvent(payload, lsn, null, null, null);

    }

    private static @Nullable String textOrNull(final JsonNode node, final String fieldName) {
        final JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private static long nextLsnOrFallback(final JsonNode root, final long fallback) {
        final String nextLsn = textOrNull(root, "nextlsn");
        if (nextLsn == null || nextLsn.isBlank()) {
            return fallback;
        }
        try {
            return LogSequenceNumber.valueOf(nextLsn).asLong();
        } catch (IllegalArgumentException e) {
            logger.debug("Failed to parse wal2json nextlsn, using stream LSN", e);
            return fallback;
        }
    }

}
