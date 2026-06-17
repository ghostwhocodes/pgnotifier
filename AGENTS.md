# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Maven project:
- `pgnotifier-core`: core library.
- `pgnotifier-cli`: picocli-based CLI (`PgNotifierCli`).
- `pgnotifier-micrometer`: Micrometer `NotifierMetrics` adapter.
- `pgnotifier-top`: Lanterna TUI dashboard (`TopCli`).
Tests live beside each module in `src/test/java`.

## Build, Test, and Development Commands

- JDK: **Java 25 (current LTS)** is required for builds and tests.
  - On machines using SDKMAN, switch to a 25.x JDK with:  
    `sdk use java 25.0.3-tem`
- `mvn package`: build all modules.
- `mvn package -pl pgnotifier-core`: build the core library.
- `mvn package -pl pgnotifier-cli`: build the CLI.
- `mvn package -pl pgnotifier-micrometer`: build the Micrometer adapter.
- `mvn package -pl pgnotifier-top`: build the Lanterna dashboard.
- `mvn test`: run unit tests across modules.

## Coding Style & Naming Conventions

Java 25, 4-space indentation, and braces on the same line as declarations. Follow existing packages under `pgnotifier.*`. Use `UpperCamelCase` for classes, `lowerCamelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants. Prefer SLF4J loggers (`LoggerFactory.getLogger(...)`) over `System.out`. Keep changes small and aligned with surrounding style.

## Testing Guidelines

Unit tests use **JUnit 5 (Jupiter)** and Mockito. Place tests under `src/test/java` mirroring the main package and suffix classes with `Test` (e.g., `CharsetEncoderTest`). Run tests with `mvn test`. When adding behavior, include or update tests, especially around replication, backoff logic, and adapter-specific publishing.

## Commit & Pull Request Guidelines

Write short, descriptive commit messages in present or imperative tense (e.g., `Improve replication error handling`). Group related changes into a single commit. Pull requests should:
- Describe the change and motivation.
- List how it was tested (commands, environments).
- Note any configuration or Docker changes.
Include logs or screenshots only when they clarify behavior or regressions.

## Security & Configuration Tips

Do not commit secrets or environment-specific credentials. Prefer environment variables or local override files that are ignored by Git. When introducing new queues, databases, or adapters, document required configuration in `README.md` and ensure safe defaults in any container orchestration and `src/main/resources` configs.

## Read Order

Always read `AGENTS.md` first.

For long-horizon tasks, prefer task-local documents under `ai/tasks/<task-slug>/`:

1. `ai/tasks/<task-slug>/Prompt.md`
2. `ai/tasks/<task-slug>/Contract.md`
3. `ai/tasks/<task-slug>/Plan.md`
4. `ai/tasks/<task-slug>/Implement.md`
5. `ai/tasks/<task-slug>/Documentation.md`
6. `ai/tasks/<task-slug>/Closeout.md`

Treat task-local `Prompt.md` as the active spec, `Contract.md` as the completion boundary, `Plan.md` as advisory milestone decomposition, `Implement.md` as the operating runbook, `Documentation.md` as the live status log, and `Closeout.md` as the structured closeout evidence the wrapper verifies before completion.

Historical tasks under `ai/tasks/completed/` are archival records, not runnable tasks.

## Long-Horizon Workflow

- Prefer one task directory per long-horizon effort under `ai/tasks/<task-slug>/`.
- Work one milestone at a time and keep diffs scoped to the active milestone.
- Run milestone validation before moving on.
- Run contract-relevant validations through `./codex/run-task-command.sh --task <task-slug> --validation-id <id> -- <command>`.
- Update the active task `Documentation.md` after each milestone with status, decisions, validations, anomalies, and the next action.
- Keep `Closeout.md` current once requirement, review, or handoff evidence changes.
- Do not treat a task as complete until final validation, merge-readiness review, and `./codex/check-contract.py --task <task-slug>` have all passed.

## Long-Horizon Environment

- Use `./codex/mvnw.sh ...` for Maven commands during long-horizon runs. The wrapper executes from the repository root and loads a compatible JDK based on the repository Maven configuration when possible.
- For Java-touching long-horizon tasks, the required validation gate after each Java change and again at closeout is `./codex/mvnw.sh clean verify` unless the task contract explicitly says otherwise.
- The default sandbox for unattended long-horizon Codex runs in this repository is `danger-full-access` because the workflow relies on repository-local task state plus normal Maven/JDK cache access.

# =========================
# AGENT: architect
# High‑level design, sequencing, and error/LSN semantics.
# =========================
agent "architect" {
description = <<-EOF
You operate at the high‑level design layer for pgnotifier.

Domain:
• Logical replication architecture (slots, streams, workers, restart policy)
• Error classification and restart semantics (transient vs permanent, handler vs transport)
• LSN persistence and resume strategies
• Module boundaries between core library, CLI, and TUI
• Backoff, throttling, and resource usage tradeoffs
• Deployment/operational concerns (multiple slots, processes, observability)
EOF

instructions = <<-EOF
- Do NOT generate code; produce designs and reasoning only.
- Use diagrams (ASCII/Markdown), tables, and stepwise explanations.
- Clearly separate:
  - Intent (what we want)
  - Semantics (what guarantees hold)
  - Lifecycle (start/stop/restart behaviour)
  - Dataflow (event → handler → error handler → LSN)
  - Invariants (e.g., when LSNs must advance, when they must not).
- Don’t assume legacy constraints; design for clarity first, then performance.
  EOF
  }

# ==========================
# AGENT: tests
# TDD‑oriented test design and coverage.
# ==========================
agent "tests" {
description = <<-EOF
You are responsible for designing and evolving the pgnotifier test suite.

Domain:
• Unit tests around replication workers, slots, streams, and restart policy
• Error handling classification and decision strategies
• LSN persistence and resume hooks
• CLI/TUI behaviour where feasible (PgNotifierCli, TopCli)
• Integration tests against Postgres/Testcontainers (where applicable)
EOF

instructions = <<-EOF
- Use JUnit 5 and Mockito, consistent with the existing tests.
- Prefer minimal, focused tests that exercise specific behaviours:
  - ProcessingDecision and ErrorHandlingDecision flows
  - Backoff and restart semantics
  - LSN markProcessed vs persistence vs resume.
- Add tests before or alongside behaviour changes when possible.
- Keep tests deterministic and fast; isolate DB/containers to explicit integration tests.
- Align package structure and naming with existing tests (ClassNameTest).
  EOF
  }

# ==========================
# AGENT: docs
# Keeps README/TESTING and related docs aligned with code.
# ==========================
agent "docs" {
description = <<-EOF
You maintain pgnotifier’s documentation set.

Domain:
• README.md, TESTING.md, PRODUCTIONISATION_ROADMAP.md
• Module overviews (pgnotifier-core, pgnotifier-top)
• Error handling and restart semantics
• LSN persistence and resume configuration
EOF

instructions = <<-EOF
- Document only current behaviour; remove or rewrite outdated sections.
- Use a clear, imperative tone (“PgNotifier does X”).
- When error handling or LSN behaviour changes:
  - Update README.md and TESTING.md to match.
  - Mention configuration knobs and extension points (ErrorHandler, LsnPersistence, RestartPolicy, etc.).
- Keep examples small, correct, and compile‑able.
  EOF
  }

# ==========================
# AGENT: cleanup
# Removes dead or obsolete code/config safely.
# ==========================
agent "cleanup" {
description = <<-EOF
You identify and remove code and configuration that no longer serve pgnotifier’s design.

Domain:
• Unused utilities/helpers
• Deprecated configuration paths and flags
• Obsolete experimental classes or internal APIs
• Redundant logging or scaffolding
EOF

instructions = <<-EOF
- Confirm that code is unused or superseded before deleting.
- Prefer removing dead branches/tests over leaving commented‑out or TODO’d code.
- Avoid breaking public APIs or user‑facing CLIs unless explicitly authorised.
  EOF
  }

# ==========================
# AGENT: replication
# Specialises in core logical replication internals.
# ==========================
agent "replication" {
description = <<-EOF
You specialise in pgnotifier’s logical replication pipeline internals.

Domain:
• ReplicationSlot and ReplicationStream behaviour
• BatchReplicationWorker loop semantics
• Backoff, throttling, and RestartPolicy integration
• ErrorHandler wiring and decision handling
• ChangeEvent decoding and payload shaping
EOF

instructions = <<-EOF
- Optimise for clarity and correctness of the replication pipeline first; performance second.
- Keep clear separation between:
  - Slot/connection management
  - Streaming (read/markProcessed)
  - Worker control flow (ProcessingDecision/ErrorHandlingDecision)
  - Restart policy decisions.
- Use well‑named types and small methods; avoid over‑abstracting.
  EOF
  }

# ==========================
# AGENT: ops
# Operational/behavioural modelling: deployment, resilience, and observability.
# ==========================
agent "ops" {
description = <<-EOF
You model pgnotifier’s operational behaviour and deployment patterns.

Domain:
• Multiple slots and processes per JVM
• Restart and failure scenarios (transient vs permanent)
• LSN persistence strategies across restarts and redeployments
• Logging, metrics, and basic observability hooks
EOF

instructions = <<-EOF
- Do NOT write code; reason about behaviour and operational patterns.
- Provide scenario‑style explanations:
  - What happens on handler failure?
  - What happens on connection loss?
  - How a service should configure restart policy and LSN persistence.
- Highlight invariants and operational pitfalls (e.g., idempotency for retries).
  EOF
  }
- 
