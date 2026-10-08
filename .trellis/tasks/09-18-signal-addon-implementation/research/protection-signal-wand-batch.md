# Research: protection-signal Signal Wand batch

- Query: Extend the existing Signal Wand from fixed-block primaries to `CURRENT_ENTRY` and `FIXED_ROUTE` protection primaries, excluding attached-signal placement and route indicators.
- Scope: internal
- Date: 2026-10-08

## Findings

### Current authored and compiled data

- `SimpleSignalsWorldDefinitions` is the authoritative authored document. It owns regions, route tables, fixed-block signals, protection signals, route indicators, attached signals, and pending boundaries (`SimpleSignalsWorldDefinitions.kt:30-64`). A protection boundary has a stable `boundaryId`, canonical rail position, and inward-facing direction (`ProtectionRegionDefinition.kt:7-20`); `effectiveBoundaries` is the draft while a region is being repaired, otherwise the accepted boundary list (`ProtectionRegionDefinition.kt:22-49`).
- The immutable live publication exposes the exact topology and definitions plus `regions`, `routeTables`, `routeSelection`, and `protectionSignals` (`SimpleSignalsWorldRuntime.kt:175-203`). The region compilation snapshot supplies `regionsById` and a rail-to-region reverse index (`ProtectionRegionCatalog.kt:29-53`). The index is built from resolved interior rails, so a click resolver should first narrow by `regionIdsByRail`, then inspect that region's `effectiveBoundaries`; this is the same low-frequency pattern used by route-preview selection (`CurrentRoutePreview.kt:58-76`).
- Authored routes are `definitions.routeTables.flatMap { it.routes }`. Each route carries globally stable `routeId`, `entryBoundaryId`, `exitBoundaryId`, and a complete traversal list (`ProtectionRouteTableDefinition.kt:28-53`). Publication rejects duplicate table-per-region and duplicate route IDs (`ProtectionRouteTableCatalog.kt:144-172`).
- Currently usable routes are `publication.routeSelection.routesById`; it is compiled only from `candidateTables.usableDefinitions()` (`SimpleSignalsWorldRuntime.kt:565-580`). `ProtectionRouteTableCatalog.usableDefinitions()` intentionally differs from the authored document: authored tables and invalid old routes remain in the document while only revalidated geometry is usable (`ProtectionRouteTableCatalog.kt:67-78,164-203`). Therefore routes for one entry can be listed once from authored definitions and marked usable by membership in `routeSelection.routesById`.
- `ProtectionSignalDefinition` already represents both requested kinds. `CURRENT_ENTRY.bindingId` is the entry boundary ID; `FIXED_ROUTE.bindingId` is the route ID. It also owns revision, zero-or-positive inspection lead time, `signalType`, and optional placement (`ProtectionSignalDefinition.kt:7-28`). The compiler deliberately accepts a binding that exists only in authored routes/entries while producing an empty usable route list, allowing broken-region configuration to survive fail-closed (`ProtectionSignalCompiler.kt:41-66`).

### Directed entry resolution and route selection

- No protection-specific Signal Wand resolver exists. The reusable algorithm is the route-preview resolver: use `regionIdsByRail`, map to resolved regions, inspect `effectiveBoundaries`, require exactly one match within `0.75 m`, and never snap across a rail joint (`CurrentRoutePreview.kt:61-76`). Signal placement must add the check omitted by route preview: the clicked/look direction must equal `boundary.insideFacing`, because this operation selects a directed entrance rather than either end of a preview.
- `samePoint` compares rail UUID plus canonical AB distance (`SectionTopology.kt:34-35`), and `SectionAuthoringActions.SNAP_METERS` is the existing `0.75 m` authoring tolerance (`SectionAuthoringActions.kt:20-35`). Section Wand warning lookup already combines the region rail reverse index with the same tolerance (`SectionWandBackend.kt:68-76`). A new small pure `ProtectionSignalEntranceResolver` should reuse these values/patterns instead of running graph resolution or scanning all regions.
- For `CURRENT_ENTRY`, a unique directed boundary is the logical binding. For `FIXED_ROUTE`, after resolving that entry, present its authored routes sorted by stable route ID (prefer also showing exit boundary ID) and bind the selected route. The PRD already fixes this two-step interaction and forbids manual ID entry (`prd.md:175-179`).
- Temporarily unavailable authored routes should be visible as unavailable so retained configuration is understandable, but should not be selectable for a **new or rebound** Wand device. The current compiler's authored-only acceptance exists to retain old configuration during repair; it also skips placement validation when no usable candidate exists (`ProtectionSignalCompiler.kt:51-64`). Allowing a new Wand binding to such a route would turn a retention path into a new authoring path. Updating an existing device may retain its unchanged authored binding, and deletion must remain possible. This is a conservative implementation recommendation; no current contract explicitly states the disabled-choice UI.

### Multiplicity, identity, update, and deletion

- Protection-signal IDs must be unique and must not collide with fixed-block primary IDs; indicator and attached-device validation completes uniqueness across all four device namespaces (`ProtectionSignalCompiler.kt:41-45,94-98`; `SimpleSignalsWorldRuntime.kt:595-607`). The fixed-block Wand already checks all four categories before submitting a generated ID (`SignalWandBackend.kt:175-185`); extract/reuse that collision check for protection creation.
- There is intentionally no uniqueness constraint on `(kind, bindingId)`. Compilation builds one entry per `signalId`, and the owner contract explicitly allows multiple devices on one directed entry (`ProtectionSignalCompiler.kt:41-66`; `protection-region-signal-selection.md:147-164`). Multiple `CURRENT_ENTRY` primaries may therefore share an entry, and multiple `FIXED_ROUTE` primaries may share a route. Attached displays are not the only multiplicity mechanism in the current model.
- Consequently a protection Wand cannot safely infer update/delete identity from entry or route alone. A binding used for update/delete needs the exact existing `signalId`; creation needs an explicit “new signal” choice. If more than one existing primary matches, the UI must list the candidates (or reject with a clear chooser requirement), never use collection order. Enforcing one primary per binding would be a new product/schema rule and would contradict the current multiplicity contract.
- Deleting a selected protection primary must submit `removeProtectionSignalIds = [exact signalId]` and, in the same patch, every attached signal whose `primarySignalId` equals it. The fixed-block implementation is directly reusable (`SignalWandBackend.kt:136-172`), and the manager already rejects orphaned attached references (`SimpleSignalsWorldRuntime.kt:595-607`). Other primaries on the same binding and their attachments must remain.

### Minimal item state and continuation changes

- Current item state is `(itemId, stateRevision, mode, signalType, fixed-block binding?)`; the persisted binding is flat `(worldId, blockId, entryDirection)` (`SignalWandHandler.kt:407-430,513-555`). It cannot encode protection kind plus entry/route without unsafe nullable-field combinations.
- Replace the binding with a tagged/sealed union, for example:
  - `FixedBlock(worldId, blockId, entryDirection, existingSignalId?)`
  - `ProtectionCurrentEntry(worldId, entryBoundaryId, existingSignalId?)`
  - `ProtectionFixedRoute(worldId, entryBoundaryId, routeId, existingSignalId?)`
  `existingSignalId == null` means an explicit new-device choice. Including `entryBoundaryId` in the fixed-route case makes status text useful and lets right-click revalidate that the authored route still belongs to the selected entry.
- Add a device/control-kind setting distinct from `signalType`: fixed block, current entry, or fixed route. Changing mode or control kind clears the binding; changing `signalType` may retain it. The settings callback must keep the existing exact `itemId + stateRevision`, catalog, permission, and owner-thread checks (`SignalWandHandler.kt:133-195`). The item state is explicitly unpublished and current policy says to correct it directly without an upcaster; old tool state should fail closed (`section-authoring-wands.md:43-49`).
- Binding/route/device chooser callbacks must be item-owned continuations: capture the complete opening state, return to the player owner, compare exact current state, increment with `Math.addExact`, then replace the main-hand state. The existing type/mode callback and bind completion already implement this pattern (`SignalWandHandler.kt:153-195,239-262`). Backend captures must retain exact catalog, runtime, publication, and topology identities (`SignalWandBackend.kt:46-74`), and right-click/delete must revalidate the selected boundary, route, existing signal revision, and usability before submitting the one normal manager patch.

### Suggested reviewable implementation boundary

- Production files: `authoring/SignalWandBackend.kt`, `authoring/SignalWandHandler.kt`, plus a pure protection-entry resolver (new file or a narrowly named object beside the fixed-block resolver). Do not change persistence/world schema, JS evaluation, route recording, attached-signal authoring, route indicators, or model-presentation lifecycle.
- Tests: extend `SignalWandHandlerTest.kt` for tagged binding round-trip, device-kind settings, chooser staleness, exact-item revision, action bar/lore, and multiple candidates. Extend `SimpleSignalsWorldRuntimeManagerTest.kt` (or a focused resolver/backend test) for both kinds: new/update/delete, all-four-kind generated-ID collision, authored-but-unavailable route rejection for new binding, retained existing unavailable binding deletion, exact cascade, stale publication/topology, edit denial, and unchanged disk/publication/source on failure.
- Reuse the existing manager transaction; it already compiles protection signals, checks revisions and references, acquires the edit permit, stages persistence, and atomically publishes (`SimpleSignalsWorldRuntime.kt:595-657`). No second protection-device store or direct model mutation is needed.

## Related specs

- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`
- `.trellis/spec/simple-signals/backend/protection-region-signal-selection.md`
- `.trellis/spec/simple-signals/backend/current-route-recording.md`
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md`
- `.trellis/tasks/09-18-signal-addon-implementation/prd.md`
- `.trellis/tasks/09-18-signal-addon-implementation/design.md`

## Caveats / Not Found

- The current contracts allow multiple protection primaries on one logical binding but do not define the exact chooser presentation or labels for selecting one existing primary versus creating another. Full update/delete support therefore needs one small UX decision. Recommended default: after selecting the directed boundary, show a player-owned dialog with “new” and existing device entries; for fixed-route creation, nest/group recorded routes by exit and mark unavailable routes disabled.
- Whether a player may deliberately create/rebind to a temporarily unavailable authored route is not explicitly frozen. The safe recommendation above is “show but disable for new/rebind; retain/update-in-place/delete existing configuration.” If the product wants offline preconfiguration of broken regions, that policy must be explicitly approved and placement validation must not be skipped.
- `protection-region-signal-selection.md:178` says fixed-block and protection definitions may use the same author ID, but production compilation rejects that collision (`ProtectionSignalCompiler.kt:41-45`). The Wand must follow production behavior and check a shared device namespace. The owner spec should be corrected in the later update-spec phase.
- Live Folia dialogs, glint, client model rendering, and unload behavior remain deployment verification, not established by this batch's offline tests.
