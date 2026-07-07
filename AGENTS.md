# AGENTS.md

Canonical machine/agent-facing norms for **reputation-pool** — a pure-Java reputation
decision engine (proxy endpoints, external accounts, sessions: anything you lease, that
degrades on failure, cools down, and recovers). Zero runtime dependencies (JDK only).

This file is the source of truth for how automated agents work in this repo. It
complements — does not replace — [CONTRIBUTING.md](CONTRIBUTING.md), which holds the
human-facing build, review, and PR detail. When they overlap, follow CONTRIBUTING.md for
process and this file for norms.

## Design principles

Enforced by the build and by review, not by convention:

1. **`core` is pure Java (JDK only).** No Spring, Netty, JDBC, or gRPC in
   `reputation-pool-core`. I/O lives behind `port` interfaces implemented in outer
   modules. An ArchUnit rule fails the build on any violating import.
2. **Decisions are pure functions.** The engine takes `(state, input, now)` and returns
   new state — no side effects, no hidden clock. This is what makes invariants
   property-testable and production incidents reproducible by replaying inputs.
3. **State is immutable.** Domain types are records; an update returns a new instance.
   Concurrency lives in the data (atomic map operations at the boundary), not in locks
   scattered through the logic.
4. **Invariants are enforced by structure.** Reject invalid values in the constructor;
   prefer types and value objects over defensive `if`s spread across call sites.
5. **Time is injected.** Never call `Instant.now()` or `System.currentTimeMillis()`
   directly — take a `java.time.Clock`. Time-dependent tests use `Clock.fixed()`.

## Methodology

- **Tactical DDD.** Value objects, immutability, invariants-in-types, and a shared
  ubiquitous language (ResourceId, Context, Outcome, ReputationCell, ...). Strategic DDD
  (multiple bounded contexts) does **not** apply — this is a single domain.
- **TDD (red → green → refactor)** is the default rhythm. It fits naturally here because
  the engine is a pure function: write the failing behavior test, make it pass, refactor.
- **BDD-flavored test naming/structure.** Tests read as behavior specifications and serve
  as living documentation. No Cucumber — this is a naming/structure style, not a tool.
- **Property-based testing with jqwik** for engine invariants — attack them over thousands
  of generated sequences, not one happy path. Examples: score stays bounded, recovery is
  monotonic, contexts do not pollute each other, a blocklisted resource can never be lent.
- **Mutation testing with PIT** verifies the tests actually have teeth (a surviving mutant
  is an assertion that never fires). Coverage alone is a weaker bar — it proves lines ran,
  not that a broken change would be caught. Run on demand: `./gradlew pitest`.
- **Integration / Testcontainers does not apply to `core`** — it performs no I/O. That
  belongs to the future server/adapter module, where ports are bound to real infrastructure.

## Working rules

- **Branch off `main`; never commit to `main`.** Open PRs against `main`.
- **English only** in all files, comments, and commit messages. This is an open-source repo.
- **Build with the JDK 25 toolchain.** Run `./gradlew build`; the Foojay resolver
  auto-provisions JDK 25, so you do not need it installed (a JDK to run Gradle is enough).
  `build` is the full gate: Spotless + tests (unit/property/concurrency) + ArchUnit.
- **PRs go through CI + CodeRabbit before merge.** Keep the build green; address review
  comments before merging.
- **[Conventional Commits](https://www.conventionalcommits.org/)** — `feat:`, `fix:`,
  `build:`, `ci:`, `docs:`, `refactor:`, `test:`, `chore:`, optionally scoped (e.g.
  `feat(core): ...`).

## Layout

```
reputation-pool-core/
  domain/   immutable records + enums + sealed types (the ubiquitous language)
  engine/   pure decision logic — (state, input, now) -> new state
  port/     interfaces = the I/O boundary (time, storage, probing, observability)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for human build/PR details.
