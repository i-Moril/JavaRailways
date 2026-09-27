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
