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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Utility helpers for executing blocks of code with unified exception handling and throttling semantics.
 *
 * @author Nos Doughty
 */
public class ExecutionUtil {

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "These functional interfaces are intended for lambdas that may throw checked exceptions.")
    public interface Block {

        void perform() throws Exception;

    }

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "These functional interfaces are intended for lambdas that may throw checked exceptions.")
    public interface BlockWithReturn<T extends @Nullable Object> {

        @Nullable T perform() throws Exception;

    }

    public interface ExceptionHandler {

        void handle(Exception e);

    }

    public interface ExceptionHandlerWithReturn<T extends @Nullable Object> {

        @Nullable T handle(Exception e);

    }

    private static ExceptionHandler defaultHandler = ExecutionUtil::handleDefaultException;

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "The default handler wraps checked exceptions and rethrows runtime exceptions by design.")
    private static void handleDefaultException(final Exception e) {
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException(e);
    }

    @SuppressWarnings("rawtypes")
    private static ExceptionHandlerWithReturn defaultHandlerWithReturn = ExecutionUtil::handleDefaultExceptionWithReturn;

    @SuppressFBWarnings(
            value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "The default handler wraps checked exceptions and rethrows runtime exceptions by design.")
    private static @Nullable Object handleDefaultExceptionWithReturn(final Exception e) {
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException(e);
    }

    /**
     * Returns the default {@link ExceptionHandler} used by helper methods.
     *
     * @return handler that rethrows {@link RuntimeException} and wraps checked exceptions
     */
    public static ExceptionHandler defaultHandler() {
        return defaultHandler;
    }

    /**
     * Returns the default {@link ExceptionHandlerWithReturn} used by helper methods.
     *
     * @param <T> result type
     * @return handler that rethrows {@link RuntimeException} and wraps checked exceptions
     */
    @SuppressWarnings("unchecked")
    public static <T extends @Nullable Object> ExceptionHandlerWithReturn<T> defaultHandlerWithReturn() {
        return (ExceptionHandlerWithReturn<T>) defaultHandlerWithReturn;
    }

    /**
     * Execute the given block, using the {@link #defaultHandler()} for exceptions.
     *
     * @param block code to run
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * ExecutionUtil.execute(() -> {
     *     // code that may throw checked exceptions
     * });
     * }</pre>
     */
    public static void execute(final Block block) {
        execute(block, defaultHandler);
    }

    /**
     * Execute the given block and delegate exceptions to the supplied handler.
     *
     * @param block   code to run
     * @param handler exception handler
     */
    public static void execute(final Block block, final ExceptionHandler handler) {

        try {

            block.perform();

        } catch (Exception e) {

            handler.handle(e);

        }

    }

    /**
     * Execute the given block with a return value using the default exception handler.
     *
     * @param block block to execute
     * @param <T>   result type
     * @return value returned by the block
     */
    public static <T extends @Nullable Object> @Nullable T execute(final BlockWithReturn<T> block) {

        return execute(block, defaultHandlerWithReturn());

    }

    /**
     * Execute the given block with a return value and delegate exceptions to the supplied handler.
     *
     * @param block   block to execute
     * @param handler exception handler
     * @param <T>     result type
     * @return value returned by the block or the handler
     */
    public static <T extends @Nullable Object> @Nullable T execute(
            final BlockWithReturn<T> block,
            final ExceptionHandlerWithReturn<T> handler) {

        try {

            return block.perform();

        } catch (Exception e) {

            return handler.handle(e);

        }

    }

    /**
     * Execute the block and then sleep for the given duration, ignoring interrupts while running
     * the block but allowing the sleep to be interrupted.
     *
     * @param block       block to run
     * @param sleepMillis sleep in milliseconds after execution
     */
    public static void performThrottled(final Block block, final long sleepMillis) {

        execute(block);

        try {

            Thread.sleep(sleepMillis);

        } catch (InterruptedException e) {
            // resume from sleep
        }

    }

    /**
     * Execute the block and sleep afterwards for the given duration whether or not a value is returned.
     *
     * @param block       block to run
     * @param sleepMillis sleep in milliseconds after execution
     * @param <T>         result type
     * @return value returned by the block
     */
    public static <T extends @Nullable Object> @Nullable T performThrottled(
            final BlockWithReturn<T> block,
            final long sleepMillis) {

        return performThrottled(block, sleepMillis, sleepMillis);

    }

    /**
     * Execute the block and sleep afterwards, choosing between {@code sleepMillis} and
     * {@code throttleMillis} depending on whether a value was returned.
     *
     * @param block         block to run
     * @param sleepMillis   sleep duration when no work was done (null result)
     * @param throttleMillis sleep duration when work was done (non-null result)
     * @param <T>           result type
     * @return value returned by the block
     */
    public static <T extends @Nullable Object> @Nullable T performThrottled(
            final BlockWithReturn<T> block, final long sleepMillis, final long throttleMillis) {

        final T val = execute(block);

        try {

            // use throttle value if work exists to be done 
            Thread.sleep(val == null ? sleepMillis : throttleMillis);

        } catch (InterruptedException e) {
            // resume from sleep
        }

        return val;

    }

    /**
     * Execute the block and then sleep uninterruptibly for the given duration.
     *
     * @param block       block to run
     * @param sleepMillis sleep in milliseconds after execution
     */
    public static void performThrottledNoInterrupt(final Block block, final long sleepMillis) {

        execute(block);

        Uninterruptibles.sleepUninterruptibly(sleepMillis, TimeUnit.MILLISECONDS);

    }

    /**
     * Execute the block with a return value and sleep uninterruptibly afterwards for the given duration.
     *
     * @param block       block to run
     * @param sleepMillis sleep in milliseconds after execution
     * @param <T>         result type
     * @return value returned by the block
     */
    public static <T extends @Nullable Object> @Nullable T performThrottledNoInterrupt(
            final BlockWithReturn<T> block,
            final long sleepMillis) {

        return performThrottledNoInterrupt(block, sleepMillis, sleepMillis);

    }

    /**
     * Execute the block with a return value and sleep uninterruptibly afterwards, choosing between
     * {@code sleepMillis} and {@code throttleMillis} depending on whether a value was returned.
     *
     * @param block         block to run
     * @param sleepMillis   sleep duration when no work was done (null result)
     * @param throttleMillis sleep duration when work was done (non-null result)
     * @param <T>           result type
     * @return value returned by the block
     */
    public static <T extends @Nullable Object> @Nullable T performThrottledNoInterrupt(
            final BlockWithReturn<T> block,
            final long sleepMillis,
            final long throttleMillis) {

        final @Nullable T val = execute(block);

        // use throttle value if work exists to be done 
        Uninterruptibles.sleepUninterruptibly(val == null ? sleepMillis : throttleMillis, TimeUnit.MILLISECONDS);

        return val;

    }

}
