# Research: Stage 2 authoring and presentation closeout inventory

- Query: Distinguish genuine remaining approved authoring work from stale checklist entries after Core closeout; identify a small implementation batch without inventing player policy.
- Scope: internal; SimpleSignals authoring/presentation only, not a module audit
- Date: 2026-10-14

本文件记录本批实施前的源码盘点，不是持续更新的能力清单。命名轨道预设随后接入Signal Wand；当前行为见[工具契约](../../../spec/simple-signals/backend/section-authoring-wands.md)，验证与剩余项见父任务`implement.md`。

## Findings

### Already implemented; do not reopen as missing

- Signal-device ground placement is real production code, not a placeholder. `authoring/SignalWandHandler.kt:144` captures a block-face pose on the player owner; `:338` passes it directly to the existing placement transaction without a rail-target lookup. `:653` computes the pose. Tests `SignalWandHandlerTest.kt:248,269` distinguish ground/rail and settings priority. This is different from boundary ground markers, which remain absent.
- Three primary signal kinds and attached signals support new/exact existing device selection, rail/ground placement and deletion. Existing route indicators support model/placement changes and exact deletion, preserving their complete mappings (`authoring/SignalWandBackend.kt:142-242,248-296`). These must not be classified as wholly missing “guided Wand”.
- Current route recording is mouse-driven and replaces the selected entry–exit path while retaining its identity. `RoutePreviewWandHandler.kt:72-124` records the current visible preview on a worker. Its route selection/render loop starts at `:133`. The authoritative `current-route-recording.md` describes the current behavior, not the old removed recording command.
- Route connectivity, directional arrows and multi-route entrance/exit warnings already have particle drawing (`authoring/RoutePreviewDrawing.kt:15-120`). This is not the promised complete editor overlay, but neither is visualization entirely absent.

### Genuine remaining work and policy boundaries

1. **Boundary ground submodel and full overlay**: PRD `:168,281` requires configurable boundary ground submodels. The existing `SectionWandBackend` only constructs section edits; no boundary model configuration/lifecycle exists. The common visual spec (`.trellis/spec/bukkit-plugin/backend/editor-markers.md`) explicitly leaves concrete speed colors, resource-pack graphics and full TextDisplay styling unfrozen. Do not manufacture a model or a default variant, and do not confuse signal-machine ground placement with this requirement. Ground-marker configuration scope/persistence must be designed before implementing it. Complete overlays additionally need an agreed concrete visual treatment, not a speculative shared object registry.
2. **Independent measurement**: PRD `:210-212,312` explicitly requires straight-line and along-track distance, and explicitly says branch path choice must be discussed. No measurement handler or command exists in SimpleSignals source. A “current turnout path” or “shortest path” choice cannot be silently selected. Existing `RailTargetService`, immutable curves and authoring worker/owner capture patterns can be reused after that player-workflow decision; no resource claims or infrastructure writes belong in it.
3. **Placement defaults/presets and instance editing**: numerical value support already exists. `presentation/ModelPlacement.kt:13-27` exposes `RailAttached.applyPreset(preset, playerFollowRailTilt)` and copies independent placement values; `LoadedModelPackage.kt:17-40` holds immutable named rail presets. `ModelPlacementTest.kt` tests independent instances, preset removal/change and explicit tilt precedence. The Wand settings currently expose only mode/control/type/model (`SignalWandHandler.kt:151-235,914-924`); PDC `SignalWandState` at `:681` has no placement preference. Existing placement updates preserve logical bindings but replace placement using the current click. Thus preset/default/instance **UI** remains genuinely absent despite backend arithmetic being complete. Arbitrary angle-editing fields are explicitly not frozen in the model contract; a named-preset selector does not need to settle them.
4. **Route deletion UI**: `current-route-recording.md` explicitly excludes it. There is no route-delete mouse action or command. The manager already supports route-table replacement/removal and the export/validate/apply draft path can delete data (`command/SimpleSignalsAdminCommand.kt:72-103`). This means backend CRUD exists, not that player-facing route deletion is implemented. Before adding a destructive UX, confirm whether removing one route retains its fixed-route signal/indicator mapping as unavailable or asks for explicit cascade; do not invent silent device deletion. Current completeness checks may reject orphan route references, so verify the actual candidate contract rather than bypassing it.
5. **New route indicator mapping UI**: `SignalWandBackend.kt:340-362` deliberately requires an existing indicator; an empty entrance explains that creation needs an aspect mapping. Current model catalogs contain variants, but no player-authored source maps the no-route state and every route to variants. Existing indicator updates preserve `noRouteModelVariant` and `modelVariantsByRouteId` (`:212-222`). A mapping page must explicitly select those values; no `default`, route-ID inference or single repeated variant is approved. This is a real UI gap, not a missing rendering engine.
6. **Hot reload**: absent, but do not promote it to an unconditional Stage 2 blocker without an explicit requirement. `SimpleSignalsWorldRuntime.kt:960,1077` documents enable-time-only loading and rejects a second prepared catalog; `SimpleSignalsPlugin.kt` invokes those once at startup. Historical “hot reload outside this follow-up” statements do not constitute an approved complete reload contract. World unload/reload lifecycle is a separate implemented concern. A future reload must reconcile JS contexts, existing devices, catalog identities and failure atomicity; it is not a one-line command that reuses load twice.

### Recommended next bounded implementation batch

Expose **named rail-placement presets in the existing Signal Wand settings**, copying a selected preset into the Wand's future-placement preference and applying that copied value only on an explicit placement/update operation. This implements an approved part of the actual UI requirement; it is not a smoke shortcut, and does not claim that all style editing is complete.

- Reuse `SignalWandHandler.openSettings/selectSetting`, exact `itemId/stateRevision`, owner continuations and the existing strict PDC state. Add no new global settings store or another editor framework.
- Extend the minimal immutable catalog projection with named preset values for the relevant selected model: primary models come from the signal-type catalog; attached/indicator models come from the separate model-only catalog. Today `SignalTypeCatalogSnapshot` exposes type/displayName and `ModelTypeCatalogSnapshot` only IDs; avoid leaking the renderer's mutable assets into UI.
- Reuse `RailAttached.applyPreset`, `ModelPlacementCalculator` and the normal manager patch. Ground placement stays unchanged; rail-only presets must not be silently interpreted as absolute ground poses.
- Do not automatically restyle existing devices when the Wand preference changes. A selected existing device changes only when the user explicitly places/updates it; logical block/entry/route/primary bindings remain exact. Preserve existing mappings and lead time. Missing presets must fail selection/reapplication, not invalidate stored independent instance values.
- Keep angular free-form instance editing, measurement path choice, boundary model selection/storage, route deletion policy and indicator mapping design out of this batch. Do not label those as completed by preset selection.
- Current world/PDC formats are unpublished; obey the versioning gate, add no version increment or old-development compatibility reader just for the added preference. Authoritative world placement values already contain the copied adjustment and tilt.

### Validation seams for that batch

- `authoring/SignalWandHandlerTest.kt`: sorted/current preset options; type/model change behavior; selection changes only Wand state; exact revision and stale callback rejection; ground unaffected; copied values survive preset source changes.
- `presentation/ModelPlacementTest.kt`: retain existing independent-copy/tilt tests instead of duplicating numerical tests in the handler.
- Real backend/manager tests in `runtime/SimpleSignalsWorldRuntimeManagerTest.kt`: the resulting persisted placement matches the copied preset; only the selected device is revised; logical binding, route-indicator mappings, existing JS output and unrelated devices remain unchanged; existing permit/candidate/persistence failure atomicity still holds.
- Existing package loader tests already validate presets. Full `:SimpleSignals:check`, Java signal API consumer compilation and plugin isolation gate remain appropriate; no tests or builds were run for this research, and no live Paper/Folia/client result is claimed.

## Related specs and source locations

- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`: current interactions and explicitly missing tools/UI.
- `.trellis/spec/simple-signals/backend/current-route-recording.md`: exact mouse recording and route deletion exclusion.
- `.trellis/spec/simple-signals/backend/model-presentation.md`: copied preset semantics, placement calculation and instance lifecycle.
- `.trellis/spec/simple-signals/backend/model-packages.md`: existing model presets and variant declaration.
- `.trellis/spec/bukkit-plugin/backend/editor-markers.md`: common visual vocabulary and intentionally unfrozen concrete visuals.
- `.trellis/spec/javarailways/shared/versioning.md`: unpublished format changes do not increment schema versions.
- Main source paths above are relative to `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/`; test paths are relative to the corresponding `src/test/kotlin/.../simplesignals/` root.

## External references

None needed: inventory of current repository contracts and production call paths only.

## Caveats / Not Found

- This is not a Basic/Core/full SimpleSignals audit, performance benchmark, test run, dependency review or deployment assessment.
- The top task summary is dated 2026-10-09; historical batch exclusions and unchecked high-level acceptance boxes are not reliable feature counters. In particular, some historical paragraphs still say ground placement or protection/attached tools are outside scope although later batches implemented them.
- No code, specs, models, build configuration, task checklist, Git state or server data was changed. Only this research file is owned by this investigation.
