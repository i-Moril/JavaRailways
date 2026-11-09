# Integrate SimpleDisplays with train-mounted displays

## Goal

Soft-integrate the SimpleDisplays display engine with JavaRailways train packages, display categories, package-provided styles, runtime overrides, and train-mounted rendering.

## Requirements

- JavaRailways Bukkit must declare `SimpleDisplays` as a soft dependency. The railway plugin must continue to load and run trains when SimpleDisplays is absent.
- SimpleDisplays remains an independently usable Bukkit/Paper/Folia plugin with its own commands. JavaRailways consumes its public API and must not depend on its implementation package.
- Screens are `type: screen` nodes in the existing part tree, supporting both inline definitions and imports in `body-parts`/`parts`. They reuse common part names and parent-relative transforms; `screen` contains category, interior/exterior classification and physical dimensions. Do not add a parallel car-level `screens` list or a separate screen ID system.
- Each screen part must declare whether its screen is inside the car. Exterior screens follow the same rendering/visibility strategy as the car body. In the first release, interior screens use distance-based per-player visibility, with a server-configured viewing distance and player command overrides, analogous to train/rail viewing distance. Interior occupancy, screen-facing, camera-frustum and occlusion checks are not part of this initial policy.
- Recommended categories initially include `head_led`, `side_led`, `cabin_side_lcd`, and `cabin_end_led`. Packages may use additional stable category IDs.
- A train package can include SimpleDisplays XML styles and provide category-level defaults. It can additionally bind a particular installation point to a style for screens whose proportions or layout differ.
- Server-side configuration can override the style for a category on a particular train. The initial precedence is train category override, package point default, then package category default.
- Screen coordinates use the SimpleDisplays convention of 128 logical units per block/metre. Templates are fitted to each installation point's physical width and height without changing the car model geometry.
- JavaRailways supplies runtime display values. The first dynamic contract covers service/train type, destination, current station, next station, and the ordered future stopping pattern. Passed stations are excluded and short turns update destination and future stops.
- Both the native Bukkit display backend and the EntityLib packet backend must render the same SimpleDisplays draw output and participate in existing train visibility, motion, model rebuild, and cleanup lifecycles.
- Folia ownership rules apply to spawning, updating, moving, and destroying screen visuals.
- Minecraft-command and per-car JS APIs for changing category styles at runtime remain a documented TODO until target scope and persistence across restart, coupling, splitting, and re-composition are decided.
- Persisted/configuration changes follow the repository versioning gate. Additive fields with explicit defaults do not cause speculative schema-version changes.

## Acceptance Criteria

- [x] JavaRailways starts successfully both with and without SimpleDisplays installed, and the generated plugin descriptor contains `SimpleDisplays` under soft dependencies.
- [ ] A package can declare multiple installation points sharing one category and can include default XML styles.
- [ ] Point-specific defaults override package category defaults, and a train-specific category override supersedes both.
- [ ] Missing SimpleDisplays, absent optional styles, or a screen-free car never prevents the train model from loading. Invalid declared styles fail with an actionable package/train/category/point diagnostic according to the agreed validation boundary.
- [ ] The same static head/side display and cabin LCD render through native and EntityLib backends and remain aligned through curves, slopes, reversing, model rebuild, audience changes, and unload.
- [ ] Exterior screens follow car-body visibility. Interior screens obey the server viewing-distance setting and player command overrides; leaving range removes their visuals and stops further screen updates for that player. Visibility does not require being seated or physically inside the car.
- [ ] A cabin LCD can render the actual ordered future stops for two runs of the same rolling stock with different stopping patterns.
- [ ] Passing a station and performing a short turn refreshes the affected LCD and destination display without rebuilding the whole train model.
- [ ] SimpleDisplays' independent demo/preview command continues to render the same template through its own native `TextDisplay` renderer on Paper/Folia.
- [ ] Automated tests cover service discovery, style resolution precedence, configuration validation, display-data projection, and backend lifecycle cleanup.
- [ ] Targeted BukkitPlugin tests, packaging checks, Kotlin compilation, and recorded Paper/Folia smoke scenarios pass.

## Notes

- SimpleDisplays lives at `../SimpleDisplays` and is a separate Git repository.
- Existing SimpleDisplays commits are `cf6b5c8` (public rendering service and independent demo), `150dfc3` (split public API types), and `21f7686` (separate API artifact).
- This task uses the dedicated `ddf5` worktree. The main checkout contains unrelated work and is not an implementation/build target.

## Confirmed visibility decision (2026-11-09)

- The user selected the distance-based initial policy above. The user also approved inline/imported screen parts with required `category`, `interior`, `width` and `height` under `screen`. Config keys, numeric defaults, command syntax, distance reference point and player-setting persistence remain implementation-design details to specify before coding; no values or persistence behavior have been agreed here. The player command is for viewing distance, distinct from deferred category-style switching.
- Continue work in `/home/user/.codex/worktrees/ddf5/JavaRailways` on `codex/simpledisplays-train-integration`, starting from `ce2bcd44`. The main checkout has other work and must not be used for implementation or builds of this task.

## Template discussion (2026-11-09)

- The user proposes reusing SimpleDisplays XML directly and adding placeholder API adaptation rather than designing another display-template language.
- Existing SimpleDisplays supports `<span bind="key">fallback</span>` and `DisplayTemplate.draw(Map<String, String>, elapsedTicks)`; the host can supply scalar values without rewriting XML or duplicating the compiler.
- Confirmed: “placeholder API” means the external Bukkit PlaceholderAPI plugin. The user also asks about JS-generated dynamic elements inside SimpleDisplays. JS input/output shape, template storage and refresh policy remain under discussion; the user subsequently authorized the scoped SimpleDisplays JS implementation below.

- Confirmed: as a reusable library, SimpleDisplays must allow the same display instance/template to produce different content for different viewers. Viewer-specific content is a supported capability, not restricted to a JavaRailways-only special case. Identical train information may still be reused, but callers must not be forced to share one frame among all viewers.

## Authorized SimpleDisplays JS slice (2026-11-09)

- Implement only the independent library's JS compile/render API: generic structured display data, explicit per-viewer input, elapsed ticks and renderer-neutral text/icon output. Preserve the existing XML API.
- An injected placeholder resolver may be called from JS; the actual Bukkit PlaceholderAPI adapter is later work. JavaRailways sends data through the direct library API, not through PAPI.
- Execute one bounded render invocation without cross-viewer mutable script-state leakage. Validate full output before returning a frame and clean up script resources on template/service/plugin close.
- Do not implement JavaRailways script resource loading, train data capture, native/entitylib mounted text output, visibility settings or commands in this slice.
- Independent source worktree: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, branch `codex/js-rendering`, base `21f7686`. Keep the original SimpleDisplays and JavaRailways main checkouts unchanged.

- Completed independent library slice as SimpleDisplays `db9c65e`; API documentation lives in that repository README. See [validation](research/simpledisplays-js-validation.md). Actual PAPI adaptation and JavaRailways use of JS remain pending.
