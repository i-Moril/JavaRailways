# Research: SimpleSignals command authoring entry

- Query: After the reported `9b2c7ac1` batch, identify the smallest useful independent admin/player entry that reuses the existing authoring transaction.
- Scope: internal; bounded production wiring, no implementation or Git operations
- Date: 2026-09-30

## Findings

### Recommendation and current gaps

Use a SimpleSignals-owned `/simplesignals` command with `inspect`, `export`, `validate`, and `apply` over a controlled current-schema JSON draft. This is a real admin authoring workflow: editing the full exported document can create, update, and delete definitions, including related definitions in one atomic patch. It is not completion of guided Wand authoring or model rendering.

Current production has no command/permission declarations and no command/tool registration. `SimpleSignalsPlugin.kt:14-42` only acquires host services, constructs the single runtime manager, registers the resource provider/contributor, and installs the world listener. `SimpleSignals/build.gradle:72` owns the generated descriptor. Register the independent command here rather than adding `/jr` host routes.

The manager's existing authoring entry is `SimpleSignalsWorldRuntime.kt:805` (`applyDefinitionPatch`). It requires a worker thread (`:810`), checks expected global revision, advances it by one (`:816-831`), composes a patch (`:832`), compiles the complete candidate (`:855`), acquires the existing exact affected-rail permit (`:873`), stages the sidecar (`:924`), and commits through atomic source replacement (`:942`). Preserve this as the only mutation path.

### Existing reusable pieces and minimal internal additions

Source root for the following short names: `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/`.

- `runtime/SimpleSignalsWorldRuntime.kt:198`: `currentDefinitions()` returns the immutable current domain document. The manager exposes only a hot-path fixed-block snapshot (`:663`), not a definitions/inspect accessor. Add a manager-owned read snapshot that captures one publication's definitions and topology identity/revision. Do not expose mutable runtime/catalog objects.
- `runtime/SimpleSignalsWorldRuntime.kt:314`: `prepareDefinitions(topology, definitions)` creates fresh catalogs, compiles regions/tables/blocks/signals/indicators, checks separation and retained object revisions, and calculates affected rails (`:430-437`). It does not replace the live publication; publication only happens in `commitPrepared` (`:442`). There is no manager dry-run method yet.
- Add a manager `validateDefinitionPatch` and extract shared revision/patch/candidate-preparation logic from `applyDefinitionPatch`, so validation and apply use the same checks. Validation discards the prepared candidate and returns changes/impact/diagnostics; it takes no permit, stages no file, publishes no source and registers no dependency. Apply repeats its own checks and preparation against current state; validation is not a reservation or token.
- `persistence/SimpleSignalsWorldDefinitions.kt:196-239`: existing strict schema-1 codec rejects wrong world, unknown fields and unsupported versions; reuse it. Add a small draft transport under `<plugin dataFolder>/drafts`, separate from the primary/backup store. Only accept controlled simple names, reject path traversal and symlink escapes, and preserve existing drafts on export collision.
- Convert the full candidate document into one `SimpleSignalsDefinitionPatch` by stable IDs across all six collections. Reject duplicate IDs before indexing; otherwise an `associateBy` conversion could hide invalid input. Upsert changed/new values and remove IDs absent from the candidate. No automatic cascading deletion or revision rewriting.
- Draft `definitionRevision` is the exported baseline: pass it as `expectedDefinitionRevision`; the existing manager alone creates baseline + 1. Retained per-object revisions still follow the existing monotonic checks (`runtime/SimpleSignalsWorldRuntime.kt:483-564`), and region/table reference revisions still need to agree. Do not ask admins to manually advance the global revision.

### Host edit checks and diagnostics

`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/infrastructure/InfrastructureResourceService.java:125-139` exposes `acquireEditPermit`, detached dependency diagnostics and live claims, but no pure edit-preflight API. Do not acquire and immediately close a permit for `validate`, and do not infer an authoritative decision from separate diagnostic lists. Validation proves candidate compilation against the captured revisions; live blockers are evaluated during apply.

Apply already returns `REVISION_REJECTED`, `PATCH_REJECTED`, `VALIDATION_REJECTED`, `EDIT_BLOCKED`, `STALE_TOPOLOGY`, `PERSISTENCE_FAILED`, and `WORLD_UNAVAILABLE` (`runtime/SimpleSignalsWorldRuntime.kt:100-115`). Its `editDecision` retains the host decision (`:898-902`). Present that status, detail, and blocker owner/revision rather than reducing it to a generic failure.

### Command and Folia patterns

- Host `/jr` uses `CloudCommandRegistrar.kt:14-21` and delegates synchronously; this registrar depends directly on `BukkitPlugin` and is not an add-on API. The smallest existing independent-plugin registration pattern is `TunnelBridgeBuilderPlugin.kt:31` plus `TunnelBridgeBuilder/build.gradle:71-77`: generated descriptor, `getCommand(...).setExecutor(...)`, plugin-owned permission. This avoids a new Cloud dependency or host command extension.
- `BukkitPlugin/.../util/BukkitAsyncTasks.kt:25-39` demonstrates worker preparation followed by entity-owned completion, but it is a host implementation package. Do not import it or `PlatformDispatcher` from SimpleSignals.
- A small SimpleSignals-owned scheduling adapter can use the same Paper APIs directly: `server.asyncScheduler.runNow(plugin, ...)` for value/JSON/manager work; player entity scheduler for player messages, including retired/disconnected cleanup; global scheduler for console completion. Capture requested world UUID and immutable arguments before dispatch, and report the originally requested world. Keep Bukkit state access off the worker and avoid blocking futures on owner threads. Add plugin enabled/world-runtime checks around queued work; disable/unload must not revive a runtime or publish late results.

### Rail targeting and Wand seams (later batch)

The host already publishes `RailTargetService` (`BukkitPlugin/.../api/rail/RailTargetService.java:8-21`) and registers it through `api/internal/JavaRailwaysServiceRegistration.kt:26`. It captures a player's eye ray on the player's owner thread and completes asynchronously with immutable `RailTarget(rail, arcLength, distanceToRay)`. No host API expansion is needed for plugin-owned point selection.

The host `RailObjectWandMode` is a fixed enum with only waypoint/direction modes (`BukkitPlugin/.../tool/RailObjectWand.kt:52-58`); `AbstractWand` takes a concrete `BukkitPlugin` and uses host implementation state (`:49-56`). Neither is a public mode-extension seam. Later SimpleSignals interaction should own its own listener/item state and use `RailTargetService`; this command batch needs neither a targeting capability gate nor Wand changes.

### Expected files and verification

Production changes can remain in SimpleSignals: `SimpleSignalsPlugin.kt`, `build.gradle`, `runtime/SimpleSignalsWorldRuntime.kt`; add one command adapter, one small scheduling adapter, one controlled draft transport/diff helper. Keep the existing definition store's save transaction unchanged. Update `SimpleSignals/README.md` with the export/edit/validate/apply workflow and revision semantics.

Meaningful tests: dry-run has zero permit/save/source/dependency calls and preserves publication; validate/apply share rejection semantics; duplicate IDs do not disappear during diff; stale baseline and object revisions reject; combined create/edit/delete applies once; path/symlink/collision restrictions; player/console/permission boundaries and worker/target scheduling. Extend the existing manager test (`SimpleSignalsWorldRuntimeManagerTest.kt:948`, `:1069`, `:1116`) for revision, host conflict and persistence failure preservation. Suggested gate: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar` plus diff check performed by the implementation owner.

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md`: independent plugin, strict codec, only public host imports, single lifecycle/transaction.
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md`: exact old/new plus structural consumer impacts, existing host permit, typed failures.
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`: asynchronous preparation, bounded commit, target-owned replies, existing single unload gate.
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md`: add-on service boundary and Wand implementation ownership.
- `ai_tasks/persistence_schema_evolution_rules.md`: reuse current document shape; no compatibility path, no schema increase for transport-only draft workflow.
- Active `prd.md`, `design.md`, `implement.md`: independent commands/tools, prepare/commit separation; guided editing, rendering, migration and timetable lifecycle remain distinct work.

## External References / Versions

No external lookup needed for this bounded internal recommendation. Local build declares Paper API `1.21.11-R0.1-SNAPSHOT`, Kotlin `2.3.0`, serialization `1.9.0`, and Java 25. Scheduling advice is based on existing in-repo Paper calls, not a claim that newest upstream APIs were verified.

## Caveats / Not Found

- Research-role isolation forbids loading implementation/check manifests; the supplied native active-task header was used, then task artifacts and relevant specs/source were read directly. No Git command was run, so `9b2c7ac1` is parent-supplied provenance rather than independently verified HEAD.
- A corrupted world has no usable local runtime; `applyDefinitionPatch` returns `WORLD_UNAVAILABLE`. This batch must not silently treat it as an empty world or invent recovery/reset. Existing file repair/reinstall remains the documented recovery boundary.
- A no-op document is rejected by the patch transaction before compilation; it is not a topology republish/reload operation.
- The full document draft makes omission a deletion. Make that explicit in help and validation change summaries. Parent confirmed baseline revision semantics; no new DSL or safety policy decision is needed. Exact command spelling, explicit world argument and permission name are routine UI choices.
- No production files, unrelated dirty files, specs, or other task directories were modified.
