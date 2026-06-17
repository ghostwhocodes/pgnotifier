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

class ErrorContextTest {

    @Test
    void factoryMethodsPopulateExpectedMetadata() {
        ChangeEvent event = new ChangeEvent("{\"id\":1}", 10L, "public", "demo", "INSERT");

        ErrorContext changeHandlerFailure = ErrorContext.changeHandlerFailure(new RuntimeException("boom"), event);
        ErrorContext replicationFailure = ErrorContext.replicationFailure(new RuntimeException("transport"));

        assertThat(changeHandlerFailure.origin()).isEqualTo(ErrorOrigin.CHANGE_HANDLER);
        assertThat(changeHandlerFailure.errorType()).isEqualTo(ErrorType.TRANSIENT);
        assertThat(changeHandlerFailure.event()).isEqualTo(event);
        assertThat(replicationFailure.origin()).isEqualTo(ErrorOrigin.REPLICATION);
        assertThat(replicationFailure.event()).isNull();
    }

    @Test
    void withErrorTypeReturnsUpdatedCopy() {
        ErrorContext context = ErrorContext.replicationFailure(new RuntimeException("boom"));

        ErrorContext updated = context.withErrorType(ErrorType.PERMANENT);

        assertThat(updated.errorType()).isEqualTo(ErrorType.PERMANENT);
        assertThat(updated.origin()).isEqualTo(context.origin());
        assertThat(updated.exception()).isEqualTo(context.exception());
    }
}
