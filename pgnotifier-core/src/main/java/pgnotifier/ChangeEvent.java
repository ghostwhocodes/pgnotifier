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

import org.jspecify.annotations.Nullable;

/**
 * Represents a single logical replication change.
 * <p>
 * A {@code ChangeEvent} contains the raw decoded payload, the last received LSN and, when available,
 * a subset of metadata such as schema, table and operation type.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ChangeEvent event = new ChangeEvent(
 *         "{\"kind\":\"insert\",\"schema\":\"public\",\"table\":\"users\"}",
 *         12345L,
 *         "public",
 *         "users",
 *         "insert");
 * String table = event.table();   // "users"
 * long lsn = event.lsn();         // 12345L
 * }</pre>
 *
 * @param payload   raw logical decoding payload (often JSON)
 * @param lsn       last received log sequence number corresponding to this change
 * @param schema    optional schema name, when extracted
 * @param table     optional table name, when extracted
 * @param operation optional operation type (e.g. {@code insert}, {@code update}, {@code delete})
 *
 * @author Nos Doughty
 */
public record ChangeEvent(
        @Nullable String payload,
        long lsn,
        @Nullable String schema,
        @Nullable String table,
        @Nullable String operation) {}
