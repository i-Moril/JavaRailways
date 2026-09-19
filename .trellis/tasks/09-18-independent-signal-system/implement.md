# Independent signal system implementation plan

## Ordered work

- [x] Record the Stage 2 MVP boundary and explicitly defer fine-grained signal-package indication/shunting API choices that do not affect the host refactor.
- [ ] Complete, test, and archive `09-18-signal-addon-host-apis`.
- [ ] Replace the old per-turnout protection-boundary assumptions with the reviewed directional-region, atomic route-window, and exit-clearance contracts.
- [ ] Add fixed-connectivity diamond-crossing topology/resource support and placement/update detection tests.
- [ ] Freeze/document the add-on API surface and record its supported lifecycle semantics.
- [ ] Start `09-18-signal-addon-implementation` only after Stage 1 acceptance criteria pass.
- [ ] Build and verify the separate Signal plugin against the Stage 1 API.
- [ ] Run cross-stage lifecycle, persistence, safety, and integration checks.
- [ ] Validate no-partial-claim behavior, exit-clearance admission, adjacent-region composition, and diamond-crossing route continuity/conflict behavior.
- [ ] Review that Signal contains no host-internal imports and JavaRailways contains no Signal policy.

## Review gates

- Stage 1 API review before any Stage 2 production dependency is added.
- Persistence schema review before Signal writes world data.
- Failure-mode review before enabling authority enforcement on live trains.
- Final install/omit/disable/restart matrix before completion.

## Validation

- Run affected Core, Basic, and BukkitPlugin tests for Stage 1.
- Build the JavaRailways host JAR and the separate Signal JAR.
- Run Signal unit tests and host/add-on integration tests.
- Run a Folia smoke test for world lifecycle, rendering ownership, and disable cleanup.
- Run `git diff --check` and inspect both JAR contents for dependency leakage.
