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

class ErrorHandlerTest {

    @Test
    void ofAdapterDelegatesExceptionAndEvent() {
        ChangeEvent event = new ChangeEvent("{\"id\":1}", 1L, "public", "demo", "INSERT");
        ErrorContext context = ErrorContext.changeHandlerFailure(new RuntimeException("boom"), event);

        ErrorHandler handler = ErrorHandler.of((exception, changeEvent) -> {
            assertThat(exception).isEqualTo(context.exception());
            assertThat(changeEvent).isEqualTo(event);
            return ErrorHandlingDecision.dropAndContinue(context);
        });

        assertThat(handler.onError(context).resolution()).isEqualTo(ErrorResolution.DROP_AND_CONTINUE);
    }
}
