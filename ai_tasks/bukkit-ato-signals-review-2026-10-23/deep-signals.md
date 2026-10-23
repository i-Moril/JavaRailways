# SimpleSignals focused ownership/publication review

Read-only continuation over current working source; no repository changes, builds, tests or runtime execution. Existing SS1-4 are not counted again. Full aspect/call-chain ledger: /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/deep-signals-coverage.tsv.

## Result
No additional confirmed runtime defect established in this focused pass. The live failed-install lifetime hole is already guarded in current source: exact callbacks retire before engine close; a failed detach keeps the engine and reservation and blocks replacement until explicit cleanup succeeds. Tests at SimpleSignalsWorldRuntimeManagerTest:5089-5248 exercise actual logic lifetime, identity and overlapping cleanup using latches; they were read, not executed.

The live model path owns allocations per world and serializes allocation/cleanup under modelLifecycleLock. Publication retires the old binding under submissionLock; old callbacks cannot use retained world handles. Candidate preparation does not allocate handles, so a validation exit before candidateModels.retire is not an entity leak. Failed allocation can recover on a subsequent definition edit (test 186-206). Failed display submission does not automatically retry unchanged output, but current spec deliberately uses later changed submissions without a new polling loop; this is a limitation, not a newly established contract violation.

Authoring uses exact runtime/base publication/topology/catalog checks around the permit and host atomic callback; staging precedes state publication and allows one topology reprepare with original selection baseline. Topology revalidation uses captured guard/base identity and only reschedules changed pending work. Snapshot containers are copied at their publication boundaries; internal handoff maps originate immutable producers. No reachable external alias mutation was established.

Simulation reverse cleanup still aggregates registration failure and then closes the engine. This superficially resembles the fixed live-install issue, but production DefaultSimulationDomainTarget explicitly runs cleanup after target worker termination (or on pre-start install failure). A callback-into-closed-engine claim is therefore not established for this path; custom target behavior remains an unverified boundary.

## Small engineering observation
SignalModelBinding.kt:173 retains a private topologyRevision constructor property that has no consumer. The manager passes publication.topology.revision, but actual fencing is active binding/publication identity. This is a small stale internal parameter, not a correctness finding. Previously reported ProtectionSignalCompiler unused changedSignalIds/routeSection remain excluded from new totals.

## Limits
This is a targeted second inspection, not a new entire-repository proof. Read ranges and call chains are recorded in the TSV. No fresh Gradle/Graal/Paper/Folia/client execution. Arbitrary malicious/infinite guest rules are explicitly outside the existing synchronous author contract; no timeout finding is added. Host normal-unload ownership and model close behavior are cross-module contracts and were not independently re-tested here.
