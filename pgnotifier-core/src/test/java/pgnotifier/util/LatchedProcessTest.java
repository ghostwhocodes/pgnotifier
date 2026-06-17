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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pgnotifier.ErrorContext;
import pgnotifier.ErrorHandlingDecision;
import pgnotifier.ErrorOrigin;
import pgnotifier.ErrorType;
import pgnotifier.RestartPolicy;
import pgnotifier.WorkerStopException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LatchedProcessTest {

    @Test
    void exposesFailedStateAfterTerminalFailure() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.never(),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            throw new RuntimeException("failure");
                        }

                        @Override
                        public void stop() {
                            // no-op
                        }
                    });

            process.start();

            awaitState(process, LatchedProcess.State.FAILED);

            assertThat(process.isRunning()).isFalse();
            assertThat(process.failureCount()).isEqualTo(1);
            assertThat(process.lastFailureEpochMillis()).isNotNull();
            assertThat(process.lastFailureRecovered()).isFalse();
            assertThat(process.lastFailureClassName()).isEqualTo(RuntimeException.class.getName());
            assertThat(process.lastFailureMessage()).isEqualTo("failure");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restartsAfterTransientFailureAndStopsAfterFatalFailure() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch fatalAttempt = new CountDownLatch(1);
            List<Integer> observedFailureCounts = new CopyOnWriteArrayList<>();
            List<Boolean> observedRestartDecisions = new CopyOnWriteArrayList<>();

            LatchedProcess.ProcessWorker worker = new LatchedProcess.ProcessWorker() {
                @Override
                public void start() {
                    if (attempts.incrementAndGet() == 1) {
                        throw new RuntimeException("transient");
                    }
                    fatalAttempt.countDown();
                    throw new WorkerStopException(ErrorHandlingDecision.stopProcess(
                            new ErrorContext(new RuntimeException("fatal"), null, ErrorOrigin.REPLICATION,
                                             ErrorType.PERMANENT)));
                }

                @Override
                public void stop() {
                    // no-op
                }
            };

            LatchedProcess.ProcessListener listener = (failureCount, cause, willRestart, backoffSeconds) -> {
                observedFailureCounts.add(failureCount);
                observedRestartDecisions.add(willRestart);
            };

            LatchedProcess process = new LatchedProcess(RestartPolicy.fixed(0L), 1, executor, worker, listener);
            process.start();

            assertThat(fatalAttempt.await(1, TimeUnit.SECONDS)).isTrue();
            awaitState(process, LatchedProcess.State.FAILED);

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(process.isRunning()).isFalse();
            assertThat(process.failureCount()).isEqualTo(2);
            assertThat(process.lastFailureEpochMillis()).isNotNull();
            assertThat(process.lastFailureRecovered()).isFalse();
            assertThat(process.lastFailureClassName()).isEqualTo(WorkerStopException.class.getName());
            assertThat(observedFailureCounts).containsExactly(1, 2);
            assertThat(observedRestartDecisions).containsExactly(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void normalWorkerReturnStopsWithoutRestarting() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();

            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.fixed(0L),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            attempts.incrementAndGet();
                        }

                        @Override
                        public void stop() {
                            // no-op
                        }
                    });

            process.start();

            awaitState(process, LatchedProcess.State.STOPPED);

            assertThat(attempts.get()).isEqualTo(1);
            assertThat(process.isRunning()).isFalse();
            assertThat(process.failureCount()).isZero();
            assertThat(process.lastFailureEpochMillis()).isNull();
            assertThat(process.lastFailureRecovered()).isTrue();
            assertThat(process.lastFailureClassName()).isNull();
            assertThat(process.lastFailureMessage()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopDuringBackoffPreventsRestart() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger stops = new AtomicInteger();

            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.fixed(30L),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            attempts.incrementAndGet();
                            throw new RuntimeException("transient");
                        }

                        @Override
                        public void stop() {
                            stops.incrementAndGet();
                        }
                    });

            process.start();
            awaitState(process, LatchedProcess.State.BACKING_OFF);
            assertThat(process.isRunning()).isTrue();

            process.stop();

            awaitState(process, LatchedProcess.State.STOPPED);

            assertThat(attempts.get()).isEqualTo(1);
            assertThat(stops.get()).isEqualTo(1);
            assertThat(process.isRunning()).isFalse();
            assertThat(process.failureCount()).isZero();
            assertThat(process.lastFailureRecovered()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopSignalsWorkerAndAllowsGracefulShutdown() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch stopped = new CountDownLatch(1);

            LatchedProcess.ProcessWorker worker = new LatchedProcess.ProcessWorker() {
                @Override
                public void start() {
                    started.countDown();
                    try {
                        stopped.await(1, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void stop() {
                    stopped.countDown();
                }
            };

            LatchedProcess process = new LatchedProcess(RestartPolicy.never(), 1, executor, worker);
            process.start();

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            awaitState(process, LatchedProcess.State.RUNNING);
            assertThat(process.isRunning()).isTrue();

            process.stop();

            awaitState(process, LatchedProcess.State.STOPPED);

            assertThat(process.isRunning()).isFalse();
            assertThat(process.lastFailureEpochMillis()).isNull();
            assertThat(process.lastFailureRecovered()).isTrue();
            assertThat(process.lastFailureClassName()).isNull();
            assertThat(process.lastFailureMessage()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void secondStartIsIgnoredWhileWorkerIsAlreadyActive() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            LatchedProcess process = new LatchedProcess(
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            attempts.incrementAndGet();
                            started.countDown();
                            try {
                                release.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void stop() {
                            release.countDown();
                        }
                    });

            process.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            awaitState(process, LatchedProcess.State.RUNNING);

            process.start();

            assertThat(attempts.get()).isEqualTo(1);

            process.stop();
            awaitState(process, LatchedProcess.State.STOPPED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentStartsScheduleSingleRunLoop() throws Exception {
        QueuingExecutorService executor = new QueuingExecutorService();
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(16);

        LatchedProcess process = new LatchedProcess(
                RestartPolicy.never(),
                1,
                executor,
                new LatchedProcess.ProcessWorker() {
                    @Override
                    public void start() {
                        // no-op
                    }

                    @Override
                    public void stop() {
                        // no-op
                    }
                });

        Thread[] callers = new Thread[16];
        try {
            for (int i = 0; i < callers.length; i++) {
                callers[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        startTogether.await(1, TimeUnit.SECONDS);
                        process.start();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completed.countDown();
                    }
                }, "latched-process-start-" + i);
                callers[i].start();
            }

            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            startTogether.countDown();
            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(executor.executeCalls()).isEqualTo(1);
            assertThat(process.state()).isEqualTo(LatchedProcess.State.STARTING);

            executor.runQueuedTask();

            awaitState(process, LatchedProcess.State.STOPPED);
        } finally {
            for (Thread caller : callers) {
                if (caller != null) {
                    caller.join(1000L);
                }
            }
            executor.shutdownNow();
        }
    }

    @Test
    void listenerFailureDoesNotPreventTerminalFailureState() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.never(),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            throw new RuntimeException("worker failure");
                        }

                        @Override
                        public void stop() {
                            // no-op
                        }
                    },
                    (failureCount, cause, willRestart, backoffSeconds) -> {
                        throw new IllegalStateException("listener failure");
                    });

            process.start();

            awaitState(process, LatchedProcess.State.FAILED);

            assertThat(process.failureCount()).isEqualTo(1);
            assertThat(process.lastFailureClassName()).isEqualTo(RuntimeException.class.getName());
            assertThat(process.lastFailureMessage()).isEqualTo("worker failure");
            assertThat(process.lastFailureRecovered()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void listenerFailureDoesNotPreventRestart() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch restarted = new CountDownLatch(1);

            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.fixed(0L),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("transient");
                            }
                            restarted.countDown();
                        }

                        @Override
                        public void stop() {
                            // no-op
                        }
                    },
                    (failureCount, cause, willRestart, backoffSeconds) -> {
                        throw new IllegalStateException("listener failure");
                    });

            process.start();

            assertThat(restarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitState(process, LatchedProcess.State.STOPPED);

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(process.lastFailureRecovered()).isTrue();
            assertThat(process.lastFailureClassName()).isEqualTo(RuntimeException.class.getName());
            assertThat(process.lastFailureMessage()).isEqualTo("transient");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startFailureFromExecutorMarksProcessFailed() {
        ExecutorService executor = new RejectingExecutorService();
        LatchedProcess process = new LatchedProcess(
                RestartPolicy.never(),
                1,
                executor,
                new LatchedProcess.ProcessWorker() {
                    @Override
                    public void start() {
                        // unreachable
                    }

                    @Override
                    public void stop() {
                        // no-op
                    }
                });

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(process::start)
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(process.state()).isEqualTo(LatchedProcess.State.FAILED);
            assertThat(process.isRunning()).isFalse();
            assertThat(process.lastFailureRecovered()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopBeforeQueuedRunExecutesLeavesProcessStopped() {
        QueuingExecutorService executor = new QueuingExecutorService();
        LatchedProcess process = new LatchedProcess(
                RestartPolicy.never(),
                0,
                executor,
                new LatchedProcess.ProcessWorker() {
                    @Override
                    public void start() {
                        // no-op
                    }

                    @Override
                    public void stop() {
                        // no-op
                    }
                });

        try {
            process.start();
            assertThat(process.state()).isEqualTo(LatchedProcess.State.STARTING);
            assertThat(process.isRunning()).isTrue();

            process.stop();
            executor.runQueuedTask();

            awaitState(process, LatchedProcess.State.STOPPED);
            assertThat(process.failureCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerExceptionDuringShutdownIsTreatedAsCleanStop() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.fixed(0L),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            started.countDown();
                            try {
                                release.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            throw new RuntimeException("shutdown");
                        }

                        @Override
                        public void stop() {
                            release.countDown();
                        }
                    });

            process.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            awaitState(process, LatchedProcess.State.RUNNING);

            process.stop();

            awaitState(process, LatchedProcess.State.STOPPED);
            assertThat(process.failureCount()).isZero();
            assertThat(process.lastFailureEpochMillis()).isNull();
            assertThat(process.lastFailureRecovered()).isTrue();
            assertThat(process.lastFailureClassName()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveredProcessRetainsLastFailureAsHistorical() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger attempts = new AtomicInteger();

            LatchedProcess process = new LatchedProcess(
                    RestartPolicy.fixed(0L),
                    1,
                    executor,
                    new LatchedProcess.ProcessWorker() {
                        @Override
                        public void start() {
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("transient");
                            }
                        }

                        @Override
                        public void stop() {
                            // no-op
                        }
                    });

            process.start();

            awaitState(process, LatchedProcess.State.STOPPED);

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(process.lastFailureEpochMillis()).isNotNull();
            assertThat(process.lastFailureRecovered()).isTrue();
            assertThat(process.lastFailureClassName()).isEqualTo(RuntimeException.class.getName());
            assertThat(process.lastFailureMessage()).isEqualTo("transient");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitState(final LatchedProcess process, final LatchedProcess.State expectedState) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (process.state() == expectedState) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for process state " + expectedState, e);
            }
        }
        throw new AssertionError("Timed out waiting for process state " + expectedState + ", last state was "
                                 + process.state());
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(final Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }

    private static final class QueuingExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;
        private volatile @Nullable Runnable queuedTask;
        private final AtomicInteger executeCalls = new AtomicInteger();

        @Override
        public void shutdown() {
            this.shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.shutdown = true;
            Runnable task = this.queuedTask;
            this.queuedTask = null;
            return task == null ? List.of() : List.of(task);
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown && this.queuedTask == null;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(final Runnable command) {
            this.executeCalls.incrementAndGet();
            this.queuedTask = command;
        }

        private int executeCalls() {
            return this.executeCalls.get();
        }

        private void runQueuedTask() {
            Runnable task = this.queuedTask;
            this.queuedTask = null;
            if (task != null) {
                task.run();
            }
        }
    }
}
