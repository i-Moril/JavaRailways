# RailWand PR Review Notes

Context: review of `feature/continuous-curve-api` against `feature/custom-car-models`.

Verification already run:

- `./gradlew test` passed.
- `git diff --check feature/custom-car-models...feature/continuous-curve-api` passed.

## PR Review Blockers (2026-06-29)

Issues to fix before merge:

1. `RailLocationWithFacing` BA-direction persistence is wrong.
   - File: `Core/src/main/java/work/art1st/mineRails/core/physics/rail/RailLocationWithFacing.kt`
   - `save()` stores `arcLengthIn(ViewDirection.AB)`, but `load()` reads that value directly from `rv.curve`.
   - For `direction == BA`, `rv.curve` is already reversed, so the loaded position is mirrored to `totalArcLength - savedArc`.
   - Fix: when loading BA, convert AB arc length to BA arc length first, or always get the point from `viewAB` and then construct the requested facing explicitly.

2. `RailWand.connectSelectedAnchors` leaks partially created rails on non-exception failure.
   - File: `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt`
   - If `createCurveBetweenAnchors(...)` returns `null` after earlier rails were already created, the method returns directly.
   - That bypasses cleanup and also bypasses pushing undo actions.
   - Fix: precompute all curves before registration, or rollback `createdRails` before returning from the failure branch.

3. Self-contained train package namespace handling is inconsistent.
   - Files:
     - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/PackageLoader.kt`
     - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/CarDefinition.kt`
     - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/ModelTreeBuilder.kt`
   - `PackageLoader` loads package cars with `CarLoader(dir, partRegistry)` but does not make the manifest/package name the car namespace.
   - A natural layout like `trains/s100/cars/head.yml` can produce `car.name == head`; then `carDef.namespace == head`, and model building resolves bare parts under `head/<part>` instead of the package registry namespace.
   - Fix: make packaged car definitions carry the package namespace for part resolution, or route `ModelTreeBuilder` part resolution through `packageName` instead of `carDef.namespace`.

4. `PlayerConfigSession.commit()` does not trigger the documented config change callbacks.
   - Files:
     - `Basic/src/main/java/work/art1st/mineRails/basic/api/ui/config/PlayerConfigSession.kt`
     - `Basic/src/main/java/work/art1st/mineRails/basic/impl/config/ConfigUIRegistry.kt`
   - API docs say session commit triggers `ConfigUI.onValueChanged`.
   - Current implementation only updates `ConfigItemImpl` and calls `item.commit()`, so `valueChangedCallback` / `onValueChanged` listeners do not see Dialog/session changes.
   - Fix: reuse `ConfigUIRegistry.setValue(...)` from session commit, or emit the same old/new callbacks for each dirty key.

Architecture note:

- This PR adds useful API surfaces (`Rail`, `RailView`, `ViewDirection`, `World.getRail(): Rail?`), but the new RailWand still imports `CoreWorld` and `CoreRail` directly.
- `requirement.md` says non-Core modules should use Core API unless necessary. If RailWand needs to create rails, the API likely still needs a rail factory or command-facing creation service.

## Curve And Server-Hang Diagnosis (2026-06-29)

Scope requested: focus on the two main runtime symptoms:

1. Slope rail display entities stay vertical to the ground, making rails look like stairs.
2. Server can freeze after restart when creating a straight rail from existing rails.

### 1. Slope display orientation

Likely root cause: generated `Curve.RawPoint.normal` is not orthogonal to `Curve.RawPoint.tangent`.

Evidence:

- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt`
  - `createStraightCurve(...)` sets `tangent = direction`, where `direction` can include Y on a slope.
  - It still sets `normal = Vector3d(0.0, 1.0, 0.0)`.
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/rail/RailExtension.kt`
  - `computeCurveRotation(...)` builds a rotation matrix from `tangent`, `normal`, and `binormal`.
  - If `tangent` and `normal` are not an orthonormal frame, display transforms are invalid or visually biased toward world-up.
- `Basic/src/main/java/work/art1st/mineRails/basic/curve/ConstraintSolver.kt`
  - `make3dNormal(...)` currently uses a positive slope component along the tangent direction.
  - For `tan3d = (tan2d, slope)`, that normal is not perpendicular to the tangent. The sign should be opposite, or the normal should be projected from world-up.

Fix direction:

- Add a shared helper to construct a stable frame:
  - normalize tangent.
  - compute `normal = up - tangent * up.dot(tangent)`.
  - normalize normal.
  - fallback to another reference axis if tangent is near vertical.
- Use that helper for:
  - RailWand straight-line RawPoints.
  - RailCommand straight-line RawPoints.
  - ConstraintSolver generated vertical-profile points.
- In `PrimeCurveImpl.getPointAt(...)`, after interpolating tangent and normal, re-project normal onto the plane perpendicular to tangent before returning `Curve.Point`.

### 2. Server freeze / card-dead risk

Primary root cause: straight rail generation still samples at `Curve.PRECISION = 0.001` meters instead of using the new continuous line segment representation.

Evidence:

- `Core/src/main/java/work/art1st/mineRails/core/api/physics/curve/Curve.kt`
  - `PRECISION` is `1e-3`.
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt`
  - `createStraightCurve(...)` computes `steps = maxOf(2, (length / Curve.PRECISION).toInt())`.
  - A 100m straight produces roughly 100,001 RawPoints; a 500m straight produces roughly 500,001 RawPoints.
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/command/RailCommand.kt`
  - The command path has the same dense straight-line generation.
- `Core/src/main/java/work/art1st/mineRails/core/physics/rail/curve/PrimeCurveImpl.kt`
  - The PR already added `Curve.SegmentType.LINE_START` / `LINE_END` and exact interpolation for that pair.
  - The straight-line creation paths are not using it.

Operational impact:

- Creation allocates a huge point list on the server thread.
- `CoreWorld.save()` persists all points into the world snapshot.
- On restart, `WorldPersistence.tryLoadSnapshot(...)` reads the large JSON snapshot back.
- `RailWorldFactory.createForBukkit(...)` restores render metadata and `RailVisualBootstrap.fromPersisted(...)` registers visual segments.
- `RailVisualBootstrap.registerSegments(...)` immediately calls `RailExtension.ensureBuilt()`, synchronously creating display entities.
- `CoreWorld.getNearestRail(...)` and train creation use `curve.getNearestPoint(...)`, which streams the full curve, making dense curves expensive for nearest-point queries too.

Fix direction:

- Change RailWand and RailCommand straight-line generation to emit exactly two RawPoints:
  - start point with `Curve.SegmentType.LINE_START`
  - end point with `Curve.SegmentType.LINE_END`
- Keep `PrimeCurveImpl` exact interpolation for line segments.
- Consider making render entity construction incremental/batched rather than immediately building all display entities in `registerSegments(...)`, especially on server startup.
- Consider adding a more efficient nearest-point implementation for continuous line segments, instead of relying on full `stream()` scans.

### Fix Priority

1. Replace dense straight-line RawPoint generation with `LINE_START` / `LINE_END`.
2. Normalize/orthogonalize tangent-normal frames in all curve generators.
3. Re-orthogonalize interpolated normal in `PrimeCurveImpl.getPointAt(...)`.
4. Only after the above, revisit render bootstrapping and nearest-point performance.

### UI Follow-up

Not investigated in detail yet. Initial code read suggests anchor display lifecycle is incomplete:

- anchor displays are spawned only during create/edit/refresh flows.
- restart does not automatically refresh displays for players.
- visibility is not tied to currently holding RailWand.
- desired behavior should be: show anchors only while holding RailWand, hide them when switching away, and refresh from persisted anchors after restart when a player equips RailWand.
