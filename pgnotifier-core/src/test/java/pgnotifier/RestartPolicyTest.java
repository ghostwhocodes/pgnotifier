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

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class RestartPolicyTest {

    @Test
    void fixedAlwaysRestartsWithConstantBackoff() {
        RestartPolicy policy = RestartPolicy.fixed(5L);

        for (int i = 1; i <= 3; i++) {
            assertThat(policy.shouldRestart(i, new RuntimeException("test"))).isTrue();
            assertThat(policy.backoffSeconds(i, new RuntimeException("test"))).isEqualTo(5L);
        }
    }

    @Test
    void fixedDoesNotRestartOnFatalErrors() {
        RestartPolicy policy = RestartPolicy.fixed(5L);

        Exception fatal = new SQLException("Invalid auth", "28P01");
        assertThat(RestartPolicy.isFatal(fatal)).isTrue();
        assertThat(policy.shouldRestart(1, fatal)).isFalse();
    }

    @Test
    void exponentialBackoffCapsAtMax() {
        RestartPolicy policy = RestartPolicy.exponential(2L, 10L, false);

        assertThat(policy.backoffSeconds(1, new RuntimeException("test"))).isEqualTo(2L);
        assertThat(policy.backoffSeconds(2, new RuntimeException("test"))).isEqualTo(4L);
        assertThat(policy.backoffSeconds(3, new RuntimeException("test"))).isEqualTo(8L);
        assertThat(policy.backoffSeconds(4, new RuntimeException("test"))).isEqualTo(10L);
        assertThat(policy.backoffSeconds(5, new RuntimeException("test"))).isEqualTo(10L);
    }

    @Test
    void jitteredBackoffStaysWithinBounds() {
        RestartPolicy policy = RestartPolicy.exponential(3L, 6L, true);

        for (int i = 0; i < 10; i++) {
            long backoff = policy.backoffSeconds(2, new RuntimeException("test"));
            assertThat(backoff).isGreaterThanOrEqualTo(1L).isLessThanOrEqualTo(6L);
        }
    }

    @Test
    void neverPolicyDisablesRestarts() {
        RestartPolicy policy = RestartPolicy.never();

        assertThat(policy.shouldRestart(3, new RuntimeException("test"))).isFalse();
        assertThat(policy.backoffSeconds(3, new RuntimeException("test"))).isEqualTo(0L);
    }

    @Test
    void fatalDetectionHandlesNestedWorkerStopAndClassLoadingErrors() {
        WorkerStopException stop = new WorkerStopException(ErrorHandlingDecision.stopProcess(
                new ErrorContext(
                        new RuntimeException("fatal"),
                        null,
                        pgnotifier.ErrorOrigin.REPLICATION,
                        pgnotifier.ErrorType.PERMANENT)));

        assertThat(RestartPolicy.isFatal(new RuntimeException(stop))).isTrue();
        assertThat(RestartPolicy.isFatal(new RuntimeException(new ClassNotFoundException("missing")))).isTrue();
        assertThat(RestartPolicy.isFatal(new RuntimeException(new NoClassDefFoundError("missing")))).isTrue();
    }

    @Test
    void fatalDetectionHandlesSqlStateSpecialCases() {
        assertThat(RestartPolicy.isFatal(new SQLException("bad auth", "28P01"))).isTrue();
        assertThat(RestartPolicy.isFatal(new SQLException("missing object", "42704"))).isTrue();
        assertThat(RestartPolicy.isFatal(new SQLException("missing plugin", "58P01"))).isTrue();
        assertThat(RestartPolicy.isFatal(new PSQLException("missing object", PSQLState.UNDEFINED_OBJECT))).isTrue();
        assertThat(RestartPolicy.isFatal(new SQLException("transient", (String) null))).isFalse();
    }

    @Test
    void exponentialPolicyCanReturnZeroBackoff() {
        RestartPolicy policy = RestartPolicy.exponential(0L, 10L, false);

        assertThat(policy.backoffSeconds(3, new RuntimeException("test"))).isZero();
        assertThat(policy.shouldRestart(1, new RuntimeException("test"))).isTrue();
    }

    @Test
    void exponentialPolicyCapsImmediatelyWhenBackoffExceedsHalfCap() {
        RestartPolicy policy = RestartPolicy.exponential(10L, 15L, false);

        assertThat(policy.backoffSeconds(2, new RuntimeException("test"))).isEqualTo(15L);
    }
}
