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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlerExecutionConfigTest {

    @Test
    void inlineFactoryReturnsInlineDefaults() {
        HandlerExecutionConfig config = HandlerExecutionConfig.inline();

        assertThat(config.mode()).isEqualTo(HandlerExecutionConfig.Mode.INLINE);
        assertThat(config.workerThreads()).isEqualTo(1);
        assertThat(config.queueCapacity()).isEqualTo(1000);
        assertThat(config.queueOverflowPolicy()).isEqualTo(HandlerExecutionConfig.QueueOverflowPolicy.BLOCK);
    }

    @Test
    void asyncFactoryValidatesInputs() {
        assertThatThrownBy(() -> HandlerExecutionConfig.asyncQueue(
                0, 1, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> HandlerExecutionConfig.asyncQueue(
                1, 0, HandlerExecutionConfig.QueueOverflowPolicy.BLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
