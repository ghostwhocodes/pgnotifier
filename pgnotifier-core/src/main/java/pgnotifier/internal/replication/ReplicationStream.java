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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import pgnotifier.util.ExecutionUtil;
import pgnotifier.util.TypedAutoCloseable;

import java.sql.SQLException;

import static java.util.Objects.requireNonNull;
import static pgnotifier.util.ExecutionUtil.performThrottled;
import static pgnotifier.util.StringUtil.toStringFrom;

/**
 * Thin wrapper around PostgreSQL's {@link PGReplicationStream}.
 * <p>
 * Provides convenience methods for blocking and non-blocking reads, LSN tracking and
 * mark-processed semantics. Not thread-safe; should be used from a single worker thread.
 *
 * @author Nos Doughty
 */
public class ReplicationStream implements TypedAutoCloseable<SQLException> {

    private final PGReplicationStream stream;

    /**
     * Creates a new {@code ReplicationStream} from the underlying PostgreSQL stream.
     *
     * @param stream underlying PG replication stream
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "This class is a thin wrapper around the mutable PostgreSQL replication stream handle.")
    public ReplicationStream(final PGReplicationStream stream) {

        this.stream = requireNonNull(stream);

    }

    /**
     * Reads a payload from the stream without blocking, applying throttling between calls.
     *
     * @param sleepMillis    sleep interval when no data is available
     * @param throttleMillis sleep interval when data was processed
     * @return payload as a UTF-8 string, or {@code null} when no data is currently available
     */
    public @Nullable String readNonBlocking(final long sleepMillis, final long throttleMillis) {

        final ExecutionUtil.BlockWithReturn<@Nullable String> fn = () -> toStringFrom(stream.readPending());

        return performThrottled(fn, sleepMillis, throttleMillis);

    }

    /**
     * Reads a payload from the stream, blocking until one is available, and applies throttling.
     *
     * @param sleepMillis    sleep interval when no data is available
     * @param throttleMillis sleep interval when data was processed
     * @return payload as a UTF-8 string
     */
    public @Nullable String readBlocking(final long sleepMillis, final long throttleMillis) {

        final ExecutionUtil.BlockWithReturn<@Nullable String> fn = () -> toStringFrom(stream.read());

        return performThrottled(fn, sleepMillis, throttleMillis);

    }

    /**
     * Returns the last receive LSN as a long value, or {@code 0} if none is available yet.
     *
     * @return last receive LSN, or {@code 0} if unknown
     */
    public long lastReceiveLsn() {
        final LogSequenceNumber lsn = stream.getLastReceiveLSN();
        return lsn != null ? lsn.asLong() : 0L;
    }

    /**
     * Marks the last received LSN as processed and returns it.
     *
     * @return processed LSN value
     */
    public long markProcessed() {

        return markProcessed(stream.getLastReceiveLSN());

    }

    /**
     * Marks the given LSN as processed.
     *
     * @param lsn LSN value to mark as processed
     * @return processed LSN value
     */
    public long markProcessed(final long lsn) {

        return markProcessed(LogSequenceNumber.valueOf(lsn));

    }

    /**
     * Marks the given LSN as processed.
     *
     * @param lsn LSN value to mark as processed
     * @return processed LSN value
     */
    public long markProcessed(final LogSequenceNumber lsn) {

        stream.setFlushedLSN(lsn); // tell pg we flushed it so it can free up resources on the server

        stream.setAppliedLSN(lsn); // tell pg we applied it (actually physical repl only, but being safe) 

        return lsn.asLong();

    }

    /**
     * Indicates whether the underlying stream has been closed.
     *
     * @return {@code true} if closed, {@code false} otherwise
     */
    public boolean isClosed() {
        return this.stream.isClosed();
    }

    /**
     * This may not be the best way to close, it may be better to close the connection, due to:
     * https://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com
     *
     * @throws SQLException if closing the underlying {@link PGReplicationStream} fails
     */
    @Override
    public void close() throws SQLException {

        this.stream.close();

    }

}
