# Train control dialog, key binding, ownership, and reverse behavior plan

## Goal

Replace the current implicit key-driven manual control with a proper train key workflow and Paper Dialog control UI:

1. A generic train key can be obtained by command.
2. While seated in a driver seat, right-click with the key binds that key to the train.
3. Right-click again opens a train control Dialog.
4. Before taking control, the Dialog shows who controls the train, offers a control-acquire attempt, and exposes only YAML-declared executable actions.
5. Manual control ownership is exclusive: a train has at most one Controller. Acquiring control fails if another player already controls it.
6. On acquiring control, direction resets to `N`, handle resets to `N`, and current train speed is preserved.
7. After acquiring control, Dialog offers automatic-control activation, direction selection `F/N/R`, and executable actions.
8. If the key-bound train is farther than a configurable distance from the player, the key auto-unbinds.
9. In manual control, reverse direction `R` uses negative speed without triggering physical `reverse()`.
10. If the player acquires control from the opposite cab, trigger train `reverse()` once.
11. Train YAML/JS can define generic `on reverse` event listeners; switching headlights is only one possible listener use case.

## Current state summary

Implemented on `feature/control`:

- `RealisticManualController` with realistic longitudinal dynamics.
- WASD input through `PlayerInputEvent`:
  - currently `S` pulls toward power, `W` pushes toward brake, `D` centers.
- `driver: true` metadata for seat parts.
- Generic `TrainKeyItem` exists and `/jr train key` gives it.
- Current control acquisition is still implicit: if a keyed player is in a driver seat, input auto-creates/acquires the controller.
- Current key is generic and unbound.
- S100 runtime package has been synced with `control.yml` and car `control` blocks.

This plan replaces the implicit acquisition with explicit key binding + Dialog acquisition.

---

## Design decisions

### Key binding model

Use a **generic key item type** plus per-item runtime binding metadata:

- Generic key marker:
  - `train_key = 1`
- Optional binding:
  - `bound_train_id = <train.id>`
  - `bound_world_id = <bukkit-world-uuid>`

The item is still a generic train key. Binding only selects which train its Dialog operates on.

Right-click behavior:

1. If held item is not a train key: ignore.
2. If the key has no valid binding:
   - Player must be seated in a `driver: true` seat.
   - Bind the key to that seat's train.
   - Open the Dialog or show actionbar confirmation; preferred: bind then open Dialog.
3. If key has a valid binding:
   - If train is loaded and within configured binding distance, open Dialog.
   - If missing/out of range, clear binding and tell player via actionbar.

### Binding distance

Add global config:

```yml
train-key-bind-distance: 10.0
```

Meaning: if the distance from the player to **every car** in the bound train is greater than this value, unbind.

Distance check should use:

```kotlin
train.cars.minOf { it.position.distance(player.position) } <= maxDistance
```

This should run:

- On key right-click before opening Dialog.
- Periodically while a player holds a bound key, e.g. every 20 ticks.
- When attempting WASD control.

### Control ownership

Introduce an explicit train-control ownership registry, not just `playerControllers`:

```kotlin
data class ManualControlSession(
    val trainId: String,
    val playerId: UUID,
    val controller: RealisticManualController
)
```

Recommended manager:

```text
BukkitPlugin/src/main/java/.../control/TrainControlManager.kt
```

Responsibilities:

- resolve key binding
- bind/unbind key
- acquire manual control
- release manual control
- query current owner
- tick key distance/unbind checks
- expose controller for WASD

Exclusive ownership rule:

- If train has no controller: acquire succeeds.
- If train has `RealisticManualController` owned by same player: acquire succeeds/idempotent.
- If train has `RealisticManualController` owned by another player: acquire fails.
- If train has another controller type (future automatic controller): manual acquire should fail unless that controller has opted into yielding, or the Dialog action explicitly switches mode.

### Preserve speed on acquire

`TrainController.speed` currently has protected setter. To preserve current train speed:

- Add constructor or method to `RealisticManualController`:

```kotlin
initialSpeed: Double = train.speed
```

or:

```kotlin
fun initializeSpeed(speed: Double)
```

On acquire:

```kotlin
val previousSpeed = train.speed
controller.setDirection(NEUTRAL)
controller.coast()
controller.initializeSpeed(previousSpeed)
```

Handle and direction reset to `N`, but speed remains previousSpeed and then dynamics naturally coasts/brakes according to forces.

### Direction semantics

Manual controller should separate:

- handle notch: `B... / N / P...`
- direction: `F / N / R`

Actionbar displays both:

```text
方向: F  挡位: P3  速度: 12.3 km/h
```

Movement semantics:

- `F`: positive controller speed, `train.moveTrainForward(+distance)`.
- `N`: no traction should be applied; train coasts with current signed speed toward 0 via resistance/brake if any.
- `R`: controller speed is negative in display/dynamics, but core movement still needs a non-negative distance API.

Because `Train.moveTrainForward(deltaS)` requires `deltaS >= 0`, implement `R` in one of two ways:

Preferred minimal-core approach:

- For `R`, when distance magnitude is positive, call `train.moveTrainForward(distanceMagnitude)` after ensuring the train's logical facing matches the intended movement direction.
- But requirement says R should **not trigger reverse** during manual driving.
- Therefore use signed speed internally for display/control, but for actual movement with current core API we need a new core API or a strategy.

Recommended core change:

```kotlin
fun moveTrainSigned(deltaS: Double)
```

or allow `moveTrainForward` equivalent for negative motion:

- `deltaS > 0`: existing forward logic.
- `deltaS < 0`: move from tail/head in reverse direction without toggling `isReversed` and without firing `reverse()`.

This is needed to satisfy: **R sets speed negative but does not trigger reverse**.

Phase this carefully because collision/signal logic is forward-head based today.

### Opposite cab acquire triggers reverse

When binding/acquiring from a driver seat, we need know whether the seat belongs to the current train head cab or tail cab.

Add seat metadata:

```yml
interaction:
  driver: true
  cab: front|rear
```

or generic:

```yml
interaction:
  driver: true
  control-end: head|tail
```

S100:

- `head` cab driver seat: `control-end: head`
- `head_tail` cab driver seat: `control-end: tail`

On acquire:

- Determine current logical train head relative to the seat's car/end.
- If player is in the opposite cab for current train orientation, call `train.reverse()` once.
- This is a **control acquisition reverse trigger**, distinct from selecting `R` while already controlling.

### Reverse event listener / on reverse behavior

Add a train-level reverse event hook called when `CoreTrain.reverse()` is triggered intentionally. This should be treated as a generic event listener mechanism, not as a headlight-specific feature:

Options:

1. Core event:
   - Add `TrainReverseEvent(train)` to core event system.
   - Fire inside `CoreTrain.reverse()`.
   - Bukkit TrainExtension listens/applies behavior/action.

2. Bukkit-only wrapper:
   - Replace command/control acquisition calls with helper `triggerTrainReverse(train)` that calls `train.reverse()` and then `train.renderer.onReverse()`.
   - Less complete because other code calling `train.reverse()` bypasses hook.

Recommended: core event or TrainExtension hook around `CoreTrain.reverse()`.

YAML/JS behavior API:

- YAML manifest or actions can map the event to one or more actions:

```yml
events:
  reverse:
    actions:
      - some-train-specific-action
```

or behavior.js can implement a listener:

```js
function onReverse(state) {
  log(`reverse triggered, reversed=${state.reversed}`)
  // Example only: a model package may choose to swap headlights here.
  // Other packages may update displays, sounds, announcements, etc.
}
```

Simpler first phase:

- Add `behavior.js` method call:

```kotlin
train.renderer.callBehavior("onReverse", listOf(train.isReversed))
```

- If missing, no-op.

For S100, swapping headlight display states can be used as a sample `onReverse` listener implementation, but the engine should expose the event generically.

### Executable YAML actions

Current `actions.yml` actions are all internal. Add metadata:

```yml
actions:
  door-left-open:
    display-name: "Open left doors"
    executable: true
    category: doors
    requires-control: false
    animations: ...
```

Fields:

| Field | Default | Meaning |
|---|---:|---|
| `display-name` | action key | Dialog label |
| `executable` | false | Show in train Dialog |
| `requires-control` | false | Whether user must own manual control |
| `enabled-when` | optional later | Future condition expression |

S100 should mark only safe public operations executable, e.g.:

```yml
executable: true
```

for:

- `door-left-open`
- `door-left-close`
- `door-right-open`
- `door-right-close`

---

## Implementation phases

### Phase 1: Config/data model extensions

Files:

- `JavaRailwaysConfig.kt`
- `PartInteractionConfig.kt`
- `PartRegistry.kt`
- `TrainActionConfig.kt`
- tests under `BukkitPlugin/src/test/...`

Tasks:

1. Add `trainKeyBindDistance: Double = 10.0`.
2. Add `interaction.control-end` / `interaction.cab` metadata for driver seats.
3. Add action metadata:
   - `displayName`
   - `executable`
   - `requiresControl`
4. Add tests for parsing.

Commit:

```text
Control dialog phase 1: parse control UI metadata
```

### Phase 2: Key binding manager

Files:

- `TrainKeyItem.kt`
- new `TrainControlManager.kt`
- `BukkitPlugin.kt`

Tasks:

1. Add key item PDC binding fields.
2. Implement bind/unbind.
3. Implement distance validation and periodic unbind.
4. Move `playerControllers` ownership out of `TrainCommand` into manager.
5. Keep `/jr train key` command.

Commit:

```text
Control dialog phase 2: bind train keys to driver seats
```

### Phase 3: Explicit control acquisition

Files:

- `TrainControlManager.kt`
- `RealisticManualController.kt`
- `TrainHandleInputListener.kt`

Tasks:

1. Remove implicit control acquisition from WASD input.
2. Add `tryAcquireManualControl(player, train)`.
3. Fail if another player owns control.
4. On acquire:
   - preserve current speed
   - set direction `N`
   - set handle `N`
5. WASD only affects controller if player already owns control.

Commit:

```text
Control dialog phase 3: require explicit manual control ownership
```

### Phase 4: Train control Dialog

Files:

- new `TrainControlDialog.kt`
- maybe extend/reuse `DialogRenderer.kt` only if ConfigUI fits; direct Paper Dialog may be simpler because this UI is button/action based.

Tasks:

Dialog before control:

- title: train ID / package name
- body:
  - bound train
  - current controller owner or none
  - speed/direction
- buttons/actions:
  - `尝试获取控制权`
  - executable YAML actions
  - close

Dialog after control:

- current direction `F/N/R`
- current handle/speed
- buttons:
  - release/activate automatic control
  - direction `F`
  - direction `N`
  - direction `R`
  - executable YAML actions

Right-click key behavior:

- if unbound and in driver seat: bind + open
- if bound: open

Commit:

```text
Control dialog phase 4: add train key control dialog
```

### Phase 5: Direction and signed movement

Files:

- `RealisticManualController.kt`
- `TrainDynamics.kt`
- Core train movement classes
- tests

Tasks:

1. Add direction enum `F/N/R`.
2. Direction N: no traction, no commanded movement beyond existing signed coasting policy.
3. Direction R: speed is negative and actionbar shows negative/reverse direction.
4. Add signed movement support without calling `reverse()` during R.
5. Add tests for:
   - F acceleration positive
   - R acceleration/speed negative
   - R does not toggle `isReversed`

Commit:

```text
Control dialog phase 5: support signed manual direction
```

### Phase 6: Cab-based acquisition reverse

Files:

- `SeatModel.kt`
- `PartInteractionConfig.kt`
- S100 seat part files
- `TrainControlManager.kt`

Tasks:

1. Add `control-end` to driver seat metadata.
2. S100 front/rear cab seats use different parts:
   - `driver_seat_head.yml`
   - `driver_seat_tail.yml`
3. On acquire from opposite cab, call reverse trigger once.
4. Ensure acquiring from same cab does not reverse.

Commit:

```text
Control dialog phase 6: reverse train when acquiring from opposite cab
```

### Phase 7: Generic reverse event hook

Files:

- Core train reverse event or Bukkit reverse helper
- `TrainExtension.kt`
- `behavior.js`
- `actions.yml`
- S100 display parts if using headlight swapping as an example

Tasks:

1. Fire/call `onReverse` whenever reverse is triggered.
2. Expose reverse state and train context to JS/YAML event listeners.
3. Optionally add S100 actions/behavior that swap head/tail lights as an example listener.
4. Tests for behavior/event call if feasible.

Commit:

```text
Control dialog phase 7: add generic reverse event hook
```

### Phase 8: Polish and docs

Tasks:

1. Update `docs/train_model_packages.md`.
2. Add usage docs:
   - `/jr train key`
   - key binding
   - Dialog ownership
   - YAML executable actions
   - driver seat metadata
3. In-game validation checklist.

Commit:

```text
Docs: document train key control dialog
```

---

## In-game validation checklist

1. `/jr train key` gives generic key.
2. Right-click while not in driver seat does not bind.
3. Sit in front cab driver seat, right-click key:
   - key binds to train
   - Dialog opens
4. Dialog before control shows no owner and executable door actions.
5. Door actions work without taking control if `requires-control: false`.
6. Acquire control succeeds if no owner.
7. On acquire:
   - handle = N
   - direction = N
   - speed preserved
8. W/S/D only work for controller owner.
9. Second player sees owner and cannot acquire.
10. Leaving driver seat coasts/holds according to final desired policy, with no chat spam.
11. Moving > configured distance unbinds the key.
12. Acquire from rear cab triggers reverse once.
13. Selecting R in Dialog changes signed direction/speed but does not call reverse.
14. Reverse trigger calls generic YAML/JS listeners; optional S100 headlight swapping is only an example listener.

---

## Open questions before implementation

1. When the controller owner leaves the driver seat, should control be released immediately, or only handle set to N while ownership remains?
   - Current behavior: handle coasts but ownership remains.
   - New Dialog design may prefer explicit release or auto-release on dismount.
2. Should executable actions require the key to be bound, or only being near the train with a key?
   - Recommended: require bound key.
3. Should the key binding persist through server restarts?
   - For test server, probably no need; item PDC would persist if stored on item.
4. How should automatic control be represented if no ATO controller exists yet?
   - Phase 4 can show disabled placeholder, or release manual control and install a no-op/future automatic controller.
