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

package pgnotifier.top;

import org.jspecify.annotations.Nullable;
import pgnotifier.ChangeEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stats model for the top-style dashboard.
 * <p>
 * Tracks total events, basic per-table and per-operation counts, and exposes
 * a simple idle-time metric based on the timestamp of the last observed event.
 *
 * @author Nos Doughty
 */
public class Stats {

    private final AtomicLong totalEvents = new AtomicLong();
    private final ConcurrentMap<String, LongAdder> tableCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> operationCounts = new ConcurrentHashMap<>();
    private final AtomicLong lastEventTimeMillis = new AtomicLong(0L);

    public void increment(final @Nullable ChangeEvent event) {

        this.totalEvents.incrementAndGet();

        if (event != null) {

            final String table = event.table();
            if (table != null) {
                tableCounts.computeIfAbsent(table, t -> new LongAdder()).increment();
            }

            final String operation = event.operation();
            if (operation != null) {
                operationCounts.computeIfAbsent(operation, op -> new LongAdder()).increment();
            }

        }

        lastEventTimeMillis.set(System.currentTimeMillis());

    }

    public long getTotalEvents() {
        return this.totalEvents.get();
    }

    /**
     * Returns a snapshot of event counts grouped by table name.
     *
     * @return immutable snapshot of table -> count mappings
     */
    public Map<String, Long> snapshotTableCounts() {

        final Map<String, Long> snapshot = new LinkedHashMap<>();
        tableCounts.forEach((table, counter) -> snapshot.put(table, counter.longValue()));
        return snapshot;

    }

    /**
     * Returns a snapshot of event counts grouped by operation type.
     *
     * @return immutable snapshot of operation -> count mappings
     */
    public Map<String, Long> snapshotOperationCounts() {

        final Map<String, Long> snapshot = new LinkedHashMap<>();
        operationCounts.forEach((operation, counter) -> snapshot.put(operation, counter.longValue()));
        return snapshot;

    }

    /**
     * Returns the number of whole seconds since the last event was observed.
     *
     * @param nowMillis current time in milliseconds
     * @return idle time in seconds, or {@code 0} if no events have been seen yet
     */
    public long getIdleSeconds(final long nowMillis) {

        final long last = lastEventTimeMillis.get();
        if (last == 0L || nowMillis <= last) {
            return 0L;
        }

        return (nowMillis - last) / 1000L;

    }

}
