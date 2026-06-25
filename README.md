# pgnotifier

<p align="center">
  <img src="./.github/assets/banner.webp" alt="pgnotifier banner">
</p>

<p align="center">
  <a href="https://ghost-who-codes.blog/open-source/pgnotifier/">Project page on Ghost Who Codes</a>
</p>

Small Java 25 library and tools for consuming PostgreSQL logical replication slots, aimed at being:

- Lightweight and embeddable (no heavy framework).
- Friendly for debugging and experimentation.
- Flexible enough to plug custom handlers and configs into.

The project has:

- A core library (`pgnotifier-core`) with:
  - `PgNotifier` / `PgNotifierBuilder` façade.
  - Logical replication integration via the `wal2json` output plugin.
- A CLI module (`pgnotifier-cli`) with a picocli-based `pgnotifier` command for printing/inspecting changes.
- A Micrometer adapter module (`pgnotifier-micrometer`) for wiring `NotifierMetrics` into `MeterRegistry`.
- A top-style dashboard (`pgnotifier-top`) with a Lanterna TUI (`pgnotifier-top`).


## Modules

- `pgnotifier-core`
  - Supported library API:
    - `PgNotifier`, `PgNotifierBuilder`
    - `ChangeEvent`, `ChangeEventDecoder`, `ChangeHandler`, `ProcessingDecision`
    - `ErrorContext`, `ErrorHandler`, `ErrorHandlingDecision`, `ErrorHandlingStrategy`, `ErrorOrigin`, `ErrorResolution`, `ErrorType`
    - `HandlerExecutionConfig`, `LsnPersistence`, `NotifierMetrics`, `ProcessState`, `RestartPolicy`
    - `PgNotifier.SlotHealthSnapshot`, `PgNotifier.WorkerFailureSnapshot`, `PgNotifier.ProcessFailureSnapshot`
  - Config abstractions:
    - `Configuration`, `ConfigurationSource`
    - `PropertiesConfigurationSource`, `MapConfigurationSource`
    - Command configuration: `pgnotifier.config.CommandConfiguration`
    - Properties adapter: `pgnotifier.config.PgNotifierProperties`
  - Replication workers, slots, streams, and wal2json builder helpers live under `pgnotifier.internal.*` and are not supported public API.

- `pgnotifier-cli`
  - Scriptable command-line interface.
  - Main class: `pgnotifier.cli.PgNotifierCli`
  - Executable jar: `pgnotifier-cli/target/pgnotifier-cli-1.0.0-all.jar`

- `pgnotifier-micrometer`
  - Micrometer `NotifierMetrics` adapter.
  - Adapter class: `pgnotifier.metrics.micrometer.MicrometerNotifierMetrics`

- `pgnotifier-top`
  - Terminal UI dashboard using Lanterna.
  - “Top”-style view showing a live event counter fed by the replication stream.
  - Displays approximate events/sec, idle time since last event, per-table and per-operation stats, and basic table row counts via `pg_stat_user_tables`.
  - Executable jar: `pgnotifier-top/target/pgnotifier-top-1.0.0-all.jar`


## Building

From the project root:

```bash
# Run the full local gate set: compile, tests, coverage, Error Prone, SpotBugs
mvn verify

# Build everything without the verify-only checks
mvn package

# Or build individual modules
mvn package -pl pgnotifier-core
mvn package -pl pgnotifier-cli
mvn package -pl pgnotifier-micrometer
mvn package -pl pgnotifier-top
```

## Build gates

- `mvn verify` is the authoritative local and CI entrypoint.
- GitHub Actions runs the same gate in the `CI` workflow using Temurin Java 25.
- Java compilation, test execution, and release Javadocs run on Java 25 LTS without preview flags.
- The compiler runs with `-Xlint:all` and `-Werror`, so javac warnings fail the build.
- Error Prone and NullAway run during compilation and fail the build on findings.
- SpotBugs runs during `verify` and fails the build on warnings.
- JaCoCo enforces `90%` line coverage at the Maven module (`BUNDLE`) level.
- The coverage gate excludes:
  - `pgnotifier.cli/**`
  - `pgnotifier.metrics.micrometer/**`
  - `pgnotifier.top.TopCli*`
- Those exclusions keep the gate focused on the core library and reusable logic rather than app entrypoints, adapter shell code, and the Lanterna wrapper.
- Docker-backed integration tests are opt-in through the `Integration Tests` workflow or `./codex/mvnw.sh --batch-mode -Pintegration-tests verify`.
- Integration tests use pinned container images: `postgres:16-alpine` and `royratcliffe/postgres-wal2json:13`.

## GraalVM

- The default build remains vendor-neutral across Java 25 JDKs.
- A dedicated `graalvm` profile is available to validate the build under GraalVM Java 25.
- The profile intentionally fails fast if Maven is not running on a GraalVM JDK.

With the installed SDKMAN candidate on this machine:

```bash
JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.2-graalce" \
PATH="$JAVA_HOME/bin:$PATH" \
mvn -Pgraalvm verify
```

Or, if you prefer SDKMAN shell switching:

```bash
sdk use java 25.0.2-graalce
mvn -Pgraalvm verify
```

This project currently verifies successfully on both:

- Temurin Java `25.0.3`
- GraalVM CE Java `25.0.2`

## Releases

- Maven coordinates are `io.github.llaith:<module>:<version>`.
- The first intended release tag is `v1.0.0`.
- Artifacts publish to GitHub Packages at `https://maven.pkg.github.com/llaith/pgnotifier`.
- Release builds activate the `release` Maven profile, which attaches source and Javadoc jars. The profile also configures GPG signing; GitHub Packages deployment can use `-Dgpg.skip` until signing secrets are added.
- The `Publish Package` workflow deploys when a GitHub Release is published. It uses the `github` Maven server id and the workflow `GITHUB_TOKEN`.
- The release plugin runs `clean verify` before tagging and uses `v<version>` tags.

Consumers need GitHub Packages credentials in Maven `settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_GITHUB_TOKEN</password>
  </server>
</servers>
```

Add the GitHub Packages repository and dependencies:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/llaith/pgnotifier</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.llaith</groupId>
    <artifactId>pgnotifier-core</artifactId>
    <version>1.0.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.llaith</groupId>
    <artifactId>pgnotifier-micrometer</artifactId>
    <version>1.0.0</version>
  </dependency>
</dependencies>
```

## License

This project is licensed under the Apache License, Version 2.0. See [LICENSE](/home/nos/Projects/SiwaPlatform/pgnotifier/LICENSE) and [NOTICE](/home/nos/Projects/SiwaPlatform/pgnotifier/NOTICE).

Executable jars are attached during `package`:

- `pgnotifier-cli/target/pgnotifier-cli-1.0.0-all.jar`
  - Main class: `pgnotifier.cli.PgNotifierCli`
- `pgnotifier-top/target/pgnotifier-top-1.0.0-all.jar`
  - Main class: `pgnotifier.top.TopCli`

The core artifact is library-only. The unclassified `*.jar` artifacts remain thin jars for embedding.


## Configuration

Configuration is layered via `ConfigurationSource` instances. By default the CLIs:

- Load a properties file (`pgnotifier.properties` by default).
- Overlay CLI flags as a higher-priority configuration source.

Expected keys in the properties file:

- Slot / connection:
  - `database` – JDBC URL, e.g. `jdbc:postgresql://localhost:5432/postgres`
  - `username` – DB user
  - `password` – DB password
  - `slotname` – replication slot name

- Process configuration (`PgNotifier.ProcessConfig`):
  - `poolsize` – worker thread pool size (default: `1`)
  - `errorbackoffseconds` – seconds to wait before restarting worker after failure (default: `10`)
  - `shutdowntimeoutseconds` – graceful shutdown timeout in seconds (default: `10`)
  - `sleepmillis` – sleep when no work is available (default: `1000`)
  - `throttlemillis` – sleep when work was done (default: `100`)

- Plugin configuration (`PgNotifier.PluginConfig`):
  - `statusintervalseconds` – replication stream status interval seconds (default: `10`)
  - `includetables` – wal2json `add-tables` option value (comma-separated table list)

- Handler execution configuration (`HandlerExecutionConfig`):
  - `handler.mode` – how `ChangeHandler` is invoked:
    - `INLINE` (default) – handler runs on the replication thread.
    - `ASYNC_QUEUE` – replication loop only reads/decodes/enqueues; one or more worker threads drain a bounded queue and invoke handlers.
  - `handler.worker.threads` – number of handler worker threads when using `ASYNC_QUEUE` (default: `1`).
  - `handler.queue.capacity` – capacity of the internal handler queue when using `ASYNC_QUEUE` (default: `1000`).
  - `handler.queue.overflow.policy` – behaviour when the queue is full:
    - `BLOCK` – block the replication thread until space is available.
    - `DROP_OLDEST` – drop the oldest queued event and enqueue the new one (LSN advances, event is skipped).
    - `DROP_NEWEST` – drop the new event (LSN still advances once earlier events complete).
    - `FATAL` – treat queue exhaustion as a fatal error and stop the worker; the restart policy decides what happens next.

Corresponding CLI flags such as `--database`, `--poolsize`, and `--handler-mode` override these values at runtime.

## LSN persistence / resume

- Implement `LsnPersistence` and register it via `PgNotifierBuilder#lsnPersistence(...)` to persist the last processed LSN.
- `persist(slotConfig, lsn)` is invoked after successful commits or drops.
- `startingLsn(slotConfig)` can provide the exact LSN to pass to PostgreSQL when opening replication streams; pgnotifier does not increment or otherwise adjust this value.


## Semantics & invariants

- **LSN advancement**
  - `ProcessingDecision.COMMIT` and `ProcessingDecision.DROP` advance the replication LSN (via `markProcessed(...)`) and persist it through `LsnPersistence`.
  - `ProcessingDecision.RETRY` does **not** advance the LSN; the same event may be delivered again after a backoff or restart.
  - Error flows with `ErrorResolution.DROP_AND_CONTINUE` also advance the LSN after any earlier ordered async work is settled; `ErrorResolution.RETRY_WITH_BACKOFF` does not.
  - In `ASYNC_QUEUE` mode, acknowledgements use the completed event's own LSN, not the latest LSN seen by the reader thread. Replication-failure stream-head drops wait for earlier queued events to settle before advancing.
  - On worker failure and restart, events after the last persisted LSN may be replayed if they were read but not persisted before the failure; handlers must be idempotent.

- **Error handling**
  - Every failure is wrapped in an `ErrorContext` with an `ErrorOrigin` (replication, change handler, error handler) and an `ErrorType` (transient/permanent).
  - `ErrorHandlingDecision.DROP_AND_CONTINUE` skips the offending event but keeps the worker running.
  - `ErrorHandlingDecision.RETRY_WITH_BACKOFF` retries the same event after a backoff without advancing the LSN.
  - `ErrorHandlingDecision.STOP_PROCESS` is a failed worker run; the configured `RestartPolicy` decides whether and when to restart.

- **Lifecycle**
  - `ProcessingDecision.STOP` is a clean intentional stop. It does not increment the failure streak and does not invoke restart policy.
  - A normal return from the worker is terminal for that run. The lifecycle wrapper does not immediately call the worker again after a clean return.
  - Restart policy applies only to exceptions thrown from the worker loop.
  - An external `PgNotifier.stop()` request transitions workers into a stopping state and, in async mode, stops reading new WAL while already queued work drains.

- **Handler execution modes**
  - `INLINE` mode invokes `ChangeHandler` on the replication thread; events are processed strictly in order.
  - `ASYNC_QUEUE` mode decouples reading from handling using a bounded queue:
    - Events are committed in sequence order only after their handler completes successfully or the event is explicitly dropped.
    - Queue overflow policies:
      - `BLOCK` back-pressures the replication loop until space is available.
      - `DROP_OLDEST` / `DROP_NEWEST` permanently skip events by marking them as processed (LSN advances even though the payload was not handled).
      - `FATAL` treats a full queue as a fatal error and stops the worker; the restart policy then applies.


## Health and observability

`PgNotifier` exposes lightweight health and metrics hooks that you can map into HTTP health endpoints and metric systems.

### Slot health snapshots

Each notifier tracks a per-slot health view that can be queried at runtime:

- `PgNotifier#health()` returns a `List<PgNotifier.SlotHealthSnapshot>` with:
  - `slot` – the logical replication slot name for this worker.
  - `state` – the current `ProcessState` (`STARTING`, `RUNNING`, `BACKING_OFF`, `STOPPING`, `STOPPED`, `FAILED`).
  - `running` – lifecycle boolean derived from `state`; true while the process is active (`STARTING`, `RUNNING`, `BACKING_OFF`, `STOPPING`).
  - `failureCount` – current failure streak for the worker.
  - `lastSuccessfulEventEpochMillis` – timestamp of the last successfully processed event (epoch millis).
  - `lastPersistedLsn` – last LSN persisted after successful processing.
  - `handlerQueueDepth` – current async handler queue depth, or `0` for inline handlers.
  - `handlerQueueCapacity` – configured async handler queue capacity, or `0` for inline handlers.
  - `observedReplicationLagBytes` – worker-observed lag as `max(0, lastReceivedLsn - lastPersistedLsn)`.
  - `lastWorkerFailure` – last failure seen inside the replication worker, including:
    - `epochMillis`
    - `recovered`
    - `origin` (`REPLICATION`, `CHANGE_HANDLER`, `ERROR_HANDLER`)
    - `errorType` (`TRANSIENT`, `PERMANENT`)
    - `className`
    - `message`
  - `lastProcessFailure` – last failure seen by the outer lifecycle wrapper, including:
    - `epochMillis`
    - `recovered`
    - `className`
    - `message`

Health snapshots intentionally exclude the full `SlotConfig`, because that config carries credentials and health payloads often end up in logs or HTTP endpoints.

The important semantic split is:

- `state` and `failureCount` describe current health.
- `lastWorkerFailure` and `lastProcessFailure` are retained diagnostics.
- `recovered=true` means the failure is historical rather than still active.

A simple usage pattern:

```java
PgNotifier notifier = new PgNotifierBuilder()
        .addSlot(slotConfig)
        .changeHandler(changeHandler)
        .errorHandler(errorHandler)
        .build()
        .start();

List<PgNotifier.SlotHealthSnapshot> health = notifier.health();
for (PgNotifier.SlotHealthSnapshot s : health) {
    System.out.println("slot=" + s.slot()
            + " state=" + s.state()
            + " running=" + s.running()
            + " failures=" + s.failureCount()
            + " lastEventEpochMillis=" + s.lastSuccessfulEventEpochMillis()
            + " lastPersistedLsn=" + s.lastPersistedLsn()
            + " observedLagBytes=" + s.observedReplicationLagBytes());
}
```

`SlotHealthSnapshot` exposes process state through `state()`, `running()`, and `failureCount()`.
When the outer process wrapper has seen a failure, `lastProcessFailure()` carries its timestamp,
recovered flag, class name, and message.

### Metrics via `NotifierMetrics`

Metrics emission is opt-in and decoupled from specific libraries through the `NotifierMetrics` interface:

- Counters:
  - `onEventProcessed(slotName)`
  - `onHandlerFailure(slotName, cause)`
  - `onReplicationFailure(slotName, cause)`
  - `onProcessRestart(slotName, failureCount)`
- Gauges / time-based hooks:
  - `onLastEventTimeUpdated(slotName, epochMilli)`
  - `onFailureStreakUpdated(slotName, failureCount)`
  - `onHandlerQueueDepthUpdated(slotName, depth, capacity)`
  - `onHandlerQueueDrainLatency(slotName, latencyMillis)`
  - `onLastPersistedLsnUpdated(slotName, lsn)`
  - `onReplicationLagBytesUpdated(slotName, lagBytes)`

The replication lag hook is worker-observed byte lag: `max(0, lastReceivedLsn - lastPersistedLsn)`. It is not a server-wide WAL lag query.

By default `PgNotifier` uses `NotifierMetrics.noop()` and incurs essentially no overhead. To plug in a real metrics backend, implement `NotifierMetrics` and wire it via the builder:

```java
NotifierMetrics metrics = new MyNotifierMetrics(underlyingRegistry);

PgNotifier notifier = new PgNotifierBuilder()
        .addSlot(slotConfig)
        .processConfig(processConfig)
        .pluginConfig(pluginConfig)
        .changeHandler(changeHandler)
        .errorHandler(errorHandler)
        .metrics(metrics)
        .build()
        .start();
```

#### Micrometer adapter

The `pgnotifier-micrometer` module includes a Micrometer adapter:

- `pgnotifier.metrics.micrometer.MicrometerNotifierMetrics`
  - Uses `MeterRegistry` to expose counters and gauges with a `slot={slotname}` tag:
    - `pgnotifier_events_processed_total`
    - `pgnotifier_handler_failures_total`
    - `pgnotifier_replication_failures_total`
    - `pgnotifier_restarts_total`
    - `pgnotifier_last_event_epoch_millis`
    - `pgnotifier_failure_streak`
    - `pgnotifier_handler_queue_depth`
    - `pgnotifier_handler_queue_capacity`
    - `pgnotifier_handler_queue_drain_latency_millis`
    - `pgnotifier_last_persisted_lsn`
    - `pgnotifier_replication_lag_bytes`

Example Spring-style wiring:

```java
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.PgNotifierBuilder;
import pgnotifier.metrics.micrometer.MicrometerNotifierMetrics;

@Configuration
class PgNotifierConfig {

    @Bean
    NotifierMetrics notifierMetrics(MeterRegistry registry) {
        return new MicrometerNotifierMetrics(registry);
    }

    @Bean(destroyMethod = "stop")
    PgNotifier pgNotifier(NotifierMetrics notifierMetrics) {
        return new PgNotifierBuilder()
                .addSlot(new PgNotifier.SlotConfig("user", "password", "jdbc:postgresql://localhost/postgres", "demo_slot"))
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandler(context -> ErrorHandlingDecision.dropAndContinue(context))
                .metrics(notifierMetrics)
                .build()
                .start();
    }
}
```

This keeps the core library free of Micrometer dependencies while still providing a ready-made adapter for applications that use it.

### Spring Boot / Actuator health endpoint sketch

`PgNotifier.health()` can be mapped directly into a Spring Boot actuator health indicator.

Example `HealthIndicator`:

```java
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import pgnotifier.PgNotifier;

@Component
class PgNotifierHealthIndicator implements HealthIndicator {

    private final PgNotifier pgNotifier;

    PgNotifierHealthIndicator(PgNotifier pgNotifier) {
        this.pgNotifier = pgNotifier;
    }

    @Override
    public Health health() {
        var snapshots = pgNotifier.health();
        boolean anyDown = snapshots.stream().anyMatch(s -> !s.running());
        Health.Builder builder = anyDown ? Health.down() : Health.up();

        for (PgNotifier.SlotHealthSnapshot s : snapshots) {
            String slot = s.slot();
            builder.withDetail(slot + ".running", s.running());
            builder.withDetail(slot + ".state", s.state().name());
            builder.withDetail(slot + ".failureCount", s.failureCount());
            builder.withDetail(slot + ".lastEventEpochMillis", s.lastSuccessfulEventEpochMillis());
            builder.withDetail(slot + ".lastWorkerFailure", s.lastWorkerFailure());
            builder.withDetail(slot + ".lastProcessFailure", s.lastProcessFailure());
        }

        return builder.build();
    }
}
```

With Spring Boot Actuator enabled (e.g. `management.endpoints.web.exposure.include=health`), you can query:

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

or, if you prefer a dedicated endpoint without Actuator, expose a simple controller:

```java
@RestController
class PgNotifierHealthController {

    private final PgNotifier pgNotifier;

    PgNotifierHealthController(PgNotifier pgNotifier) {
        this.pgNotifier = pgNotifier;
    }

    @GetMapping("/pgnotifier/health")
    List<PgNotifier.SlotHealthSnapshot> health() {
        return pgNotifier.health();
    }
}
```

and query it with:

```bash
curl -s http://localhost:8080/pgnotifier/health | jq .
```


## Production usage

This section sketches how to wire `PgNotifier` into a long‑running microservice with explicit reliability knobs, metrics, and health checks.

### Reliability configuration via `PgNotifierBuilder`

Programmatic configuration exposes the main reliability controls directly on the builder:

- Restart policy:
  - `fixedRestartPolicy(backoffSeconds)` – always restart after a fixed delay (default when no policy is set; delay comes from `ProcessConfig#errorBackoffSeconds`).
  - `exponentialRestartPolicy(initialBackoffSeconds, maxBackoffSeconds, jitter)` – exponential backoff capped at `maxBackoffSeconds`, with optional full jitter.
  - `neverRestart()` – never restart; let your orchestration layer (systemd, Kubernetes, etc.) handle failures.
- Handler execution / queueing:
  - `inlineHandlers()` – run handlers on the replication thread (simple, but handlers must be fast).
  - `asyncHandlerQueue(workerThreads, queueCapacity)` – queue‑based execution with blocking behaviour when full.
  - `asyncHandlerQueue(workerThreads, queueCapacity, overflowPolicy)` – same, with an explicit `QueueOverflowPolicy` (`BLOCK`, `DROP_OLDEST`, `DROP_NEWEST`, `FATAL`).
- Error handling strategy:
  - `errorHandlingStrategy(ErrorHandlingStrategy.dropOnTransientStopOnPermanent())` – drop transient failures (including most decode/replication glitches) and stop on permanent ones.
  - `errorHandlingStrategy(ErrorHandlingStrategy.dropOnAnyError())` – always drop and continue (development / low‑risk).
  - `errorHandlingStrategy(ErrorHandlingStrategy.stopOnAnyError())` – always stop and let the restart policy decide.
  - For full control, you can still pass a custom `ErrorHandler` via `errorHandler(...)`.

Combined with `LsnPersistence` and `NotifierMetrics`, these knobs make it straightforward to choose between “always restart and keep going” and “fail fast and let orchestration react”.

### Example: Spring Boot microservice wiring

The following sketch combines a robust restart policy, async handler queue, Micrometer metrics, and a managed lifecycle:

```java
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pgnotifier.ErrorHandlingStrategy;
import pgnotifier.HandlerExecutionConfig;
import pgnotifier.NotifierMetrics;
import pgnotifier.PgNotifier;
import pgnotifier.PgNotifierBuilder;
import pgnotifier.ProcessingDecision;
import pgnotifier.metrics.micrometer.MicrometerNotifierMetrics;

@Configuration
class PgNotifierConfig {

    @Bean
    NotifierMetrics notifierMetrics(MeterRegistry registry) {
        return new MicrometerNotifierMetrics(registry);
    }

    @Bean(destroyMethod = "stop")
    PgNotifier pgNotifier(NotifierMetrics notifierMetrics) {
        return new PgNotifierBuilder()
                .addSlot(new PgNotifier.SlotConfig(
                        "user",
                        "password",
                        "jdbc:postgresql://localhost/postgres",
                        "demo_slot"))
                .processConfig(new PgNotifier.ProcessConfig(
                        1,   // pool size
                        10,  // errorBackoffSeconds (also used by default restart policy)
                        10,  // shutdownTimeoutSeconds
                        PgNotifier.ProcessConfig.DEFAULT_SLEEP_MILLIS,
                        PgNotifier.ProcessConfig.DEFAULT_THROTTLE_MILLIS))
                .exponentialRestartPolicy(5L, 60L, true) // robust restart policy
                .asyncHandlerQueue(
                        4,                    // handler worker threads
                        10_000,               // queue size (bounded)
                        HandlerExecutionConfig.QueueOverflowPolicy.DROP_OLDEST)
                .changeHandler(event -> ProcessingDecision.COMMIT)
                .errorHandlingStrategy(ErrorHandlingStrategy.dropOnTransientStopOnPermanent())
                .metrics(notifierMetrics)
                .build()
                .start();
    }
}
```

For Micrometer wiring details, see the “Micrometer adapter” section above. For HTTP health endpoints, either:

- Use the `PgNotifierHealthIndicator` sketch under “Spring Boot / Actuator health endpoint sketch”, or
- Expose `PgNotifier.health()` directly via a controller as shown there.


## CLI: `pgnotifier` (`pgnotifier-cli` module)

The CLI is a simple, scriptable tool for printing and inspecting logical replication changes. It:

- Connects to a logical replication slot (`wal2json` by default).
- Produces structured `ChangeEvent` objects (payload, LSN, schema, table, operation).
- Pretty-prints JSON payloads when possible.
- Can filter by table and collect stats.

### Basic usage

Assuming you have a running PostgreSQL with a logical slot configured (and `wal2json` installed for JSON mode):

```bash
java -jar pgnotifier-cli/target/pgnotifier-cli-1.0.0-all.jar \
  --config pgnotifier.properties
```

This will:

- Connect using `database`, `username`, `password`, and `slotname` from the properties.
- Start consuming change events and printing JSON payloads to stdout.

### Useful flags

- Connection / slot overrides:

  ```bash
  --database jdbc:postgresql://localhost:5432/postgres
  --username postgres
  --password secret
  --slotname notifyslot
  ```

- Process tuning:

  ```bash
  --poolsize 1
  --error-backoff-seconds 10
  --shutdown-timeout-seconds 10
  --sleep-millis 1000
  --throttle-millis 100
  --handler-mode ASYNC_QUEUE
  --handler-worker-threads 4
  --handler-queue-capacity 1000
  --handler-queue-overflow-policy DROP_OLDEST
  ```

- Output control:

  ```bash
  --print-table                 # prefix output with table name when available
  --filter-table some_table     # only show events for a specific table
  ```

- Stats / summary:

  ```bash
  --summary                     # print summary stats periodically and at shutdown
  --summary-only                # only print summary stats (no per-event output)
  --summary-interval-events 100 # show summary every 100 events
  ```

- Exit conditions:

  ```bash
  --max-events 10               # exit after 10 events
  --once                        # exit after a single event (equivalent to --max-events=1)
  ```

- Dry-run (validate config + DB connectivity only):

  ```bash
  --dry-run                     # connect to the DB and exit with 0/1
  ```


## CLI: `pgnotifier-top` (Lanterna dashboard)

The `pgnotifier-top` module is a small Lanterna-based TUI that shows a live counter of replication events.

First build the module:

```bash
mvn package -pl pgnotifier-top
```

Run it:

```bash
java -jar pgnotifier-top/target/pgnotifier-top-1.0.0-all.jar \
  --config pgnotifier.properties
```

What you’ll see:

- A full-screen terminal UI with:
  - A header: `pgnotifier-top`
  - A hint: `Press 'q' to quit`
  - A live `Total events: N` counter.
  - An approximate `Events/sec` rate and “idle seconds” since the last event.
  - Simple per-table and per-operation event breakdowns.
  - Periodic table row counts pulled from `pg_stat_user_tables` via a separate JDBC connection.

Under the hood:

- It uses the same properties and override assembly as the CLI, via `CommandConfiguration` and `PgNotifierProperties`.
- Starts a `PgNotifier` instance, whose `ChangeHandler` increments a shared `Stats` object.
- Opens a separate JDBC connection (using the same `SlotConfig`) to query `pg_stat_*` views for basic table metrics.
- A dedicated UI loop (Lanterna `Screen`) redraws the dashboard roughly 4 times per second.


## Example: properties file

Here’s a minimal `pgnotifier.properties` to get started with `wal2json`:

```properties
# core connection
database=jdbc:postgresql://localhost:5432/postgres
username=postgres
password=password
slotname=notifyslot

# process defaults
poolsize=1
errorbackoffseconds=10
shutdowntimeoutseconds=10
sleepmillis=1000
throttlemillis=100

# wal2json plugin options
statusintervalseconds=10
includetables=

# handler execution defaults
handler.mode=INLINE
handler.worker.threads=1
handler.queue.capacity=1000
handler.queue.overflow.policy=BLOCK
```

You can then run:

```bash
java -jar pgnotifier-cli/target/pgnotifier-cli-1.0.0-all.jar \
  --config pgnotifier.properties --summary --summary-interval-events 100

java -jar pgnotifier-top/target/pgnotifier-top-1.0.0-all.jar \
  --config pgnotifier.properties
```
