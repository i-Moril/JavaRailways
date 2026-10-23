# Formation provider authorization checkpoint — 2026-10-22

## Implemented scope

This checkpoint adds policy authorization transport to the existing prepared-formation route path. It does not complete fixed coupling/splitting, visual structural authoring, or an operational shunting permission model.

- Bukkit `RouteResourceContributor.authorizeFormationRoute` defaults to rejection. Every registered contributor must explicitly accept the exact host-issued context and route intent.
- Bukkit `InfrastructureResourceProvider.authorizeFormationResource` defaults to rejection. For output purposes, the host invokes the exact registered provider for each retained stable resource. Its accepted response must contain exactly one matching identity, with a boundary within the complete frozen route, or an explicit null boundary meaning that resource is not needed. Missing, foreign, duplicate and overlong response entries cannot stand in for clearance.
- The host captures world/contributor/provider registrations before callbacks and rechecks referential registration identity and enabled plugin ownership afterward. Exceptions reject without claim mutation. World/context mismatch rejects.
- Detached simulation exposes an atomic `registerFormationRouteContributor(id, collect, authorize)` registration. An independent callback cannot impersonate an existing contributor by reusing its ID. Ordinary contributors remain formation-denying. Simulation providers expose the same exact-resource authorization, and the host rechecks registrations after callbacks.
- SimpleSignals LIVE and SIM use `FixedBlockDomainResourcePolicy`. Output clearances use the complete selected frozen route, not the short body-authority prefix, and preserve the last route exit for repeated visits. Off-route retained resources receive an explicit null only after existence and normal route-policy checks succeed.
- Fixed-block matcher, operating directions, topology/unavailable-territory checks remain active. No partner-lock inference, signal aspect override, inspection withdrawal, or signal-0 bypass was added. Existing signal sources remain installed in detached simulation.

`FormationRouteContext`, purposes and clearance DTOs are owned by Basic; this checkpoint consumes them. The context constructors are invoked reflectively only in test fixtures because production creation remains Basic-internal. No persisted format or schema version changed.

## Files owned by this slice

- BukkitPlugin API: `InfrastructureResourceProvider.java`, `RouteResourceContributor.java`.
- BukkitPlugin host: `DefaultInfrastructureResourceService.kt`, plus focused test additions in `DefaultInfrastructureResourceServiceTest.kt`.
- SimpleATO neutral SIM API/adapter: `PreparedSimulationFeature.kt`, `DefaultSimulationDomainTarget.kt`, `IsolatedTimetableSimulation.kt`, plus focused additions in `IsolatedTimetableSimulationTest.kt`.
- SimpleSignals: `FixedBlockRouteResourcePolicy.kt`, `SimpleSignalsSimulationFeature.kt`, `FixedBlockRouteResourcePolicyTest.kt`.

Other changes already present in this shared worktree belong to earlier task batches or other agents and were preserved. Basic's factory callback parameter placement was corrected by its owner to retain existing trailing `stallWorld` lambda calls.

## Successful verification

In `/workspace/minecraft/JavaRailways/.worktree/station-operation-programming`:

```sh
./gradlew -I /tmp/jr-program-tests-heap.init.gradle --max-workers=1 \
  :BukkitPlugin:test --tests '*DefaultInfrastructureResourceServiceTest' \
  :SimpleATO:test --tests '*IsolatedTimetableSimulationTest' \
  :SimpleSignals:test --tests '*FixedBlockRouteResourcePolicyTest' \
  -x :WebUI:installFrontend
```

The temporary init file sets test heap to 1 GiB and `maxParallelForks=1`. The successful run completed in 20 seconds. Log: `/tmp/jr-formation-provider-gate.log`.

XML results (UTC):

- BukkitPlugin: 32 tests, 0 failures/errors/skips, 08:37:23.
- SimpleATO: 15 tests, 0 failures/errors/skips, 08:37:24.
- SimpleSignals: 19 tests, 0 failures/errors/skips, 08:37:37.
- Total: 66 tests. `git diff --check` passed.

New focused assertions cover contributor default rejection/opt-in and registration removal; exact/foreign/missing/overlong/null provider clearances; duplicate detached contributor identity; full-route clearance beyond a short body authority; off-route existence vs unknown identity; and unchanged direction/unavailable-territory rejection. Production and all test sources of these modules compiled as dependencies of this gate.

Initial attempts were not successful evidence: sandbox Gradle local-socket initialization required escalation; one set/list identity comparison compile mistake was corrected; an incorrectly placed Gradle filter accidentally ran broad SimpleATO tests and exhausted its test heap in an existing `PrimeCurveImpl` station fixture; and the new Basic callback's initial parameter position broke existing trailing-lambda test call sites before its owner corrected it. The final command above explicitly attaches each filter to its intended task.

## Evidence limits / follow-up

No server deployment, game/client check, complete structural event, or physical ATO coupling-contact run was performed. Host aggregation tests call the host authorization boundary using fixture contexts; shared policy tests exercise the same function installed by the LIVE and SIM adapters. This is not evidence that an occupied destination block permits shunting: a real signal 0 still binds. A movement path that needs permission to pass that signal requires a separately agreed operational policy. Active admission publication, actual contact, formation event completion and held output consumption remain Basic/Core/SimpleATO integration work owned by the coordinating agents.
