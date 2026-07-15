# Contributing to reputation-pool

Thanks for your interest in contributing. This document describes how to build the project and the
conventions a change is expected to follow.

## Building

```bash
./gradlew build
```

`build` is the full gate and must be green before a change is merged:

- **Spotless** — formatting (palantir-java-format). Run `./gradlew spotlessApply` to fix violations.
- **Tests** — unit, property (jqwik), and concurrency tests.
- **ArchUnit** — enforces that `reputation-pool-core` stays free of framework dependencies.

The build provisions **JDK 25** automatically via the Foojay toolchain resolver, so you do not need
JDK 25 installed to build — but a JDK is required to run Gradle itself.

## Design principles

These are not style preferences; they are the reasons the project is structured the way it is, and
they are enforced by the build and by review.

1. **`core` is pure Java (JDK only).** No Spring, Netty, JDBC, or gRPC in `reputation-pool-core`.
   I/O lives behind the `port` interfaces and is implemented in outer modules. ArchUnit fails the
   build on violations.
2. **Decisions are pure functions.** The engine takes `(state, input, now)` and returns new state —
   no side effects, no hidden clock. This is what makes the logic property-testable and incidents
   reproducible.
3. **State is immutable.** Domain types are records; updates return new instances. Concurrency is
   handled at the boundary with atomic map operations, not locks scattered through the logic.
4. **Invariants are enforced by structure.** Reject invalid values in the constructor; prefer types
   and value objects over defensive `if`s spread across call sites.
5. **Time is injected.** Never call `System.currentTimeMillis()` or `Instant.now()` directly; take a
   `java.time.Clock`. Time-dependent tests use `Clock.fixed()`.

## Tests

- New reputation logic needs a **property** test (jqwik) that attacks its invariants, not just a
  happy-path example.
- New concurrent paths need a **multi-thread** test that reproduces real contention.
- Do not write tests that assert nothing meaningful.

## Comments

Comment the non-obvious *why* — a trade-off, a pitfall, a constraint from an external system. Do not
add comments that restate the code, and do not add uniform Javadoc to every method for its own sake.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `build:`, `ci:`,
`docs:`, `refactor:`, `test:`, `chore:`, optionally scoped (e.g. `feat(core): ...`).

## Issues and planning

Non-trivial changes start from an issue: written in English, titled in the conventional-commit style
(same prefixes as commits), and labelled with `type:` and `area:`. Related issues are grouped by a
**milestone** — a themed batch (e.g. `Test-suite hardening`) or a version ahead of a release (e.g.
`core 0.2.0`) — and the milestone is where batch progress lives. The project does not use GitHub
Projects boards; the README roadmap and milestones are the planning surface.

## Pull requests

Open PRs against `main`. Reference the issue the PR resolves with `Closes #N` in the body so the
merge closes it. Fill in the PR template checklist. CI and CodeRabbit run automatically; address
review comments before merge.

Changes to `advisor.proto` must pass the `buf breaking` check in CI: the wire contract stays
backward-compatible with the shape already on `main` (the comparison source switches to release
tags once the server ships versioned releases). An intentional breaking change goes into a new
major package (e.g. `...grpc.v2`) instead of editing the `v1` shapes.
