# Changelog

All notable changes to `reputation-pool-core` — the module published to Maven Central as
`io.github.preagile:reputation-pool-core` — are documented here.

Modules published to Maven Central share one version. **`reputation-pool-core`** (the pure decision
engine — `io.github.preagile:reputation-pool-core`) has shipped since 0.1.0. Since 0.2.1 the
**`reputation-pool-persistence`** (PostgreSQL adapter), **`reputation-pool-adapters`**, and
**`reputation-pool-server`** modules are also published; the gRPC surface **`reputation-pool-grpc`**
was extracted into its own module and published at 0.3.0. Internal test fixtures and integration-test
source sets are not part of the published artifacts.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1] - Unreleased

Per-pool state isolation, so one PostgreSQL database can hold many independent pools — the upstream
half of reputation-pool-cloud's multi-tenant pool isolation. A backward-compatible, additive change
(hence a patch): existing single-pool consumers keep working under the `default` pool id.

### Added

- **`ReputationAdvisorService.pool()` seam** (`reputation-pool-grpc`) — a `protected ResourcePool
  pool()` hook the gRPC handlers now call, so a host can select the pool per request (e.g. by tenant)
  without re-implementing any handler. The default returns the injected pool, so the reference server
  is unaffected; a new `protected ReputationAdvisorService(EventBroadcaster)` constructor lets a
  subclass supply pools dynamically. (#67)
- **`pool_id` namespace in the persistence schema** — `PostgresResourceStore` now takes a pool id and
  scopes every read and write to it (`V3__pool_id.sql` adds `pool_id` to the snapshot tables, keys it
  first in every primary key, and drops the single-row `snapshot_meta` marker). Two pools in one
  database no longer overwrite each other on checkpoint. The existing constructors default to
  `default`, so single-pool callers are unchanged. (#67)

## [0.3.0] - 2026-07-15

### Changed

- **The gRPC surface moved into its own `reputation-pool-grpc` module** — the proto contract, stubs,
  and the `ReputationAdvisorService` handler now ship as `io.github.preagile:reputation-pool-grpc`, so
  a host consumes the gRPC adapter as a published artifact instead of vendoring it. (#66)

## [0.2.1] - 2026-07-15

### Added

- **`reputation-pool-persistence`, `reputation-pool-adapters`, and `reputation-pool-server` are now
  published to Maven Central**, so a host can depend on the PostgreSQL adapter and the assembled
  server directly instead of vendoring them. Persistence provides the PostgreSQL implementations of
  the core's `ResourceStore` (whole-pool snapshot store) and `EventSink` (append-only audit trail)
  ports plus their Flyway schema on plain JDBC, and depends (`api`) on `reputation-pool-core` so the
  core types come transitively. Unlike core, it carries runtime dependencies by design (the PostgreSQL
  driver and Flyway). (#65)

### Changed

- **CI rejects release-version downgrades and duplicates** before building, so a tag or dispatch that
  reuses or lowers an already-released version fails fast rather than pushing a bad coordinate to an
  immutable registry. (#64)

## [0.2.0] - 2026-07-15

The snapshot/persistence surface added since 0.1.0. All core additions are backward-compatible; one
core type moved package (see Changed).

### Added

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

[Unreleased]: https://github.com/PreAgile/reputation-pool/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/PreAgile/reputation-pool/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/PreAgile/reputation-pool/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/PreAgile/reputation-pool/releases/tag/v0.2.0
[0.1.0]: https://central.sonatype.com/artifact/io.github.preagile/reputation-pool-core/0.1.0
