# Research: Protection-region full-train exit clearance

- Query: Identify entry-time exit-clearance requirements, source support and minimum API gaps; detailed design belongs to the next batch.
- Scope: internal, source-only; no builds or runtime validation.
- Date: 2026-10-05
- Status: **TARGET — not implemented**. This batch removes default routes; these are planning findings only.
- Checkout: parent supplied `dev/signal-system`, HEAD `39a235d0`; no Git operation performed here.

## Findings

### Current source support

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/region/ProtectionRegionInspectionSource.kt:34-44,70-98`: floor is region exit or unique same-point immediate fixed-block window end, without train length. Update handler at lines 48-59 emits no restriction.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionRouteBindingCompiler.kt:33-65`: reuse this immutable-publication compiler for exact frozen-route geometry, rather than rematching per tick.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionRouteSelectionRuntime.kt:199-215,253-259`: routeReady checks committed grant against static floor and physical continuations, not whether downstream/operation policy permits movement to it.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockInspectionSource.kt:67-108`: published effective signal speed already becomes route-local restrictions for active occurrences; missing configured signal state is zero.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt:584-623,675-809`: existing controller pass combines signal, infrastructure, following, external, mission and station constraints before movement. This is the meeting point for per-train stopping facts.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/constraint/AtoConstraint.kt:73-95,121-139`: stop/authority targets account for existing margins; authority stops safetyDistance before the nominal grant end.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/inspection/AuthorityRequestFloors.kt:19-42`: floor expansion already visits newly crossed declarations in one ordered pass; no new fixed-point/resource machinery is needed.

### Next-batch target contract

For exit coordinate `x` and authoritative train metre length `L`, the head must be able to reach at least `x + L`, subject to the established geometric clearance convention. Requested grant must also account for the actual controller authority stopping margin. Never clamp insufficient clearance down to the frozen route/operational endpoint and call it safe.

Before entry, prove both resource coverage and current movement readiness: no known operational stop/end or downstream zero-speed boundary can stop the head before the tail-clear coordinate. A positive speed cap is not automatically a blocker. Reuse published route-local signal state and existing operational/controller constraints.

Exclude the entrance's own signal restriction by exact identity/occurrence, and exclude the previous clearance hold itself, to prevent self-red deadlock. Continue acquiring the clearance grant while the entrance is held. Include downstream restrictions inside the clearance interval even when outside nominal lookahead; omitted coverage cannot imply permission.

Shared signal routeReady has no mission/station-stop context. A small neutral requirement in the existing inspection contract, enforced where SimpleATO builds typed constraints, is a plausible minimal path; exact interfaces are deferred. Avoid per-train callbacks in every signal wave, queues, new resource ownership and recursive contributors. Keep Basic claims, preparation and actual-tail release intact.

### Minimum public input gaps

- `SimpleATO/.../api/inspection/InspectionSource.kt:17-25`: bind input only has stamp/train ID/route, no immutable metre-length profile. Documented profile lookup is not an implemented host capability.
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainSnapshot.kt:11-20`: perception snapshot has car count/head/tail, no metre length. `Train.length` is car count (`Train.kt:36-37`).
- Authoritative consist length is available through public car lengths, already used by Dispatcher (`TimetableDispatcher.kt:778,3300`). Coherent Basic observation also has ordered conservative rear-to-head body traversal (`Basic/.../api/observation/RailwayObservation.kt:26-32,43-49`); sum absolute arc differences for a safety upper bound without live Train access. Preserve repeated occurrences. An empty initial body traversal is unusable for admission (`Core/.../TrainFrameSnapshot.kt:83-95`).
- Profile changes must invalidate binding reuse; current same-route synchronization only updates stamp (`InspectionRuntime.kt:193-224`). Basic must still validate authoritative geometry independently.

### Three examples

1. Exit 25 m, train length 10 m, direct block ends 30 m: current floor is too short. Head clearance requires at least 35 m plus the authority stopping margin. An operation ending at 30 m must refuse entry rather than clamp.
2. Same exit/length, grant reaches 40 m, downstream red stops the head at 28 m: reservation exists but movement cannot clear the tail. Hold at entrance until downstream allows clearance. A stop at 38 m can suffice if its actual stopping margin still leaves the head beyond 35 m.
3. Exit 25 m, length 10 m, station stop at 33 m: complete region traversal and a block token are insufficient; the planned stop leaves the tail inside. Stop at 38 m may satisfy the contract after existing stop/position tolerances.

### Related specs and later tests

The current owner spec `.trellis/spec/simple-signals/backend/protection-region-authority-transition.md` explicitly excludes general tail clearance; update it in the implementation batch. Neutral inspection/controller ownership is in `.trellis/spec/javarailways/simpleato/operational-routing-and-authority.md`; Basic grant safety/release remains in `.trellis/spec/javarailways/basic/route-resource-admission.md`.

Later test targets: `ProtectionRegionInspectionSourceTest`, `ProtectionRouteSelectionRuntimeTest`, `InspectionRuntimeTest`, `SimpleATOTest`, `AuthorityRequestFloorsTest`, `TimetableDispatcherSharedAdmissionTest`. Cover short/no block, insufficient endpoint, downstream red despite grant, operational stop, clearing on next controller tick with Dispatcher stopped, self-gate exclusion, horizon coverage, BA/repeated visits and held resources against a competing train.

## Caveats / Not Found

- Entry-time readiness cannot guarantee eventual exit against future red changes, emergency stops, derailment or failure. The promise concerns current route, length, grant and stopping restrictions; normal safety stops remain active, and Basic holds resources until proven tail clearance.
- No direct immutable train-length field was found in inspection/perception inputs. TARGET is not existing behavior.
- No code implementation, builds, tests, live Folia validation or full interface design was performed. Default removal/directions were outside this research ownership; unrelated files were untouched.
- Memory only routed inspection toward existing grants, tail release and controller handoff; findings were checked in current source.
