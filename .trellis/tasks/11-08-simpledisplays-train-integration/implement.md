# Implementation Plan

## 1. Baseline and optional API loading

- [x] Record SimpleDisplays API artifact/version consumption for local development and release packaging.
- [x] Add the compile-only API dependency without shading API classes into JavaRailways.
- [x] Add `SimpleDisplays` to generated soft dependencies.
- [x] Implement a small optional-service loader and tests for present, absent, and incompatible provider cases.
- [x] Verify JavaRailways starts without SimpleDisplays.
- [x] Commit and stop for review.

## 2. Package declarations and style assets

2026-11-09 scope refinement: the immediate slice is [screen part definitions/schema/parser](schema/README.md).
The user approved implementing only the reviewed screen-part model/schema/parser slice. Style assets, package defaults,
new package/snapshot formats, rendering and distance config/commands are later slices. Existing model-tree serialization
and copies must preserve the new metadata; that does not authorize a separate persistence redesign.
The checklist below is not one implementation batch.

- [x] Write the schema draft and examples as `type: screen` parts, supporting existing inline/import forms. Remove the rejected car-level screen-list draft.
- [x] User confirmed the part-tree shape and inline `screen` definition (2026-11-09); runtime implementation remains pending.
- [x] Record initial visibility policy: exterior screens follow the car body; interior screens use config/player-command viewing distance without occupancy or other screen-specific culling (2026-11-09).
- [ ] Confirm package manifest/snapshot release baseline before changing persistent models.
- [x] Add screen-part model/parser support first; package style/default models are a separate later slice.
- [x] Integrate the reviewed shape into the official part schema and cover inline/import/nested parsing, invalid fields, copy preservation and screen-free hash stability.
- [x] Compile all PartType consumers; screen nodes currently provide only the ordinary transform/group behavior, without rendering screen content.
- [ ] Add and validate interior/exterior installation metadata, including pinned snapshots and fixtures; cabin occupancy geometry is not required by the initial policy.
- [ ] Parse and validate parent-relative position/rotation, dimensions, category, part references, style IDs, and package XML assets.
- [ ] Include display declarations and required style content in pinned package snapshots/hashes.
- [ ] Add fixtures for screen-free packages, multiple points per category, point-specific defaults, and invalid references.
- [ ] Document recommended category names without restricting custom categories.
- [ ] Commit and stop for review.

## 3. Style selection and compiled-template cache

- [ ] Implement the precedence resolver for train category override, point default, and category default.
- [ ] Prepare compiled templates off the render owner path and publish an immutable cache.
- [ ] Define diagnostics for missing optional bindings and invalid declared styles.
- [ ] Test reload/pinned-model behavior and cache invalidation by content identity.
- [ ] Commit and stop for review.

## 4. Backend text visuals

- [ ] Extend `DisplayVisualContent` and `DisplayVisual` for text creation and updates.
- [ ] Implement native Bukkit `TextDisplay` support.
- [ ] Implement equivalent EntityLib packet text-display support.
- [ ] Verify metadata, transforms, brightness/background/alignment, viewer visibility, and cleanup parity.
- [ ] Add focused backend tests without disturbing unrelated existing render changes in the worktree.
- [ ] Commit and stop for review.

## 5. Car-mounted screen groups

- [ ] Specify interior-screen config keys, default/allowed distances, player command syntax, distance reference point and player-setting lifetime using existing view-distance contracts before implementation.
- [ ] Add server-configured interior-screen viewing distance and player command overrides; changing the setting refreshes that player's recipients.
- [ ] Exterior screens follow car-body visibility; enforce interior-screen distance filtering before spawning/sending. Test distance boundaries, players inside/outside cars, moving trains beside stationary players, and recipient removal without occupancy/FOV/occlusion checks.
- [ ] Build screen groups from car installation points and resolved templates.
- [ ] Convert top-left logical draw coordinates into the installation plane and fit the physical dimensions.
- [ ] Attach groups to car body motion, packet bundling, audience handling, rebuild, reverse, and destruction lifecycle.
- [ ] Avoid content metadata writes when neither values nor animation frame changed.
- [ ] Test curves, slopes, reversing, rebuild, unload, and partial construction failure.
- [ ] Commit and stop for review.

## 6. Operational display data

- [ ] Define an immutable Bukkit projection from SimpleATO run/unit state.
- [ ] Expose destination, train/service type, current/next station, and ordered future stops.
- [ ] Extend SimpleDisplays API/XML syntax for repeated future-stop content and paging in its separate repository.
- [ ] Refresh only affected screens as calls complete, stopping patterns change, or short turns occur.
- [ ] Test two runs of the same train package with different stop patterns and pass stations.
- [ ] Commit and stop for review.

## 7. Overrides, validation, and smoke checks

- [ ] Add server-side train/category style overrides with the agreed non-runtime configuration entry point.
- [ ] Test precedence across package and server sources.
- [ ] Run `./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar` and SimpleDisplays compilation/tests.
- [ ] Run Paper and Folia smoke scenarios with both renderer backends and with SimpleDisplays absent.
- [ ] Add command/JS runtime switching questions to `TODO.md` without implementing the interface.
- [ ] Review diffs, update relevant specs, and commit the final verified slice.

## Commit discipline

Each completed phase ends in one focused commit and a user report before proceeding. Stage only paths changed for this task because the JavaRailways worktree contains unrelated changes.

## Stage 1 evidence (2026-11-09)

See [validation report](research/stage1-validation.md) for API/artifact provenance, seven passing integration tests, packaging, successful Paper startup with/without SimpleDisplays, and the unrelated full-suite expression-test failure. Full-suite success is not claimed. Review this stage before starting stage 2.

## Screen-definition slice evidence (2026-11-09)

Model/schema/parser implementation and independent review are complete. See [validation record](research/screen-definition-validation.md): 29 scoped tests pass, official schemas/examples validate, packaging and developer compilation pass. The full isolated suite has 747 tests with the same expression-test failure recorded in stage 1 (1 failure, 1 skipped). No screen visuals or view-distance controls are implemented. Stop after the focused slice commit for review.
