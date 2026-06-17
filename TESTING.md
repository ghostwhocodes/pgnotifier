# Testing & Build Gates

This document describes the current test strategy for `pgnotifier` and the gates enforced by the Maven build.

## Toolchain

- Runtime / build JDK: Java 25 LTS.
- Test framework: JUnit 5 (Jupiter).
- Mocking: Mockito.
- Integration testing: Testcontainers.
- Static analysis: Error Prone, NullAway, SpotBugs.
- Coverage: JaCoCo with a bundle-level `90%` line coverage gate.

`./codex/mvnw.sh --batch-mode verify` is the authoritative local and CI command.

## Build gates

`mvn verify` runs:

- Java compilation with `-Xlint:all` and `-Werror`
- Error Prone and NullAway during compilation
- unit tests through Surefire
- integration-test class compilation
- JaCoCo report plus coverage enforcement
- SpotBugs verification

The build runs on Java 25 without preview flags.

Docker-backed integration tests are opt-in and run through Failsafe:

```bash
./codex/mvnw.sh --batch-mode -Pintegration-tests verify
```

Default `mvn verify` compiles `*IT` classes but does not execute them.

GitHub Actions uses the same wrapper commands:

- `CI` runs on push and pull request with Temurin Java 25 and executes `./codex/mvnw.sh --batch-mode verify`.
- `Integration Tests` runs on manual dispatch and a weekly schedule with Temurin Java 25 and executes `./codex/mvnw.sh --batch-mode -Pintegration-tests verify`.
- `Publish Package` runs when a GitHub Release is published and deploys the release profile to GitHub Packages.

## Coverage scope

The JaCoCo gate includes production code under `pgnotifier/**` and excludes:

- `pgnotifier.cli/**`
- `pgnotifier.metrics.micrometer/**`
- `pgnotifier.top.TopCli*`

That keeps the enforced threshold on reusable library logic and the TUI model rather than app entrypoints, adapter shell code, and the thin Lanterna shell wrapper.

## Test layers

Fast unit tests cover:

- notifier construction and orchestration
- slot runtime assembly, lifecycle delegation, and health snapshot composition
- restart policy logic
- process lifecycle states and clean-stop vs failure semantics
- health snapshot diagnostics for worker failures vs outer process failures, including recovery markers
- replication stream wrappers
- worker processing decisions, retries, queue overflow policies, and error handling
- configuration parsing and clamping
- command configuration override assembly and CLI/TUI flag parsing
- metrics and health helper types

Opt-in Testcontainers-backed integration tests cover:

- JDBC access against `postgres:16-alpine`
- logical replication consumption against `royratcliffe/postgres-wal2json:13`

The logical replication IT exercises the internal `ReplicationSlot` / `ReplicationStream` / `BatchReplicationWorker` path, verifies decoded wal2json content, exact persisted LSN advancement, and passes `LsnPersistence.startingLsn(...)` through as the exact PostgreSQL replication start position.

## Logging in tests

`pgnotifier-core` uses [`logback-test.xml`](/home/nos/Projects/SiwaPlatform/pgnotifier/pgnotifier-core/src/test/resources/logback-test.xml) to keep expected error-path logging and Testcontainers chatter under control. The goal is quieter output, not weaker assertions.

## Practical guidance

- Prefer real classes unless the boundary is JDBC, PostgreSQL replication APIs, or thread scheduling.
- Keep concurrency tests deterministic with latches, bounded queues, and short timeouts.
- When changing async replication semantics, add a test that asserts the exact LSN being acknowledged, not only that an acknowledgement happened.
- When changing restart or health behavior, update both the unit tests and the README semantics section.
- When changing executable packaging, smoke-test the attached `*-all.jar` artifacts with `java -jar ... --help`.
