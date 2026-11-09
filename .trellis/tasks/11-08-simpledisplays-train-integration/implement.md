# Implementation Plan

## 1. Baseline and optional API loading

- [x] Record SimpleDisplays API artifact/version consumption for local development and release packaging.
- [x] Add the compile-only API dependency without shading API classes into JavaRailways.
- [x] Add `SimpleDisplays` to generated soft dependencies.
- [x] Implement a small optional-service loader and tests for present, absent, and incompatible provider cases.
- [x] Verify JavaRailways starts without SimpleDisplays.
- [x] Commit and stop for review.

## 2. Package declarations and style assets

- [ ] Confirm package manifest/snapshot release baseline before changing persistent models.
- [ ] Add installation-point and package style/default models with explicit empty defaults.
- [ ] Parse and validate position, rotation, dimensions, category, point ID, style IDs, and package XML assets.
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
