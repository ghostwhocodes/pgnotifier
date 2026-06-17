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
 * Built-in error handling strategies that can be mapped directly to an {@link ErrorHandler}.
 * <p>
 * Strategies are intentionally small and opinionated so that applications can:
 * <ul>
 *     <li>Choose a sensible default without writing boilerplate handlers, and</li>
 *     <li>Override behaviour entirely by providing a custom {@link ErrorHandler} when needed.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * PgNotifier notifier = new PgNotifierBuilder()
 *         .addSlot(slotConfig)
 *         .changeHandler(event -> ProcessingDecision.COMMIT)
 *         .errorHandler(ErrorHandlingStrategy.dropOnTransientStopOnPermanent().toErrorHandler())
 *         .build()
 *         .start();
 * }</pre>
 *
 * @author Nos Doughty
 */
@FunctionalInterface
public interface ErrorHandlingStrategy {

    /**
     * Decide how to handle a failure given its context.
     *
     * @param context error context including origin and classification
     * @return decision that controls worker behaviour
     */
    ErrorHandlingDecision decide(ErrorContext context);

    /**
     * Converts this strategy into an {@link ErrorHandler}.
     *
     * @return error handler delegating to this strategy
     */
    default ErrorHandler toErrorHandler() {
        return this::decide;
    }

    /**
     * Strategy that:
     * <ul>
     *     <li>Drops transient failures (e.g. temporary handler/replication issues) and continues.</li>
     *     <li>Stops the worker on permanent failures so the restart policy can take over.</li>
     * </ul>
     *
     * <p>This is a good default for production services that want to:
     * <ul>
     *     <li>Skip clearly bad payloads or transient transport issues, and</li>
     *     <li>Surface configuration or authentication problems as hard failures.</li>
     * </ul>
     */
    static ErrorHandlingStrategy dropOnTransientStopOnPermanent() {
        return context -> {
            if (context.errorType() == ErrorType.PERMANENT) {
                return ErrorHandlingDecision.stopProcess(context);
            }
            return ErrorHandlingDecision.dropAndContinue(context);
        };
    }

    /**
     * Strategy that always drops the offending event and continues processing.
     * <p>
     * This keeps the worker running even on permanent failures. It is most suitable
     * for development and low-risk deployments where losing some events is acceptable.
     */
    static ErrorHandlingStrategy dropOnAnyError() {
        return context -> ErrorHandlingDecision.dropAndContinue(context);
    }

    /**
     * Strategy that always stops the worker on failure.
     * <p>
     * The restart policy then decides whether to restart or halt permanently.
     * This is appropriate when handlers are expected to be strictly reliable and
     * any failure should surface as a process-level error.
     */
    static ErrorHandlingStrategy stopOnAnyError() {
        return context -> ErrorHandlingDecision.stopProcess(context);
    }

}

