# Design

## Ownership and boundaries

SimpleDisplays owns XML compilation, data binding, icon animation, logical screen coordinates, and the public draw-result types. Its standalone plugin owns its preview command and native world placement.

JavaRailways owns train-package discovery, car installation points, category/style selection, operational data projection, car-local-to-world transforms, viewer visibility, backend-specific entities, and lifecycle cleanup. The integration loads `simpledisplays.api.DisplayService` from Bukkit's service registry only when the soft dependency is present.

SimpleATO remains the source of timetable and stopping-pattern state. It does not import Bukkit or SimpleDisplays types. Bukkit adapts immutable operational snapshots into SimpleDisplays values.

## Package model

Current slice: [screen part definitions and examples](schema/README.md). The user approved only the
reviewed part model/schema/parser shape. It is implemented without screen visuals; style resources and
new package/snapshot formats remain later work. Definition, copy and canonical-hash contracts are owned
by the [screen-part spec](../../spec/bukkit-plugin/backend/screen-parts.md).

Add `screen` to the existing part types. Screens may be inline nodes or imported reusable parts in
`body-parts`/`parts`; they reuse common `name`, parent-relative `offset`/`rotation`, nested parts and
animation structure. The required `screen` object contains `category`, `interior`, `width` and `height`.
Do not add a parallel car-root `screens` collection or separate screen IDs. Point-specific style binding
is later work: determine instance addressing from the existing part tree before freezing a binding key.

Add package-level category-to-style defaults and package-contained XML style assets. Style references use stable namespaced IDs. Package validation rejects non-positive screen dimensions, invalid poses, unsafe/missing asset paths, and unresolved defaults. Existing part-name/import rules remain authoritative; reusable part names are not globally unique screen instance IDs.

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

Exterior screen groups follow the car body's audience rules. Interior screen groups further filter that audience by the player's configured interior-screen viewing distance, as described below. All screen groups follow the car model's destruction completion. Plugin absence yields no screen group. JavaRailways never asks SimpleDisplays to spawn standalone Bukkit entities for train-mounted screens.

## Runtime data

Introduce a Bukkit-side immutable projection containing scalar train/service fields and an ordered list of future stops. Keep the SimpleDisplays API evolution needed for repeated/list content in the SimpleDisplays repository. JavaRailways supplies values and identifiers, while the template decides layout, highlighting, paging, and animation.

Refresh draw content when the selected style, projected operational data, or relevant animation frame changes. Avoid recompiling XML or rebuilding the entire car model for routine station progress.

## Lifecycle and failure behavior

Discover the optional service during BukkitPlugin enable after optional dependencies have loaded and before model packages are prepared. Do not retain implementation classes or assume the provider survives disable/reload.

All entity operations use the existing target-aware render ownership paths. Model rebuild, world unload, car removal, audience loss, plugin disable, and partial construction failure retire every screen visual. A failed screen creation rolls back the partially created screen group without discarding the rest of the car model unless package validation had already declared the model invalid.

## Deferred runtime switching

Record command and JS switching as TODO work. The future API should share one style-override service, but this task does not decide whether overrides target a train, operating unit, car, or installation point, or whether they persist across lifecycle changes.

This deferral concerns category-style switching only. Player commands for interior-screen viewing distance are part of the confirmed visibility requirement.

## Screen visibility decision (user confirmed, 2026-11-09; not yet implemented)

Exterior screens are simple and use the same rendering/visibility strategy as the car body; no additional screen-specific culling policy is required for them. Interior screens initially use a configurable viewing distance, analogous to existing train/rail viewing distance. Server config supplies the setting and players can override their own interior-screen distance using a command. The screen part's interior/exterior attribute selects the policy; category names do not imply it.

Reuse the existing car audience as the candidate set and apply the effective interior-screen distance as an additional filter. Do not require a cabin occupancy box, seat membership or same-car membership. A player outside the car may receive its interior screens when within the effective viewing range and the existing car audience. The first release does not add screen-backface, camera-FOV/frustum, raycast or mesh-occlusion tests.

Existing evidence: `RenderChunkManager` spatially indexes car candidates and refreshes on player movement and moving renderables; `ViewDistanceManager` stores player-specific distance settings; `CarExtension` has a separate distance-based detail audience. Reuse these ownership and refresh patterns rather than scanning all world players per screen. Concrete config/command integration and the distance reference point still need design; no key names, numeric defaults, persistence behavior or exact comparison formula are frozen by this decision.

On player entry, send the group's current state; on exit, remove its visuals for that player and stop movement/content packets. Do not broadcast metadata to excluded players. A group with no viewers should not evaluate animation frames solely for invisible output; reconstruct its current frame from the animation clock when a viewer returns. Actual backend parity and ownership must be verified in the screen-group implementation stage. A moving train must refresh stationary-player eligibility, and command changes must refresh the requesting player's audience. Capture player state on its entity owner and consume immutable car frame data, without cross-region live-player reads.

Stage 2 declares, validates and snapshots the interior/exterior screen-part metadata. Stage 5 implements distance filtering, config and player command support with screen groups. The screen-part interior field is implemented; the visibility algorithm, config and commands remain unimplemented.
