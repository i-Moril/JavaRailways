# ATO rebuild on deployed per-car baseline

## Provenance and boundary
Base commit: 47e65386 (b97bd98d plus exact deployed studio/session/autosave/door source). No cherry-pick or merge of 2b9ec9d1. Prior integration is an abandoned candidate and a read-only source of independently reviewed mathematical ideas. Keep deployed per-car dynamics, door/program semantics, resource admission and unrelated UI unchanged.

## Requirements
- Pure driving and precision-stop strategies use actual vehicle/device snapshots and declared actuator characteristics. Nominal reference acceleration/deceleration default to 1 m/s², prepared per model. Stop acquisition below 20 km/h when a real zero-speed target exists; moving authority extension must release old stop targets.
- No future tick-by-tick real dynamics prediction in online ATO/ATP. Actual physical motion integrates once per tick. Finite analytic response phases and compiled speed/grade brake-distance tables are permitted.
- ATP is a pure judgement, executor intervenes. Use most adverse grade under the consist and forward route, actual residual traction/air/electric state, traction cutoff and brake build delay, jerk and bounded variable supervision intervals.
- AUTO intervention is abnormal and records immutable physical position, route targets, state, device state, command and safety witness; releases automatically only at rest with fresh safe judgement. SIM records the same and aborts. MANUAL requires rest, fresh safe judgement and explicit player acknowledgement; controller replacement cannot clear a manual-origin latch.
- Nominal-point prediction infeasibility is an operating precision diagnosis, not proof of ATP danger. Every executed candidate must still satisfy hard safety. If nominal candidates are empty, choose an admissible ordinary braking/coast command; never blindly issue a max-B that resets device delay. If all hard-safe ordinary candidates are empty, emergency brake and diagnose.
- Shared spatial acceptance policy is 0.3 m including plugins; geometric target remains the actual mark. Experiment precision goal <=0.05 m; do not move the aim to the tolerance edge or hide misses by clipping physical motion.

## Implementation and evidence
1. Preserve baseline and baseline regressions. Work only on codex/ato-per-car-rebuild.
2. Add value-only numeric command/state/capability contracts and shared known-interval device algebra derived from the deployed kernel; preserve exact Car bindings and device history across ownership/consist changes.
3. Implement pure reference/stop strategy and conservative ATP tables, then integrate controller/manual/simulation executors and diagnostics.
4. Compare response and safety bounds against real per-car physics across heterogeneous cars, speeds, grades, device phases and variable ticks. Run full module regressions; reproduce frozen live S100/Ha inputs separately from synthetic fixtures. Report stopping accuracy, ATP events, terminal changes and paired runtime cost with provenance.
5. Static/check-agent review, update owner specs. No deployment or deletion of unrelated work. Old ATO commit is excluded from new branch ancestry; only retire old branch after replacement is complete and recovery is preserved.

## Data boundary
No format bumps without published/frozen baseline proof. Optional nominal-control config fields keep current version. Fixed tolerance changes authored destination data shape: provide dry-run one-shot migration with recovery, never a runtime legacy reader. No live data migration in this request.

## Legacy retirement
`dev/signal-system` has been restored from rejected ATO commit `2b9ec9d1` to its parent `6d2d1039` using `reset --keep`; unrelated dirty/untracked files were retained. Recovery branch `codex/ato-rejected-legacy-20261102` retains the old commit; the old docs submodule commit `c51d82d` also has a recovery branch. No old ATO commit is a parent of this rebuild. The prior `/tmp/jr-ato-deployment-integration` remains an abandoned, un-deployed candidate for audit only.

## Verification notes
- Clean-tree Basic: 463 tests passed, including explicit device source and controllerless real split history.
- Core and WebUI tests passed after generating unchanged frontend assets in this checkout; frontend bundle identities match the deployed-source baseline.
- Initial full compilation mismatches were test actuator API migrations; corrected without a production legacy target-speed fallback.
- Initial world runs exposed loss of ongoing-current-limit semantics when shifting candidate targets; addressed in ATP with current and crossed-limit regression.
- Final claims must use the final clean-tree Gradle/full-S100 evidence, not obsolete old-branch counts or interrupted standalone logs.

- Frozen live S100 first six world scenarios passed: 5-car flat STOP100m, STATION_RUN500m and MA100m, both directions at50ms, no ATP or final-metre traction, no resting drift. Largest operating error0.339mm, last metre7–10changes. MA operational margin5m is recorded separately from error. This predates final72-scenario acceptance and is not a full timetable-program completion claim.
- Additional check findings fixed: motor force-curve peaks can exceed maxForce; external stop signed distance; driver/session/seat revocation for manual acknowledgement including quit; native speed limits on every occupied body rail, even between bogies. New regression cases included.
- A mission awaiting its first explicit authority now produces a speed hold, not an invented physical boundary at its route origin. Real explicit authorities retain signed distance and fail on overrun.

## Final measured evidence (2026-11-02)
- Final six-module XML results: Core540, Basic463, SimpleATO733, SimpleSignals675, BukkitPlugin728, WebUI77; zero failures/errors/skips. The last SimpleATO batch includes the real issued-MA0 versus waiting-hold regression and routed-manual grant propagation. Full command log: `/tmp/jr-ato-rebuild-final-gate.log` (also checks public consumer compilation, Core/Basic/SimpleATO Dokka, shadow JAR and module boundaries).
- `VehicleDeviceDynamicsTest` and pure response tests compare the analytic known-interval result to real per-car integration; 216 response cases, 40 strategy scenarios, 29 combined executor/protection trajectories. Conservative ATP tests include1944 sustained-stop upper-bound comparisons; all pass.
- Fixed-tolerance migration:7 tests pass (`python3 ai_tasks/migrations/test_remove_authored_stop_tolerance.py`). No live file was migrated.
- Frozen S10072-scenario CSV: [ato-per-car-rebuild-s100.csv](ato-per-car-rebuild-s100.csv). STOP100m/STATION_RUN500m/AUTHORITY100m; five/ten cars; +/-35per mille and flat; AB/BA; fixed50ms/variable25-200ms. All stopped and arrived, maximum absolute operating error20.9775mm, zero terminal traction/after-rest drift/ATP incidents; terminal command changes at most25. An authority target keeps its separate5m operating margin; the physical boundary error column intentionally includes it. No clipping or movement teleport is used to improve errors.
- Frozen package directory `/tmp/jr-ato-deploy-20261102-2b9ec9d1/live-s100-model` contains data only, never classes/JARs; aggregate SHA256(relative-path,NUL,bytes,NUL sorted) `2a21c2c71c73d2d1098db1785464587dfbe85a209614ef930e4565c6797d6795`. All code/classpaths in these experiments come from this rebuild checkout. To reproduce: set `JR_ATO_ACCURACY_MODEL_DIR` to the frozen package, `JR_ATO_ACCURACY_OUTPUT` to a CSV path and `JR_ATO_ACCURACY_FULL=1`, then run `:BukkitPlugin:test --tests '*AtoStoppingAccuracyExperiment'`.
- Executor microbenchmark:6 captured real five-car S100 states, each2k warmup+50k repeated calls, JDK25/1GiB heap/8logical CPUs. Running mean66-72us, p99<=289us; stopping mean239-247us, p99<=413us; overall maximum16.20ms including JIT/GC/scheduling. Includes executor/reference/ATP and cruise decision reuse, excludes capability capture, perception, model preparation, physics, rendering and I/O. Log `/tmp/jr-ato-rebuild-s100-benchmark-final.log`; local scripts in `build/ato-benchmark/`. No paired old/new baseline or server-capacity claim.
- Limitations: this proves the frozen vehicle/kernel scenarios and automated world regressions. It is not a completed Ha shared-program run, live-client acceptance, deployment, or a proof for every third-party arbitrary force callback. A settled tiny miss can retain `TARGET_UNREACHABLE` as exact-mark precision diagnosis; arrival remains governed by the shared0.3m contract, independent from ATP. No terminal retraction is used.

Built but not deployed: `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`, SHA256 `6633b32b8c67c0f7a38c952a57840ab45a1e9f34ebf012fab50c347722bd19e2`. Final gate succeeded in3m30s.
