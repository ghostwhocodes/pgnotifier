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

import static java.util.Objects.requireNonNull;
import static pgnotifier.util.ExecutionUtil.execute;

/**
 * Utility for registering JVM shutdown hooks that coordinate with the current thread.
 *
 * @author Nos Doughty
 */
public class ShutdownUtil {

    private ShutdownUtil() {}

    // Package-private for tests
    static Thread buildHookThread(final Thread thread, final Runnable runnable) {
        return new Thread(() -> {
            runnable.run();
            // Avoid blocking JVM shutdown indefinitely; only wait briefly for the target thread.
            execute(() -> {
                if (Thread.currentThread() != thread && thread.isAlive()) {
                    thread.join(5000L);
                }
            });
        });
    }

    /**
     * Register a shutdown hook for the current thread.
     * <p>
     * The hook will execute the given {@link Runnable} and then join the current thread
     * to ensure orderly shutdown.
     *
     * @param runnable logic to execute during JVM shutdown
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * ShutdownUtil.addShutdownHook(() -> notifier.stop());
     * }</pre>
     */
    public static void addShutdownHook(final Runnable runnable) {

        addShutdownHook(Thread.currentThread(), runnable);

    }

    /**
     * Register a shutdown hook bound to the given thread.
     *
     * @param thread   thread to join on shutdown
     * @param runnable logic to execute before joining
     */
    public static void addShutdownHook(final Thread thread, final Runnable runnable) {

        requireNonNull(thread, "The passed in thread cannot be null");
        requireNonNull(runnable, "The passed in runnable cannot be null");

        Runtime.getRuntime().addShutdownHook(buildHookThread(thread, runnable));

    }

}
