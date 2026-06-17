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

package pgnotifier.metrics.micrometer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import pgnotifier.NotifierMetrics;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example {@link NotifierMetrics} implementation backed by Micrometer.
 * <p>
 * This class is provided as a thin adapter to demonstrate how to bind
 * the core metrics callbacks into a {@link MeterRegistry}. It is not
 * wired by default; applications are expected to construct it and pass
 * it into {@link pgnotifier.PgNotifierBuilder#metrics(NotifierMetrics)}.
 *
 * <p>Metrics exposed per slot ({@code slot} tag):</p>
 * <ul>
 *   <li>{@code pgnotifier_events_processed_total} – counter for successfully processed events</li>
 *   <li>{@code pgnotifier_handler_failures_total} – counter for handler failures</li>
 *   <li>{@code pgnotifier_replication_failures_total} – counter for replication failures</li>
 *   <li>{@code pgnotifier_restarts_total} – counter for process restarts</li>
 *   <li>{@code pgnotifier_last_event_epoch_millis} – gauge tracking the timestamp of the last processed event</li>
 *   <li>{@code pgnotifier_failure_streak} – gauge tracking the current process failure streak</li>
 *   <li>{@code pgnotifier_handler_queue_depth} – gauge tracking queued handler events</li>
 *   <li>{@code pgnotifier_handler_queue_capacity} – gauge tracking handler queue capacity</li>
 *   <li>{@code pgnotifier_handler_queue_drain_latency_millis} – summary of enqueue-to-dequeue latency</li>
 *   <li>{@code pgnotifier_last_persisted_lsn} – gauge tracking the last persisted LSN</li>
 *   <li>{@code pgnotifier_replication_lag_bytes} – gauge tracking worker-observed LSN lag</li>
 * </ul>
 *
 * @author Nos Doughty
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "This adapter is expected to retain the caller-provided MeterRegistry reference.")
public class MicrometerNotifierMetrics implements NotifierMetrics {

    private final MeterRegistry registry;
    private final Clock clock;

    private final Map<String, Counter> eventsProcessed = new ConcurrentHashMap<>();
    private final Map<String, Counter> handlerFailures = new ConcurrentHashMap<>();
    private final Map<String, Counter> replicationFailures = new ConcurrentHashMap<>();
    private final Map<String, Counter> restarts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastEventEpochMillis = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failureStreaks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> handlerQueueDepths = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> handlerQueueCapacities = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> handlerQueueDrainLatencies = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastPersistedLsns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> replicationLagBytes = new ConcurrentHashMap<>();

    /**
     * Creates a new Micrometer-backed metrics adapter using the system clock.
     *
     * @param registry meter registry to bind to
     */
    public MicrometerNotifierMetrics(final MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /**
     * Creates a new Micrometer-backed metrics adapter using the provided clock.
     *
     * @param registry meter registry to bind to
     * @param clock    clock used for time-based gauges
     */
    public MicrometerNotifierMetrics(final MeterRegistry registry, final Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public void onEventProcessed(final String slotName) {
        counter(eventsProcessed, "pgnotifier_events_processed_total", slotName).increment();
    }

    @Override
    public void onHandlerFailure(final String slotName, final Throwable cause) {
        counter(handlerFailures, "pgnotifier_handler_failures_total", slotName).increment();
    }

    @Override
    public void onReplicationFailure(final String slotName, final Throwable cause) {
        counter(replicationFailures, "pgnotifier_replication_failures_total", slotName).increment();
    }

    @Override
    public void onProcessRestart(final String slotName, final int failureCount) {
        counter(restarts, "pgnotifier_restarts_total", slotName).increment();
    }

    @Override
    public void onLastEventTimeUpdated(final String slotName, final long epochMilli) {
        AtomicLong holder = lastEventEpochMillis.computeIfAbsent(slotName, slot -> {
            AtomicLong ref = new AtomicLong(epochMilli);
            Gauge.builder("pgnotifier_last_event_epoch_millis", ref, AtomicLong::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        holder.set(epochMilli);
    }

    @Override
    public void onFailureStreakUpdated(final String slotName, final int failureCount) {
        AtomicInteger holder = failureStreaks.computeIfAbsent(slotName, slot -> {
            AtomicInteger ref = new AtomicInteger(failureCount);
            Gauge.builder("pgnotifier_failure_streak", ref, AtomicInteger::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        holder.set(failureCount);
    }

    @Override
    public void onHandlerQueueDepthUpdated(final String slotName, final int depth, final int capacity) {
        AtomicInteger depthHolder = handlerQueueDepths.computeIfAbsent(slotName, slot -> {
            AtomicInteger ref = new AtomicInteger(depth);
            Gauge.builder("pgnotifier_handler_queue_depth", ref, AtomicInteger::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        depthHolder.set(depth);

        AtomicInteger capacityHolder = handlerQueueCapacities.computeIfAbsent(slotName, slot -> {
            AtomicInteger ref = new AtomicInteger(capacity);
            Gauge.builder("pgnotifier_handler_queue_capacity", ref, AtomicInteger::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        capacityHolder.set(capacity);
    }

    @Override
    public void onHandlerQueueDrainLatency(final String slotName, final long latencyMillis) {
        handlerQueueDrainLatencies.computeIfAbsent(slotName, slot ->
                DistributionSummary.builder("pgnotifier_handler_queue_drain_latency_millis")
                        .tag("slot", slot)
                        .register(registry))
                .record((double) Math.max(0L, latencyMillis));
    }

    @Override
    public void onLastPersistedLsnUpdated(final String slotName, final long lsn) {
        AtomicLong holder = lastPersistedLsns.computeIfAbsent(slotName, slot -> {
            AtomicLong ref = new AtomicLong(lsn);
            Gauge.builder("pgnotifier_last_persisted_lsn", ref, AtomicLong::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        holder.set(lsn);
    }

    @Override
    public void onReplicationLagBytesUpdated(final String slotName, final long lagBytes) {
        AtomicLong holder = replicationLagBytes.computeIfAbsent(slotName, slot -> {
            AtomicLong ref = new AtomicLong(lagBytes);
            Gauge.builder("pgnotifier_replication_lag_bytes", ref, AtomicLong::get)
                    .tag("slot", slotName)
                    .register(registry);
            return ref;
        });
        holder.set(lagBytes);
    }

    /**
     * Helper to lazily create or look up a counter tagged with the slot name.
     */
    private Counter counter(final Map<String, Counter> cache, final String name, final String slotName) {
        return cache.computeIfAbsent(slotName, slot ->
                Counter.builder(name)
                        .tag("slot", slot)
                        .register(registry));
    }

    /**
     * Returns the current instant according to the configured clock.
     */
    protected Instant now() {
        return clock.instant();
    }

}
