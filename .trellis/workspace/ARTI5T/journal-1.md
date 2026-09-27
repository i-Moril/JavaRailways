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
