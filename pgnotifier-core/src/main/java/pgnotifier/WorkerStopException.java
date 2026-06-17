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
 * Signals that the worker should stop and let the restart policy decide what happens next.
 */
public class WorkerStopException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorHandlingDecision decision;

    public WorkerStopException(final ErrorHandlingDecision decision) {
        super("Worker stop requested: " + decision.resolution());
        this.decision = decision;
    }

    public ErrorHandlingDecision decision() {
        return decision;
    }

    public boolean isPermanent() {
        return decision.errorType() == ErrorType.PERMANENT;
    }

}
