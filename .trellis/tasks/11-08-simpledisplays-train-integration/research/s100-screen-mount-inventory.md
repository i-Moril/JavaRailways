# S100 screen mount inventory (2026-11-10)

## Baseline and scope

- Existing display integration and schema changes committed locally as `cad8d2cd`.
- `models/s100` YAML and `models/s100.zip` already contain the current animation-map / standalone-version conventions. Re-running the converter on `models` reports zero changes (`/tmp/jr-s100-schema-current.json`). No schema version bump is needed.
- This inventory is source inspection, not screen installation or client acceptance. No server deployment performed.
- Production and test-source compilation succeeded before commit (`/tmp/jr-display-precommit-compile.log`); test execution remains deferred by user decision.

## Confirmed geometry

Resource geometry directory: `models/resourcepack/assets/minecraft/models/item/art1st/tc/s100/`.
Part directory: `models/s100/parts/s100/`.
Car directory: `models/s100/cars/s100/`.

### Front exterior destination

`body_head.json` contains `head_display_back` and its surrounding frame. `front_display.json` defines the matching north-facing LCD plane: from `[-9,21.1,-11]` to `[25,25.1,-11]` (raw model units, 34 by 4). The standalone `front_display.yml` exists but no car imports it. `body_head_tail.yml` uses the same head item model. Thus both cab ends have a candidate front display; both now have generated display children under the head parts (see the implementation section).

### Interior screen housing halves — not separate screens

The elements named `display` in `body_head`, `body_seated`, `body_connector` and `body_connector_toilet` are the two-piece housing of the same door-area LCD. Each half has a 12×6-unit face; interpreting that face's 2:1 ratio as a separate screen was incorrect.

After the neighboring body-section transforms, the halves are centered 0.375 blocks before/after a door group, spanning a combined 1.5×0.375-block housing (4:1). The `door_display` LCD is centered in that housing at 1×0.25 blocks (4:1), with the same 22.5-degree tilt. The user confirmed that the cabin has only this one inclined screen type. Do not count the 60 housing halves as screens or mount extra nodes on them.

### Interior door-area screens

Each car imports `door_display` three times, at parent z=-6,0,+6 and y=1.25. `door_display.json` supplies two inward-facing LCD planes per import:

- Left plane: x=-11.92388, y=21.38268..25.38268, z=0..16; z rotation -22.5 degrees about `[-12.92388,20.38268,2]`; east face.
- Right plane: x=27.92388, same y/z ranges; z rotation +22.5 degrees about `[28.92388,20.38268,2]`; west face.
- Each plane is 16 by 4 raw model units. Total six faces per car, 30 over the composition.
- `head`, `motor`, `trailer`: door-display rotations at z=(-6,0,+6) are (0,180,180).
- `head_tail`, `motor_tail`: rotations at z=(+6,0,-6) are (180,0,0).

### Exterior side screens

Each car imports `side_display` at parent z=±3 and y=1.25. Each import contains two outward-facing planes, at x=-14.5 and x=30.5, y=20..23, z=4..12, with west/east faces respectively (8 by 3 raw model units). Four faces per car, 20 in the manifest composition. Tail-car imports rotate 180 degrees.

## Installation implications

- Verified mounting targets in this batch: 30 interior door-area faces and 2 front exterior faces. The 20 existing exterior side faces are separately identified. The earlier count assigning 60 embedded body faces to exterior screens was based on an incorrect location interpretation and must not be used for installation.
- Mount reusable screen children under the existing parts tree so section/car transforms remain authoritative. Housing halves remain part of the original body mesh; only actual LCD planes receive generated display children.
- Use car HEAD-based physical door-side for each installed interior face, accounting for each imported parent's rotation; do not map texture labels `left_lcd/right_lcd` directly to final car-side values.
- Raw Blockbench coordinates and element pivots above are verified. Final world-facing screen centers, quaternions, drawable insets and depth offsets require applying item-model rendering conventions and checking in game. Avoid using section anchor coordinates as the final screen point for view distance.
- Existing XML demos are not yet actual S100 template/glyph resources. Template/resource installation remains a separate implementation step.

## Template packaging decision (corrected and user confirmed)

- Front 8.5:1 and existing body-joint exterior side 8:3 templates belong to S100 and share service-class badge + destination information.
- Door-area LCDs use one independent 4:1 interior style, referenced as `cabin-route.route`.
- No 2:1 screen exists; the erroneous standalone template and catalog/default entries have been removed at the user's request.
- The E002 2:1 rectangle glyph remains needed by the front service-class badge; it does not represent a physical screen.
- Chinese/English rotation remains 15 seconds with equal dimensions for both languages.

## Front and door mounts implemented (2026-11-10, client acceptance pending)

- `body_head` and `body_head_tail` each own one `exterior_front` child (`2.125 × 0.25` blocks), at part-local `(0, 0.94375, 1.1885)`. The north face of `head_display_back` uses the new opaque `display_background` material; other frame/back textures and all mesh coordinates remain unchanged.
- `door_display` retains its item model and physical LCD planes, replacing only the old content textures with opaque backing. Two `interior_door` children use the independent `cabin-route.route` style, dimensions `1 × 0.25` blocks. Main-composition counts are 2 front exterior and 30 door interior screens.
- `door_display_reversed` uses the same item model/children transforms but swaps the fixed installed-car HEAD `door-side` values. Only car imports with Y=180 use this variant. This prevents a parent rotation from accidentally leaving `left` assigned to the physical right side. The extra `passenger.yml` example is updated consistently, although it is not an additional car in the five-car manifest.

### Coordinate evidence and exact conversion

Native backend explicitly sets item transform `NONE`. `ModelTreeBuilder` builds part rotations with JOML `rotateXYZ`; `DisplayModel` forwards that transform without another item-specific rotation. Minecraft's item-display renderer adds Y=π before rendering a model centered at `(8,8,8)`: the effective raw-model-to-part mapping is `(8-x, y-8, 8-z)/16`, after applying the element rotation about its raw pivot. This also agrees with the positive-z cab placement versus the raw negative-z nose geometry.

Source reference inspected: [Mojang renderer source mirror, ItemDisplayRenderer.renderInner](https://github.com/mahtomedi/minecraft/blob/main/src/main/java/net/minecraft/client/renderer/entity/DisplayRenderer.java), which explicitly applies `Axis.YP.rotation(Math.PI)` before `ItemRenderer.renderStatic`. This source establishes the convention; real target-client acceptance is still pending and is not replaced by this inspection.

- Door raw left center `(-11.92388,23.38268,8)`, pivot `(-12.92388,20.38268,2)`, z=-22.5 produces part center `(1.17824688565,0.92322719782,0)` and inward normal `(-cos22.5,-sin22.5,0)`. Right is x-mirrored.
- Mounted text origins move 0.001 block along the inward normal: left `(1.177323006,0.922844514,0)`, right `(-1.177323006,0.922844514,0)`. Visibility thus measures the actual screen center instead of the section anchor.
- Screen local +z is its readable face. Left orientation is `Ry(-90) Rx(22.5)`; right is `Ry(90) Rx(22.5)`. Because YAML uses `rotateXYZ` rather than YX Euler order, the equivalent actual YAML rotations are `(x=90,y=-67.5,z=90)` and `(x=90,y=67.5,z=-90)`. A superficially plausible `{x:22.5,y:90}` would produce an incorrect plane.

## Current mounting status

Front2 and door30 generated display definitions are present. Existing side_display exterior LCDs provide20 real faces, but generated side-screen mounting remains a subsequent step. Original housing geometry is preserved. The earlier 2:1/8:3 location question is resolved: the 2:1 elements are housing halves, not additional screens. No new exterior geometry is needed.

Validation remains static/compile only; test suites and real server/client acceptance are deferred. Removing the mistaken XML changes resources only, with no schema version or runtime compatibility change.
