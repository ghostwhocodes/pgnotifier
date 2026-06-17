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

import java.util.Objects;

/**
 * Decision returned by an {@link ErrorHandler} describing how to react to a failure.
 *
 * @param resolution   action to take
 * @param errorType    classification of the failure
 * @param origin       origin of the error
 * @param backoffMillis optional backoff in milliseconds (used with {@link ErrorResolution#RETRY_WITH_BACKOFF})
 */
public record ErrorHandlingDecision(
        ErrorResolution resolution,
        ErrorType errorType,
        ErrorOrigin origin,
        long backoffMillis) {

    public static final long NO_BACKOFF = 0L;

    public ErrorHandlingDecision {
        Objects.requireNonNull(resolution, "Error resolution must be provided");
        Objects.requireNonNull(errorType, "Error type must be provided");
        Objects.requireNonNull(origin, "Error origin must be provided");
        backoffMillis = Math.max(0L, backoffMillis);
    }

    /**
     * Drop the event and continue processing using the supplied context values.
     *
     * @param context error context
     * @return decision
     */
    public static ErrorHandlingDecision dropAndContinue(final ErrorContext context) {
        return dropAndContinue(context.errorType(), context.origin());
    }

    /**
     * Drop the event and continue processing.
     *
     * @param errorType classification of the error
     * @param origin    origin of the failure
     * @return decision
     */
    public static ErrorHandlingDecision dropAndContinue(
            final ErrorType errorType,
            final ErrorOrigin origin) {
        return new ErrorHandlingDecision(ErrorResolution.DROP_AND_CONTINUE, errorType, origin, NO_BACKOFF);
    }

    /**
     * Retry the event after a backoff using the supplied context values.
     *
     * @param context      error context
     * @param backoffMillis backoff in milliseconds
     * @return decision
     */
    public static ErrorHandlingDecision retryWithBackoff(
            final ErrorContext context,
            final long backoffMillis) {
        return retryWithBackoff(context.errorType(), context.origin(), backoffMillis);
    }

    /**
     * Retry the event after a backoff.
     *
     * @param errorType    classification of the error
     * @param origin       origin of the failure
     * @param backoffMillis backoff in milliseconds
     * @return decision
     */
    public static ErrorHandlingDecision retryWithBackoff(
            final ErrorType errorType,
            final ErrorOrigin origin,
            final long backoffMillis) {
        return new ErrorHandlingDecision(ErrorResolution.RETRY_WITH_BACKOFF, errorType, origin, backoffMillis);
    }

    /**
     * Stop the worker using the supplied context values.
     *
     * @param context error context
     * @return decision
     */
    public static ErrorHandlingDecision stopProcess(final ErrorContext context) {
        return stopProcess(context.errorType(), context.origin());
    }

    /**
     * Stop the worker.
     *
     * @param errorType classification of the error
     * @param origin    origin of the failure
     * @return decision
     */
    public static ErrorHandlingDecision stopProcess(
            final ErrorType errorType,
            final ErrorOrigin origin) {
        return new ErrorHandlingDecision(ErrorResolution.STOP_PROCESS, errorType, origin, NO_BACKOFF);
    }

    /**
     * Resolves the configured backoff, falling back to a default when unset.
     *
     * @param defaultBackoffMillis default backoff in milliseconds
     * @return resolved backoff
     */
    public long resolvedBackoffMillis(final long defaultBackoffMillis) {
        return backoffMillis > 0L ? backoffMillis : Math.max(0L, defaultBackoffMillis);
    }

}
