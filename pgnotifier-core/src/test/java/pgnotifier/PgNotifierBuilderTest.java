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

package pgnotifier;

import org.junit.jupiter.api.Test;
import pgnotifier.util.LatchedProcess;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgNotifierBuilderTest {

    @Test
    void buildFailsWhenNoSlotsConfigured() {
        PgNotifierBuilder builder = new PgNotifierBuilder()
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one SlotConfig must be provided");
    }

    @Test
    void buildFailsWhenHandlersMissing() {
        PgNotifierBuilder builder = new PgNotifierBuilder()
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slot");

        assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ChangeHandler must be provided");
    }

    @Test
    void buildCreatesRuntimeConfigForEachSlot() {
        List<SlotRuntime.Config> capturedConfigs = new ArrayList<>();
        PgNotifier.ProcessConfig processConfig = new PgNotifier.ProcessConfig(
                2,
                42,
                8,
                50L,
                25L);
        PgNotifier.PluginConfig pluginConfig = new PgNotifier.PluginConfig(15, "public.demo");
        HandlerExecutionConfig handlerExecutionConfig = HandlerExecutionConfig.asyncQueue(
                3,
                20,
                HandlerExecutionConfig.QueueOverflowPolicy.DROP_OLDEST);
        ChangeHandler changeHandler = event -> ProcessingDecision.COMMIT;
        ErrorHandler errorHandler = context -> ErrorHandlingDecision.dropAndContinue(context);
        LsnPersistence persistence = LsnPersistence.noop();
        RestartPolicy restartPolicy = RestartPolicy.never();
        NotifierMetrics metrics = mock(NotifierMetrics.class);

        PgNotifier notifier = new PgNotifierBuilder()
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slotA")
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slotB")
                .processConfig(processConfig)
                .pluginConfig(pluginConfig)
                .handlerExecutionConfig(handlerExecutionConfig)
                .changeHandler(changeHandler)
                .errorHandler(errorHandler)
                .lsnPersistence(persistence)
                .restartPolicy(restartPolicy)
                .metrics(metrics)
                .slotRuntimeFactory(config -> {
                    capturedConfigs.add(config);
                    return runtime(config.slotConfig().slotname());
                })
                .build();

        assertThat(notifier.health())
                .extracting(PgNotifier.SlotHealthSnapshot::slot)
                .containsExactly("slotA", "slotB");
        assertThat(capturedConfigs)
                .hasSize(2)
                .extracting(config -> config.slotConfig().slotname())
                .containsExactly("slotA", "slotB");

        for (SlotRuntime.Config config : capturedConfigs) {
            assertThat(config.processConfig()).isSameAs(processConfig);
            assertThat(config.pluginConfig()).isSameAs(pluginConfig);
            assertThat(config.handlerExecutionConfig()).isSameAs(handlerExecutionConfig);
            assertThat(config.changeHandler()).isSameAs(changeHandler);
            assertThat(config.errorHandler()).isSameAs(errorHandler);
            assertThat(config.lsnPersistence()).isSameAs(persistence);
            assertThat(config.restartPolicy()).isSameAs(restartPolicy);
            assertThat(config.metrics()).isSameAs(metrics);
        }
    }

    @Test
    void defaultRestartPolicyFollowsProcessConfigBackoff() {
        List<SlotRuntime.Config> capturedConfigs = new ArrayList<>();
        PgNotifier.ProcessConfig processConfig = new PgNotifier.ProcessConfig(
                1,
                42,
                10,
                PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
                PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS);

        new PgNotifierBuilder()
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slot")
                .processConfig(processConfig)
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
                .slotRuntimeFactory(config -> {
                    capturedConfigs.add(config);
                    return runtime(config.slotConfig().slotname());
                })
                .build();

        assertThat(capturedConfigs).hasSize(1);
        RestartPolicy policy = capturedConfigs.getFirst().restartPolicy();
        assertThat(policy.shouldRestart(1, new RuntimeException("boom"))).isTrue();
        assertThat(policy.backoffSeconds(1, new RuntimeException("boom"))).isEqualTo(42L);
    }

    @Test
    void errorHandlerOverloadAcceptsErrorHandlingStrategy() {
        PgNotifier notifier = new PgNotifierBuilder()
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slot")
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandlingStrategy(ErrorHandlingStrategy.dropOnTransientStopOnPermanent())
                .slotRuntimeFactory(config -> runtime(config.slotConfig().slotname()))
                .build();

        assertThat(notifier).isNotNull();
    }

    @Test
    void restartShortcutOverridesArePreservedAfterProcessConfigChanges() {
        List<SlotRuntime.Config> capturedConfigs = new ArrayList<>();

        new PgNotifierBuilder()
                .addSlot("user", "pass", "jdbc:postgresql://localhost/postgres", "slot")
                .fixedRestartPolicy(99L)
                .processConfig(new PgNotifier.ProcessConfig(
                        1,
                        5,
                        10,
                        PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
                        PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS))
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
                .slotRuntimeFactory(config -> {
                    capturedConfigs.add(config);
                    return runtime(config.slotConfig().slotname());
                })
                .build();

        assertThat(capturedConfigs).hasSize(1);
        assertThat(capturedConfigs.getFirst().restartPolicy().backoffSeconds(2, new RuntimeException("boom")))
                .isEqualTo(99L);
    }

    @Test
    void builderSupportsAsyncQueueMetricsAndAlternateRestartPolicies() {
        List<SlotRuntime.Config> capturedConfigs = new ArrayList<>();
        NotifierMetrics metrics = mock(NotifierMetrics.class);

        PgNotifier notifier = new PgNotifierBuilder()
                .addSlot(new PgNotifier.SlotConfig("user", "pass", "jdbc:postgresql://localhost/postgres", "slot"))
                .inlineHandlers()
                .asyncHandlerQueue(2, 8)
                .asyncHandlerQueue(2, 8, HandlerExecutionConfig.QueueOverflowPolicy.DROP_NEWEST)
                .pluginConfig(new PgNotifier.PluginConfig(25, "public.demo"))
                .metrics(metrics)
                .exponentialRestartPolicy(1L, 8L, false)
                .neverRestart()
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
                .slotRuntimeFactory(config -> {
                    capturedConfigs.add(config);
                    return runtime(config.slotConfig().slotname());
                })
                .build();

        assertThat(notifier.health()).hasSize(1);
        SlotRuntime.Config config = capturedConfigs.getFirst();
        assertThat(config.handlerExecutionConfig().mode()).isEqualTo(HandlerExecutionConfig.Mode.ASYNC_QUEUE);
        assertThat(config.handlerExecutionConfig().workerThreads()).isEqualTo(2);
        assertThat(config.handlerExecutionConfig().queueCapacity()).isEqualTo(8);
        assertThat(config.handlerExecutionConfig().queueOverflowPolicy())
                .isEqualTo(HandlerExecutionConfig.QueueOverflowPolicy.DROP_NEWEST);
        assertThat(config.pluginConfig()).isEqualTo(new PgNotifier.PluginConfig(25, "public.demo"));
        assertThat(config.metrics()).isSameAs(metrics);
        assertThat(config.restartPolicy().shouldRestart(1, new RuntimeException("boom"))).isFalse();
    }

    private static SlotRuntime runtime(final String slotName) {
        LatchedProcess process = mock(LatchedProcess.class);
        when(process.state()).thenReturn(LatchedProcess.State.STOPPED);
        when(process.isRunning()).thenReturn(false);
        return new SlotRuntime(
                new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/test", slotName),
                new SlotHealth(),
                process);
    }

}
