# Research: passenger jerk and boundary-release feedback

- Query: Why does a walking passenger jerk relative to its carriage when fixed seats remain smooth, including with no WASD input? Latest clarified exit symptom: movement briefly stalls at the boundary, then continuing to walk succeeds; this occurs on a stationary train too. The earlier pullback/remount interpretation is superseded.
- Scope: internal code and locally cached Paper 1.21.11 implementation; no server mutations, network, source/test edits, or git operations.
- Date: 2026-11-13
- Supplied baseline: `feat/passenger-movement`, HEAD `362ff669`; branch state supplied by the coordinating agent. Research does not run git operations.

## Latest feedback interpretation and conclusion

The user clarified that the player is **briefly blocked at the boundary, not pulled back into the carriage**. The ride-to-walk transition, rather than re-mounting, is now the relevant investigation. Exact running Folia 26.1.2 bytecode confirms that the current native `stopRiding` path always opens a DISMOUNT teleport-confirmation window; while that confirmation is pending, incoming normal movement packets update rotation but skip XYZ. This is a concrete, stationary-compatible interruption in the existing handoff. It is not a boundary-margin rule and requires no recapture theory. The exact duration/visible client behavior still needs a real client trace.

The exact-version follow-up and accepted handoff direction are recorded at the end of this note. Earlier moving-platform and pullback sections are investigation history, not claims about the user's clarified symptom.

## Findings

### Confirmed synchronization mismatch

Fixed seats consume the frame that the carriage actually applies, and queue their visible movement inside the same packet frame:

1. `WorldManager.dispatchPublishedTrainFrames` captures the mailbox-selected `trainFrame` and creates immutable car frame values (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt:750-770`).
2. Its latest-only train dispatcher permits one in-flight frame and one pending latest frame (`WorldManager.kt:727-728`). `scheduleTrainFrame` dispatches the chosen values to location owners; the chunk callback encloses all matching car applications in `plugin.trainPacketBundles.frame` (`WorldManager.kt:774-805`, especially `787-792`).
3. `CarExtension.applyPublishedFrame` checks exact publication stream/revision and installs `latestFrame` before committing (`.../extension/train/CarExtension.kt:331-378`). `renderFrame()` reads that installed frame (`CarExtension.kt:716`), and `updateModelBundled` applies its body and bogie transforms to the tree (`CarExtension.kt:539-595`).
4. `SeatModel.updateState` derives the carrier target from that tree update (`.../render/model/SeatModel.kt:129-154`). `moveCarrier` calls `packetMotion.renderSeatCarrier` immediately, then dispatches only the authority movement to its entity owner with a `movementRevision` freshness check (`SeatModel.kt:283-294`). Therefore the virtual seat packet shares the carriage packet frame; authority does not choose a new physics pose.

Walking passengers reuse the packet protocol but bypass that presentation timing:

1. `watch` creates an independent per-player entity fixed-rate task (`.../passenger/PassengerMovementManager.kt:48-61`). Every active session calls `platforms.capture` from that task (`PassengerMovementManager.kt:142-157`).
2. Registry capture directly samples the **latest physics** `registration.train.publishedFrame` (`.../passenger/PassengerPlatforms.kt:68-91`, exact read at `72`). This is atomic and internally consistent across cars, but has no relationship to which revision is currently executing or rendered in the train frame dispatcher.
3. `PacketPassengerCarrierAccess.move` calls both visible render and authority movement consecutively on the entity tick (`.../passenger/PassengerCarrierAccess.kt:24-27`). It runs outside the carriage packet frame.
4. The bundler uses a `ThreadLocal` active frame; without an active frame each enqueue is immediately sent as its own packet group (`.../render/backend/TrainPacketBundleCoordinator.kt:72-98`). Merely sharing the `TrainPacketBundleCoordinator` object does not merge two independently scheduled callbacks.
5. `PacketVirtualSeat.sync` uses the same position-sync packet builder for both fixed seats and passengers (`.../render/backend/PacketVirtualSeat.kt:32-67`, `149-153`). Thus there is no source evidence of a different interpolation protocol to tune. The timing/frame mismatch exists with direction zero because `PassengerWalking.advance` preserves local coordinates but still projects them through the newly sampled pose (`PassengerGeometry.kt:102-104`).

This is a concrete source-level defect matching the no-input jerk report. Its exact perceived magnitude or server/client scheduling frequency remains a game observation, not a unit-test fact.

### Smallest reuse-based correction

- Keep per-player owner ticks for reading `currentInput`, yaw, and fixed-speed local movement, including stationary trains.
- Separate `PassengerCarrierAccess` visible render from authority application, following `SeatModel.moveCarrier`, rather than creating another packet protocol.
- Propagate the exact already-selected train frame into the car/presentation boundary. `PendingTrainFrame` currently retains only stream/revision plus per-car values, so capture/provide the exact full `TrainFrameSnapshot` there if the registry needs all cars for seamless crossing; do not re-read `train.publishedFrame` inside a later callback.
- At `CarExtension.updateModelBundled` / accepted frame application, publish passenger visual targets derived from that exact frame while still inside the existing `trainPacketBundles.frame`. Queue authority to its owning entity thread with a monotonically checked presentation sequence, like fixed seats, so an old queued target cannot apply after a newer walking/presentation target or after release.
- Static local walking should use the last **applied presentation frame**, not the latest unpublished-to-render physics frame. A tick advancing physics must not cause an independent carrier visual update before the carriage frame is applied. Walking during moving-frame publication needs one defined merge point so an old independently sampled visual packet cannot follow a newer render packet.
- Do not integrate walking a second time from each car callback or each frame revision. Geometry-only/metadata frames and multiple cars must not multiply the 0.215 m per entity tick input distance.
- Reuse existing `TrainPacketBundleCoordinator` and `PacketEntityMotion`; no Core physics, persistent/config fields, WorldManager world-tick cadence, or interpolation-constant changes are justified.

Candidate implementation scope: `passenger/PassengerMovementManager.kt`, `PassengerPlatforms.kt`, `PassengerCarrierAccess.kt`, `extension/train/CarExtension.kt`, and the narrow `logic/WorldManager.kt` frame envelope seam if needed to preserve the exact full frame. Existing backend protocol can stay intact. Tests belong to PassengerMovementTest plus a production bundle/frame propagation test; the coordinating implement agent owns those changes.

### Regression tests that distinguish the bug

The current two-real-car test publishes physics then immediately advances the entity clock and expects the passenger to follow (`.../src/test/java/.../passenger/PassengerMovementTest.kt:203-233`). It never delays carriage presentation, so it validates atomic physics sampling but cannot catch this defect.

Add a test which mounts at presented revision N, publishes physics N+1 but holds its render dispatch, then advances a **zero-input** entity tick. No passenger visible target may run ahead of the carriage. Apply N+1 in the existing packet frame and verify passenger + carriage targets occur in that same viewer bundle and use the exact supplied frame, even if physics has meanwhile reached N+2. Deliver a stale queued authority target after a newer target and after release to prove freshness and termination fences. Retain a stationary train test showing held WASD still advances at 0.215 m per owner tick with no new Core frame or double integration.

### Current boundary predicate and release path

- Platform extents are grid-center bounds plus cell half-width 0.5 m (`.../render/model/PlatformModel.kt:40-43`), with no passenger horizontal exit margin.
- `PassengerSurface.contains` permits only floating tolerance `1e-7`; first boarding contact allows local height difference up to 0.15 m (`PassengerGeometry.kt:47-53`, `135-137`). Old platform activation margins remain a separate watch/audience path (`PlatformModel.kt:155-172`, `233-240`).
- Walking computes the first rectangle edge; no connected target is allowed unless its plane contains the boundary within `1e-7`, and the requested direction enters it (`PassengerGeometry.kt:89-127`). It consumes the full remaining step to an outside feet target rather than clamping to the edge (`120-122`).
- Manager first moves the mounted carrier to that outside feet plus the 0.6 m carrier lift, then `end(session, outsideFeet)` (`PassengerMovementManager.kt:159-170`). `end` atomically sets `ending` before owner cleanup, calls dismount, releases the packet carrier only after detach completes, and removes the session after cleanup (`178-235`). Normal exit does not remove candidate platform watches if they remain active (`238-240`).
- `SeatAuthorityMover.dismountAt` moves the marker origin down to the exact outside feet without repositioning its passenger, then calls native `removePassenger` (`.../render/backend/SeatAuthorityMover.kt:14-24`). It does not call fixed-seat `alignAfterDismount`, whose next-tick player teleport is confined to `SeatModel.kt:227-235`.
- No stale manager movement exists after the `ending` fence in the current single owner-tick path. The already-confirmed presentation phase mismatch can affect how the displayed edge relates to the selected exit frame; changing geometric rules or adding a cooldown is not yet justified.

### Cached native behavior checked

Local source bundle: `/home/user/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_c9a53f01acfb65c4a1dc2a7800c5c82b06fd8bca489383ff45ca9abd7808383a/output.jar` (Paper dev bundle used by this workspace; Minecraft 1.21.11). No external web reference was needed.

- `org/bukkit/craftbukkit/entity/CraftEntity.java:522-526`: `removePassenger` invokes passenger handle `stopRiding` and returns true, rather than proving an uncancelled dismount.
- `net/minecraft/world/entity/LivingEntity.java:3920-3925`: after accepted detach, `dismountVehicle` runs; at `2935-2956` it uses carrier `getDismountLocationForPassenger` then invokes passenger `dismountTo`.
- `net/minecraft/world/entity/Entity.java:4658-4659`: default dismount target is carrier X/Z and bounding-box maxY; this matches the zero-height marker-origin maneuver.
- `net/minecraft/server/level/ServerPlayer.java:2820-2831`: removing the vehicle sends a native set-passengers update; the existing PacketEntityMotion filter redirects this to the virtual seat (`PacketEntityMotion.kt:380-394`). At `2231-2245`, `dismountTo` goes through connection teleport with cause DISMOUNT.
- `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1833-1841`: an equal `from` / `to` teleport performs internal synchronization **without firing PlayerTeleportEvent**. Consequently PassengerMovementManager.onTeleport cannot be relied upon to forget the watch on a normal exact-feet release. Internal teleport (`1872-1904`) updates position, tracks the outstanding teleport confirmation, and emits the client player position packet.

The release test's carrier double directly assigns the requested feet after removePassenger (`PassengerMovementTest.kt:367-370`); it does not execute these native event and packet behaviors. Therefore that test cannot rule out native correction/remount behavior.

## Related specs

- `.trellis/spec/bukkit-plugin/backend/passenger-movement.md`: current standalone entity tick / physics capture description will need updating after a synchronized presentation fix.
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md:143-166`: immutable selected render frames, same-chunk packet bundle, exact runtime stream, stale revision gate.
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`: explicit entity ownership and real completion for cleanup; preserve presentationOwner cleanup lifetime.

## Caveats / Not Found

- Initial research boundary (historical): synchronization was confirmed before exact runtime native classes became available. The later exact 26.1.2 section establishes the boundary teleport-confirmation interruption. A packet trace/video/native client fixture has not yet established the duration and visual continuity seen by the user.
- A **conditional remount path** exists: normal release can leave its watch alive; if the next presented/physics geometry moves over the outside player's feet while local height remains within 0.15 m, next `tick` can create another carrier (`PassengerMovementManager.kt:97-115`). This requires concrete motion/location values and must not be presented as the observed incident's proven cause. Forced `CraftEntity.addPassenger` ignores vanilla boarding cooldown (`CraftEntity.java:514-518`). A no-input presentation regression and a just-released moving-platform regression should first characterize the real transition; do not invent a time cooldown or enlarged exit boundary.
- No production files, tests, specs, or server files were changed. Only this research note is owned here.

## Follow-up: static-train release and production geometry probe

The user subsequently confirmed that the exit pullback also occurs while the train is stationary. This **rules out the moving-platform re-cover scenario as a sufficient explanation** of the reported defect. It remains an independently reachable transition only.

Research probe `PassengerBoundaryProbe.java` invokes the already compiled production `PassengerWalking.advance` and `PassengerSurface.contact`; it does not instantiate a Bukkit server, touch runtime state, implement substitutes for those predicates, or modify production/tests. Run with the workspace's Java 25 toolchain and classpath comprising `BukkitPlugin/build/classes/kotlin/main`, Kotlin stdlib 2.3.0 and JOML 1.10.8. (Default shell Java 21 cannot load this workspace's existing Java 25 class files.)

Observed output (exit 0):

```text
frame N centerZ=0; local startZ=0.99; input +Z; step=0.215
outside feet=PassengerPoint(x=0.0, y=0.0, z=1.205)
same frame contact=null
frame N+1 centerZ=+0.3 (train 6 m/s); outside feet unchanged
new frame contact=PassengerPoint(x=0.0, y=0.0, z=0.905)
new frame contact after 0.08m fall=PassengerPoint(x=0.0, y=0.0, z=0.905)
side +X exit under pure +Z train motion=null
```

This directly proves that this ordinary **static** surface cannot recapture its outside point through the existing contact predicate alone, and limits the dynamic recapture case to relevant train motion and exit direction. It is a production-geometry regression probe, not a native/session/client reproduction.

### Packet release/correction audit

- `PacketEntityMotion.release` removes the exact managed carrier (`PacketEntityMotion.kt:224-230`); `ManagedEntity.retire` cancels the pending target gate, clears viewers and destroys the virtual seat (`426-430`).
- `PacketVirtualSeat.destroy` clears bookkeeping and emits only `DestroyEntities` (`PacketVirtualSeat.kt:92-101`). Neither function issues a previous-position target or player teleport.
- Native `SET_PASSENGERS` is redirected while the managed carrier still exists (`PacketEntityMotion.kt:380-394`). Native `ServerPlayer.removeVehicle` immediately emits that empty-passengers update before `LivingEntity.stopRiding` performs `dismountVehicle` and its DISMOUNT position synchronization. Release occurs after the synchronous detach invocation completes.
- `SeatTeleportCorrectionGate.mark` is used only for managed entities **without** a virtual seat (`PacketEntityMotion.kt:263-272`); this passenger feature always registers one. Thus normal passenger movement does not create a correction marker that could swallow its own DISMOUNT teleport. A marker originating in a different feature must not be invented without concrete runtime evidence.
- `SeatAuthorityMover.move` does not collide against the shulkers: `configureSeatCarrier` sets native noPhysics (`SeatCarrierConfiguration.kt:15`), and cached Entity.java:1126-1131 calls exact `setPos` in that case.
- Cached `ArmorStand.java` has no `getDismountLocationForPassenger` override; marker dimensions are `(0,0)` (`60`, `719-727`). `Player.removeVehicle` only resets boarding cooldown after the superclass (`Player.java:934-937`); it does not restore a previous position. This further supports the existing marker-origin calculation in the cached version.

### Initial version boundary (historical; resolved below)

The implementation agent reports the real server uses Minecraft 26.1.2. The current compile-time dev bundle and locally found runtime jars are 1.21.11 (`BukkitPlugin/build.gradle:89-90`; `BukkitPlugin/run/versions/1.21.11/paper-1.21.11.jar`). The Gradle default run-paper target is 26.1.2 (`BukkitPlugin/build.gradle:47`), which does not prove native implementation identity. These cached native findings **must not be claimed as verified 26.1.2 behavior**. At this stage the next check was exact runtime bytecode. The coordinator subsequently supplied six exact running classes; the section below records their verified transition/confirmation behavior. The earlier pullback interpretation was superseded by the user's clarification to a brief stall. Actual client smoothness remains a game-verification boundary, and no change to boundary margins/cooldowns is justified.

## Exact running Folia 26.1.2: boundary stall mechanism

### Artifact identity and method evidence

The coordinating agent extracted six relevant classes from the running server into `/tmp/jr-passenger-feedback-native-26.1.2.jar`; its `extraction.json` records:

- Exact source: `/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96/versions/26.1.2/folia-26.1.2.jar`.
- Source JAR SHA-256: `1c6040315d5b608916ca6811f3a93c72ac950d0ca14cc2dd64aaa85fb27b124d`.
- Classes inspected read-only with the existing Java 25 `javap -p -c -l`: `Entity`, `LivingEntity`, `ServerPlayer`, `ServerGamePacketListenerImpl`, `CraftEntity`, `CraftPlayer`.

Line references in this section are **the exact 26.1.2 class LineNumberTable**, not line numbers borrowed from cached 1.21.11 sources. Bytecode offsets disambiguate the relevant statements.

1. `CraftEntity.removePassenger` calls native `passenger.stopRiding()` and unconditionally returns true. It does not bypass the living-player dismount-positioning path.
2. `LivingEntity.stopRiding(boolean)` (source lines 3997-4002; offsets 0-44) captures the old vehicle, calls its superclass, and after an accepted detach invokes `dismountVehicle(oldVehicle)` (offset 39). The ordinary live/non-portal branch calls the carrier's `getDismountLocationForPassenger` then passenger `dismountTo`.
3. `Entity.getDismountLocationForPassenger` (line 5535) is still `Vec3(carrierX, carrierBB.maxY, carrierZ)`. This matches the old marker-origin-lowering calculation. No changed X/Z targeting in this inspected class explains a return into the car.
4. `ServerPlayer.dismountTo` (line 2755; offsets 0-8) calls `teleportTo(..., TeleportCause.DISMOUNT)`. Its cause-aware `teleportTo` constructs an absolute destination with relative DELTA/ROTATION flags and calls the connection teleport method.
5. `ServerGamePacketListenerImpl.teleport(PositionMoveRotation, Set, cause)` (lines 1923-1950) checks `from.equals(to)` at offsets 56-63. Equal positions still call `internalTeleport` at offset 69. They only skip the Bukkit teleport event; they **do not skip the network teleport or its confirmation**.
6. `internalTeleport` (lines 1977-1993; offsets 113-233) marks `justTeleported`, increments `awaitingTeleport`, applies the destination, records `awaitingPositionFromClient`, updates last plugin-event position/rotation, and sends `ClientboundPlayerPositionPacket` with the new teleport ID.
7. `handleMovePlayer` (lines 1512-1513; offsets 136-153) first calls `updateAwaitingTeleport()`. While the awaiting position exists, the method only applies `absSnapRotationTo(yaw,pitch)` and jumps past normal XYZ movement. `updateAwaitingTeleport` (lines 1838-1854) returns true whenever that field is non-null.
8. `handleAcceptTeleportPacket` (lines 804-826) only accepts the matching current teleport ID. It snaps the player to the awaiting destination, updates last-good coordinates, and clears `awaitingPositionFromClient` (line 823, bytecode offset 126-128). Only then can subsequent normal move packets take the ordinary XYZ path. An older teleport ID is ignored.

Therefore the former release sequence—mounted final move to outside feet, marker lowering, native `stopRiding`, immediate carrier cleanup—contains a **mandatory normal-movement interruption until a native client confirmation returns**, even on a stationary train and even when the requested dismount position equals the existing authoritative feet position. Removing a redundant carrier move by itself leaves this confirmation window intact. The precise on-screen stall duration cannot be derived from bytecode; RTT/client tick timing requires real observation.

### Accepted existing-mechanism handoff direction

The coordinator accepted a narrowly scoped controlled detach for this owned passenger session:

1. On the shared carrier/player entity owner, use the existing `SeatAuthorityMover.move` to the desired outside carrier location (outside feet plus the existing 0.6 m lift). This updates the carrier and its passenger's authoritative feet via `positionRider`.
2. Invoke the native player's `removeVehicle()` directly. It retains native detach/list updates/events and `ServerPlayer`'s normal set-passengers packet, but does **not** enter `LivingEntity.stopRiding`'s subsequent `dismountVehicle`/DISMOUNT teleport. Keep the existing virtual-seat protocol; do not add a new position-packet fallback or suppress teleport acknowledgements.
3. Call that player's existing `connection.resetPosition()` on the same owner to align native movement baselines to the actual current position. This does not emit a player teleport packet or create an awaiting-confirmation window.

This research does not implement the change. The production implement agent owns the native handoff and tests. The visible client handoff and virtual-seat detach ordering still require game verification; eliminating the identified server confirmation window is a source-level result, not proof that every client interpolation effect disappears.

### Exact cancellation and ownership conditions

- `Entity.removeVehicle(boolean)` (lines 3361-3370) captures the old vehicle; temporarily clears `player.vehicle` (offsets 12-14); calls `oldVehicle.removePassenger(player, suppressCancellation)` (17-23); restores the old `player.vehicle` if that returns false (26-28); then performs the normal dismount game-event side effect.
- `Entity.removePassenger(Entity, boolean)` emits the usual `EntityDismountEvent` (lines 3431-3437; offsets 165-217). With the existing default `suppressCancellation=false`, the event is cancellable. A cancelled event returns false before changing the carrier's passengers list. An accepted event updates that list (lines 3441-3444; offsets 218-284) and writes the ordinary boarding cooldown (line 3447). A marker ArmorStand is not a Bukkit Vehicle; the general method also retains the vehicle-exit event branch for applicable vehicle types.
- `ServerPlayer.removeVehicle(boolean)` (lines 3383-3394) calls its superclass, processes existing vehicle-effect cleanup, and sends `ClientboundSetPassengersPacket(oldVehicle)` (3391-3392; offsets 81-97). Even cancellation can therefore send the unchanged passenger list. The method is void, and `CraftEntity.removePassenger`'s boolean was not an acceptance proof either. When completion needs to prove detach, inspect the actual remaining vehicle identity. Do not change cancellation policy to `true` silently.
- Exact 26.1.2 `CraftPlayer.getHandle()` has no ownership guard. `connection.resetPosition()` (lines 461-480) also has no scheduler/ownership guard: it directly rewrites first-good and last-good X/Y/Z and refreshes root-vehicle movement baselines from actual current entity state. The call is only valid on the player's owner. The current boundary cleanup already checks `dispatcher.owns(player)` and that the player still rides this carrier; preserve that contract and keep move/detach/reset synchronous in one owner action.
- If an event changes the player's state, reset must reflect the **actual** resulting player position; do not schedule a later move back to the pre-event outside target. Already accepted external teleports must retain priority.

### Remaining evidence boundary

The six exact native classes prove the server confirmation window and the direct-detach distinction. They do not include the client's `ClientPacketListener` or exact PacketEvents 2.14.0 outbound handler. No claim is made here that a late native empty-passengers packet definitely misses the old virtual-seat mapping, or that destruction definitely snaps a client to an old interpolation position. Those would require exact outbound interception timing/client observation. The current feature's correction gate does not intentionally mark virtual-seat moves; adding suppression of native player-position packets would be a new protocol and is not justified.

Minimal game observation after the owned handoff fix: hold a movement key continuously through a stationary platform edge, confirm one accepted detach and no native DISMOUNT player-position/teleport-confirmation pair from this controlled exit, then confirm the next ordinary movement packet advances feet continuously and the virtual carrier is destroyed. Repeat on a moving carriage for the separately fixed synchronized-frame path. No cooldown, expanded exit bounds, or synthetic re-mount restriction belongs to this fix.

### Concrete rapid-exit regression to handle before completing the direct-detach change

The implementing agent identified a specific outstanding-confirmation case; exact runtime bytecode verifies it:

- Actual `ServerPlayer.startRiding(Entity, boolean, boolean)` positions the rider, calls `connection.teleport(currentPlayerPosition, Relative.ROTATION)`, and sends native set-passengers. Boarding therefore creates its own pending teleport confirmation.
- `resetPosition()` only rewrites movement baselines and root-vehicle state. Neither it nor `removeVehicle()` clears/updates `awaitingPositionFromClient` or the current teleport ID.
- If boarding created pending position A, and this feature can walk the carrier outside to B and release **before A's real confirmation arrives**, the later matching boarding confirmation still executes `absSnapTo(A)`. With the old boundary DISMOUNT teleport, a fresh ID/destination B superseded A; the proposed direct detach deliberately removes that superseding operation.
- Inspection of all writes to `awaitingPositionFromClient` in the exact connection class found `internalTeleport`, `handleAcceptTeleportPacket`, and the stop-sleep command branch. The public method list has no pending-await getter/cancellation API. `resetPosition` is not an acknowledgement substitute.

This is a concrete possible regression of a rapid one-tick exit with network latency, not the cause of the user's clarified existing brief-stall report. It must be considered in implementation/tests before claiming a fully reliable handoff. The implementing agent and coordinator were notified immediately. Do not solve it by fabricating teleport confirmations, silently clearing private connection state, adding cooldowns, or assuming the initial handshake always finishes in one tick. An owned boarding-confirmation phase can correlate the actual native mount teleport and its actual confirmation through existing packet infrastructure if the coordinator chooses that necessary scope; this research does not prescribe or implement an unreviewed packet mechanism.

### Bounded follow-up: no startRiding overload disables boarding teleport

Exact 26.1.2 `javap -p -c -l` confirms that the public overloads are `Entity.startRiding(Entity)` and `startRiding(Entity, boolean force, boolean sendEventAndTriggers)`. There is no additional teleport-control boolean:

- `Entity.startRiding(Entity)` delegates with `(force=false, sendEventAndTriggers=true)` (source line 3278, offsets 0-7).
- The third parameter is named `sendEventAndTriggers` in the exact runtime LocalVariableTable. It is read only at `Entity.startRiding` offset 257; the conditional block at offsets 261-305 (source lines 3330-3334) emits `GameEvent.ENTITY_MOUNT` and advancement triggers. The Bukkit `EntityMountEvent` at offsets 182-225 is outside that conditional.
- `ServerPlayer.startRiding(Entity, boolean, boolean)` passes both booleans to its superclass at offsets 0-4. Once the superclass succeeds (source line 3367), **every** successful path performs `positionRider` (line 3368, offsets 10-12), `connection.teleport(currentPlayerPosition, Relative.ROTATION)` (line 3369, offsets 15-38), and native set-passengers (line 3374, offsets 70-82). Passing the third parameter as false still creates the boarding teleport/await state.
- Compile-time cached 1.21.11 exposes the same overload: `Entity.java:3240` calls the third flag `triggerEvents`, with its restricted use at `3283-3287`; `ServerPlayer.java:2804-2813` unconditionally sends the boarding teleport on superclass success. The compiled API therefore has no simpler supported skip-teleport seam either.

Existing gate reuse limits, without implementing tracking:

- `PacketEntityMotion.LatestTargetGate` (`PacketEntityMotion.kt:502-535` in the inspected baseline) serializes/coalesces authority teleport targets and has irreversible cancellation. It neither knows native player teleport IDs nor proves a confirmation was consumed.
- `SeatTeleportCorrectionGate` (`SeatTeleportCorrectionGate.kt:14-43`, `70-72`) correlates by player UUID and approximate destination within three metres for five seconds. Its existing consumer cancels a matching outbound player-position packet and synthesizes an acknowledgement (`PacketEntityMotion.kt:335-355`). That is not a real boarding-confirmation tracker and cannot be treated as one merely to reuse a class.
- Any owned-confirmation state would need the exact controlled session/carrier and exact native teleport ID, lifecycle invalidation, and separation from external teleports. Seeing an incoming confirmation in a Netty packet listener does not itself prove the native owner-thread `handleAcceptTeleportPacket` has applied it; publishing `exitReady` there can race ahead of the actual state clear. The design must establish that ordering explicitly. This note adds no tracker, cooldown, fallback, synthetic acknowledgment, or production change.
