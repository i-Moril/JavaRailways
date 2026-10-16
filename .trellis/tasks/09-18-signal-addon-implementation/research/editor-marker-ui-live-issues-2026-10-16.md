# Research: Player editor markers and hidden identities

- Query: Remove player-facing rail/block UUIDs, replace particle section/direction geometry with resource-pack-backed TextDisplay, and show section entrances while holding Signal Wand.
- Scope: internal source/spec/artifact-layout inspection only
- Date: 2026-10-16

## Findings

### Current implementation and cause

- `SimpleSignals/.../authoring/SectionOverlayDrawing.kt:18-25` stores particle points separately from text labels and applies a 64m nearby filter. `:44-55` emits boundary particle columns and chevrons; `:56-73` emits sampled particle coverage, periodic labels and chevrons. TextDisplay therefore already exists for **labels only**, not geometric markers.
- `SectionWandOverlay.kt:31-33` recognizes only `SectionWandHandler.readTool`; Signal Wand is an unrelated PDC codec and never acquires this overlay. Its constructor/session lifecycle and backend caching can be reused rather than building a second overlay.
- `SectionWandOverlay.kt:148-162` submits text and then draws rotating particle batches. Replace this geometric-particle path for the section overlay; retain unrelated route-preview rendering unless explicitly in scope.
- `SimpleSignalsPlugin.kt:88-109` constructs the section overlay first and wires its check callback only to section tools; SignalWandHandler currently receives no overlay callback. Both command-give and tool/settings changes must make the overlay react, not only inventory switching.
- `BukkitPlugin/.../tool/RailObjectWand.kt:185-202,529-575` refreshes operating-direction particles from current Basic directions, with nearby candidate selection. `:577-620` similarly visualizes waypoint arrival direction. Both are genuine direction visuals; leaving the latter particle-only would leave inconsistent semantics if the user expects the complete RailObjectWand direction scheme migrated.

### Player-facing identity surfaces

- `SectionOverlayDrawing.kt:78-83`: block ID in start/end and repeated coverage text. Region/boundary/pending labels at `:93-102` also show generated IDs.
- `SectionAuthoringActions.kt:49`: create-block success text includes blockId; `:83,129-130` includes region IDs. Generated IDs use `simplesignals:${UUID.randomUUID()}` (`SectionWandBackend.kt:87`, `SectionAuthoringActions.kt:91`). Removing only UUID classes/imports would be incorrect: identity must remain in definitions, indexes and stable label keys.
- `SignalWandHandler.kt:1040` binding description includes raw blockId; reused by action bar (`:805`) and item lore (`:824`). `SignalWandBackend.kt:310-311` also uses raw blockId in chooser labels, and `:542-543` emits boundary IDs for indicator choices. Audit success/device-choice text too: newly generated signal IDs are also opaque IDs, not meaningful player labels.
- `BukkitPlugin/.../tool/RailWand.kt:3896-3906` includes `rail.id` as the first argument to `message.rail_selected`; the Chinese key is at `rail_wand_zh_CN.properties:181`. Edit/decorate/cleanup messages and derived endpoint anchor IDs (`rail:<UUID>:A/B`) can leak UUIDs indirectly through anchor strings. `RailWandDecorateMode.kt:39`, mode-specific anchor selection messages, and anchor editor ID row (`RailWand.kt:867-868`) deserve deliberate treatment. User-authored plain anchor names are not UUIDs and need not all be removed.
- `RailObjectWand.kt:471,492` explicitly prints rail.id in direction-edit confirmations (also AB/BA enum rather than spatial direction).
- Rail geometry floating text (`RailWand.kt:5450-5540`) already displays geometry/speed only. Its UUID grouping key (`:5459`) is internal, not a visible UUID leak; preserve it.
- Keep admin inspect/export, persistent IDs, structured diagnostic logging, PDC binding and stable label IDs. Prefer descriptive player text (block entry/exit, direction, validity, length where already available) without introducing a persistent display-name registry or short-ID schema.

### Reusable host boundary and required minimal extension

- `bukkit/api/editor/EditorTextLabel.java` has only id, xyz and Component. `DefaultEditorTextService.kt:195-198` forces `Billboard.CENTER`, shadowed text. Components already permit a custom Adventure font key; no separate font-fetch API is required.
- Billboard labels cannot faithfully point along a 3D rail tangent or stretch to represent a rail segment. Minimal generic orientation/scale style is justified by the two actual consumers (Signals and RailObjectWand). Preserve existing five-argument source call shape with default billboard style, and add a small immutable pose/style value or constructor for fixed oriented glyphs. Do not expose mutable Bukkit TextDisplay or business block objects.
- Style-only changes must be applied in the existing entity update path (`DefaultEditorTextService.kt:175-180`), not only when spawning. Keep existing location-change recreate behavior across Folia regions, latest-only submission, player isolation and cleanup completion.
- A host-owned glyph definition/helper can expose the actual custom font namespace and point/arrow/span glyph Components. Section interpretation remains SimpleSignals-owned; rail direction semantics remain Basic/RailObjectWand-owned. Pure quaternion/tangent/normal conversion should use existing geometry conventions; the implementation must test AB/BA and sloped curves, not a screen-facing Unicode arrow workaround.
- Geometry preparation remains worker-side and change-triggered. Existing section backend captures exact definition/topology/direction/geometry references; moving viewers filters already prepared labels. TextDisplay persistence makes per-refresh particle budgets inappropriate for the migrated geometry, but does not justify adding a new business-object cap.

### Resource-pack ownership and version

- Repository search found no existing `font/*.json` provider or editor glyph asset set. Existing rail/train packs and JR four-light resource pack contain models/textures, not an editor font.
- `SimpleSignals/examples/jr-four-light/resourcepack/pack.mcmeta` targets resource format75.0/Minecraft1.21.11, and its README explicitly states that target (`:51`). It should not become the owner of generic Bukkit editor assets.
- `models/resourcepack/` and adjacent ZIPs are existing combined/development artifacts, some owned by unrelated dirty work. Do not overwrite them. Prefer a small independent source resource pack under a BukkitPlugin-owned example/resourcepack location, with reproducible ZIP output and clear installation order/instructions; exact path is an implementation choice.
- Actual previous deployment record identifies Folia26.1.2 client resource format84.0; the existing `models/jr-four-light-26.1.2.zip` is not evidence that source format75 font assets exist. New editor pack must explicitly support the actual target format84.0 and validate its root `pack.mcmeta`, font provider path and glyph texture references. No server-side automatic download URL or hosting changes are required by this UI request.
- No generic pack-zip builder found. `scripts/build_s100_transparency_pack.py` is specific to train textures and unsuitable as an editor-pack owner without substantial unrelated expansion. Standard deterministic ZIP assembly/test of the tiny standalone tree is sufficient. Confirm current official font provider semantics if adding bitmap-specific fields; this investigation did not browse external documents or claim client validation.

### Small ownership split

1. **Bukkit editor API/service + assets + RailObjectWand:** extend EditorTextLabel/DefaultEditorTextService minimally, share glyph namespace and style, add standalone source editor pack, migrate operating/arrival direction visuals through the same host-managed service and lifecycle. Do not rewrite RailWand core.
2. **SimpleSignals authoring:** migrate SectionOverlayDrawing to glyph labels, drop section geometric particles, add SignalWand holding recognition and give/change callback, replace block/region/signal-generated IDs in player-facing text while retaining stable internal keys. Reuse SectionOverlayBackend/cache/session.
3. **Narrow RailWand text cleanup:** update visible rail/derived-endpoint UUID strings and matching zh_CN/en_US placeholders at call sites, not all ID-bearing internal strings. This can be assigned separately if ownership is clearly disjoint.

### Existing tests and focused additions

- `SectionOverlayDrawingTest`: modify existing assertions to check glyph Components/font keys, entry/exit and direction pose, coverage and unavailable geometry, no raw UUID text; preserve internal stable IDs.
- `SectionWandOverlayTest`: reuse fake scheduler/handle to cover block→signal→non-tool, same geometry capture reuse, signal give/settings activation, player/world retirement and late-result cleanup. Existing tests already exercise unchanged capture and nearby filtering.
- `RuntimeSectionOverlayBackendTest`: retain exact input/caching invariants; do not duplicate with per-tick sampling tests.
- `DefaultEditorTextServiceTest`: fixed style spawn, same-position pose update, old five-argument label semantics, no-op equality, cleanup after paused spawn. Java API consumer compiles new and old constructors.
- `DirectionRestrictionParticleLayoutTest`: retire particle-flow-only tests once no consumer remains; keep useful arc placement constraints in direct replacement geometry tests instead of retaining dead helper code.
- `RailObjectWandI18nTest`, `RailWandI18nTest`, `RailWandGeometryDisplayTest`: update locale placeholders and assert useful geometry labels remain. Do not assume a string scan proves real client marker readability.

## Related Specs

- `.trellis/spec/bukkit-plugin/backend/editor-markers.md`: CURRENT is text-only API plus particles; full TextDisplay geometry/custom-font style was explicitly deferred and needs updating with this newly authorized migration.
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md:235-252`: currently documents section-tools-only, particles, rotating particle budget and identity labels; must be corrected together with implementation.
- `.trellis/spec/bukkit-plugin/backend/index.md`: owner-thread rendering, asynchronous geometry, public service boundary, observable cleanup.
- `.trellis/spec/simple-signals/backend/index.md`: independent add-on, only public host APIs, shared Kotlin class identity.
- Task `prd.md`, `design.md`, `implement.md`: current game acceptance remains in progress; these UI issues are actual player feedback, not a reason to restart earlier module-wide audits.

## Caveats / Not Found

- No source edits, builds, git operations, SSH or deployment performed. No actual TextDisplay pose/client resource-pack acceptance established by this inspection.
- Role-isolated research does not load implement.jsonl/check.jsonl; task artifacts and relevant owner specs were inspected directly.
- Renderer font spacing, billboard/fixed orientation, scale, rail cant and occlusion require real-client validation after offline checks. A custom font applied to a CENTER billboard alone does not meet directional geometry requirements.
