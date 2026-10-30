# Research: Current train information in program data

- Query: Implement feedback 17 as read-only current train name, car count, complete registered unit count and operating direction; find the smallest genuinely executable expression design.
- Scope: internal
- Date: 2026-10-28

## Findings

### Existing architecture and boundaries

- `WebUI/frontend/src/types.ts:114-140` defines typed values as literal or parameter references. There is no general expression AST or scalar-string-consuming action.
- `WebUI/frontend/src/programBlocks.ts:6-8` maps action slots. Practical data consumers are SET_DISPLAY.values and EXTENSION.parameters, both JSON_OBJECT. BRANCH.parameters is also JSON_OBJECT but must remain compile-time-only.
- `WebUI/frontend/src/ProgramBlockEditor.tsx:27-29,113-129` builds typed inputs and the existing inspector; add a typed JSON_OBJECT value block plus row editor here, preserving existing Blockly/Ant Design UI.
- `WebUI/frontend/src/stationPrograms.ts:45-62,132-159` validates values and display size; dynamic data must be checked structurally without evaluating a reference vehicle.
- `WebUI/src/main/java/work/art1st/javarailways/webui/StationProgramValidator.java:215-235` accepts only exact single-field literal/reference expressions, including library API and binding validation. A new expression form needs an explicit allowed-context argument, not wholesale acceptance wherever JSON_OBJECT appears.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationPrograms.kt:33-89,103-135` eagerly resolves every expression, emits concrete actions, and folds all conditions from planned context. Runtime data needs preserved model fields, never worker-time evaluation.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationStopPrograms.kt:61-88,101-104` separately compiles shared station definitions and currently accepts literal-only expressions. Both compilers must preserve the same runtime binding contract.

### Smallest useful data contract

Proposed authoring expression: `{trainInfo:{displayKey:"NAME",carCount:"CAR_COUNT",unitCount:"UNIT_COUNT",direction:"DIRECTION"}}`. The map selects output JSON keys and one of four closed-enum properties. This is one typed JSON_OBJECT block, with a concise key/property inspector; no generic object builder, mathematical AST, arbitrary JavaScript, or four disconnected scalar blocks with no usable consumers.

Only permit it in SET_DISPLAY.values and EXTENSION.parameters. Continue rejecting it in BRANCH.parameters, all time/route/stop/identity selectors, and program call arguments unless those arguments deliberately gain a separate runtime contract. Existing literals remain arbitrary literals, including objects containing a `trainInfo` key; never interpret markers nested in plain literal JSON.

Saved/runtime model should use an explicit immutable binding map alongside its existing concrete JSON object, default empty. Dynamic source maps may compile to empty concrete object plus the binding map; static arguments continue producing concrete objects. Choose a single ownership location for extension bindings (e.g. call/action model), preserve it across both worker and SimpleATO DTOs, freeze it on installation and include it in equality/hash, dumper and capture/restore. Binding output keys must be nonblank, bounded, unique, and use the existing object/display limits; if later mixed constants are supported, define collisions instead of silently overwriting.

### Runtime sources and exact semantics

- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/Train.kt:21-34`: ID is stable identity; name is player-assigned world-unique name. `isReversed` means opposite to stable consist order, NOT line UP/DOWN, cardinal orientation, or signed speed.
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainFrameSnapshot.kt:102-112` already publishes `trainName`, `reversed`, `cars` together; `CarFrameSnapshot.carId` is at line 12. Use the current train's publishedFrame once when the instruction starts, and validate matching train identity. Missing frame is a runtime failure, not fabricated data.
- NAME -> frame.trainName; CAR_COUNT -> frame.cars.size; DIRECTION -> FORWARD/REVERSED relative to stable consist order, documented in UI. This is independent of timetable direction.
- UNIT_COUNT -> depot complete-unit resolver over that frame's car IDs, only if requested. `SimpleATO/.../api/depot/Depot.kt:412-414,514-530` verifies every car belongs to a complete contiguous registered unit, accepts whole-unit reversal and rejects partial/unregistered membership; it also rejects incompatible control families. Do not return zero or count cars on failure. Other three properties must work on trains without registered units.
- Read at execution of each individual instruction, including after reversal or structural handoff, not at program compilation or whole-program invocation. Do not use stale `run.lastPublishedAtoState` for membership/name/direction after a split.
- `SimpleATO/.../timetable/TimetableDispatcher.kt:4024-4092` handles station instruction starts; SET_DISPLAY at 4053 and EXTENSION at 4063. Resolve data before provider start / display publication, once per ticket start.
- The same dispatcher duty path uses SET_DISPLAY at 4211-4221 and EXTENSION at 4589-4606. Share one resolver for both paths, backed by the actual run train, with existing error/stall handling. Async retries must not resample/restart an accepted provider ticket.

### Extension validation caveat

`SimpleATO/.../timetable/Timetable.kt:938-954` calls provider.validate(call.parameters) during validation. A dynamic object cannot pass a provider schema with required fields unless the lifecycle changes explicitly. Preserve immediate static validation when bindings are empty; for dynamic bindings validate binding structure/provider existence at compile time, then provider.validate(resolvedObject) before start at runtime. Reject validation errors through existing runtime failure handling, and never call start on invalid resolved data. Do not insert fake placeholders just to satisfy static validation.

### Exact affected file groups

- Frontend: types.ts, stationPrograms.ts, programBlocks.ts, ProgramBlockEditor.tsx, i18n, their tests.
- WebUI server: StationProgramValidator.java and its tests, repository save/restart round-trip coverage.
- Worker: StationPrograms.kt, StationStopPrograms.kt, Model.kt, StationStopProgramModel.kt; extension call DTO if separate; compiler tests.
- SimpleATO: Timetable.kt, StationStopPrograms.kt, TimetableDispatcher.kt; extension contract location; persistence/TimetableSnapshot.kt and StationStopProgramSnapshot.kt; TimetableDumper.kt and immutable/freeze consumers of any extended models.
- Simulation: real isolated simulation reuses dispatcher; ensure bindings survive capture/clone and read simulated actual train, not authoring reference/profile data.

### Necessary focused acceptance

1. Blockly roundtrip, allowed two sinks, library binding, shared station zero-parameter context, invalid field/property/key rejection; preserve literal objects with identical-looking keys.
2. Compiler retains the enum binding map with no concrete train snapshot or condition folding; dynamic branch/time/route arguments remain invalid.
3. Both actual station and duty paths produce the same current name/count/direction, including after actual reversal.
4. Real split/recipient handoff reports new membership/name from the acquired train; unequal unit lengths distinguish unit count from car count.
5. Unregistered train supports NAME/CAR_COUNT/DIRECTION; requested UNIT_COUNT fails explicitly. No all-properties eager lookup.
6. Extension provider sees actual typed numbers/strings, validates before start once, and polling does not resample data. Invalid resolved payload never reaches start.
7. Saved worker JSON -> SimpleATO restore/capture -> isolated simulation and restarted authoring repository retains exact data binding. Keep development format version 1.

### Related specs and references

- `.trellis/spec/webui/frontend/program-blocks.md`: typed Blockly values, context boundaries, static planned conditions, real usable instruction tree.
- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md`: owner, successful-publication sequencing, ticket lifecycle, fail-closed behavior.
- `.trellis/spec/javarailways/simpleato/timetable-model-and-validation.md`: immutable installation and validated execution authority.
- `.trellis/spec/javarailways/shared/versioning.md`: unreleased development format stays at existing baseline; no speculative migration or compatibility readers.
- Prior memory quick-pass confirmed historical station entry applicability only; current source/spec was used for every implementation finding above. Memory reference: MEMORY.md:108-109, rollout thread 01a169fd-2c07-7513-856b-1ffaf5f32f80.
- No external reference was needed; no external library or documentation lookup performed.

## Caveats / Not Found

- This is research only. No code, tests, commit, deployment, or runtime acceptance was performed.
- No existing generic runtime expression engine was found; adding a broad AST would exceed this batch.
- The complete-unit resolver enforces a homogeneous control family beyond simple counting; use its existing strict registered-formation meaning and document the error, or explicitly isolate a different counting requirement before changing depot semantics.
- Dynamic EXTENSION payloads defer content validation until execution; this must remain explicit in checks/UI. A compile success cannot guarantee runtime provider acceptance.
