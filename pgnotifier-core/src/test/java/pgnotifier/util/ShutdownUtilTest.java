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
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShutdownUtilTest {

    @Test
    void buildHookRunsRunnableAndJoinsTargetThread() throws Exception {
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Thread targetThread = new Thread(() -> {
            try {
                latch.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // ignored for test
            }
        });
        targetThread.start();

        Thread hook = ShutdownUtil.buildHookThread(targetThread, () -> ran.set(true));
        latch.countDown();
        hook.start();

        hook.join(1000L);
        targetThread.join(1000L);

        assertThat(ran).isTrue();
        assertThat(targetThread.isAlive()).isFalse();
    }

    @Test
    void addShutdownHookValidatesArguments() throws Exception {
        Method method = ShutdownUtil.class.getMethod("addShutdownHook", Thread.class, Runnable.class);

        assertThatThrownBy(() -> invoke(method, null, () -> {}))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> invoke(method, Thread.currentThread(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static void invoke(
            final Method method,
            final @Nullable Thread thread,
            final @Nullable Runnable runnable) throws Throwable {
        try {
            method.invoke(null, thread, runnable);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
