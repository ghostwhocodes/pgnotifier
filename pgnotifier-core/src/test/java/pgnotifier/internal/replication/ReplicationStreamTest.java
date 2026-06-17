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

package pgnotifier.internal.replication;

import org.junit.jupiter.api.Test;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ReplicationStreamTest {

    @Test
    void readNonBlockingUsesUnderlyingStreamAndReturnsUtf8String() throws Exception {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(pgStream.readPending()).thenReturn(ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8)));

        ReplicationStream stream = new ReplicationStream(pgStream);

        String result = stream.readNonBlocking(0L, 0L);

        assertThat(result).isEqualTo("payload");
        verify(pgStream).readPending();
    }

    @Test
    void lastReceiveLsnReturnsZeroWhenNoneAvailable() {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(pgStream.getLastReceiveLSN()).thenReturn(null);

        ReplicationStream stream = new ReplicationStream(pgStream);

        assertThat(stream.lastReceiveLsn()).isEqualTo(0L);
    }

    @Test
    void lastReceiveLsnReturnsValueWhenAvailable() {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        LogSequenceNumber lsn = LogSequenceNumber.valueOf(123L);
        when(pgStream.getLastReceiveLSN()).thenReturn(lsn);

        ReplicationStream stream = new ReplicationStream(pgStream);

        assertThat(stream.lastReceiveLsn()).isEqualTo(123L);
    }

    @Test
    void readBlockingUsesUnderlyingStreamAndReturnsUtf8String() throws Exception {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(pgStream.read()).thenReturn(ByteBuffer.wrap("payload".getBytes(StandardCharsets.UTF_8)));

        ReplicationStream stream = new ReplicationStream(pgStream);

        String result = stream.readBlocking(0L, 0L);

        assertThat(result).isEqualTo("payload");
        verify(pgStream).read();
    }

    @Test
    void markProcessedUsesLastReceiveLsnAndUpdatesStream() {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        LogSequenceNumber lsn = LogSequenceNumber.valueOf(321L);
        when(pgStream.getLastReceiveLSN()).thenReturn(lsn);

        ReplicationStream stream = new ReplicationStream(pgStream);

        long processed = stream.markProcessed();

        assertThat(processed).isEqualTo(321L);
        verify(pgStream).setFlushedLSN(lsn);
        verify(pgStream).setAppliedLSN(lsn);
    }

    @Test
    void markProcessedWithExplicitLsnUpdatesStream() {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        ReplicationStream stream = new ReplicationStream(pgStream);

        long processed = stream.markProcessed(999L);

        assertThat(processed).isEqualTo(999L);
        verify(pgStream).setFlushedLSN(any(LogSequenceNumber.class));
        verify(pgStream).setAppliedLSN(any(LogSequenceNumber.class));
    }

    @Test
    void closeDelegatesToUnderlyingStream() throws Exception {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        ReplicationStream stream = new ReplicationStream(pgStream);

        stream.close();

        verify(pgStream).close();
    }

    @Test
    void isClosedDelegatesToUnderlyingStream() {
        PGReplicationStream pgStream = mock(PGReplicationStream.class, withSettings().withoutAnnotations());
        when(pgStream.isClosed()).thenReturn(true);

        ReplicationStream stream = new ReplicationStream(pgStream);

        assertThat(stream.isClosed()).isTrue();
    }
}
