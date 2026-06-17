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

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlingStrategyTest {

    @Test
    void dropOnTransientStopOnPermanentDropsTransientErrors() {
        ErrorHandlingStrategy strategy = ErrorHandlingStrategy.dropOnTransientStopOnPermanent();

        ErrorContext transientContext = new ErrorContext(
                new RuntimeException("transient"),
                null,
                ErrorOrigin.REPLICATION,
                ErrorType.TRANSIENT);

        ErrorHandlingDecision decision = strategy.toErrorHandler().onError(transientContext);

        assertThat(decision.resolution()).isEqualTo(ErrorResolution.DROP_AND_CONTINUE);
        assertThat(decision.errorType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(decision.origin()).isEqualTo(ErrorOrigin.REPLICATION);
    }

    @Test
    void dropOnTransientStopOnPermanentStopsOnPermanentErrors() {
        ErrorHandlingStrategy strategy = ErrorHandlingStrategy.dropOnTransientStopOnPermanent();

        ErrorContext permanentContext = new ErrorContext(
                new RuntimeException("permanent"),
                null,
                ErrorOrigin.CHANGE_HANDLER,
                ErrorType.PERMANENT);

        ErrorHandlingDecision decision = strategy.toErrorHandler().onError(permanentContext);

        assertThat(decision.resolution()).isEqualTo(ErrorResolution.STOP_PROCESS);
        assertThat(decision.errorType()).isEqualTo(ErrorType.PERMANENT);
        assertThat(decision.origin()).isEqualTo(ErrorOrigin.CHANGE_HANDLER);
    }

    @Test
    void dropOnAnyErrorAlwaysDrops() {
        ErrorHandlingStrategy strategy = ErrorHandlingStrategy.dropOnAnyError();

        ErrorContext context = new ErrorContext(
                new RuntimeException("any"),
                null,
                ErrorOrigin.REPLICATION,
                ErrorType.PERMANENT);

        ErrorHandlingDecision decision = strategy.toErrorHandler().onError(context);

        assertThat(decision.resolution()).isEqualTo(ErrorResolution.DROP_AND_CONTINUE);
        assertThat(decision.errorType()).isEqualTo(ErrorType.PERMANENT);
        assertThat(decision.origin()).isEqualTo(ErrorOrigin.REPLICATION);
    }

    @Test
    void stopOnAnyErrorAlwaysStops() {
        ErrorHandlingStrategy strategy = ErrorHandlingStrategy.stopOnAnyError();

        ErrorContext context = new ErrorContext(
                new RuntimeException("any"),
                null,
                ErrorOrigin.REPLICATION,
                ErrorType.TRANSIENT);

        ErrorHandlingDecision decision = strategy.toErrorHandler().onError(context);

        assertThat(decision.resolution()).isEqualTo(ErrorResolution.STOP_PROCESS);
        assertThat(decision.errorType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(decision.origin()).isEqualTo(ErrorOrigin.REPLICATION);
    }
}

