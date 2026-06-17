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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Context provided to {@link ErrorHandler} when a failure occurs.
 *
 * @param exception the thrown exception
 * @param event     the change being processed (may be {@code null})
 * @param origin    where the error originated
 * @param errorType classification of the error
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "ErrorContext intentionally exposes the original exception object to downstream handlers.")
public record ErrorContext(
        Exception exception,
        @Nullable ChangeEvent event,
        ErrorOrigin origin,
        ErrorType errorType) {

    public ErrorContext {
        Objects.requireNonNull(exception, "Exception must be provided");
        Objects.requireNonNull(origin, "Error origin must be provided");
        Objects.requireNonNull(errorType, "Error type must be provided");
    }

    /**
     * Context for a {@link ChangeHandler} failure.
     *
     * @param exception exception thrown by the handler
     * @param event     event being processed
     * @return error context
     */
    public static ErrorContext changeHandlerFailure(final Exception exception, final ChangeEvent event) {
        return new ErrorContext(exception, event, ErrorOrigin.CHANGE_HANDLER, ErrorType.TRANSIENT);
    }

    /**
     * Context for a replication or transport error.
     *
     * @param exception exception thrown while reading/acknowledging the stream
     * @return error context
     */
    public static ErrorContext replicationFailure(final Exception exception) {
        return new ErrorContext(exception, null, ErrorOrigin.REPLICATION, ErrorType.TRANSIENT);
    }

    /**
     * Returns a copy of this context with the given error type.
     *
     * @param newErrorType updated classification
     * @return updated context
     */
    public ErrorContext withErrorType(final ErrorType newErrorType) {
        return new ErrorContext(exception, event, origin, newErrorType);
    }

}
