# Design

## Ownership and boundaries

SimpleDisplays owns XML compilation, data binding, icon animation, logical screen coordinates, and the public draw-result types. Its standalone plugin owns its preview command and native world placement.

JavaRailways owns train-package discovery, car installation points, category/style selection, operational data projection, car-local-to-world transforms, viewer visibility, backend-specific entities, and lifecycle cleanup. The integration loads `simpledisplays.api.DisplayService` from Bukkit's service registry only when the soft dependency is present.

SimpleATO remains the source of timetable and stopping-pattern state. It does not import Bukkit or SimpleDisplays types. Bukkit adapts immutable operational snapshots into SimpleDisplays values.

## Package model

Add an additive screen-installation collection to the current car definition. An installation point contains:

- a car-local stable ID
- a category string
- a local position and rotation
- physical width and height
- an optional package-local style reference

Add package-level category-to-style defaults and package-contained XML style assets. Style references use stable namespaced IDs. Package validation rejects duplicate point IDs, non-positive dimensions, invalid poses, unsafe/missing asset paths, and unresolved defaults.

The package snapshot/pinning behavior must include everything required to reproduce the installed train's display geometry and package-owned style content. Before changing snapshot fields, confirm whether the package boundary has a released or frozen baseline. Prefer additive fields with explicit empty defaults.

## Style resolution

Resolve one style per installation point using this order:

1. train-instance category override
2. package point-specific default
3. package category default

No match means the installation point is inactive. A category is a selector and semantic convention, not an enum discriminator. Recommended names are documented, while custom names remain legal.

Compile XML once per package/style content identity and cache immutable `DisplayTemplate` instances. Runtime draw evaluation receives immutable values and elapsed animation ticks. Reload publishes a fully prepared replacement cache and does not mutate templates already used by pinned models.

## Rendering adapter

Extend JavaRailways' display abstraction with text content and text updates. Native creates Bukkit `TextDisplay` entities. EntityLib creates equivalent packet text-display wrappers. Both consume the same `TextDraw` list.

Each logical screen is a child render group under the car body transform. Draw coordinates are converted from the template's top-left coordinate system into the installation plane. The adapter applies physical fitting and the 128-units-per-block convention. Content/animation changes update text metadata, while normal train ticks update the screen group's world transform through the existing bundled car update path.

The screen group follows the car model's audience rules and destruction completion. Plugin absence yields no screen group. JavaRailways never asks SimpleDisplays to spawn standalone Bukkit entities for train-mounted screens.

## Runtime data

Introduce a Bukkit-side immutable projection containing scalar train/service fields and an ordered list of future stops. Keep the SimpleDisplays API evolution needed for repeated/list content in the SimpleDisplays repository. JavaRailways supplies values and identifiers, while the template decides layout, highlighting, paging, and animation.

Refresh draw content when the selected style, projected operational data, or relevant animation frame changes. Avoid recompiling XML or rebuilding the entire car model for routine station progress.

## Lifecycle and failure behavior

Discover the optional service during BukkitPlugin enable after optional dependencies have loaded and before model packages are prepared. Do not retain implementation classes or assume the provider survives disable/reload.

All entity operations use the existing target-aware render ownership paths. Model rebuild, world unload, car removal, audience loss, plugin disable, and partial construction failure retire every screen visual. A failed screen creation rolls back the partially created screen group without discarding the rest of the car model unless package validation had already declared the model invalid.

## Deferred runtime switching

Record command and JS switching as TODO work. The future API should share one style-override service, but this task does not decide whether overrides target a train, operating unit, car, or installation point, or whether they persist across lifecycle changes.
