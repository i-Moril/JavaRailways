# Stage 1 implementation plan

## Preparation

- [ ] Load Core, Basic, BukkitPlugin, shared cross-layer, persistence, and threading specs.
- [ ] Inventory current route-allocation, physics-publication, controller-constraint, service-registration, world-unload, and topology-edit callers.
- [ ] Inventory and remove runtime assumptions that derive one stop/release boundary per turnout, including `single()` turnout paths.
- [ ] Inventory every `RailView.selectNextRail` caller and route turnout mutation through either protected automatic allocation or the separately guarded manual operation.
- [ ] Write contract tests for an external compile-only consumer before moving implementations.

## Implementation

- [x] Add immutable epoch/revision observation DTOs and coherent publication access.
- [x] Add atomic shared infrastructure leases plus one edit-permit/diagnostic service covering applied-plan leases and live route claims.
- [x] Add direction-aware boundary, bounded-region, frozen route-window, exit-clearance, and composite-request public contracts.
- [ ] Add a typed `PROTECTION_REQUIRED` result and make a validated complete protected window mandatory for every automatic/`MANUAL_ROUTED` controlled-resource request; provide no raw planned-section automatic overload.
- [ ] Generalize route resources from turnout-owner keys to all-or-nothing rail, turnout, fixed-crossing, and clearance claims.
- [ ] Remove turnout-derived protection-point synthesis from route compilation and reject automatic mission activation when any controlled resource lacks complete explicit region coverage.
- [ ] Centralize turnout mutation and add the physical-lock/active-claim checked manual operation that produces no automatic authority.
- [ ] Replace turnout-derived release assumptions with region-entry/tail-exit and generic conflict-envelope release tracking.
- [ ] Add fixed-connectivity diamond topology nodes plus placement/update spatial-candidate and exact-geometry detection.
- [ ] Add namespaced revisioned train restriction contracts and enforcement adapter.
- [ ] Add Bukkit public service interfaces, internal implementations, lifecycle registration, and cleanup.
- [ ] Migrate `TimetableDispatcher` and `SimpleATO` integration to the shared services.
- [ ] Add topology edit guards for active external claims.
- [ ] Replace dispatcher-specific RailWand/auto-connect guards with the common edit guard and cover protection-region, signal, topology migration, and administrative mutation paths.
- [ ] Document a minimal external consumer and supported failure/lifecycle behavior.

## Validation

- [ ] Focused Core tests for immutable publication, epochs, occupancy, diamond topology connectivity, detection/update, and controller enforcement.
- [ ] Focused Basic tests for no-partial atomic claims, conflicts, zero/one/multi-turnout windows, exit clearance, composite adjacent regions, stale revisions, tail release, and timetable migration.
- [ ] Tests for uncovered/partially covered/already-aligned turnout denial, no mutation on `PROTECTION_REQUIRED`, revision-driven rather than per-tick retry, no-controlled-resource routes, and guarded manual selection.
- [ ] Tests for overlapping shared timetable leases, atomic lease acquisition/release, edit denial diagnostics, reload reconstruction, and no partial mutation across every supported infrastructure editor.
- [ ] Bukkit tests for service registration/lookup, plugin disable, world unload, and target-aware dispatch.
- [ ] Compile-only external consumer test imports public packages only.
- [ ] Run `./gradlew :Core:test :Basic:test :BukkitPlugin:test`.
- [ ] Run `./gradlew :BukkitPlugin:shadowJar` and inspect service/API packaging.
- [ ] Run `git diff --check`.

## Rollback points

- Keep resource model, restriction model, and Bukkit service exposure in reviewable commits.
- Do not remove the old timetable path until parity tests pass against the shared service.
- Do not declare the Stage 2 dependency ready until disable/failure semantics are tested.
