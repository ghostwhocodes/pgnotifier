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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Wal2JsonChangeEventDecoder}.
 *
 * @author Nos Doughty
 */
class Wal2JsonChangeEventDecoderTest {

    private final Wal2JsonChangeEventDecoder decoder = new Wal2JsonChangeEventDecoder();

    @Nested
    @DisplayName("decode wal2json payloads")
    class DecodePayloads {

        @Test
        void decodesBasicChangeMetadata() {

            final String payload = """
                    {"xid":3402,"nextlsn":"0/1749820","timestamp":"2019-05-21 19:41:17.075757+01",
                     "change":[{"kind":"insert","schema":"wal_test","table":"some_table",
                     "columnnames":["id","data"],"columntypes":["bigint","text"],
                     "columnvalues":[2847,"Tue May 21 19:41:17 BST 2019"]}]}""";

            final ChangeEvent event = decoder.decode(payload, 123L);

            assertThat(event.payload()).isEqualTo(payload);
            assertThat(event.lsn()).isEqualTo(24418336L);
            assertThat(event.schema()).isEqualTo("wal_test");
            assertThat(event.table()).isEqualTo("some_table");
            assertThat(event.operation()).isEqualTo("insert");
        }

        @Test
        void fallsBackToRawEventOnInvalidJson() {

            final String payload = "not-json-at-all";

            final ChangeEvent event = decoder.decode(payload, 456L);

            assertThat(event.payload()).isEqualTo(payload);
            assertThat(event.lsn()).isEqualTo(456L);
            assertThat(event.schema()).isNull();
            assertThat(event.table()).isNull();
            assertThat(event.operation()).isNull();
        }

        @Test
        void fallsBackToRawEventWhenChangeArrayMissing() {

            final String payload = """
                    {"xid":1,"nextlsn":"0/0","timestamp":"2020-01-01 00:00:00+00"}""";

            final ChangeEvent event = decoder.decode(payload, 789L);

            assertThat(event.payload()).isEqualTo(payload);
            assertThat(event.lsn()).isEqualTo(789L);
            assertThat(event.schema()).isNull();
            assertThat(event.table()).isNull();
            assertThat(event.operation()).isNull();
        }

        @Test
        void handlesEmptyChangeArray() {

            final String payload = """
                    {"xid":1,"nextlsn":"0/0","timestamp":"2020-01-01 00:00:00+00",
                     "change":[]}""";

            final ChangeEvent event = decoder.decode(payload, 100L);

            assertThat(event.schema()).isNull();
            assertThat(event.table()).isNull();
            assertThat(event.operation()).isNull();
        }
    }
}
