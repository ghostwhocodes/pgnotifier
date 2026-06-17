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
 * Resolution chosen for a processing failure.
 */
public enum ErrorResolution {

    /**
     * Skip the offending event and continue processing.
     */
    DROP_AND_CONTINUE,

    /**
     * Retry the same event after backing off.
     */
    RETRY_WITH_BACKOFF,

    /**
     * Stop the worker and let the restart policy decide what happens next.
     */
    STOP_PROCESS

}
