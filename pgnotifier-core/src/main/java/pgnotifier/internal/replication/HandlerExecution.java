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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgnotifier.ChangeEvent;
import pgnotifier.ChangeHandler;
import pgnotifier.ErrorContext;
import pgnotifier.ErrorHandler;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorResolution;
import pgnotifier.ErrorType;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.NotifierMetrics;
import pgnotifier.ProcessingDecision;
import pgnotifier.PgNotifier;
import pgnotifier.SlotHealth;
import pgnotifier.WorkerStopException;
import pgnotifier.RestartPolicy;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

interface HandlerExecution {

    static HandlerExecution create(
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final HandlerExecutionConfig config,
            final long sleepMillis,
            final long errorBackoffMillis,
            final EventSettlement eventSettlement,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        final HandlerExecutionConfig effectiveConfig = Objects.requireNonNullElse(config, HandlerExecutionConfig.inline());
        return switch (effectiveConfig.mode()) {
            case INLINE -> new InlineHandlerExecution(
                    slotConfig,
                    changeHandler,
                    errorHandler,
                    sleepMillis,
                    errorBackoffMillis,
                    eventSettlement,
                    slotHealth,
                    metrics);
            case ASYNC_QUEUE -> new AsyncQueueHandlerExecution(
                    slotConfig,
                    changeHandler,
                    errorHandler,
                    effectiveConfig,
                    sleepMillis,
                    errorBackoffMillis,
                    eventSettlement,
                    slotHealth,
                    metrics);
        };
    }

    void start();

    Result handleEvent(ChangeEvent event, ReplicationStream stream);

    void handleReplicationFailure(Exception exception, ReplicationStream stream);

    void settleCompletedEvents(ReplicationStream stream);

    void requestStop();

    boolean shouldPauseReading();

    boolean shouldStopImmediately();

    boolean isDrained();

    void propagateFatalFailure();

    void shutdown();

    enum Result {
        CONTINUE,
        STOP
    }

}

abstract class AbstractHandlerExecution implements HandlerExecution {

    private static final Logger logger = LoggerFactory.getLogger(HandlerExecution.class);
    private static final long BACKOFF_SLICE_MILLIS = 50L;

    protected final PgNotifier.SlotConfig slotConfig;
    protected final ChangeHandler changeHandler;
    protected final ErrorHandler errorHandler;
    protected final long sleepMillis;
    protected final long errorBackoffMillis;
    protected final EventSettlement eventSettlement;
    protected final SlotHealth slotHealth;
    protected final NotifierMetrics metrics;

    private volatile boolean stopRequested;

    AbstractHandlerExecution(
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final long sleepMillis,
            final long errorBackoffMillis,
            final EventSettlement eventSettlement,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        this.slotConfig = Objects.requireNonNull(slotConfig);
        this.changeHandler = Objects.requireNonNull(changeHandler);
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.sleepMillis = sleepMillis;
        this.errorBackoffMillis = errorBackoffMillis;
        this.eventSettlement = Objects.requireNonNull(eventSettlement);
        this.slotHealth = Objects.requireNonNull(slotHealth);
        this.metrics = Objects.requireNonNull(metrics);

    }

    @Override
    public void start() {
        this.stopRequested = false;
    }

    @Override
    public void handleReplicationFailure(final Exception exception, final ReplicationStream stream) {
        final ErrorContext context = failureContext(exception, null, ErrorOrigin.REPLICATION);
        this.slotHealth.recordWorkerFailure(context);
        this.metrics.onReplicationFailure(this.slotConfig.slotname(), exception);

        final ErrorHandlingDecision decision = safeOnError(context);

        if (decision.resolution() == ErrorResolution.DROP_AND_CONTINUE) {
            settleReplicationFailureDrop(stream);
            return;
        }

        if (decision.resolution() == ErrorResolution.RETRY_WITH_BACKOFF) {
            this.eventSettlement.leaveUnadvancedForRetry();
            backoff(decision.resolvedBackoffMillis(this.errorBackoffMillis));
            return;
        }

        throw new WorkerStopException(decision);
    }

    protected void settleReplicationFailureDrop(final ReplicationStream stream) {
        this.eventSettlement.settleStreamHeadDrop(stream);
    }

    @Override
    public void settleCompletedEvents(final ReplicationStream stream) {
        // no-op
    }

    @Override
    public void requestStop() {
        this.stopRequested = true;
    }

    @Override
    public boolean shouldPauseReading() {
        return false;
    }

    @Override
    public boolean shouldStopImmediately() {
        return false;
    }

    @Override
    public boolean isDrained() {
        return true;
    }

    @Override
    public void propagateFatalFailure() {
        // no-op
    }

    @Override
    public void shutdown() {
        // no-op
    }

    protected ErrorContext recordHandlerFailure(final Exception exception, final ChangeEvent event) {
        final ErrorContext context = failureContext(exception, event, ErrorOrigin.CHANGE_HANDLER);
        this.slotHealth.recordWorkerFailure(context);
        this.metrics.onHandlerFailure(this.slotConfig.slotname(), exception);
        return context;
    }

    protected ErrorHandlingDecision safeOnError(final ErrorContext context) {
        try {
            final ErrorHandlingDecision decision = this.errorHandler.onError(context);
            return Objects.requireNonNull(decision, "ErrorHandler must not return null");
        } catch (Exception handlerFailure) {
            final ErrorContext fallbackContext = new ErrorContext(
                    handlerFailure,
                    context.event(),
                    ErrorOrigin.ERROR_HANDLER,
                    ErrorType.PERMANENT);
            this.slotHealth.recordWorkerFailure(fallbackContext);
            this.metrics.onHandlerFailure(this.slotConfig.slotname(), handlerFailure);
            logger.error("Error handler failed while handling {} failure", context.origin(), handlerFailure);
            return ErrorHandlingDecision.stopProcess(fallbackContext);
        }
    }

    protected ErrorContext failureContext(
            final Exception exception,
            final @Nullable ChangeEvent event,
            final ErrorOrigin origin) {

        final ErrorType errorType = RestartPolicy.isFatal(exception)
                ? ErrorType.PERMANENT
                : ErrorType.TRANSIENT;

        return new ErrorContext(exception, event, origin, errorType);
    }

    protected void backoff(final long millis) {
        long remainingMillis = millis;
        while (remainingMillis > 0L && !stopRequested()) {
            final long sleepForMillis = Math.min(remainingMillis, BACKOFF_SLICE_MILLIS);
            Uninterruptibles.sleepUninterruptibly(sleepForMillis, TimeUnit.MILLISECONDS);
            remainingMillis -= sleepForMillis;
        }
    }

    protected boolean stopRequested() {
        return this.stopRequested;
    }

}

final class InlineHandlerExecution extends AbstractHandlerExecution {

    InlineHandlerExecution(
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final long sleepMillis,
            final long errorBackoffMillis,
            final EventSettlement eventSettlement,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        super(slotConfig, changeHandler, errorHandler, sleepMillis, errorBackoffMillis,
                eventSettlement, slotHealth, metrics);

    }

    @Override
    public Result handleEvent(final ChangeEvent event, final ReplicationStream stream) {
        while (!stopRequested()) {
            try {
                final ProcessingDecision decision = this.changeHandler.onChange(event);

                if (decision == ProcessingDecision.COMMIT || decision == ProcessingDecision.DROP) {
                    this.eventSettlement.settleHandledEvent(stream, event);
                    return Result.CONTINUE;
                }

                if (decision == ProcessingDecision.STOP) {
                    return Result.STOP;
                }

                if (decision == ProcessingDecision.RETRY) {
                    this.eventSettlement.leaveUnadvancedForRetry();
                    backoff(this.errorBackoffMillis);
                }
            } catch (Exception handlerException) {
                final ErrorContext context = recordHandlerFailure(handlerException, event);
                final ErrorHandlingDecision decision = safeOnError(context);

                if (decision.resolution() == ErrorResolution.DROP_AND_CONTINUE) {
                    this.eventSettlement.settleFailedEventDrop(stream, event);
                    return Result.CONTINUE;
                }

                if (decision.resolution() == ErrorResolution.RETRY_WITH_BACKOFF) {
                    this.eventSettlement.leaveUnadvancedForRetry();
                    backoff(decision.resolvedBackoffMillis(this.errorBackoffMillis));
                    continue;
                }

                throw new WorkerStopException(decision);
            }
        }

        this.eventSettlement.leaveUnadvancedForRetry();
        return Result.STOP;
    }

}

final class AsyncQueueHandlerExecution extends AbstractHandlerExecution {

    private static final Logger logger = LoggerFactory.getLogger(AsyncQueueHandlerExecution.class);

    private final HandlerExecutionConfig config;
    private final BlockingQueue<EventState> eventQueue;
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final AtomicReference<@Nullable RuntimeException> fatalErrorFromHandlers = new AtomicReference<>();

    private volatile boolean drainRequested;
    private volatile boolean immediateStopRequested;
    private volatile boolean streamHeadDropDeferred;
    private @Nullable ExecutorService handlerExecutor;

    AsyncQueueHandlerExecution(
            final PgNotifier.SlotConfig slotConfig,
            final ChangeHandler changeHandler,
            final ErrorHandler errorHandler,
            final HandlerExecutionConfig config,
            final long sleepMillis,
            final long errorBackoffMillis,
            final EventSettlement eventSettlement,
            final SlotHealth slotHealth,
            final NotifierMetrics metrics) {

        super(slotConfig, changeHandler, errorHandler, sleepMillis, errorBackoffMillis,
                eventSettlement, slotHealth, metrics);
        this.config = Objects.requireNonNull(config);
        this.eventQueue = new ArrayBlockingQueue<>(config.queueCapacity());
        reportHandlerQueueDepth();

    }

    @Override
    public void start() {
        super.start();
        this.drainRequested = false;
        this.immediateStopRequested = false;
        this.streamHeadDropDeferred = false;
        this.fatalErrorFromHandlers.set(null);
        this.eventQueue.clear();
        this.eventSettlement.resetAsyncSettlement(this.sequenceCounter.get() - 1L);
        reportHandlerQueueDepth();

        final int workerThreads = Math.max(1, this.config.workerThreads());
        this.handlerExecutor = Executors.newFixedThreadPool(workerThreads);

        for (int i = 0; i < workerThreads; i++) {
            this.handlerExecutor.execute(this::handlerLoop);
        }
    }

    @Override
    public Result handleEvent(final ChangeEvent event, final ReplicationStream stream) {
        final long sequence = this.sequenceCounter.getAndIncrement();
        final EventState state = new EventState(
                this.eventSettlement.beginAsyncEvent(sequence, event),
                System.nanoTime());

        switch (this.config.queueOverflowPolicy()) {
            case BLOCK -> {
                Uninterruptibles.putUninterruptibly(this.eventQueue, state);
                reportHandlerQueueDepth();
            }
            case DROP_OLDEST -> {
                if (!this.eventQueue.offer(state)) {
                    final EventState dropped = this.eventQueue.poll();
                    if (dropped != null) {
                        logger.warn("event=handler_queue_drop_oldest slot={} sequence={}",
                                    this.slotConfig.slotname(), dropped.sequence());
                        dropped.queueDropped();
                    }
                    Uninterruptibles.putUninterruptibly(this.eventQueue, state);
                }
                reportHandlerQueueDepth();
            }
            case DROP_NEWEST -> {
                if (!this.eventQueue.offer(state)) {
                    logger.warn("event=handler_queue_drop_newest slot={} sequence={}",
                                this.slotConfig.slotname(), state.sequence());
                    state.queueDropped();
                }
                reportHandlerQueueDepth();
            }
            case FATAL -> {
                if (!this.eventQueue.offer(state)) {
                    logger.error("event=handler_queue_full_fatal slot={} sequence={}",
                                 this.slotConfig.slotname(), state.sequence());
                    state.notAdvanced();
                    final ErrorContext context = new ErrorContext(
                            new IllegalStateException("Handler queue full (FATAL policy)"),
                            null,
                            ErrorOrigin.REPLICATION,
                            ErrorType.TRANSIENT);
                    throw new WorkerStopException(ErrorHandlingDecision.stopProcess(context));
                }
                reportHandlerQueueDepth();
            }
            default -> throw new IllegalStateException("Unknown queue overflow policy: " + this.config.queueOverflowPolicy());
        }

        return Result.CONTINUE;
    }

    @Override
    public void settleCompletedEvents(final ReplicationStream stream) {
        this.eventSettlement.settleCompletedAsyncEvents(stream, this::reportHandlerQueueDepth);
        if (this.streamHeadDropDeferred && !this.eventSettlement.hasPendingAsyncEvents()) {
            this.eventSettlement.settleStreamHeadDrop(stream);
            this.streamHeadDropDeferred = false;
        }
    }

    @Override
    protected void settleReplicationFailureDrop(final ReplicationStream stream) {
        if (this.eventSettlement.hasPendingAsyncEvents()) {
            this.eventSettlement.leaveUnadvancedForRetry();
            this.streamHeadDropDeferred = true;
            return;
        }

        this.eventSettlement.settleStreamHeadDrop(stream);
    }

    @Override
    public void requestStop() {
        super.requestStop();
        this.drainRequested = true;
    }

    @Override
    public boolean shouldPauseReading() {
        return this.drainRequested || this.immediateStopRequested || this.streamHeadDropDeferred;
    }

    @Override
    public boolean shouldStopImmediately() {
        return this.immediateStopRequested;
    }

    @Override
    public boolean isDrained() {
        if (!this.eventSettlement.hasPendingAsyncEvents()) {
            return true;
        }

        if ((stopRequested() || this.immediateStopRequested)
                && this.eventQueue.isEmpty()
                && this.eventSettlement.asyncSettlementBlockedByNotAdvanced()
                && this.eventSettlement.allPendingAsyncEventsCompleted()) {
            this.eventSettlement.abandonAsyncSettlement(this.sequenceCounter.get() - 1L);
            reportHandlerQueueDepth();
            return true;
        }

        return false;
    }

    @Override
    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Fatal async handler failures are rethrown so the worker can terminate immediately.")
    public void propagateFatalFailure() {
        final RuntimeException fatal = this.fatalErrorFromHandlers.get();
        if (fatal != null) {
            throw fatal;
        }
    }

    @Override
    public void shutdown() {
        final ExecutorService executor = this.handlerExecutor;
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        try {
            executor.awaitTermination(this.sleepMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handlerLoop() {
        while (!this.immediateStopRequested && this.fatalErrorFromHandlers.get() == null) {
            if (this.drainRequested && this.eventQueue.isEmpty()) {
                break;
            }

            try {
                final EventState state = this.eventQueue.poll(this.sleepMillis, TimeUnit.MILLISECONDS);
                if (state == null) {
                    continue;
                }

                recordHandlerQueueDrainLatency(state);
                reportHandlerQueueDepth();
                processEvent(state);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("event=handler_worker_failed slot={} message=\"Handler worker failed\"",
                             this.slotConfig.slotname(), e);
                signalFatalFromHandlers(new RuntimeException("Handler worker failed", e));
                break;
            }
        }
    }

    private void processEvent(final EventState state) {
        final ChangeEvent event = state.event();

        while (!stopRequested() && !this.immediateStopRequested && this.fatalErrorFromHandlers.get() == null) {
            try {
                final ProcessingDecision decision = this.changeHandler.onChange(event);

                if (decision == ProcessingDecision.COMMIT || decision == ProcessingDecision.DROP) {
                    state.handled();
                    return;
                }

                if (decision == ProcessingDecision.STOP) {
                    this.immediateStopRequested = true;
                    state.notAdvanced();
                    return;
                }

                if (decision == ProcessingDecision.RETRY) {
                    this.eventSettlement.leaveUnadvancedForRetry();
                    backoff(this.errorBackoffMillis);
                }
            } catch (Exception handlerException) {
                final ErrorContext context = recordHandlerFailure(handlerException, event);
                final ErrorHandlingDecision decision = safeOnError(context);

                if (decision.resolution() == ErrorResolution.DROP_AND_CONTINUE) {
                    state.failedEventDropped();
                    return;
                }

                if (decision.resolution() == ErrorResolution.RETRY_WITH_BACKOFF) {
                    this.eventSettlement.leaveUnadvancedForRetry();
                    backoff(decision.resolvedBackoffMillis(this.errorBackoffMillis));
                    continue;
                }

                this.immediateStopRequested = true;
                signalFatalFromHandlers(new WorkerStopException(decision));
                state.notAdvanced();
                return;
            }
        }

        state.notAdvanced();
    }

    private void signalFatalFromHandlers(final RuntimeException cause) {
        this.fatalErrorFromHandlers.compareAndSet(null, cause);
        this.immediateStopRequested = true;
    }

    private void reportHandlerQueueDepth() {
        final int depth = this.eventQueue.size();
        final int capacity = this.config.queueCapacity();
        this.slotHealth.recordHandlerQueueDepth(depth, capacity);
        this.metrics.onHandlerQueueDepthUpdated(this.slotConfig.slotname(), depth, capacity);
    }

    private void recordHandlerQueueDrainLatency(final EventState state) {
        final long latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - state.enqueuedNanos);
        this.metrics.onHandlerQueueDrainLatency(this.slotConfig.slotname(), Math.max(0L, latencyMillis));
    }

    private static final class EventState {

        private final EventSettlement.AsyncEvent settlement;
        private final long enqueuedNanos;

        private EventState(final EventSettlement.AsyncEvent settlement, final long enqueuedNanos) {
            this.settlement = settlement;
            this.enqueuedNanos = enqueuedNanos;
        }

        private long sequence() {
            return this.settlement.sequence();
        }

        private ChangeEvent event() {
            return this.settlement.event();
        }

        private void handled() {
            this.settlement.handled();
        }

        private void failedEventDropped() {
            this.settlement.failedEventDropped();
        }

        private void queueDropped() {
            this.settlement.queueDropped();
        }

        private void notAdvanced() {
            this.settlement.notAdvanced();
        }

    }

}
