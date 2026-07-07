<!-- Keep the PR focused. A green build is necessary but not sufficient — the checklist below is the bar for merge. -->

## Summary
<!-- What changed, and why. -->

## Related issue
<!-- Closes #... , or the roadmap item this advances. -->

## Type of change
- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Build / CI

## Checklist
- [ ] `./gradlew build` is green (Spotless + unit + property + concurrency + ArchUnit)
- [ ] Invariants are enforced by **structure** (constructor / type), not scattered `if`s
- [ ] `core` stays pure — no Spring / Netty / JDBC / gRPC imports (ArchUnit passes)
- [ ] New reputation logic is covered by a **property** test; new concurrent paths by a **multi-thread** test
- [ ] Time is obtained via an injected `Clock`, and time-dependent tests use `Clock.fixed()`
- [ ] Public API changes are reflected in Javadoc and the README, if applicable

## Notes for the reviewer
<!-- Trade-offs, known limitations, anything non-obvious. -->
