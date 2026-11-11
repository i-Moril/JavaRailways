# Research: Remaining vehicle-model information and original feedback 10

- Query: Whether the original feedback 17 vehicle-model request can be completed honestly from current authorities, particularly after mixed formation changes; assess feedback 10 without hiding configuration.
- Scope: internal
- Date: 2026-10-28

## Findings

### Existing four read-only fields

`SimpleATO/.../timetable/TrainInfo.kt:8-27` implements NAME, CAR_COUNT, UNIT_COUNT, DIRECTION from current published train state and the complete-unit registry. There is no MODEL field. These four were the explicitly approved narrow batch. The broader original feedback mentioned vehicle model but did not settle its meaning or cardinality for mixed formations.

### Distinct existing meanings of “model”

1. **Registered aggregate model package:** `SimpleATO/.../api/depot/Depot.kt:53-72` RollingStockRecord has nullable modelPackageId keyed by trainId. Command registration copies `train.rendererOrNull.modelPackageName()` at BukkitPlugin JavaRailwaysCommand.kt:658-680. This is declared depot classification, not a fresh physical per-car model observation.
2. **Fixed unit control family:** FixedEmuUnit.kt:7-21 has id, homeDepotId, carIds and controlCompatibilityKey. It has no model/package/template/display-name field. Its controlCompatibilityKey must not be relabelled as vehicle model.
3. **Creation template:** BukkitPlugin extension/train/TrainExtension.kt:121-145 exposes modelPackageName via savedPackageName. CarExtension.kt:69,118-126 stores resourceType, assigned from the creation `type`. This is the creation template root and can differ from the source package of imported car models.
4. **Per-car render model:** CarExtension.kt:61-62 has carModelName, e.g. `s100/passenger`; per-car save at lines107-115 preserves it and resourceType. A train may legitimately contain different such IDs. This render-model ID is not a human-readable vehicle family/type label.

### Mixed formation loss in aggregate classification

FormationOutputMetadata.kt:43-65 combines stock classification and sets modelPackageId only if all source values are identical; mixed sources get null. Splitting at lines70-84 preserves the aggregate source modelPackageId. Thus a mixed aggregate split remains null even if one output happens to consist of an original homogeneous unit. No per-unit model lineage is retained in the depot registry. It cannot reconstruct a complete-unit车型列表 from that authority.

An honest tiny addition would be a specifically named nullable **登记模型包ID**, with unknown/mixed semantics. It would not satisfy a general physical vehicle-model or per-unit type request, and should not be advertised as such. Avoid empty-string/first-member/control-family fallbacks.

Per-car model/template IDs could be accurately exposed by a new platform metadata gateway keyed by actual current frame car IDs, because physical structure operations preserve each car and its renderer extension. That needs explicit immutable publication and isolated-simulation cloning. Core Car/TrainFrameSnapshot deliberately has no renderer metadata, and SimpleATO must not depend on Bukkit CarExtension. This is a new data boundary, not just adding an enum field. No existing platform-independent per-unit human-readable model authority was found.

### Recommended disposition of original feedback 17

Do not select “add车型” as an automatic small follow-up while silently choosing one of the four meanings. The approved four fields are complete. A future model-information batch needs a concrete meaning: registered aggregate package versus current per-car template/render ID versus a new declared unit-level vehicle type. Preserve static plan-condition limits in all cases.

### Original feedback 10: smallest proper replacement

Current DutyManagement.tsx:56 still mounts DutyGraphEditor under Advanced. DutyGraphEditor.tsx:35-41 actively edits operational-trip identity, default display data, run segments and selected whole-unit slots. This is the remaining original item, but the backing operationalTrips model is not merely presentation.

Runtime TimetableRunMetadata.kt:128-147,232-281,410-446 uses it for physical unit continuity/future destination and display lifecycle. SET_DISPLAY programs replace explicit display mutation, but do not automatically replace that cross-run grouping and lifecycle proof.

A proper removal batch would require one of these explicit contracts:

- Keep grouping metadata as a supported concept and provide another real editing owner before deleting the independent old panel; that would need a new UI agreement, rather than pretending the existing display action already edits it.
- Retire operational-trip grouping from structured authoring as obsolete scope, deliberately remove associated authoring model/validation/generation and document/development-data migration, while deciding separately whether direct runtime/DSL APIs remain. This removes functionality and needs a clear acceptance contract for existing display behavior.

Neither simply hiding the advanced collapse nor silently retaining hidden saved configuration is a complete fix. No new implementation is recommended until that semantic choice is made.

### Files and related specs

- `SimpleATO/.../api/depot/FixedEmuUnit.kt`: complete-unit identity and control family, no model metadata.
- `SimpleATO/.../api/depot/Depot.kt`: train-level declared model package.
- `SimpleATO/.../timetable/FormationOutputMetadata.kt`: aggregate classification algebra and information loss on mixed formation.
- `BukkitPlugin/.../extension/train/{TrainExtension,CarExtension}.kt`: actual platform template/render identifiers and persistence.
- `BukkitPlugin/.../command/JavaRailwaysCommand.kt`: depot-registration producer for modelPackageId.
- `.trellis/spec/javarailways/simpleato/through-running-and-unit-displays.md`: operational-trip continuity ownership.
- `.trellis/spec/javarailways/simpleato/{dispatcher-runtime,simulation}.md`: current runtime state and equivalent isolated-simulation behavior.
- `.trellis/spec/javarailways/shared/versioning.md`: adding persisted metadata needs publication-baseline discipline, not speculative compatibility layers.

## Caveats / Not Found

- Read-only source research; no tests, source edits, commits or deployment.
- No existing authoritative human-readable vehicle type per complete fixed unit was found.
- No external documents or library-version information needed.
