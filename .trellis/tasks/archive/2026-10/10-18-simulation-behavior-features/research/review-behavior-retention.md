# Review fixes: simulation behavior retention

Scope: user P2 findings 2 and 3. Existing candidate and upstream adaptation remain in place; no commit, merge or build was performed by this implementer.

## Effect observation

`SimulationCarBehaviorExtension` no longer owns an effect list or `effectSnapshot()`. It accepts an internal nullable `(Car, SimulationBehaviorEffect) -> Unit` observer. Animation capability/scene validation remains on the production path; accepted sound/animation effects still participate in the same `CarBehavior` completion semantics. Only a supplied observer constructs an event value: Kotlin safe-call argument evaluation skips event construction when the default observer is null.

The existing internal HTTP simulation factory has one optional constructor argument forwarding this observer to newly seeded exact-car extensions. It does not capture history or add a public API. Test-only `SimulationBehaviorEffectCollector` stores observations by exact `Car` identity; gateway tests and both real joint fixtures explicitly opt in. Shared same-capture two-target tests remain separated by exact car objects.

## One lifecycle owner

Removed `SimulationTrainOperationGateway.ownedCars` and its additions. Actual lifecycle trace:

1. `CoreTrain.destroy()` runs inside its admitted owner phase and awaits each exact `CoreCar.destroyOwnedResources()`.
2. `CoreCar.destroyOwnedResources()` includes every attached extension's `destroy()` stage. `SimulationCarBehaviorExtension` retains its single `Destruction` result, fails outstanding operations, retires delay registrations, and closes its persistent JS context.
3. Core unregisters the train; coupling retirement intentionally does not destroy cars transferred to the new train. Split/link survivors remain discoverable through their actual current train.
4. After true physics/generic worker termination, gateway shutdown takes a local snapshot of current World cars and attempts each current behavior close independently. This snapshot exists only during close; no historical car ownership collection survives operations.

Seed failure already registers `behavior::close` with `CoreTrain.createOwned.onFailure`. Target operation-resource creation/normal/error cleanup remains under `IsolatedTimetableSimulationWorld` and is unchanged. Thus no replacement registry, destruction listener, weak reference or GC-based assumption is needed.

## Tests and validation boundary

Existing gateway tests and both `FullFeatureSimulationIsolationTest` cases now use only opt-in test observation. Four added gateway cases cover:

- default null outlet accepts repeated valid animation/sound actions and rejects a missing scene;
- actual Core split plus destruction retires exactly the removed car's pending work and context, while its survivor remains operational until gateway shutdown;
- failed real Core train destruction unregisters the retired train, and gateway shutdown does not re-observe that historical car's retained failed destruction stage;
- one current-car completion callback throwing during shutdown preserves that close failure and still fails pending tickets and closes remaining current cars.

The failed `CoreTrain.destroy()` case deliberately trips process-wide `RailwaySimulationHalt`. It uses the existing upstream `@IsolateGlobalHalt` fixture to isolate exactly that invocation, preserving the real process-terminal policy without reset APIs or a new harness. The old `ownedCars` implementation would rethrow the retired extension's failed stage during gateway shutdown; current-car shutdown remains possible after the halt. This is not a GC/reachability benchmark.

`git diff --check` passed after edits. Source search finds neither production `effectSnapshot()` nor `ownedCars`. Root owns the single build token and must record actual compiled/test results; this note makes no test-pass claim.
