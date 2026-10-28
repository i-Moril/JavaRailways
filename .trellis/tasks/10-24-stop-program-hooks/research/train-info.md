# Original feedback17: readonly train information (2026-10-28)

User approved this batch. Continue existing task; preserve previous uncommitted duty formation work. No commit/deploy/archive. Prior dirty baseline saved at /tmp/jr-before-train-info.

## Frozen contract

Authoring StationProgramValue JSON_OBJECT slots add one explicit alternative: `{trainInfo:{outputKey: field,...}}`. Fields are exactly NAME, CAR_COUNT, UNIT_COUNT, DIRECTION. One to64 distinct nonblank keys, max256 UTF16 chars/key. Only SET_DISPLAY.values and EXTENSION.parameters accept this alternative. Conditions, routing, waits, selection, selectors and other expressions remain literal/declared-parameter according to existing context. No runtime arbitrary JavaScript, generic AST, embedded marker interpretation or new parameter types. Existing literal object JSON remains literal even if its content resembles this syntax.

Blockly adds a JSON_OBJECT value block 'Read current train information'. Its inspector edits key/property rows; defaults four rows name/carCount/unitCount/direction. Values can connect only to permitted action inputs; even if connected elsewhere, semantic validation must fail, preserving invalid workspace draft. Library, shared arrival/preparation all support the same data source. Existing ownership/history/recovery/content identities retained. Plain direction refers to actual canonical formation direction FORWARD or REVERSED, never UP/DOWN or geographic direction; show an explanation. Complete unit count means registered indivisible units, not car count.

Compiler carries explicit bindings into saved action DTO `trainInfo: Map<String, SavedTrainInfoField> = emptyMap()` (worker and SimpleATO mirror enum SavedTrainInfoField). Runtime enums TrainInfoField with same4 names. TimetableAction.SetDisplay, TimetableAction.Extension, StationProgramAction.SetDisplay, StationProgramAction.Extension gain explicit immutable `trainInfo` binding map (default empty, appended constructor arg). Literal values/extension call.parameters are empty for bound source. Preserve current unreleased schema version; additive default field, no compatibility path. Bindings are on actions, not TimetableExtensionCall/conditions; therefore no dynamic condition leak. Restore/capture full parity.

Dispatcher evaluates requested keys once at instruction start against current bound train's published TrainFrameSnapshot, NAME=trainName, CAR_COUNT=cars.size, DIRECTION=reversed?REVERSED:FORWARD. UNIT_COUNT queries depot fixedUnitsForFormation with that snapshot's carIds, fails honestly if unregistered/partial. Do not require registered units when UNIT_COUNT absent. No caching compile-time/simulation reference/previous output values; after reverse, split or handoff read actual current train. Common runtime resolver used by both duty and stop actions. SET_DISPLAY retains normal display validation and units selection; EXTENSION provider existence remains validated, literal params retain compile-time validation; explicit dynamic bindings defer parameter validation until resolved runtime then validate before provider.start. Errors enter existing failed/recovery path, never return fabricated zeros or success.

## Ownership

Runtime: all SimpleATO changes/model/persistence/dispatcher/validation/tests. Compiler/boundary: Bukkit scriptworker DTO+expansion+station compiler and tests; WebUI Java validator+HTTP/restart/library tests. UI: frontend types/block conversion/editor/inspector/validator/i18n/tests and actualbrowserQA. Root: docs/spec/backlog, test serialization and integration review. Workers must not touch each other's owned files.

## Gates

Frontend full tests/build, scoped and full affected backend/runtime tests, snapshot+worker roundtrip, literals unchanged, all4 actualruntimevalues before/after reversal and bound train handoff, unknown field/illegalposition/malformed bindings rejected at all boundaries. Browser real block creation/connection/inspector/history/recovery and invalid context, no fake backend success. No live game acceptance inferred.

## Validation evidence

- Frontend276 tests, TypeScript and production Vite build passed. Scoped reviewer independently ran7 trainInfo tests and TypeScript compile; no remaining scoped findings.
- Actual ProgramBlockEditor+Blockly browser fixture: native source-mode creation connects display and extension inputs; compact map inspector visually inspected; duplicate key blocks export and leaves accepted definition unchanged; Undo/Redo and reload keep invalid raw rows, correction restores validity; whitespace keys preserved, arrival context same four fields, Chinese remount retains data source. Forced actual Blockly JSON_OBJECT connection into BRANCH.parameters is rejected by semantic validation and invalid workspace persists. No console errors. No fake live backend save/execution. Screenshot `/tmp/jr-train-info-ui-block.png`; dedicated session/port18904/temp source cleaned.
- Initial full SimpleATO run641 tests had2 new fixture failures (missing declared home depot, inserted extension before acquisition and wrong post-acquisition tick). Fixtures corrected, production validation retained. Targeted actual formation+station dispatcher63 tests passed after correction, including real reversal and both physical split output/source+receiver observations. Earlier duplicate constructor argument fixed before tests.
- Final gate passed exit0: SimpleATO641/WebUI44/BukkitPlugin645 tests, zero failures/errors/skips; compileSignalApiConsumerJava passed. Core524/Basic435 were up-to-date in the first integration invocation. Command: JDK25 ./gradlew :SimpleATO:test :WebUI:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava --offline --max-workers=1 -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g" -Pkotlin.compiler.execution.strategy=in-process -I /tmp/station-gradle-final.init.gradle -x :WebUI:buildFrontend -x :WebUI:installFrontend. Existing compiler/JDK deprecation warnings remain. git diff --check passed.

No live game acceptance, commit, deployment or archive. Prior uncommitted duty formation work retained.

## Split commit request

User requested separate local commits before continuing. Previous formation batch committed5be9d190; this train-info batch is staged independently. Independent staged frontend tests/build and index-vs-working-source audit supplement the full runtime/backend gates above. No push/deploy/archive.
