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

package pgnotifier.internal.replication.config;

import org.junit.jupiter.api.Test;
import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static pgnotifier.internal.replication.config.ReplicationSlotJsonConfiguration.SlotPropertiesBuilder.jsonSlotProperties;

class ReplicationSlotJsonConfigurationTest {

    @Test
    void slotPropertiesBeanSupportsAccessors() {
        ReplicationSlotJsonConfiguration.SlotProperties properties =
                new ReplicationSlotJsonConfiguration.SlotProperties();

        properties.setIncludeXids(false);
        properties.setIncludeLsn(false);
        properties.setIncludeTimestamp(false);
        properties.setIncludeSchemas(false);
        properties.setIncludeTypes(false);
        properties.setIncludeTypmod(false);
        properties.setIncludeTypeOids(true);
        properties.setIncludeNotNull(true);
        properties.setPrettyPrint(true);
        properties.setWriteInChunks(true);
        properties.setIncludeUnchangedToast(false);
        properties.setStatusIntervalSeconds(42);
        properties.setIncludeTables("public.demo");

        assertThat(properties.isIncludeXids()).isFalse();
        assertThat(properties.isIncludeLsn()).isFalse();
        assertThat(properties.isIncludeTimestamp()).isFalse();
        assertThat(properties.isIncludeSchemas()).isFalse();
        assertThat(properties.isIncludeTypes()).isFalse();
        assertThat(properties.isIncludeTypmod()).isFalse();
        assertThat(properties.isIncludeTypeOids()).isTrue();
        assertThat(properties.isIncludeNotNull()).isTrue();
        assertThat(properties.isPrettyPrint()).isTrue();
        assertThat(properties.isWriteInChunks()).isTrue();
        assertThat(properties.isIncludeUnchangedToast()).isFalse();
        assertThat(properties.getStatusIntervalSeconds()).isEqualTo(42);
        assertThat(properties.getIncludeTables()).isEqualTo("public.demo");
    }

    @Test
    void slotConfigurerUsesWal2JsonPlugin() {
        ChainedLogicalCreateSlotBuilder builder =
                mock(ChainedLogicalCreateSlotBuilder.class, withSettings().withoutAnnotations());
        when(builder.withOutputPlugin("wal2json")).thenReturn(builder);

        jsonSlotProperties().toConfig().slotConfigurer().accept(builder);

        verify(builder).withOutputPlugin("wal2json");
    }

    @Test
    void streamConfigurerAppliesConfiguredWal2JsonOptions() {
        ChainedLogicalStreamBuilder builder =
                mock(ChainedLogicalStreamBuilder.class, withSettings().withoutAnnotations());
        when(builder.withStatusInterval(anyInt(), any(TimeUnit.class))).thenReturn(builder);
        when(builder.withSlotOption(anyString(), anyBoolean())).thenReturn(builder);
        when(builder.withSlotOption("add-tables", "public.demo")).thenReturn(builder);

        ReplicationSlotJsonConfiguration configuration = jsonSlotProperties()
                .setIncludeXids(false)
                .setIncludeLsn(false)
                .setIncludeTimestamp(false)
                .setIncludeSchemas(false)
                .setIncludeTypes(false)
                .setIncludeTypmod(false)
                .setIncludeTypeOids(true)
                .setIncludeNotNull(true)
                .setPrettyPrint(true)
                .setWriteInChunks(true)
                .setIncludeUnchangedToast(false)
                .setStatusIntervalSeconds(42)
                .setIncludeTables("public.demo")
                .toConfig();

        configuration.streamConfigurer().accept(builder);

        verify(builder).withStatusInterval(42, TimeUnit.SECONDS);
        verify(builder).withSlotOption("include-timestamp", false);
        verify(builder).withSlotOption("include-xids", false);
        verify(builder).withSlotOption("include-lsn", false);
        verify(builder).withSlotOption("include-schemas", false);
        verify(builder).withSlotOption("include-types", false);
        verify(builder).withSlotOption("include-typmod", false);
        verify(builder).withSlotOption("include-type-oids", true);
        verify(builder).withSlotOption("include-not-null", true);
        verify(builder).withSlotOption("write-in-chunks", true);
        verify(builder).withSlotOption("pretty-print", true);
        verify(builder).withSlotOption("add-tables", "public.demo");
    }

    @Test
    void streamConfigurerSkipsAddTablesWhenValueIsBlank() {
        ChainedLogicalStreamBuilder builder =
                mock(ChainedLogicalStreamBuilder.class, withSettings().withoutAnnotations());
        when(builder.withStatusInterval(anyInt(), any(TimeUnit.class))).thenReturn(builder);
        when(builder.withSlotOption(anyString(), anyBoolean())).thenReturn(builder);

        jsonSlotProperties()
                .setIncludeTables("")
                .toConfig()
                .streamConfigurer()
                .accept(builder);

        verify(builder, never()).withSlotOption("add-tables", "");
    }
}
