# pgnotifier Context

## Event Settlement

The replication pipeline behaviour that decides whether and how an event's LSN is acknowledged, persisted, reflected in health, reflected in metrics, or left unadvanced for retry.

Important invariants:
- Commit and drop outcomes advance the relevant LSN exactly once.
- Retry outcomes do not advance the LSN.
- Persistence follows acknowledgement.
- Health, metrics, and observed lag describe the same acknowledged LSN.

Related terms: ChangeEvent, LSN persistence, ReplicationStream, Slot health, Notifier metrics.

## Handler Execution

The replication pipeline behaviour that invokes user change handlers and error handlers, either inline on the reader thread or through asynchronous worker execution.

Important invariants:
- Handler decisions must produce the same event settlement semantics in every execution mode.
- Async execution must preserve ordered acknowledgement while allowing handler work to run independently.
- Overflow and stop behaviour must be explicit and testable through the handler execution seam.

Related terms: ChangeHandler, ErrorHandler, HandlerExecutionConfig, Event settlement.

## Slot Runtime

The per-slot runtime assembled from a slot configuration, replication slot, decoder, worker, lifecycle process, health state, metrics hooks, and executor ownership.

Important invariants:
- PgNotifier remains the lifecycle facade for configured slot runtimes.
- Per-slot construction and health snapshot composition should have locality in one module.
- Test-only seams should not leak into the public facade when a deeper runtime module can own them internally.

Related terms: PgNotifier, PgNotifierBuilder, ReplicationSlot, BatchReplicationWorker, LatchedProcess, SlotHealthSnapshot.

## Command Configuration

The command-line and terminal-dashboard configuration path that combines property files, command overrides, defaults, validation, clamping, and enum parsing into PgNotifier configuration records.

Important invariants:
- Property keys, defaults, and validation rules should have locality in one module.
- CLI and TUI adapters should collect user input without reimplementing the same mapping rules.
- Documentation must describe current external behaviour only.

Related terms: CommandConfiguration, PgNotifierProperties, Configuration, PgNotifierCli, TopCli.
