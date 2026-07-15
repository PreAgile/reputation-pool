# Changelog

All notable changes to `reputation-pool-core` — the module published to Maven Central as
`io.github.preagile:reputation-pool-core` — are documented here.

Two modules are published to Maven Central: **`reputation-pool-core`** (the pure decision engine —
`io.github.preagile:reputation-pool-core`) and, from 0.2.0, **`reputation-pool-persistence`** (the
PostgreSQL adapter — `io.github.preagile:reputation-pool-persistence`). Both share one version. The
`adapters` and `server` modules are not published and are not tracked here, and neither are internal
test fixtures or integration-test source sets (they are not part of the published artifacts).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-07-15

The snapshot/persistence surface added since 0.1.0, and the first publication of the PostgreSQL
adapter. All core additions are backward-compatible; one core type moved package (see Changed).

### Added

- **`reputation-pool-persistence` is now published to Maven Central** for the first time
  (`io.github.preagile:reputation-pool-persistence`). It provides the PostgreSQL implementations of
  the core's `ResourceStore` (whole-pool snapshot store) and `EventSink` (append-only audit trail)
  ports, plus their Flyway schema, on plain JDBC. Consumers can now depend on the Postgres adapter
  directly instead of vendoring it; it depends (`api`) on `reputation-pool-core`, so the core domain
  types come transitively. Unlike core, this module carries runtime dependencies by design (the
  PostgreSQL driver and Flyway).

- **`ResourceStore` port** (`core.port`) — the I/O boundary for persisting the pool's durable state,
  so it survives a process restart. As with `EventSink`, the core declares the contract in domain
  terms and an outer module fulfils it (the first implementation is a PostgreSQL store), keeping the
  core free of any database or SQL concern. (#38)
- **`PoolSnapshot` record** (`core.domain`) — a point-in-time, immutable capture of the pool's whole
  durable state: cells, blocklist, and registered resources bundled together, so a store can never
  persist the cells while silently dropping the blocklist. (#38)
- **`CellKey` record** (`core.domain`) — the `(resource × context)` identity of a `ReputationCell`,
  and the map key of `PoolSnapshot.cells()`. Promoted from a private detail of the pool facade to a
  public value object now that the pool's durable state is externally visible. (#38)
- **`ResourcePool.snapshot()` and `ResourcePool.restore(PoolSnapshot)`** — capture the pool's durable
  state as a `PoolSnapshot` for a `ResourceStore` to persist, and rehydrate it at startup. Leases are
  intentionally excluded from the snapshot; nothing is held immediately after a restart. (#38)

### Changed

- **`Blocklist` moved from `core.pool` to `core.domain`.** It is durable domain state carried inside a
  `PoolSnapshot`, not an internal of the pool facade, so it now lives with the other domain value
  objects. This is source-incompatible for code that imported
  `io.github.preagile.reputationpool.core.pool.Blocklist`; update the import to
  `io.github.preagile.reputationpool.core.domain.Blocklist`. The type's own API is unchanged. (#38)
- **Concurrency of the `LeaseRegistry` / `ResourcePool` facade is now proven linearizable with
  Lincheck.** No API change — this is a strengthened correctness guarantee about the published code:
  the model checker exhaustively explores thread interleavings (within its bounds) rather than relying
  on stress tests alone. (#49)

## [0.1.0] - 2026

Initial release — the pure decision engine, published to Maven Central at the L2 milestone.

### Added

- **Decision engine** (`core.engine`) — `ReputationEngine` applies `(cell, outcome, now) -> next cell`
  as a pure function with no side effects and no hidden clock, plus `CooldownPolicy` and the default
  `AdaptiveCooldownPolicy`.
- **Domain model** (`core.domain`) — immutable records and sealed/enum types forming the ubiquitous
  language: `ResourceId`, `ResourceKind`, `Context`, `Outcome` (`Success` / `Failure`), `FailureType`,
  `ResourceState`, `ReputationCell`, and `PoolEvent`.
- **Concurrency facade** (M2) — `ResourcePool`, `LeaseRegistry`, `SelectionStrategy`, and `Blocklist`
  (then in `core.pool`), with atomicity from concurrent-map operations rather than scattered locks.
- **`EventSink` port** (`core.port`) — the first I/O boundary, for observing pool events.
- **Zero runtime dependencies** — JDK only; the dependency-free boundary is enforced by an ArchUnit
  rule that fails the build on any `core -> Spring / Netty / JDBC / gRPC` import.

[Unreleased]: https://github.com/PreAgile/reputation-pool/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/PreAgile/reputation-pool/releases/tag/v0.2.0
[0.1.0]: https://central.sonatype.com/artifact/io.github.preagile/reputation-pool-core/0.1.0
