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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionUtilTest {

    @Test
    void executeRunsBlockAndUsesDefaultHandler() {
        AtomicBoolean ran = new AtomicBoolean(false);

        ExecutionUtil.execute(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void defaultHandlerWrapsCheckedExceptions() {
        assertThatThrownBy(() -> ExecutionUtil.defaultHandler().handle(new Exception("boom")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void defaultHandlerWithReturnPropagatesRuntimeExceptions() {
        assertThatThrownBy(() -> ExecutionUtil.<Object>defaultHandlerWithReturn().handle(new RuntimeException("boom")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void defaultHandlerWithReturnWrapsCheckedExceptions() {
        assertThatThrownBy(() -> ExecutionUtil.<Object>defaultHandlerWithReturn().handle(new Exception("boom")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void executeWithReturnRunsBlockAndReturnsValue() {
        Integer result = ExecutionUtil.execute(() -> 42);

        assertThat(result).isEqualTo(42);
    }

    @Test
    void executeWithHandlerReceivesException() {
        AtomicBoolean handled = new AtomicBoolean(false);

        ExecutionUtil.execute(
                (ExecutionUtil.Block) () -> {
                    throw new Exception("boom");
                },
                e -> handled.set(true));

        assertThat(handled).isTrue();
    }

    @Test
    void executeUsesDefaultHandlerForRuntimeExceptions() {
        assertThatThrownBy(() -> ExecutionUtil.execute(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void executeWithReturnHandlerReturnsFallbackValue() {
        Integer result = ExecutionUtil.execute(
                (ExecutionUtil.BlockWithReturn<Integer>) () -> {
                    throw new Exception("boom");
                },
                e -> 7);

        assertThat(result).isEqualTo(7);
    }

    @Test
    void performThrottledSleepsAndReturnsValue() {
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottled(() -> 1, 5L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void performThrottledUsesThrottleMillisWhenWorkDone() {
        AtomicInteger counter = new AtomicInteger();
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottled(
                () -> counter.incrementAndGet(),
                10L,
                20L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(20L);
    }

    @Test
    void performThrottledUsesSleepMillisWhenNoWorkDone() {
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottled(
                () -> null,
                15L,
                5L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(15L);
    }

    @Test
    void performThrottledVoidVariantSleepsAfterWork() {
        AtomicBoolean ran = new AtomicBoolean(false);
        long start = System.currentTimeMillis();

        ExecutionUtil.performThrottled(() -> ran.set(true), 5L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(ran).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void performThrottledNoInterruptSleepsUninterruptibly() {
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottledNoInterrupt(() -> 1, 5L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void performThrottledNoInterruptVoidVariantSleepsAfterWork() {
        AtomicBoolean ran = new AtomicBoolean(false);
        long start = System.currentTimeMillis();

        ExecutionUtil.performThrottledNoInterrupt(() -> ran.set(true), 5L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(ran).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void performThrottledNoInterruptUsesThrottleMillisWhenWorkDone() {
        AtomicInteger counter = new AtomicInteger();
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottledNoInterrupt(
                () -> counter.incrementAndGet(),
                5L,
                20L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isEqualTo(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(20L);
    }

    @Test
    void performThrottledNoInterruptUsesSleepMillisWhenNoWorkDone() {
        long start = System.currentTimeMillis();

        Integer value = ExecutionUtil.performThrottledNoInterrupt(() -> null, 5L, 25L);

        long elapsed = System.currentTimeMillis() - start;
        assertThat(value).isNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(5L);
    }
}
