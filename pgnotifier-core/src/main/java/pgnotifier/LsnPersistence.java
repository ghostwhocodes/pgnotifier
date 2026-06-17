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

import java.util.Optional;

/**
 * Hook for persisting and restoring replication LSNs.
 */
public interface LsnPersistence {

    /**
     * Persist the last successfully processed LSN for the given slot.
     *
     * @param slotConfig slot being processed
     * @param lsn        processed LSN
     */
    default void persist(final PgNotifier.SlotConfig slotConfig, final long lsn) {
        // noop
    }

    /**
     * Provide the exact PostgreSQL start position for the given slot, if available.
     * <p>
     * PgNotifier passes this value to the replication stream without incrementing it.
     *
     * @param slotConfig slot being processed
     * @return start position LSN, or {@link Optional#empty()} to use the server default
     */
    default Optional<Long> startingLsn(final PgNotifier.SlotConfig slotConfig) {
        return Optional.empty();
    }

    /**
     * Convenience no-op persistence implementation.
     *
     * @return persistence adapter that does nothing
     */
    static LsnPersistence noop() {
        return new LsnPersistence() {};
    }

}
