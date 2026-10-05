# Journal - ARTI5T (Part 1)

> AI development session journal
> Started: 2026-08-28

---


## Session 1: Allow station berths without door operations
<!-- trellis-session: v=2 fp=7449292d60e27fda -->

**Date**: 2026-09-17
**Task**: Allow station berths without door operations
**Package**: javarailways
**Branch**: `feat/webui-timetable-editor`

### Summary

Removed the stale StationBerthRole DoorSide.NONE restriction, added registration regression coverage, explicitly ignored and documented local MCSM test-server operations, verified Basic and BukkitPlugin tests, deployed hash 22b0b36c to JavaRailwaysTest, and confirmed successful Folia startup.

### Git Commits

| Hash | Message |
|------|---------|
| `33e0558` | fix(station): allow berths without door operations |

### Status

[OK] **Completed**


## Session 2: Unify SimpleATO and manual train dynamics
<!-- trellis-session: v=2 fp=0a524d6d42472db4 -->

**Date**: 2026-09-27
**Task**: Unify SimpleATO and manual train dynamics
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

SimpleATO and manual control now share Basic handle-based train dynamics; gravity is configured as track.gravity; operating direction is separate from model direction; reverse-grade force semantics were fixed and covered by tests.

### Git Commits

| Hash | Message |
|------|---------|
| `560fb0f0` | feat(physics): unify manual and ATO train dynamics |

### Status

[OK] **Completed**


## Session 3: Complete Stage 1 signal add-on host APIs
<!-- trellis-session: v=2 fp=3ba35f0ce1c54458 -->

**Date**: 2026-09-27
**Task**: Complete Stage 1 signal add-on host APIs
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Completed and archived Stage 1 host APIs. Applied timetable routes now install static infrastructure edit dependencies; signal restrictions are consumed by train controllers before every physics epoch without depending on Dispatcher progress; Bukkit exposes and verifies edit-permit, conflict-inspection, source-lifecycle, and related host services through a real external Java consumer. Updated owner specs and task acceptance evidence, reran Core/Basic/SimpleATO/BukkitPlugin tests plus consumer compilation and shadowJar after the separate shared-dynamics change 560fb0f0, and left Stage 2 plus the endpoint-format migration open for later work.

### Git Commits

| Hash | Message |
|------|---------|
| `57ab8374` | feat(simpleato): lock applied timetable infrastructure |
| `47cd99db` | fix(simpleato): enforce signal restrictions on physics ticks |
| `98627482` | test(signal-api): verify host service contracts |
| `ae55fa6d` | docs(signal): close stage 1 host API task |

### Status

[OK] **Completed**


## Session 4: Stage 2 local topology revalidation checks
<!-- trellis-session: v=2 fp=4955ffb73602285c -->

**Date**: 2026-10-05
**Task**: Stage 2 local topology revalidation checks
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Completed local topology invalidation review and offline cross-module gate; retained active Stage 2 task for user review.

### Main Changes

- Core affected-rail notifications drive Basic and ATO local validity plus SimpleSignals worker revalidation.
- Reviewed late-epoch output continuity, invalid-route occupant retention, overlap repair dependencies, fresh-frame coherence and consecutive swaps.

### Git Commits

| Hash | Message |
|------|---------|
| `9c4491cf` | fix(signals): invalidate topology dependencies locally |

### Testing

- [OK] 276 suites, 1655 tests: 1648 passed, 7 baseline skips, zero failures/errors; final SimpleSignals tests and verifyPluginJar executed, unchanged modules reused prior results.
- [OK] Java consumer compile, Bukkit shadowJar, diff check and 12/14 context entries validated; no live Folia or scale test.

### Status

[OK] **Completed**

### Next Steps

- Stop for user review of 9c4491cf; Stage 2 remains in progress. Startup recovery and coordinate remapping are not complete.


## Session 5: Protection region current route preview
<!-- trellis-session: v=2 fp=44e06b6074677aa8 -->

**Date**: 2026-10-05
**Task**: Protection region current route preview
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Implemented read-only current-turnout protection route preview in SimpleSignals; 34 new tests and 392 module tests passed; route recording and live platform validation remain pending.

### Main Changes

- Added /simplesignals wand route: left entry, right exit, current physical choices only, explicit actual stop reasons.
- Coalesced relevant events on workers; prepared bounded particle batches; closed subscriptions and late tasks on player/world/plugin teardown.
- Independent review removed unreachable endpoint selection logic and fixed pending invalidation to unchanged-region recovery notification.

### Git Commits

| Hash | Message |
|------|---------|
| `dee2409d` | feat(simplesignals): preview current protection routes |

### Testing

- [OK] Focused 33 preview tests and one manager regression passed; regression failed before notification fix.
- [OK] Final offline SimpleSignals check and Java consumer gate passed: 40 suites, 392 tests, zero failures/errors/skips; tests and verifyPluginJar executed, Java consumer reused cache.
- [OK] git diff --check and task manifests validation passed; live Folia/client and load tests not performed.

### Status

[OK] **Completed**

### Next Steps

- Stop for user review; route recording is the next implementation item. Stage 2 remains active.
