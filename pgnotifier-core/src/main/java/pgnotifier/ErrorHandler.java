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

/**
 * Callback invoked when a {@link ChangeHandler} throws while processing a {@link ChangeEvent}.
 * <p>
 * Implementations can log, emit metrics, decide whether to retry or drop the offending event, or
 * instruct the worker to stop entirely.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ErrorHandler errorHandler = context -> {
 *     System.err.println("Failed payload: " + (context.event() != null ? context.event().payload() : "<none>"));
 *     context.exception().printStackTrace();
 *     return ErrorHandlingDecision.dropAndContinue(context);
 * };
 * }</pre>
 *
 * @author Nos Doughty
 */
@FunctionalInterface
public interface ErrorHandler {

    /**
     * Handle an exception that occurred while processing a change.
     *
     * @param context error context including exception metadata
     * @return decision on how to proceed
     */
    ErrorHandlingDecision onError(ErrorContext context);

    /**
     * Convenience factory to keep existing two-argument lambdas terse.
     *
     * @param delegate function that maps an exception/event pair to a decision
     * @return wrapped error handler
     */
    static ErrorHandler of(
            final java.util.function.BiFunction<Exception, ChangeEvent, ErrorHandlingDecision> delegate) {
        java.util.Objects.requireNonNull(delegate, "Delegate error handler must be provided");
        return context -> delegate.apply(context.exception(), context.event());
    }

}
