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

package pgnotifier.internal.replication;

import com.google.common.util.concurrent.Uninterruptibles;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgnotifier.ChangeEvent;
import pgnotifier.ChangeHandler;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.ErrorHandler;
import pgnotifier.LsnPersistence;
import pgnotifier.PgNotifier;
import pgnotifier.NotifierMetrics;
import pgnotifier.SlotHealth;
import pgnotifier.WorkerStopException;
import pgnotifier.util.LatchedProcess;

import java.util.concurrent.TimeUnit;

import java.util.Objects;

/**
 * Default replication worker that reads changes in batches from a {@link ReplicationSlot}
 * and delegates processing to {@link pgnotifier.ChangeHandler} and {@link pgnotifier.ErrorHandler}.
 * <p>
 * For each payload, the worker decodes a {@link ChangeEvent} and delegates handler invocation
 * and settlement decisions to the handler execution module.
 *
 * @author Nos Doughty
 */
public class BatchReplicationWorker implements LatchedProcess.ProcessWorker {

    private static final Logger logger = LoggerFactory.getLogger(BatchReplicationWorker.class);

    private final ReplicationSlot slot;

    private final PgNotifier.SlotConfig slotConfig;

    private final pgnotifier.ChangeEventDecoder decoder;

    private final long sleepMillis;

    private final long throttleMillis;

    private final EventSettlement eventSettlement;
    private final HandlerExecution handlerExecution;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;


    /**
     * Creates a new batch worker.
     *
     * @param slot          replication slot to consume from
     * @param slotConfig    configuration for the slot (used for LSN persistence)
     * @param changeHandler handler for successful events
     * @param errorHandler  handler for failures
     * @param decoder       decoder that converts payloads into {@link ChangeEvent}s
     * @param sleepMillis   sleep interval when no data is available
     * @param throttleMillis sleep interval when data was processed
     * @param errorBackoffMillis default backoff when retrying after errors
     * @param lsnPersistence persistence adapter for processed LSNs
     */
    public BatchReplicationWorker(
            final ReplicationSlot slot,
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final pgnotifier.ChangeEventDecoder decoder,
            final long sleepMillis,
            final long throttleMillis,
            final long errorBackoffMillis,
            final LsnPersistence lsnPersistence) {

        this(slot, slotConfig, changeHandler, errorHandler, decoder, sleepMillis, throttleMillis,
                errorBackoffMillis, lsnPersistence, HandlerExecutionConfig.inline(), new SlotHealth(), NotifierMetrics.noop());

    }

    /**
     * Creates a new batch worker with explicit health and metrics hooks.
     *
     * @param slot          replication slot to consume from
     * @param slotConfig    configuration for the slot (used for LSN persistence)
     * @param changeHandler handler for successful events
     * @param errorHandler  handler for failures
     * @param decoder       decoder that converts payloads into {@link ChangeEvent}s
     * @param sleepMillis   sleep interval when no data is available
     * @param throttleMillis sleep interval when data was processed
     * @param errorBackoffMillis default backoff when retrying after errors
     * @param lsnPersistence persistence adapter for processed LSNs
     * @param slotHealth    mutable health state for this slot
     * @param metrics       metrics bridge implementation
     */
    public BatchReplicationWorker(
            final ReplicationSlot slot,
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final pgnotifier.ChangeEventDecoder decoder,
            final long sleepMillis,
            final long throttleMillis,
            final long errorBackoffMillis,
            final LsnPersistence lsnPersistence,
            final HandlerExecutionConfig handlerExecutionConfig,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        this.slot = Objects.requireNonNull(slot);

        this.slotConfig = Objects.requireNonNull(slotConfig);

        this.decoder = Objects.requireNonNull(decoder);

        this.sleepMillis = sleepMillis;
        this.throttleMillis = throttleMillis;
        final SlotHealth effectiveSlotHealth = Objects.requireNonNull(slotHealth);
        final NotifierMetrics effectiveMetrics = Objects.requireNonNull(metrics);
        this.eventSettlement = new EventSettlement(this.slotConfig, lsnPersistence, effectiveSlotHealth, effectiveMetrics);
        this.handlerExecution = HandlerExecution.create(
                this.slotConfig,
                changeHandler,
                errorHandler,
                handlerExecutionConfig,
                sleepMillis,
                errorBackoffMillis,
                this.eventSettlement,
                effectiveSlotHealth,
                effectiveMetrics);

    }


    @Override
    public void start() {

        // start the run only if we aren't already running
        if (!this.running) {

            this.running = true;
            this.stopRequested = false;

            logger.info("event=replication_worker_start slot={}", this.slotConfig.slotname());

            // then run the loop
            final ReplicationStream stream = acquireStream(this.slot);
            this.handlerExecution.start();
            this.processStream(stream);

        }

    }


    @Override
    public void stop() {

        // signal the worker loop to stop
        this.stopRequested = true;
        this.handlerExecution.requestStop();

        if (this.handlerExecution.isDrained()) {
            this.running = false;
        }
        logger.info("event=replication_worker_stop_requested slot={}", this.slotConfig.slotname());

    }

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Startup failures are surfaced as RuntimeException so the restart policy can act on them.")
    private ReplicationStream acquireStream(final ReplicationSlot slot) {

        try { // try to get a stream

            // try to get a stream - an error here won't allow us to start
            return slot.newReplicationStream();

        } catch (Exception e) { // but if we fail, undo the start and abort

            // don't allow it to start
            this.running = false;

            logger.error("event=replication_stream_start_failed slot={}", this.slotConfig.slotname(), e);

            // we won't have a stream to close, so just bail out
            throw new RuntimeException("Cannot start replication-streaming!", e);

        }

    }

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Replication loop failures are intentionally surfaced to the restart policy.")
    private void processStream(final ReplicationStream stream) {

        try {

            // we managed to start the stream, now we loop until the flag turns off
            while (this.running) {

                this.handlerExecution.propagateFatalFailure();
                this.handlerExecution.settleCompletedEvents(stream);

                if (this.handlerExecution.shouldStopImmediately()) {
                    this.running = false;
                    break;
                }

                if ((this.stopRequested || this.handlerExecution.shouldPauseReading())
                        && this.handlerExecution.isDrained()) {
                    this.running = false;
                    break;
                }

                if (this.stopRequested || this.handlerExecution.shouldPauseReading()) {
                    // External stop should quiesce the reader and let the in-flight queue drain.
                    backoff(Math.max(1L, this.sleepMillis));
                    continue;
                }

                try {

                    final String result = stream.readNonBlocking(this.sleepMillis, this.throttleMillis);

                    if (result == null) {
                        continue;
                    }

                    final long receivedLsn = stream.lastReceiveLsn();
                    this.eventSettlement.recordReceivedLsn(receivedLsn);
                    final ChangeEvent event = decoder.decode(result, receivedLsn);
                    final HandlerExecution.Result handlerResult = this.handlerExecution.handleEvent(event, stream);
                    if (handlerResult == HandlerExecution.Result.STOP) {
                        this.running = false;
                    }

                } catch (WorkerStopException stop) {

                    throw stop;

                } catch (Exception replicationException) {

                    this.handlerExecution.handleReplicationFailure(replicationException, stream);

                }

            }

            this.handlerExecution.propagateFatalFailure();
            this.handlerExecution.settleCompletedEvents(stream);

        } catch (WorkerStopException stop) {

            throw stop;

        } catch (Exception e) {

            throw new RuntimeException("Failure when processing the replication-stream, please re-open and try again!",
                                       e);

        } finally {

            this.handlerExecution.shutdown();
            this.closeStream(stream);

        }

    }

    private void closeStream(final ReplicationStream stream) {

        try {

            // we will have a stream, we need to close
            stream.close();

        } catch (Exception e) {

            // not much we can do here, if we can't close it, it's probably already gone.
            logger.error("event=replication_stream_close_failed slot={} message=\"Ignoring failure to close the replication-stream after an error\"",
                         this.slotConfig.slotname(), e);

        }

        // mark the loop as stopped
        this.running = false;

    }

    private void backoff(final long millis) {
        if (millis > 0L) {
            Uninterruptibles.sleepUninterruptibly(millis, TimeUnit.MILLISECONDS);
        }
    }

}
