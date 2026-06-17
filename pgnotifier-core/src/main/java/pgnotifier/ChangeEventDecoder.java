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
 * Translates the raw replication payload into a {@link ChangeEvent}.
 * <p>
 * Implementations understand the wire format produced by a logical decoding plugin (such as wal2json)
 * and populate {@link ChangeEvent} instances accordingly.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ChangeEventDecoder decoder = ChangeEventDecoder.raw();
 * ChangeEvent event = decoder.decode("{\"kind\":\"insert\"}", 42L);
 * }</pre>
 *
 * @author Nos Doughty
 */
@FunctionalInterface
public interface ChangeEventDecoder {

    /**
     * Decode the given payload into a {@link ChangeEvent}.
     *
     * @param payload raw payload from the replication stream, may be {@code null}
     * @param lsn     last received log sequence number
     * @return decoded change event
     */
    ChangeEvent decode(@Nullable String payload, long lsn);

    /**
     * Decoder that simply wraps the raw payload and LSN.
     *
     * @return a decoder that preserves the payload and LSN and leaves metadata {@code null}
     */
    static ChangeEventDecoder raw() {
        return (payload, lsn) -> new ChangeEvent(payload, lsn, null, null, null);
    }

}
