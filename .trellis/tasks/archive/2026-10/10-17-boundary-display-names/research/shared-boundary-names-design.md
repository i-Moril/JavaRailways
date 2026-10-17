# Research: Shared physical boundary name metadata

- Query: Smallest persistence and identity design covering block endpoints, accepted/draft/pending protection boundaries, one name shared by adjacent owners, without changing geometry equality or region revisions.
- Scope: internal
- Date: 2026-10-17

## Findings

### Definite recommendation

Store display metadata separately at the top level of `SimpleSignalsWorldDefinitions`: an immutable `boundaryNames: List<BoundaryName>` defaulting to empty, each entry containing a raw authored `RailCutPosition` and normalized nonempty plain-text name. Persist as flat `{railId, arcLengthInAbMeters, name}` records in the existing sidecar. No new boundary UUID/group object, no name fields in geometric cut/region/block definitions, and no schema bump. The world ID already scopes the document. The new empty default directly reads existing files.

Names have physical ownership, rather than owner-ID ownership. A topology-scoped **derived** grouping unifies actual authored boundary locations that are the same rail cut or the two ports of a true ordinary continuation/degree-two loop anchor. Rename updates name entries for every raw authored location in this group; duplicates on different physical groups are allowed. Facing is not part of naming identity. This handles adjacent block start/end and protection boundary sharing without adding fictitious block boundary IDs.

Persist actual authored raw locations, not only a canonical representative on the peer rail. A minimum-endpoint representative may be a location that no authored boundary uses; if that rail is later disconnected/deleted, the surviving owner would otherwise lose its name. Raw anchors keep the name on each extant side and make a split inherit the previously shared name on both sides.

### Physical identity evidence and limits

- `authoring/SectionTopology.kt:39–40`: `samePoint` uses same rail ID plus `Curve.TOLERANCE` difference in canonical AB metres; no world XYZ comparison.
- `authoring/SectionTopology.kt:53–66`: `sameBlockBoundary` first accepts samePoint, otherwise resolves endpoints and accepts only `ordinaryContinuationAt` or a shared LoopAnchor. It deliberately does not merge crossing tracks or turnout branches.
- `authoring/SectionOverlayBackend.kt:60–74`: `overlayBoundaryIdentity` canonicalizes actual ordinary/loop peers by minimum rail UUID and endpoint enum. It normalizes facing AB and endpoint metres. Move/reuse this as a shared topology helper for naming and overlay, rather than maintaining two definitions of connected-end identity.
- `authoring/SectionOverlayDrawing.kt:90–106` uses this identity only for block caption aggregation today; names must use it across **all** boundary types.

Keep snapping radius (0.75m at `SectionAuthoringActions.kt:32`) separate from identity. The click can snap to the nearest existing boundary; that radius must never merge two distinct names. Group exact stored same-rail cuts, using existing tiny geometry tolerance only for matching numerical representation; reject tied nearest groups rather than arbitrarily renaming one. If a tolerance group is required, choose one deterministic existing position; do not round arbitrary interior arc metres into broad buckets. Existing accepted cuts are created by snapping and generally share exact stored metres.

Do not establish persistent names from world-space proximity, change names when a turnout switches, or use route ID as a name key. Grouping uses structural ordinary continuity; current switch state is irrelevant.

### Lifecycle and normalization

Enumerate placed raw anchors from pendingProtectionBoundaries, each region.effectiveCuts, and first/last traversal endpoints of every fixed block (`SectionAuthoringActions.kt:34–39`). BoundaryDraft, including empty draft, is authoritative for currently placed cuts. Old accepted boundaries retained only for repair are **not** separately placed owners while omitted from the draft. Keep names when geometry becomes unavailable but the authored owner remains; missing curve/topology must not silently delete metadata. Deleting the last placed owner removes the name, so recreation after a committed deletion starts unnamed.

After structural definition patch merge, normalize candidate name anchors against candidate placed boundary ownership: remove entries whose raw location has no remaining owner, preserve surviving ownership, and propagate a previous group name to surviving/new raw aliases only when that old group has exactly one distinct nonempty name. If old records or a new merged group contain multiple distinct names, retain the conflicting raw records; do not choose a winner or propagate one over another. A pending→block or pending→accepted-region conversion keeps its name by physical cut, without copying metadata through every geometry constructor. Replacing boundary/region IDs at the same physical cut likewise keeps the name. Moving a boundary to another cut does not drag an unrelated cut's name; only existing destination ownership may supply a destination name.

Normalization must use the captured immutable topology and old/new owner sets. Current `prepareDefinitionPatch` calls applyPatch **before** capturing topology (`runtime/SimpleSignalsWorldRuntime.kt:1467–1485`); move topology capture/stale check earlier or pass topology to a subsequent candidate metadata normalization step. Do not infer current topology inside a pure helper or at UI render time and then save that result against a different root.

Full `prepareDefinitions` is also called for topology revalidation (`SimpleSignalsWorldRuntime.kt:753`), not just authoring. Do not silently rewrite metadata or increment definitionRevision on revalidation; derive group display resolution from the captured topology, retain authored raw records, and normalize stored records on the next explicit authoring transaction.

### Topology merges and splits: explicit conflict state

When previously separate named boundaries become one ordinary connected boundary, two existing names may conflict. Resolve each current physical group into Unnamed, Named(one distinct nonempty name), or Conflict(multiple distinct nonempty names). Every display of a conflicting group shows the same localized “名称冲突” plus position; do not silently choose a name based on anchor ordering, map order, owner type, or proximity. Keep the existing conflicting raw records so the player can resolve intentionally. The next explicit rename overwrites all current authored aliases; explicit empty input clears all. This is display metadata conflict, not a topology/publication failure and not a route restriction.

During structural normalization, first inspect the previous group and then the new topology group. Propagate from an old group only when its name is unambiguous; union retained named records in a newly merged candidate group before deriving its state. A conflict must stay a conflict until explicit rename, not disappear because a new alias happened to inherit the first record. If deletion removes the last anchor carrying one conflicting value, remaining records naturally determine the surviving group state; do not preserve nameless phantom owner records.

Splitting a formerly shared consistently named boundary leaves each raw owner with the same previous name; subsequent renames are independent. This is metadata continuity, not a global forever-linked group. A current group with a mixture of one named record and unnamed aliases is Named; a subsequent ordinary authoring normalization may copy that one value to all extant aliases. No metadata mutation occurs simply by rendering or during topology revalidation.

The conflict policy is proposed for the final reviewed plan; the user has confirmed shared naming in ordinary cases but not this uncommon merge case separately.

### All persistence / replacement paths

`persistence/SimpleSignalsWorldDefinitions.kt:34–65`: add immutable metadata collection to private primary and public copying constructor. `empty` at 73 defaults empty. File DTO at 85 adds default-empty saved collection. Decode construction at 265–280 and encode construction at 284–358 both carry it. Validate exact duplicate raw anchor entries and invalid/blank names; duplicate text is legal. Validate finite nonnegative arc and canonical UUID like existing cut decoding. Unknown/unavailable topology is not a codec concern.

`runtime/SimpleSignalsWorldRuntime.kt:473–484`: full immutable preparation reconstructs `copiedDefinitions` and must copy metadata (stable sort for reproducibility); otherwise names disappear on publication/revalidation.

`runtime/SimpleSignalsWorldRuntime.kt:81–127`: add optional full-list metadata replacement to the existing immutable patch, null=unchanged / empty=clear. `applyPatch:1804–1813` must recognize metadata edits as nonempty; candidate reconstruction at 1865–1875 must carry retained/replaced metadata; unchanged detection at 1876–1882 must compare it. A rename operation computes replacement against exact captured definitions and is guarded by expected world definitionRevision, so full replacement cannot overwrite a concurrent rename.

`persistence/SimpleSignalsDefinitionDrafts.kt:56–77`: definitionDraftPatch must propagate names on full-document imports. Existing contract is omission means deletion, so a decoded draft with no names defaults empty and an explicit full-document apply clears existing name metadata. `definitionDraftSummary:80–94` must count metadata changes; otherwise a names-only draft falsely shows zero updates. Export/read already reuse the same strict codec (17–30).

Search of main product sources found four direct `SimpleSignalsWorldDefinitions(...)` construction sites: empty, decode, runtime copy, patch candidate. Each is listed above. Ordinary generated `copy` retains the new field, but private-copy visibility should remain consistent with the existing immutable document policy.

### Sole transaction and non-null impact gate

Rename is a normal world-definition patch: advance only definitionRevision, not region/block/table/device revisions. Keep boundaries/cuts/traversals/route mappings bit-for-bit equal. Reuse manager's compilation, model/occupancy preparation, edit permit, staged save, exact topology/source checks, and atomic commit.

**Required integration:** `runtime/SimpleSignalsDefinitionEditImpact.kt:27–33` currently compares only geometric/device definitions; line 58 compares pending cuts; none sees new metadata. Names-only patch therefore yields no impact. `SimpleSignalsWorldRuntime.kt:1497–1502` rejects empty affectedRailIds and line 1604 requires nonnull editImpact. Add changed old/new name anchor rail IDs directly into the impact rail set, including all group aliases changed by rename/clear. Do not fake region upserts/revisions or mark all region paths and upstream consumers changed simply to obtain a permit. Existing rail edit protection may still reject naming on in-use track; preserving this conservative behavior is the minimum design. Allowing metadata edits during operations would need a separately reviewed transaction/permit contract change.

`SimpleSignalsWorldRuntime.kt:1589–1594,1670–1682,1693–1697` checks exact publication, catalog, runtime/active guard, topology and staged save. New rename backend captures those same roots; no direct UI disk write and no auxiliary file. Rejected rename leaves names and revision unchanged.

### Confirmed UX integration

Section Wand Shift+right settings → Name boundary → click existing boundary → native text submit; empty/trimmed empty clears; one operation exits. Keep existing create/delete click semantics while naming mode is not armed. `authoring/SectionWandHandler.kt:100–103` owns settings/clear gestures, so intercept armed rename mode before dispatching ordinary mutation, without bypassing sneaking cancellation/settings. Do not add a persistent wand PDC mode for a transient operation.

`authoring/SectionWandSessions.kt:9–15,20–27` owns transient intent and reuses an existing Session when world/state match; store armed naming intent and dialogToken there. **begin clears dialogToken** at line 27, so explicitly manage token generation across selection and input instead of assuming a settings token survives the next click. Invalidation on swap/F/world/offline/permission loss and one-use `AtomicBoolean` must apply to this new flow.

`authoring/WandDialogs.kt:34–53` provides text input/customClick callback precedent. Add dedicated name dialog. Backend enforces trimmed plain text ≤64 Unicode codepoints and rejects control/newline text; client maxLength alone may count UTF-16 units, so do not claim it fully enforces codepoint semantics. All rendered names use Component.text. Read initial shared name from captured group, never extract metadata from overlay caption strings. Save does one guarded worker transaction and exits on completion; cancel/stale/repeated callback does no write. A hit with no boundary stays armed or exits according to explicit UX choice; recommend stay armed with feedback until valid selection or user cancellation.

### Display consumers and tests

Resolve physical names through the same topology-group lookup for overlay region/pending/block captions and route endpoint labels. RouteIndicatorWandOptions currently only has route IDs (`SignalWandBackend.kt:65,416–419`); add immutable per-route entry/exit display data while preserving exact route ID callback identity. Use authored table routes, not only currently compiled available routes. Keep stable sort and local row numbering because duplicate names and multiple paths with same endpoints remain legal. Route deletion (`RoutePreviewWandHandler.kt:180`) should use the same endpoint captions.

Tests required:

- Old fixed JSON fixture lacks boundaryNames and reads empty; named current round-trip/draft export-import retains names and all geometry/revisions.
- Names preserved across runtime prepare/revalidation and pending→block/region closure; no regionRevision bump and no route mapping change.
- Interior shared exact cut sync; A↔B/A↔A/B↔B ordinary endpoint sync; loop anchor sync; turnout/crossing/coincident disconnected tracks remain distinct; ambiguous near hit rejects.
- Adjacent owner removed retains remaining owner's name; last owner deleted prunes; unavailable but retained owner preserves; later recreation starts unnamed.
- Metadata impact includes changed old/new anchors and permits names-only transaction; edit denied, persistence failure, topology/publication race preserve old bytes/name/revision.
- Split/merge names or explicit conflict consistent across all displays; no silent merge winner; rename replaces all aliases; no derived canonical peer-only orphan after disconnect.
- Names may duplicate; trimmed clear; 64 codepoint boundary including supplementary Unicode; control text rejected; no formatting evaluation.
- Settings→click→input→save one-shot; cancel/escape, invalidation, permissions, changed world/tool, repeated callbacks, stale publication and topology never save.

## Related Specs / External References

Read policies remain `.trellis/spec/javarailways/shared/versioning.md`, `ai_tasks/persistence_schema_evolution_rules.md`; no schema version change for default-empty additive metadata.

Update/review `.trellis/spec/simple-signals/backend/section-authoring-wands.md`, `definition-edit-impact.md`, `index.md`, `section-topology-revalidation.md`, and relevant current-route authoring docs. Existing docs currently say no separate saved display identity and generated UUIDs hidden; clarify names as authored metadata without changing identity.

No new external dependency/API needed; text dialog follows current repository Paper API usage.

## Caveats / Not Found

- Planning only; no product code/spec/git edits or runtime/server activity.
- This design supersedes the earlier protection-only per-cut field suggestion in `boundary-names.md` now that all types/shared physical naming are confirmed. Keeping metadata separate intentionally avoids the earlier whole-cut equality hazard.
- Explicit conflict and split behavior above is a recommended engineering policy to make the final plan explicit, not a previously user-confirmed case.
- Same-rail tiny-tolerance grouping must not accidentally create non-transitive chain merges; use deterministic existing-anchor matching and test it, or rely on exact shared snapped stored positions and keep tolerance only in hit matching.
- No exhaustive complexity benchmark performed. Build a grouped lookup once per captured low-frequency UI/overlay preparation, with records keyed by raw cut / endpoint identity; do not scan all names per physics tick or perform graph searches for naming.
