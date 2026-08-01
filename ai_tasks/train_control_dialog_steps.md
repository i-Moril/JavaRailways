# Train control dialog executable steps

This file converts `ai_tasks/train_control_dialog_plan.md` into an implementation checklist. Each step should be small enough to implement, test, and commit independently.

## Step 0 — Baseline checks

- Confirm branch is `feature/control`.
- Confirm unrelated dirty files are not staged.
- Run:

```text
bash ./gradlew :BukkitPlugin:test --rerun-tasks
```

Acceptance:

- Tests pass before starting functional changes.

---

## Step 1 — Parse control UI metadata

Files:

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/JavaRailwaysConfig.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartInteractionConfig.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartRegistry.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/TrainActionConfig.kt`
- tests under `BukkitPlugin/src/test/...`

Tasks:

1. Add global config field:

```kotlin
trainKeyBindDistance: Double = 10.0
```

2. Add driver-seat control-end metadata:

```yml
interaction:
  driver: true
  control-end: head # head | tail
```

3. Add action UI metadata:

```yml
actions:
  door-left-open:
    display-name: "Open left doors"
    executable: true
    requires-control: false
```

4. Extend parsers and defaults:
   - `displayName = action name`
   - `executable = false`
   - `requiresControl = false`
5. Add tests:
   - config default and custom `train-key-bind-distance`
   - part interaction parses `control-end`
   - action metadata parses correctly

Acceptance:

- Existing configs still load.
- New tests pass.

Commit:

```text
Control dialog phase 1: parse control UI metadata
```

---

## Step 2 — Mark S100 executable actions and cab ends

Files:

- `models/s100/actions.yml`
- `models/s100/parts/s100/body_head.yml`
- `models/s100/parts/s100/driver_seat*.yml`
- `models/s100/cars/s100/head_tail.yml` or related rear cab part references
- runtime copies under `BukkitPlugin/run/plugins/JavaRailways/trains/s100/`

Tasks:

1. Mark S100 door actions executable:
   - `door-left-open`
   - `door-left-close`
   - `door-right-open`
   - `door-right-close`
2. Add display names for those actions.
3. Split driver seat parts if needed:

```text
driver_seat_head.yml -> control-end: head
driver_seat_tail.yml -> control-end: tail
```

4. Ensure front cab references head driver seat and rear cab references tail driver seat.
5. Sync runtime S100 package.

Acceptance:

- S100 package loads.
- Door actions appear as executable metadata in tests or manual loader check.

Commit:

```text
S100 control: expose cab seats and executable door actions
```

---

## Step 3 — Implement key binding data

Files:

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainKeyItem.kt`
- tests if practical

Tasks:

1. Keep `/jr train key` as the way to obtain a generic train key.
2. Add key item binding PDC fields:

```text
bound_train_id
bound_world_id
```

3. Add functions:

```kotlin
isKey(item)
boundTrainId(item)
bind(item, train)
unbind(item)
```

4. Do not add compatibility for old test keys.

Acceptance:

- A fresh key has no binding.
- Binding writes train/world IDs.
- Unbinding removes binding but keeps generic key marker.

Commit:

```text
Control dialog phase 2: add train key binding data
```

---

## Step 4 — Add TrainControlManager skeleton

Files:

- new `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlManager.kt`
- `BukkitPlugin.kt`
- remove/migrate relevant `TrainCommand` controller registry later, not all at once if risky

Tasks:

1. Add manager owned by `BukkitPlugin`.
2. Move ownership state into manager:

```kotlin
data class ManualControlSession(
  val trainId: String,
  val playerId: UUID,
  val controller: RealisticManualController
)
```

3. Add query methods:

```kotlin
ownerOf(train): UUID?
sessionFor(player): ManualControlSession?
controllerFor(player): RealisticManualController?
```

4. Register manager tick task if needed for key distance checks later.

Acceptance:

- No behavior change yet except code location if wired carefully.
- Tests pass.

Commit:

```text
Control dialog phase 3: add train control manager
```

---

## Step 5 — Bind/unbind key to train by right-click

Files:

- `TrainControlManager.kt`
- new or existing listener, likely `TrainKeyListener.kt`
- `BukkitPlugin.kt`

Tasks:

1. Listen to `PlayerInteractEvent`.
2. If player right-clicks with train key:
   - cancel event.
   - if unbound and player is in a driver seat: bind to that train.
   - if unbound and not in driver seat: actionbar error.
   - if bound: validate train is loaded and within distance.
3. If invalid/out of range: unbind.
4. Do **not** acquire manual control here yet unless Dialog action does so.
5. Open placeholder Dialog or send actionbar until Step 7.

Acceptance:

- Right-click key binds only while in driver seat.
- Right-click bound key resolves target train.
- Out-of-range binding clears.

Commit:

```text
Control dialog phase 4: bind train key from driver seat
```

---

## Step 6 — Distance-based auto-unbind

Files:

- `TrainControlManager.kt`
- `JavaRailwaysConfig.kt` if not finished in Step 1

Tasks:

1. Every 20 ticks, scan online players' main/offhand key items.
2. For bound keys:
   - resolve bound world/train.
   - compute nearest car distance.
   - if all cars are farther than config distance: unbind.
3. Use actionbar, not chat, for unbind notification.

Acceptance:

- Bound key remains bound near train.
- Bound key unbinds beyond configured distance.

Commit:

```text
Control dialog phase 5: auto-unbind distant train keys
```

---

## Step 7 — Build train control Dialog MVP

Files:

- new `TrainControlDialog.kt`
- `TrainKeyListener.kt` / `TrainControlManager.kt`

Tasks:

1. Use direct Paper Dialog API; do not force this into ConfigUI unless convenient.
2. Dialog body before control:
   - bound train ID
   - current controller owner: none / player name / other controller type
   - current speed
3. Dialog actions before control:
   - `尝试获取控制权`
   - executable YAML actions with `requires-control=false`
4. Dialog after control:
   - owner = self
   - current direction
   - current handle
   - current speed
   - buttons for `F`, `N`, `R`
   - `自动控制/释放控制权` placeholder
   - executable YAML actions allowed by metadata
5. Reopen/update Dialog after state-changing button clicks where useful.

Acceptance:

- Right-click bound key opens Dialog.
- Executable S100 door actions are visible and apply.
- Non-executable actions are hidden.

Commit:

```text
Control dialog phase 6: add train key control dialog
```

---

## Step 8 — Explicit manual control acquisition

Files:

- `TrainControlManager.kt`
- `RealisticManualController.kt`
- `TrainHandleInputListener.kt`
- `TrainCommand.kt` cleanup if needed

Tasks:

1. Remove implicit acquisition from WASD.
2. Add:

```kotlin
tryAcquireManualControl(player, train): AcquireResult
releaseManualControl(player)
```

3. Acquisition rules:
   - no train controller: success
   - same player's controller: success/idempotent
   - other player's manual controller: fail
   - other controller type: fail or future automatic-yield path
4. On success:
   - preserve current speed
   - direction = `N`
   - handle = `N`
5. WASD only affects controller when manager says the player owns manual control.

Acceptance:

- Key + driver seat alone no longer moves train.
- Dialog acquire enables WASD.
- Second player cannot acquire while first controls.

Commit:

```text
Control dialog phase 7: require explicit manual control ownership
```

---

## Step 9 — Controller direction F/N/R and actionbar

Files:

- `RealisticManualController.kt`
- `TrainDynamics.kt`
- `TrainHandleInputListener.kt`

Tasks:

1. Add direction enum:

```kotlin
ManualDirection { F, N, R }
```

2. Direction behavior before signed core movement exists:
   - `F`: current behavior.
   - `N`: no traction command; handle forced/kept N as policy.
   - `R`: store desired reverse direction and display it, but actual negative movement depends on Step 10.
3. Actionbar format:

```text
方向: F  挡位: P3  速度: 12.3 km/h
```

Acceptance:

- Dialog can switch direction F/N/R.
- Actionbar reflects direction.

Commit:

```text
Control dialog phase 8: add manual direction state
```

---

## Step 10 — Signed movement without trigger reverse

Files:

- Core train movement classes
- `RealisticManualController.kt`
- tests in Core and/or BukkitPlugin

Tasks:

1. Add a core movement API for signed manual movement, e.g.:

```kotlin
fun moveTrainSigned(deltaS: Double)
```

2. Implement negative movement without toggling `isReversed` and without calling `reverse()`.
3. Review collision/signal behavior for negative movement; add minimal safe behavior if full reverse collision scanning is too large.
4. Make `R` produce negative speed and negative signed distance.
5. Tests:
   - R creates negative speed.
   - R movement does not toggle `isReversed`.
   - F still works.

Acceptance:

- Manual R backs train without triggering reverse event.

Commit:

```text
Control dialog phase 9: support signed manual movement
```

---

## Step 11 — Cab-end-based acquisition reverse

Files:

- `SeatModel.kt`
- `PartInteractionConfig.kt`
- `TrainControlManager.kt`
- S100 driver seat parts

Tasks:

1. Track driver seat `control-end` in `SeatBinding`.
2. On manual acquisition, compare seat end to current train orientation.
3. If player controls from opposite cab, call the reverse trigger once.
4. This reverse is distinct from selecting direction `R`.

Acceptance:

- Front cab acquire does not reverse when already front-forward.
- Rear cab acquire triggers reverse exactly once.

Commit:

```text
Control dialog phase 10: reverse on opposite-cab acquisition
```

---

## Step 12 — Generic reverse event hook

Files:

- Core event system or train reverse helper
- `CoreTrain.kt`
- `TrainExtension.kt`
- behavior runtime/tests

Tasks:

1. Add generic reverse event/hook fired whenever train reverse is intentionally triggered.
2. Expose to YAML/JS event listeners.
3. JS first pass:

```js
function onReverse(state) { ... }
```

4. YAML event map first pass:

```yml
events:
  reverse:
    actions: [...]
```

5. Missing listener is no-op.

Acceptance:

- Reverse trigger calls listener.
- Direction `R` does not call listener.

Commit:

```text
Control dialog phase 11: add generic reverse event hook
```

---

## Step 13 — Optional S100 reverse listener example

Files:

- `models/s100/actions.yml`
- `models/s100/behavior.js` or event YAML
- S100 display/light parts only if needed

Tasks:

1. Use S100 as an example listener package.
2. If light/display assets exist, swap front/rear indication on reverse.
3. Keep this framed as package behavior, not engine logic.
4. Sync runtime S100 package.

Acceptance:

- S100 can demonstrate `onReverse`, but engine remains generic.

Commit:

```text
S100 control: add reverse listener example
```

---

## Step 14 — Command cleanup and docs

Files:

- `TrainCommand.kt`
- `docs/train_model_packages.md`
- `ai_tasks/train_control_dialog_plan.md` if needed

Tasks:

1. Ensure no manual driving commands remain documented or reachable:
   - no `/jr train start`
   - no `/jr train throttle`
   - no `/jr train handle`
   - no `/jr train reverse` for manual control
2. Keep `/jr train key`.
3. Document:
   - key binding
   - Dialog control acquisition
   - ownership failure
   - executable YAML actions
   - F/N/R semantics
   - reverse event listener semantics
   - binding distance config

Acceptance:

- Docs match behavior.
- Tests pass.

Commit:

```text
Docs: document train key control dialog workflow
```

---

## Final validation scenario

1. Start server with current plugin.
2. `/jr train key` gives a generic key.
3. Create S100.
4. Sit in driver seat with key.
5. Right-click key:
   - binds key to train
   - opens Dialog
6. Before control:
   - owner visible
   - acquire button visible
   - executable door actions visible
7. Trigger door action from Dialog.
8. Acquire manual control.
9. Confirm:
   - direction `N`
   - handle `N`
   - speed preserved
10. Set direction `F`; hold `S`; train accelerates.
11. Press `D`; handle returns `N`.
12. Set direction `R`; hold `S`; train moves backward with negative speed and does not trigger reverse event.
13. Second player cannot acquire while first owns control.
14. Move away > configured distance; key unbinds.
15. Bind/acquire from opposite cab; train reverse event fires once.
16. Optional S100 reverse listener runs.

## Commit discipline

Before every code commit:

```text
bash ./gradlew :BukkitPlugin:test --rerun-tasks
```

Do not stage unrelated dirty/untracked files.
