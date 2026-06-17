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

package pgnotifier.util;

import com.google.common.util.concurrent.Uninterruptibles;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pgnotifier.RestartPolicy;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A wrapper for a process that will be started and stopped on demand.
 * <p>
 * {@code LatchedProcess} manages an internal worker loop, applying a {@link RestartPolicy}
 * when the worker fails and coordinating shutdown via a latch.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * ExecutorService executor = Executors.newSingleThreadExecutor();
 * LatchedProcess.ProcessWorker worker = new LatchedProcess.ProcessWorker() {
 *     public void start() { runForever(); }
 *     public void stop() { requestShutdown(); }
 * };
 * LatchedProcess process = new LatchedProcess(30, executor, worker);
 * process.start();
 * // later
 * process.stop();
 * }</pre>
 *
 * @author Nos Doughty
 */
public class LatchedProcess {

    private static final Logger logger = LoggerFactory.getLogger(LatchedProcess.class);

    public enum State {
        STARTING,
        RUNNING,
        BACKING_OFF,
        STOPPING,
        STOPPED,
        FAILED
    }

    public interface ProcessWorker {

        void start();

        void stop();

    }

    @FunctionalInterface
    public interface ProcessListener {

        void onFailure(int failureCount, Exception cause, boolean willRestart, long backoffSeconds);

    }

    private static final ProcessListener NOOP_PROCESS_LISTENER = (failureCount, cause, willRestart, backoffSeconds) -> {
    };

    private final int shutdownTimeoutSeconds;

    private final ExecutorService executor;

    private final ProcessWorker worker;
    private final ProcessListener processListener;
    private final Object lifecycleLock = new Object();

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private final RestartPolicy restartPolicy;

    private volatile CountDownLatch completionLatch = new CountDownLatch(0);
    private volatile CountDownLatch stopSignal = new CountDownLatch(0);

    private final AtomicInteger failureCount = new AtomicInteger();

    private volatile @Nullable Long lastFailureEpochMillis;
    private volatile boolean lastFailureRecovered = true;

    private volatile @Nullable Throwable lastFailure;

    /**
     * Creates a new {@code LatchedProcess} with a fixed restart policy.
     *
     * @param shutdownTimeoutSeconds maximum time to wait for shutdown
     * @param executor               executor used to run the worker
     * @param worker                 worker to manage
     */
    public LatchedProcess(
            final int shutdownTimeoutSeconds,
            final ExecutorService executor,
            final ProcessWorker worker) {

        this(RestartPolicy.fixed(10L), shutdownTimeoutSeconds, executor, worker, NOOP_PROCESS_LISTENER);

    }

    public LatchedProcess(
            final RestartPolicy restartPolicy,
            final int shutdownTimeoutSeconds,
            final ExecutorService executor,
            final ProcessWorker worker) {

        this(restartPolicy, shutdownTimeoutSeconds, executor, worker, NOOP_PROCESS_LISTENER);

    }

    public LatchedProcess(
            final RestartPolicy restartPolicy,
            final int shutdownTimeoutSeconds,
            final ExecutorService executor,
            final ProcessWorker worker,
            final ProcessListener processListener) {

        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.executor = Objects.requireNonNull(executor);
        this.worker = Objects.requireNonNull(worker);
        this.restartPolicy = Objects.requireNonNull(restartPolicy);
        this.processListener = Objects.requireNonNull(processListener);

    }

    // wrap this call this with a post-construct, eg Spring's: javax.annotation.@PostConstruct
    /**
     * Starts the worker loop asynchronously using the configured {@link ExecutorService}.
     */
    public void start() {

        final CountDownLatch runCompletionLatch = new CountDownLatch(1);
        final CountDownLatch runStopSignal = new CountDownLatch(1);

        synchronized (this.lifecycleLock) {
            while (true) {
                final State currentState = this.state.get();
                logger.info("event=latched_process_start requested=true current_state={}", currentState);

                if (currentState != State.STOPPED && currentState != State.FAILED) {
                    logger.info("event=latched_process_start_ignored current_state={}", currentState);
                    return;
                }

                if (this.state.compareAndSet(currentState, State.STARTING)) {
                    this.failureCount.set(0);
                    this.stopRequested.set(false);
                    this.completionLatch = runCompletionLatch;
                    this.stopSignal = runStopSignal;
                    break;
                }
            }
        }

        try {
            this.executor.execute(() -> this.run(runCompletionLatch, runStopSignal));
        } catch (RejectedExecutionException e) {
            this.lastFailureEpochMillis = System.currentTimeMillis();
            this.lastFailureRecovered = false;
            this.lastFailure = e;
            this.state.set(State.FAILED);
            runStopSignal.countDown();
            runCompletionLatch.countDown();
            throw e;
        }

    }

    // wrap this call this with a pre-destroy, eg Spring's: javax.annotation.@PreDestroy
    /**
     * Requests the worker to stop and waits up to the configured timeout for it to terminate.
     */
    public void stop() {

        final CountDownLatch latchToAwait;

        synchronized (this.lifecycleLock) {
            final State currentState = this.state.get();
            logger.info("event=latched_process_stop requested=true shutdown_timeout_seconds={} current_state={}",
                        this.shutdownTimeoutSeconds, currentState);

            if (currentState == State.STOPPED || currentState == State.FAILED) {
                return;
            }

            transitionToStopping();
            this.stopRequested.set(true);
            this.stopSignal.countDown();
            latchToAwait = this.completionLatch;
        }

        // then stop the wrapped delegate
        this.worker.stop();

        // wait out its timeout
        final boolean unused = Uninterruptibles.awaitUninterruptibly(
                latchToAwait,
                this.shutdownTimeoutSeconds,
                TimeUnit.SECONDS);
        if (!unused) {
            logger.warn("event=latched_process_stop_timeout shutdown_timeout_seconds={}",
                        this.shutdownTimeoutSeconds);
        }

    }

    private void run(final CountDownLatch runCompletionLatch, final CountDownLatch runStopSignal) {

        try {
            while (true) {

                if (this.stopRequested.get()) {
                    this.failureCount.set(0);
                    this.state.set(State.STOPPED);
                    return;
                }

                this.state.set(State.RUNNING);
                if (this.lastFailure != null) {
                    this.lastFailureRecovered = true;
                }

                try {

                    // A normal worker return is a clean terminal stop. Only failures go through restart policy.
                    logger.info("event=latched_process_worker_start");
                    this.worker.start();
                    this.failureCount.set(0);
                    this.state.set(State.STOPPED);
                    return;

                } catch (Exception e) {

                    if (this.stopRequested.get()) {
                        logger.info("event=latched_process_worker_stopped_during_shutdown");
                        this.failureCount.set(0);
                        this.state.set(State.STOPPED);
                        return;
                    }

                    final int currentFailureCount = this.failureCount.incrementAndGet();
                    this.lastFailureEpochMillis = System.currentTimeMillis();
                    this.lastFailureRecovered = false;
                    this.lastFailure = e;

                    final boolean fatal = RestartPolicy.isFatal(e);
                    final boolean willRestart = !fatal && this.restartPolicy.shouldRestart(currentFailureCount, e);
                    final long backoffSeconds = willRestart
                            ? this.restartPolicy.backoffSeconds(currentFailureCount, e)
                            : 0L;

                    if (!willRestart) {
                        this.state.set(State.FAILED);
                        notifyFailureListener(currentFailureCount, e, false, 0L);
                        if (fatal) {
                            logger.error(
                                    "event=latched_process_worker_failed fatal=true will_restart=false failure_count={}",
                                    currentFailureCount, e);
                        } else {
                            logger.error(
                                    "event=latched_process_worker_failed fatal=false will_restart=false failure_count={}",
                                    currentFailureCount, e);
                        }
                        return;
                    }

                    this.state.set(State.BACKING_OFF);
                    notifyFailureListener(currentFailureCount, e, true, backoffSeconds);
                    logger.error(
                            "event=latched_process_worker_failed fatal=false will_restart=true failure_count={} backoff_seconds={}",
                            currentFailureCount, backoffSeconds, e);

                    final boolean stopDuringBackoff = Uninterruptibles.awaitUninterruptibly(
                            runStopSignal,
                            backoffSeconds,
                            TimeUnit.SECONDS);
                    if (stopDuringBackoff || this.stopRequested.get()) {
                        this.failureCount.set(0);
                        this.state.set(State.STOPPED);
                        return;
                    }
                }
            }
        } finally {
            runCompletionLatch.countDown();
        }

    }

    private void notifyFailureListener(
            final int currentFailureCount,
            final Exception cause,
            final boolean willRestart,
            final long backoffSeconds) {

        try {
            this.processListener.onFailure(currentFailureCount, cause, willRestart, backoffSeconds);
        } catch (RuntimeException e) {
            logger.warn(
                    "event=latched_process_failure_listener_failed failure_count={} will_restart={} backoff_seconds={}",
                    currentFailureCount, willRestart, backoffSeconds, e);
        }

    }

    private void transitionToStopping() {
        while (true) {
            final State currentState = this.state.get();
            if (currentState == State.STOPPING || currentState == State.STOPPED || currentState == State.FAILED) {
                return;
            }
            if (this.state.compareAndSet(currentState, State.STOPPING)) {
                return;
            }
        }
    }

    /**
     * Whether this process is currently running.
     *
     * @return {@code true} if the worker loop is active
     */
    public boolean isRunning() {
        return switch (this.state.get()) {
            case STARTING, RUNNING, BACKING_OFF, STOPPING -> true;
            case STOPPED, FAILED -> false;
        };
    }

    /**
     * Current lifecycle state for this process.
     *
     * @return current process state
     */
    public State state() {
        return this.state.get();
    }

    /**
     * Current failure streak for this process.
     *
     * @return number of consecutive failures since the last successful run
     */
    public int failureCount() {
        return this.failureCount.get();
    }

    /**
     * Class name of the last failure that occurred in the worker loop.
     *
     * @return fully qualified exception class name or {@code null} if none
     */
    public @Nullable String lastFailureClassName() {
        final Throwable failure = this.lastFailure;
        return failure != null ? failure.getClass().getName() : null;
    }

    /**
     * Timestamp of the last failure that occurred in the worker loop.
     *
     * @return epoch milliseconds or {@code null} if none
     */
    public @Nullable Long lastFailureEpochMillis() {
        return this.lastFailureEpochMillis;
    }

    /**
     * Whether the last process failure has subsequently recovered.
     *
     * @return {@code true} if the failure is historical rather than currently active
     */
    public boolean lastFailureRecovered() {
        return this.lastFailureRecovered;
    }

    /**
     * Message of the last failure that occurred in the worker loop.
     *
     * @return exception message or {@code null} if none
     */
    public @Nullable String lastFailureMessage() {
        final Throwable failure = this.lastFailure;
        return failure != null ? failure.getMessage() : null;
    }

}
