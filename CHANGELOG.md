# Changelog

All notable changes to `reputation-pool-core` — the module published to Maven Central as
`io.github.preagile:reputation-pool-core` — are documented here.

Modules published to Maven Central share one version. **`reputation-pool-core`** (the pure decision
engine — `io.github.preagile:reputation-pool-core`) has shipped since 0.1.0. Since 0.2.1 the
**`reputation-pool-persistence`** (PostgreSQL adapter), **`reputation-pool-adapters`**, and
**`reputation-pool-server`** modules are also published; the gRPC surface **`reputation-pool-grpc`**
was extracted into its own module and published at 0.3.0; **`reputation-pool-prober`** (the recovery
scheduler) joins the published set as of this release. Internal test fixtures and integration-test
source sets are not part of the published artifacts.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

A `COOLING` resource is never offered by `acquire`, so once its cooldown passes there was nothing
lease-driven left to report a success and let it probate into `RECOVERING` — it stayed `COOLING`
forever unless something called `report()` for it directly. `ProxyPoolIntegrationTest` already had to
work around this by hand (a loop of manual `pool.report(...)` calls commented "no lease while
cooling"); this release closes the gap with a real component instead of a hand-written test loop.

That component is only a faithful signal for four of the five `FailureType`s, though. `SLOW`,
`TIMEOUT`, `CONNECTION_RESET`, and `TLS_HANDSHAKE` all live in the resource itself, so a request to a
neutral target measures them directly; `BLOCKED` lives in the relationship between one resource and
one site, and a `200` from an unrelated URL says nothing about it. Recovery therefore splits by cause:
a probe for the four it can judge, and half-open admission — one real request — for the one it cannot.

### Added

- **`reputation-pool-prober`** (new module) — `RecoveryProbe`, the per-`ResourceKind` contract for
  actively testing a resource outside the lease flow, and `RecoveryScheduler`, which drives it off two
  paths built together, not phased: an event-driven fast path (schedules a probe the moment a
  `ResourceCooled` event names a cooldown deadline) and a periodic backstop sweep (catches whatever the
  event path missed — a dropped event, or a restart between a cooldown firing and its scheduled probe).
  A failed probe re-cools through the normal `report()` → `CooldownPolicy` path; no separate backoff is
  invented. (#87)
- **`ResourcePool.dueForRecoveryProbe(Instant now)`** (`reputation-pool-core`) — a small, pure,
  read-only query: the `COOLING` cells past their own cooldown, excluding anything blocklisted,
  currently leased under another context, or cooled by a `BLOCKED` (which half-open admission owns
  instead — see below). Not a new `port` — `report()` already works without a lease, so the missing
  piece was purely "who decides it's time, and who does the trying," an outer-module concern.
  (#87, #90)
- **`HttpProxyRecoveryProbe`** (`reputation-pool-adapters`) — the reference `RecoveryProbe` for
  `PROXY` resources (v1 scope: proxies only): a plain `java.net.http` request routed through the
  candidate proxy at a lightweight target, classified by the same `OutcomeClassifier` normal traffic
  uses. The target is resolved **per `Context`** through a `Function<Context, Optional<URI>>`, because
  sites differ in network path and CDN: latency and reset behaviour measured against an unrelated host
  say nothing about the route real traffic takes. A context with no configured target yields
  `Optional.empty()` — a skip, not a failure, and not even an `HttpClient` — matching what
  `RecoveryProbe` already documents for an unresolvable endpoint. A map-backed resolver is
  `ctx -> Optional.ofNullable(map.get(ctx))`; one target shared by every context is
  `ctx -> Optional.of(uri)`. The skip is logged at `WARN` **once per context**, because on its own it
  is indistinguishable from "nothing was due" — an assembly that forgot a context would otherwise
  never proactively probe anything cooled in it, silently. Once per context, not per probe: the
  backstop sweep runs on a short period and repeating it would bury the message. (#87, #90, #100)
- **`AdvisorServer` recovery wiring** (`reputation-pool-server`) — two new `create(...)` overloads
  accepting a `Map<ResourceKind, RecoveryProbe>`; the scheduler joins the existing event fan-out
  alongside the broadcaster and audit sink, and its backstop sweep rides the same periodic scheduler
  thread as the checkpoint. Recovery does not require a `ResourceStore` — an in-memory pool recovers
  the same way a durable one does. (#87)

### Changed

- **`acquire` admits a half-open trial for a resource cooled by a site block** (`reputation-pool-core`)
  — a `COOLING` cell whose cooldown has elapsed and whose cooling cause was `BLOCKED` is selectable
  again for exactly one real request, the classic circuit-breaker half-open state. Real traffic is the
  only signal with full fidelity here: whatever the workload does to get blocked is exactly what gets
  retried, where a probe against a neutral target would report a `Success` that observed nothing and
  promote the cell for a site still refusing it. The blast radius needed no new machinery — the lease
  registry already caps it at one in-flight trial, `WeightedRandomSelectionStrategy` gives the
  lowest-scored candidate only its exploration floor, and a failed trial re-cools on the next step of
  the same backoff curve. (#90)
- **`ReputationCell` gains a `cooldownCause` component** (`reputation-pool-core`) — the `FailureType`
  that sized the cell's current `cooldownUntil`, `null` for a cell that has never cooled. It is what
  the half-open/probe split above is keyed on. The split originally re-derived that cause by scanning
  the outcome window backwards for the newest failure, which is a different value: failures reported
  while a cell is already `COOLING` are appended to the window without resizing the cooldown, and a
  full window eventually evicts the causing failure altogether — so a cell cooled by a site block
  could drift onto the prober's side and be promoted out of `COOLING` by a synthetic `Success`, the
  exact false recovery the split exists to prevent. The engine now records the cause on the transition
  that sets the cooldown, and nothing later rewrites it. **Source-incompatible** for callers of
  `ReputationCell`'s canonical constructor (the `Builder` and `fresh(...)` are unaffected). (#97)
- **`cell.cooldown_cause`** (`reputation-pool-persistence`, `V6__cell_cooldown_cause.sql`) — an
  additive nullable `text` column carrying the `FailureType` name, backfilled for existing `COOLING`
  rows from the newest failure in their stored window, which is exactly the value the old scan read —
  so an existing checkpoint keeps the ownership decision it already had. (#97)

## [0.5.0] - 2026-07-22

The gRPC event stream can now be scoped per pool, completing the upstream side of
reputation-pool-cloud's per-tenant event isolation. Additive (a minor): the reference server and any
existing subclass keep their single-pool behaviour under the `default` pool.

### Added

- **`ReputationAdvisorService.subscriptionPoolId()` seam** (`reputation-pool-grpc`) — a `protected
  String subscriptionPoolId()` hook, the streaming counterpart of `pool()`. `subscribeEvents` now
  registers via `broadcaster.subscribe(subscriptionPoolId(), observer)`; the default returns
  `"default"`, and a multi-tenant host overrides it (resolving the pool from the tenant on the gRPC
  context) so a subscriber receives only its own pool's events — without re-implementing
  `subscribeEvents` or exposing the package-private `subscribe(poolId, observer)`. Pairs with
  `EventBroadcaster.forPool` (0.4.0) to close cross-tenant event-stream leakage. The proto is
  unchanged. (#77)

## [0.4.0] - 2026-07-22

Per-tenant audit trail and event stream — the upstream half of reputation-pool-cloud's multi-tenant
isolation — plus dependency currency and a security fix. Additive (hence a minor): existing
single-pool consumers keep working under the `default` pool id, and the gRPC/proto contract is
unchanged.

### Added

- **`audit_event.pool_id`** (`reputation-pool-persistence`, `V5__audit_pool_id.sql`) — an additive
  `pool_id text NOT NULL DEFAULT 'default'` backfilling every existing row, plus an
  `audit_event_pool_seq_idx (pool_id, seq)` index for pool-scoped keyset paging. No primary key is
  redefined — the append-only IDENTITY `seq` is already globally unique across pools. (#74)
- **`PostgresAuditTrail.forPool(String)`** — an `EventSink` view that tags every appended row with a
  pool id, sharing the trail's one queue, writer thread, and dropped counter. The bare `emit` still
  appends under `default`; the pool id travels as wiring (who emitted), never as a field on the
  pool-agnostic `PoolEvent`, mirroring the per-tenant sink pattern. (#74)
- **`EventBroadcaster.forPool(String)`** and pool-scoped subscriptions — an event emitted for one pool
  is fanned out only to that pool's subscribers, so a multi-tenant host's `SubscribeEvents` streams no
  longer leak across tenants. The bare `emit`/`subscribe` stay on `default`; the proto is unchanged
  (subscription tenancy is a server-side decision, never on the wire). (#74)

### Changed

- **protobuf-java 3.25.1 → 3.25.8** (`reputation-pool-grpc`) — matches the protobuf-java that
  grpc-protobuf 1.82.2 resolves, and stays on the 3.25.x LTS line deliberately (a 4.26+ gencode would
  embed a runtime-version check that breaks downstream hosts pinned to an older protobuf). (#75)
- **gRPC 1.63.0 → 1.82.2**, **Flyway 12.11.0 → 13.0.0**, and a group of minor/patch dependency
  updates. (#70, #72, #73)

### Security

- **CVE-2024-7254** (protobuf-java, High — unbounded-recursion DoS on untrusted messages, fixed
  upstream in 3.25.5) remediated via the protobuf-java 3.25.8 bump. (#75)

## [0.3.1] - 2026-07-17

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
