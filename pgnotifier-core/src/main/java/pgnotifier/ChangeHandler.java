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
 * Callback that processes a single {@link ChangeEvent} and decides what to do with it.
 * <p>
 * Implementations are responsible for applying side effects (publishing to queues, updating caches, etc.)
 * and returning a {@link ProcessingDecision} to control how the replication stream advances.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ChangeHandler handler = event -> {
 *     System.out.println("Payload: " + event.payload());
 *     return ProcessingDecision.COMMIT;
 * };
 * }</pre>
 *
 * @author Nos Doughty
 */
@FunctionalInterface
public interface ChangeHandler {

    /**
     * Handle a single change event.
     *
     * @param event change event to process
     * @return a {@link ProcessingDecision} indicating whether to commit, retry, drop or stop
     */
    ProcessingDecision onChange(ChangeEvent event);

}
