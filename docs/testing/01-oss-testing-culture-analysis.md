# OSS Testing-Culture Analysis — Lettuce · Armeria · Resilience4j · Kotest vs reputation-pool

| Key | Value |
|-----|-------|
| **Created** | 2026-07-08 22:37:05 |
| **Updated** | 2026-07-08 22:37:05 |
| **Version** | v1.0.0 |
| **Topic** | testing |
| **Doc** | #01 |

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| v1.0.0 | 2026-07-08 22:37:05 | Initial document |

---

## Summary

We cloned and analyzed the actual test suites of four mature open-source projects and
compared them against this repo's suite. Headline: **none of the four uses jqwik or PIT.**
That is not a verdict against our methodology — each project sources its confidence from
whatever actually breaks in its domain, and for a pure decision engine, property + mutation
testing is the right fit. Our real gaps are in *coverage placement*, not methodology: the
most important surfaces (the `ResourcePool` facade, the `report()` path under contention)
are the least verified.

## Per-project testing culture

| Project | Domain | Source of confidence | Signature technique |
|---|---|---|---|
| **Lettuce** | Redis client (I/O) | Real-server volume: a 7-version Redis matrix on every PR, Toxiproxy chaos | One sync-style suite replayed over async/reactive/kotlin APIs via reflection handlers |
| **Armeria** | Netty framework | Runtime instrumentation: BlockHound (no event-loop blocking) + paranoid leak detection as build gates | Test fixtures (`ServerExtension`) shipped as public artifacts; the project is its own first consumer |
| **Resilience4j** | State-machine library (closest to us) | Injected time + JCStress: MockClock drives OPEN→HALF_OPEN→CLOSED deterministically | Per-source time seams: `Clock`/MockClock where code takes a clock, a spyable `currentNanoTime()` where it needs nanoTime |
| **Kotest** | Test framework itself | Event-stream meta-verification: assert on the raw events the engine emitted, never on "was it green" | Fixed-seed golden-master assertions turn the probabilistic shrinker into a byte-exact regression test |

## Common ground across all four

1. JUnit 5 + AssertJ + BDD `should...` naming, no Cucumber — identical to this repo.
2. No property-based or mutation testing (only Kotest dogfoods its own property engine).
   Substitutes: `@ParameterizedTest` volume, real-infra matrices, runtime instrumentation.
3. Flakiness is surfaced, not hidden: Resilience4j and Armeria both set
   `failOnPassedAfterRetry = true` — a test that only passes on retry fails the build.
4. Time is always injected; `Thread.sleep` is reserved for tests of real background threads.
5. Shared test infrastructure is extracted (`java-test-fixtures`, dedicated test modules).

## Gaps found in this repo's suite (inventory of 17 test classes, 137 @Test / 15 @Property)

1. **Concurrency tests are one-sided.** Only the `acquire` path is race-tested;
   `report()` / `block()` / `unblock()` / `renew()` mutate shared state with zero
   contention coverage. `report()` is the highest-frequency production call.
2. **The signature invariant is not a property.** "A blocklisted resource can never be
   lent" (named in AGENTS.md as a jqwik target) is only example-tested at the facade;
   there is no stateful property driving random acquire/report/block/unblock/renew
   interleavings.
3. **Concurrency tests run once.** A 1-in-1000 race stays green on its single shot.
4. **Selection-distribution tests are seed-pinned and brittle** — tight bands asserted
   off `new Random(1/3/5/7)` fixed streams; a real weighting regression can hide inside
   the band while an RNG-order refactor breaks the test with no behavior change.
5. **PIT is off the CI gate** (on-demand only) and `ResourcePool.blockPermanently()` has
   zero coverage.
6. **`SettableClock` is copy-pasted** between `ResourcePoolTest` and
   `ProxyPoolIntegrationTest`.

## Recommendations (by priority)

1. **Stateful property test on `ResourcePool`** — jqwik-generated random action sequences
   asserting invariants at every step (blocklisted never lent, no double-lease, cooling
   not selectable). Highest-leverage single change; closes gaps 1-adjacent and 2.
2. **`report()`-path concurrency test** — reuse the CountDownLatch start-gate idiom from
   `LeaseRegistryTest`; verify counter integrity under 32-thread contention.
3. **Extract `SettableClock` into `java-test-fixtures`** (Resilience4j pattern), replacing
   both copies.
4. **Property-test the selection distribution over random seeds** with generous bands,
   replacing brittleness with statistical robustness.
5. **Put PIT on a schedule** — nightly/dispatch CI job, since it is our only "do the tests
   have teeth" proof (no Sonar here).
6. **Embed issue numbers in regression test names** (Kotest pattern,
   e.g. `regression for #4944`) — adopt as convention going forward.

**Deliberately NOT adopting:** real-server version matrices (Lettuce) and
BlockHound/leak-detection (Armeria) — both are I/O-domain answers with no target in a
pure, dependency-free core.

## Provenance

Analysis performed 2026-07-08 by five parallel research agents over shallow clones of
redis/lettuce, line/armeria, resilience4j/resilience4j, kotest/kotest, plus a read-only
inventory of this repo. Tracked as a GitHub issue on this repo (see issue link in the
implementing PR).
