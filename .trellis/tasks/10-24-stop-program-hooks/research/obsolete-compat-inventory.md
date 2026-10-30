# Research: Obsolete development compatibility inventory

- Query: Inventory old development compatibility paths across the current project; separate removable old paths from current independent functions.
- Scope: internal source inventory and selected caller tracing, not an exhaustive behavioral audit
- Date: 2026-10-27

## Findings

### Coverage

Module inventory comes from `settings.gradle`: Core, Basic, SimpleATO, SimpleATOBukkit, SimpleSignals, Annotation, BukkitPlugin, BukkitPluginDev, TunnelBridgeBuilder, ConfigLib, WebUI, Diagnostics. All twelve modules were searched for legacy/compatibility/deprecated/old-schema indicators in Java/Kotlin production sources; WebUI TS/TSX production and focused tests were also searched. Hits were traced selectively below. Build outputs, dependencies, archived tasks and docs-site independent submodule were not treated as current production source. No statement that every unlabelled fallback has been audited is justified.

### Strong removal candidates

1. **Old per-call arrival execution is still a live parallel implementation.**
   - `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt:247` (also 269,290) keeps `arrivalSteps` beside `stopPrograms` on DestinationOptions/SelectStop/CallStation, with explicit mutual-exclusion wording for legacy/shared inputs.
   - `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/TimetableSnapshot.kt:153` persists it and lines176–232 restore it; capture copies it at255/293/314.
   - `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Model.kt:28` duplicates that worker wire field.
   - `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt:476` still accepts `arrivalProgram`, expands it into AT children, and creates ARRIVAL branch trace entries at509. `StationPrograms.kt:223` exclusively validates the old callback input.
   - `SimpleATO/.../TimetableDispatcher.kt:2389,2472` enters old arrival execution when the shared station program branch is absent; `beginArrivalProgram` at4121 and `finishArrivalProgramIfExited` maintain a separate continuation. Associated `DispatcherArrivalExecution`, ActiveRun arrivalExecution, branch guards and cursor ARRIVAL branches require coherent removal, not just deleting decode keys.
   - `SimpleATO/.../TimetableArrivalProgram.kt` defines legacy `arrivalSteps()` and permitted action checks. `TimetableProgramCursor.kt:93`, `TimetableBranchGuard.kt:45,76`, validators and dumper consume it.
   - Current station-program arrival/preparation functionality must stay: `StationStopPrograms`, `beginStopProgram`, source provenance `arrival:<callId>` are not evidence of compatibility merely because they contain arrival in the name.
   - Current tests exercise the old route (e.g. TimetableStationCallDispatcherTest268–381); migrate tests of current arrival semantics to shared stop programs and remove tests only asserting the unsupported input.

2. **Dead frontend service row compiler.**
   - `WebUI/frontend/src/schedule.ts:15` and following own old `ServiceEditorRowV1` generation, edit operations and `compileServiceRows`.
   - Project-wide import/identifier search found consumers only inside that file and `schedule.test.ts`, no production component imports. Remove the module and obsolete tests; then separately trace row fields in `types.ts:22,31` and runtime DTOs before removing associated schema.
   - `i18n.tsx` legacyRouteNote has no consumer anywhere outside bilingual definitions; safe orphan cleanup.

3. **Old source-path adapter.**
   - `WebUI/frontend/src/programNavigation.ts:49` matches `runs[index].calls[index].arrivalProgram` and routes to new station programs. Current authoring model explicitly rejects that obsolete input (`authoring.ts:376`), so this indexed alias can go. Current compile trace/provenance resolving should remain.
   - Explicit rejection in `authoringRecovery.ts:36` and `authoring.ts:376` is not acceptance/compatibility. Keeping a precise unsupported-input error is different from supporting old data.

4. **Unused Core persistence typealiases.**
   - `Core/src/main/java/work/art1st/javarailways/core/physics/train/TrainPersistence.kt:10–12` aliases SavedTrain/SavedCar/SavedBogie to V1 classes.
   - Whole project source search found no usages beyond declarations. They can be removed without renaming current Saved*V1 classes or changing serialized schema versions.

### Hits that should not be blindly removed

- **One-time migration tooling:** `BukkitPlugin/.../persistence/RailInfrastructureMetadataMigration.kt` reads old rail metadata solely for explicit migration. Spec `bukkit-plugin/backend/offline-development-migration.md` deliberately excludes these offline tools from deployed JAR. Their presence satisfies the requested migration policy; it is not runtime fallback support. Deleting tools would reduce recovery capability.
- **Core controller commit:** `Core/.../api/control/TrainController.kt:171` has an API compatibility comment, but method implements current Synchronizable and publishes controller/train state. Removing it requires lifecycle API redesign, not keyword cleanup.
- **Current physical compatibility:** Depot selectors and TimetableUnitCirculation use compatibility for control-family agreement, capacity, traction/braking constraints and actual unit selection. This is physical correctness, not old data support.
- **Current component model adapter:** `BukkitPlugin/.../config/model/ModelTreeBuilder.kt:325` converts configured customModelData integer into current Paper float component. It is a usable resource-pack capability, not proof of an obsolete parser alias.
- **Rail wand helper misleading comment:** `RailWand.kt:106` says canonicalizes legacy settings, but signature only takes current enabled/design-speed/gauge inputs and returns current automatic SuperelevationConfig; no observed manual-cant input reader. Comment can be corrected; don't remove actual current functionality.
- **TunnelBridgeBuilder placementRule:** `BlockWandState.kt:38` defaults to REGULAR. Test calls omission legacy, but this is simply an optional current field with a defined default, no alternate decoder/upcaster. Same caution applies to all default arguments in persisted models.
- **Diagnostics old/current log files:** DiskLogReader rotates/reads both current and old file portions, required live log-history pagination; unrelated to schema compatibility.
- **SimpleSignals YAML aliases/endpoint aliases:** YAML alias rejection and geometric endpoint aliases are current safety/domain terminology, not old API aliases. No specific obsolete decoder identified there in this inventory.

## Related Specs

Read `.trellis/workflow.md`, shared/versioning.md, shared/index.md, simpleato/index.md, webui/frontend/index.md, bukkit-plugin/backend/index.md and `ai_tasks/persistence_schema_evolution_rules.md`.

Policy: direct current-model correction for unreleased development formats; do not increase versions because implementation changes. Strict decoders should reject removed keys. Audit/dry-run/recovery snapshots for necessary deployed development-data conversion. Do not default to silently discarding old authored actions.

## External References

None needed: all conclusions concern checked-out source, not library recommendations.

## Caveats / Not Found

- This is a module coverage inventory plus focused caller tracing, not a line-by-line full-project behavior audit. Unlabelled aliases/fallbacks can still exist.
- No source changes, tests, deployment, or Git operations were performed by this researcher. Root is concurrently changing archive behavior; line numbers may shift.
- Deleting all old arrival input support crosses persisted timetable, worker wire, DSL, runtime continuation, dump/trace and tests. It is substantive but matches the explicit user request; it should be one coherent tested removal rather than claiming archive-only removal completes the whole request.
- The old rows model also exists in SimpleATO saved/runtime service editor metadata. Its runtime consumers must be checked before widening the dead frontend-module removal.
