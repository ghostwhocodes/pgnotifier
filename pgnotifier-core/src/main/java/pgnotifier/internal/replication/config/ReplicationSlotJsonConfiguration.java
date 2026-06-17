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

import org.jspecify.annotations.Nullable;
import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Configuration of wal2json-based logical replication slots and streams.
 *
 * @author Nos Doughty
 */
public class ReplicationSlotJsonConfiguration {

    /**
     * Mutable wal2json slot properties bean.
     */
    public static class SlotProperties extends AbstractSlotProperties {

        public boolean isIncludeXids() {
            return includeXids;
        }

        public void setIncludeXids(final boolean includeXids) {
            this.includeXids = includeXids;
        }

        public boolean isIncludeLsn() {
            return includeLsn;
        }

        public void setIncludeLsn(final boolean includeLsn) {
            this.includeLsn = includeLsn;
        }

        public boolean isIncludeTimestamp() {
            return includeTimestamp;
        }

        public void setIncludeTimestamp(final boolean includeTimestamp) {
            this.includeTimestamp = includeTimestamp;
        }

        public boolean isIncludeSchemas() {
            return includeSchemas;
        }

        public void setIncludeSchemas(final boolean includeSchemas) {
            this.includeSchemas = includeSchemas;
        }

        public boolean isIncludeTypes() {
            return includeTypes;
        }

        public void setIncludeTypes(final boolean includeTypes) {
            this.includeTypes = includeTypes;
        }

        public boolean isIncludeTypmod() {
            return includeTypmod;
        }

        public void setIncludeTypmod(final boolean includeTypmod) {
            this.includeTypmod = includeTypmod;
        }

        public boolean isIncludeTypeOids() {
            return includeTypeOids;
        }

        public void setIncludeTypeOids(final boolean includeTypeOids) {
            this.includeTypeOids = includeTypeOids;
        }

        public boolean isIncludeNotNull() {
            return includeNotNull;
        }

        public void setIncludeNotNull(final boolean includeNotNull) {
            this.includeNotNull = includeNotNull;
        }

        public boolean isPrettyPrint() {
            return prettyPrint;
        }

        public void setPrettyPrint(final boolean prettyPrint) {
            this.prettyPrint = prettyPrint;
        }

        public boolean isWriteInChunks() {
            return writeInChunks;
        }

        public void setWriteInChunks(final boolean writeInChunks) {
            this.writeInChunks = writeInChunks;
        }

        public boolean isIncludeUnchangedToast() {
            return includeUnchangedToast;
        }

        public void setIncludeUnchangedToast(final boolean includeUnchangedToast) {
            this.includeUnchangedToast = includeUnchangedToast;
        }

        public int getStatusIntervalSeconds() {
            return statusIntervalSeconds;
        }

        public void setStatusIntervalSeconds(final int statusIntervalSeconds) {
            this.statusIntervalSeconds = statusIntervalSeconds;
        }

        public @Nullable String getIncludeTables() {
            return includeTables;
        }

        public void setIncludeTables(final @Nullable String includeTables) {
            this.includeTables = includeTables;
        }

    }

    /**
     * Builder for {@link ReplicationSlotJsonConfiguration} instances.
     */
    public static class SlotPropertiesBuilder extends AbstractSlotProperties {

        public static ReplicationSlotJsonConfiguration.SlotPropertiesBuilder jsonSlotProperties() {
            return new ReplicationSlotJsonConfiguration.SlotPropertiesBuilder();
        }

        public ReplicationSlotJsonConfiguration toConfig() {
            return new ReplicationSlotJsonConfiguration(this);
        }

        public SlotPropertiesBuilder setIncludeXids(final boolean includeXids) {
            this.includeXids = includeXids;
            return this;
        }

        public SlotPropertiesBuilder setIncludeLsn(final boolean includeLsn) {
            this.includeLsn = includeLsn;
            return this;
        }

        public SlotPropertiesBuilder setIncludeTimestamp(final boolean includeTimestamp) {
            this.includeTimestamp = includeTimestamp;
            return this;
        }

        public SlotPropertiesBuilder setIncludeSchemas(final boolean includeSchemas) {
            this.includeSchemas = includeSchemas;
            return this;
        }

        public SlotPropertiesBuilder setIncludeTypes(final boolean includeTypes) {
            this.includeTypes = includeTypes;
            return this;
        }

        public SlotPropertiesBuilder setIncludeTypmod(final boolean includeTypmod) {
            this.includeTypmod = includeTypmod;
            return this;
        }

        public SlotPropertiesBuilder setIncludeTypeOids(final boolean includeTypeOids) {
            this.includeTypeOids = includeTypeOids;
            return this;
        }

        public SlotPropertiesBuilder setIncludeNotNull(final boolean includeNotNull) {
            this.includeNotNull = includeNotNull;
            return this;
        }

        public SlotPropertiesBuilder setPrettyPrint(final boolean prettyPrint) {
            this.prettyPrint = prettyPrint;
            return this;
        }

        public SlotPropertiesBuilder setWriteInChunks(final boolean writeInChunks) {
            this.writeInChunks = writeInChunks;
            return this;
        }

        public SlotPropertiesBuilder setIncludeUnchangedToast(final boolean includeUnchangedToast) {
            this.includeUnchangedToast = includeUnchangedToast;
            return this;
        }

        public SlotPropertiesBuilder setStatusIntervalSeconds(final int statusIntervalSeconds) {
            this.statusIntervalSeconds = statusIntervalSeconds;
            return this;
        }

        public SlotPropertiesBuilder setIncludeTables(final @Nullable String includeTables) {
            this.includeTables = includeTables;
            return this;
        }

    }

    private static class AbstractSlotProperties {

        boolean includeXids = true;
        boolean includeLsn = true;
        boolean includeTimestamp = true;
        boolean includeSchemas = true;

        boolean includeTypes = true;
        boolean includeTypmod = true;
        boolean includeTypeOids = false;
        boolean includeNotNull = false;
        boolean prettyPrint = false;
        boolean writeInChunks = false;
        boolean includeUnchangedToast = true;
        int statusIntervalSeconds = 10;
        @Nullable String includeTables = null;

    }

    private static final String WAL_2_JSON_PLUGIN = "wal2json";

    private final AbstractSlotProperties properties;

    public ReplicationSlotJsonConfiguration(final AbstractSlotProperties properties) {
        this.properties = properties;
    }

    public Consumer<ChainedLogicalCreateSlotBuilder> slotConfigurer() {

        return (builder) -> builder.withOutputPlugin(WAL_2_JSON_PLUGIN);

    }

    public Consumer<ChainedLogicalStreamBuilder> streamConfigurer() {

        return (builder) -> {

            builder
                    .withStatusInterval(properties.statusIntervalSeconds, TimeUnit.SECONDS)
                    .withSlotOption("include-timestamp", properties.includeTimestamp)
                    .withSlotOption("include-xids", properties.includeXids)
                    .withSlotOption("include-lsn", properties.includeLsn)
                    .withSlotOption("include-schemas", properties.includeSchemas)
                    .withSlotOption("include-types", properties.includeTypes)
                    .withSlotOption("include-typmod", properties.includeTypmod)
                    .withSlotOption("include-type-oids", properties.includeTypeOids)
                    .withSlotOption("include-not-null", properties.includeNotNull)
                    .withSlotOption("write-in-chunks", properties.writeInChunks)
                    .withSlotOption("pretty-print", properties.prettyPrint);

            if (properties.includeTables != null && !properties.includeTables.isEmpty())
                builder.withSlotOption("add-tables", properties.includeTables);

        };

    }

}
