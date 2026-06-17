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

import org.postgresql.util.PSQLException;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for deciding if and how a failed worker should be restarted.
 *
 * @author Nos Doughty
 */
public interface RestartPolicy {

    /**
     * Whether the given exception represents a fatal condition that should halt restarts.
     *
     * @param cause exception that caused the failure
     * @return {@code true} if the error should be treated as fatal
     */
    static boolean isFatal(final Throwable cause) {
        Throwable current = cause;

        while (current != null) {
            if (current instanceof pgnotifier.WorkerStopException stopException && stopException.isPermanent()) {
                return true;
            }
            if (current instanceof PSQLException psqlException) {
                final String sqlState = psqlException.getSQLState();
                if (isFatalSqlState(sqlState)) {
                    return true;
                }
            }
            if (current instanceof SQLException sqlException) {
                final String sqlState = sqlException.getSQLState();
                if (isFatalSqlState(sqlState)) {
                    return true;
                }
            }
            if (current instanceof ClassNotFoundException || current instanceof NoClassDefFoundError) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    private static boolean isFatalSqlState(final String sqlState) {
        if (sqlState == null) {
            return false;
        }
        return sqlState.startsWith("28")
                || "58P01".equals(sqlState) // undefined_file (e.g. missing output plugin)
                || "42704".equals(sqlState); // undefined_object
    }

    /**
     * Whether the worker should be restarted after the given failure.
     *
     * @param failureCount number of consecutive failures
     * @param cause        exception that caused the failure
     */
    boolean shouldRestart(int failureCount, Exception cause);

    /**
     * Backoff in seconds before the next restart attempt.
     *
     * @param failureCount number of consecutive failures
     * @param cause        exception that caused the failure
     */
    long backoffSeconds(int failureCount, Exception cause);

    /**
     * Simple fixed-delay policy that always restarts.
     *
     * @param backoffSeconds delay in seconds between restarts
     * @return restart policy instance
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * RestartPolicy policy = RestartPolicy.fixed(5L); // always restart after 5 seconds
     * }</pre>
     */
    static RestartPolicy fixed(final long backoffSeconds) {
        return new RestartPolicy() {
            @Override
            public boolean shouldRestart(int failureCount, Exception cause) {
                return !RestartPolicy.isFatal(cause);
            }

            @Override
            public long backoffSeconds(int failureCount, Exception cause) {
                return backoffSeconds;
            }
        };
    }

    /**
     * Exponential backoff policy with an upper bound and optional jitter.
     *
     * @param initialBackoffSeconds initial delay before retrying
     * @param maxBackoffSeconds     maximum delay cap
     * @param jitter                whether to apply full jitter (random delay up to the capped backoff)
     * @return restart policy instance
     */
    static RestartPolicy exponential(final long initialBackoffSeconds,
                                     final long maxBackoffSeconds,
                                     final boolean jitter) {
        return new RestartPolicy() {
            @Override
            public boolean shouldRestart(int failureCount, Exception cause) {
                return !RestartPolicy.isFatal(cause);
            }

            @Override
            public long backoffSeconds(int failureCount, Exception cause) {
                final int attempts = Math.max(1, failureCount);
                final long cap = maxBackoffSeconds > 0L ? maxBackoffSeconds : Long.MAX_VALUE;
                long backoff = Math.max(0L, initialBackoffSeconds);

                for (int i = 1; i < attempts && backoff < cap; i++) {
                    if (backoff > (cap / 2)) {
                        backoff = cap;
                    } else {
                        backoff = backoff * 2;
                    }
                    if (backoff >= cap) {
                        backoff = cap;
                        break;
                    }
                }

                if (backoff == 0L) {
                    return 0L;
                }

                final long capped = Math.min(backoff, cap);

                if (!jitter) {
                    return capped;
                }

                return ThreadLocalRandom.current().nextLong(1, capped + 1);
            }
        };
    }

    /**
     * Restart policy that never restarts.
     *
     * @return restart policy instance
     */
    static RestartPolicy never() {
        return new RestartPolicy() {
            @Override
            public boolean shouldRestart(int failureCount, Exception cause) {
                return false;
            }

            @Override
            public long backoffSeconds(int failureCount, Exception cause) {
                return 0L;
            }
        };
    }

}
