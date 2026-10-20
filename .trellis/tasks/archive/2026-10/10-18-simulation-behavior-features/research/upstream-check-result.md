# Independent upstream-alignment check

**Verdict: PASS for the reviewed combined candidate against dev/signal-system@87fe3e45.** No unresolved implementation or test defect was found in this review scope. The current 2491-test JVM suite and aggregate build are green. This is an upstream compatibility and agreed domain-simulation check, not a claim of process-level failure isolation or Paper/Folia/client acceptance.

## Candidate and review scope

- Worktree/branch: `.worktree/simulation-world-alignment`, `codex/simulation-world-alignment`.
- Upstream base and rechecked current dev tip: `87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7`.
- Final combined source fingerprint: **`ae862ac844d99e997c76bbf840a6ded5fe589b1d44a037f989e0effabcbcef79`**, 48 paths including deleted files, recorded in `/tmp/simulation-alignment-gate-source.json` and durable task artifact `research/upstream-final-evidence.json`. Independently recomputed every listed path hash after aggregate completion: zero mismatches.
- Original candidate and verified backup remain separate recovery artifacts. No commit, merge into dev, push or deployment was performed by this checker.
- Loaded the actual behavior-task PRD/design/implementation/check context and current upstream research/specs. The stale injected signal-task header was superseded by the explicit behavior-task dispatch.
- Reviewed combined production changes and actual consumers across Bukkit/SimpleATO/SimpleSignals: behavior capture/operations/profile/owned seed, simulation domain assembly/retirement, public feature registration/target API, signal catalog/execution/manager lifecycle and authoring retry. Read the changed behavior/admission regression tests, existing real signal-monitor matrix and both rewritten joint fixtures. Core/Basic contracts were traced to current implementations; this does not claim a fresh full-source audit of every unchanged module.

## Findings (fixed)

- **World arbiter migration cleanup — production defect fixed by align_domain; independently reviewed.** After borrowing `CoreWorld.infrastructureCoordination`, the old SIM cleanup closed the Basic runtime and validator but retained the host's claims/static dependencies in the still-live World registry. The first current SimpleATO run caught this through the actual admission/edit regression. `SimulationRouteAdmissionHost.close` now uses `releasePlatformOwner(this)` for this host's completed claims, enumerates `dependenciesOwnedBy(requester)` and removes each exact owner/revision, then closes only its validator token. Existing `closeOwned` attempts every cleanup. It neither closes the World arbiter nor removes foreign owners. Pending reservation protection stays with Core's existing fail-closed rules. The enhanced regression installs a real static dependency and verifies a legal edit after retirement; all375 SimpleATO tests now pass.
- **Current test/API adaptations — completed by implementation owners.** New consumers were adapted to current owned construction, Unit visitor, controller constructor, ticket access, migrated resource DTO and completion-stage destruction APIs without restoring deleted APIs. The Java fixed-block fixture explicitly supplies the current `bidirectional=false` argument. The authoring retry regression's successful-publication counter correctly expects only the initial installation when both stale callbacks abort; it still requires two attempts, RETRY_REQUIRED, permit cleanup and unchanged durable/source state. All644 SimpleSignals tests pass.
- **Joint fixture strengthened and validated.** The official four-light/s100 case now crosses a forward signal boundary on two rails and authors real AB operating directions plus a finite10m/s SUBGRADE limit before capture. Its official0.6-second delayed door animation/sound completion gates remain strict. The second case executes the production collision-owner transaction with real captured controller/profile/extension state. Both pass in the full Bukkit run. No assertions were weakened to bypass an unavailable signal rule or fabricate operation completion.

The checker wrote this report only; product/test fixes above belong to the named implementation work, not unreported checker edits.

## Findings (not fixed): explicit limits, not unresolved defects

1. **Fatal host failures remain process-wide by upstream policy.** Shared `CarBehavior` maps guest load/declaration/call errors to `TrainBehaviorScriptException`; the SIM adapter records target identity, submits a derail to the car's current exact train and fails the operation. Unknown host failures retain `RailwaySimulationHalt`, which stops all coordinators in this process. Changing that policy is a design decision outside this preserve-upstream alignment. Normal World execution/state isolation must not be presented as protection from every possible failure. The parent goal retains this qualification.
2. **Collision-stage coverage is bounded.** The new joint case covers the real coupling owner transaction, pending exact-car JS/effects, persistent state and incoming controllerless-car profile resolution. Broadphase/contact detection is covered by existing Core tests; the joint case does not rerun that stage. No standalone whole-collision-pipeline claim is made from this fixture.
3. **Platform/runtime acceptance was not performed.** No Paper/Folia server or client was run. Actual display/audio playback, entity visibility and player sessions remain excluded by the agreed simulation scope. Offline domain tests do not establish deployment or client behavior.
4. **Existing shared curve-offset edge remains outside this batch.** As already recorded in the task, LIVE and SIM use the existing train-ID keyed speed-profile store and neither this review nor these fixtures establish a nonzero curve-offset inheritance matrix after split. This alignment does not silently change that separate shared policy.

No additional unresolved product-code finding was identified in the reviewed candidate.

## Static invariants confirmed

- SIM admission borrows the target World arbiter and only owns its validator/claims/declarations. Public infrastructure DTOs come from Core; actual admission/preparation remains in Basic. Real grant conflicts and post-retirement edits exercise the same arbiter used by Core topology protection.
- Seed uses `CoreTrain.createOwned`, immediately registers newly acquired behavior rollback, initializes installation direction/extensions/profiles/location before `publish`, retains actual saved turnout selection and captures `atoAdmissionRetryTicks`. Deleted DI/world-parent lifecycle APIs are not restored.
- Car/delay destruction return the same retained `CompletionStage<Void>` through `Destruction`; synchronous close observes completed cleanup failure. Closed metadata rejects before shared JS rather than triggering a global host fault. Resource retirement still follows actual physics/generic worker termination.
- Guest failure resolves the current exact car/train relationship and checks it at owner execution. Real split/link keep the original car extension, JS and pending effects. ATO resolves current captured car profiles through the existing resolver, including incoming cars without a counterpart controller, without retaining a LIVE factory/plugin lookup.
- `CarBehavior` and `CarOperationBatch` remain the shared business algorithms. LIVE/SIM use the same direction/metadata helper and atomic ticket terminal transition. Generic workers advance delay time; owner commit runs effect closures; SIM records semantic starts without Bukkit effects.
- Each LIVE World and SIM installation owns a `SignalLogicRuntime`/Engine. Only immutable model-neutral rules are shared. Same-instance evaluate/close remain serialized; no manager-wide shared JS engine or source-world callback enters target state.
- Manager directory locks remain short; World-local management protects only that runtime. Compilation, guest waiting and close are not moved under the shared directory lock. Capture does not take management/guest monitors. Definition CAS, original selection root/rail incarnation checks and exactly one unrelated-edit reprepare remain; recursive retry starts outside the management lock.
- Public feature contracts remain in `simpleato.api.simulation` and `bukkit.api.simulation`; target APIs do not expose CoreWorld or runtime implementations. Bukkit's SimpleSignals dependency is test-only. The Java fixture uses existing JVM-public constructors/owner transaction without reflection or widening production visibility.
- The formal joint case runs capture→create→real physics/signals/Dispatcher, checks both mixed-orientation cars' delayed animation and sound gate, compares deterministic traces, lets B finish while A's owner is held, and advances B after A closes. The existing actual SignalLogicRuntime monitor tests cover LIVE/LIVE, LIVE/SIM and SIM/SIM execution independence.
- No persistence format/version, compatibility path, UI or coordinate behavior is changed by this alignment.

## Verification

**Lint / whitespace: PASS.** Independently ran `git diff --check HEAD`; checked changed source/spec files for conflict markers. No outstanding index conflicts remain according to root; the checker did not mutate the index.

**Task context: PASS.** Independently ran behavior-task validation:24 implement and24 check entries. The existing36KB lifecycle-spec injection warning remains a context-size warning, not a failed code check; full relevant source/spec sections were read directly.

**TypeCheck / build: PASS.** Independently inspected `/tmp/simulation-alignment-aggregate-gate.log`: **BUILD SUCCESSFUL in1m28s**,65 actionable tasks,4 executed/61 up-to-date. The aggregate includes current public Java API consumer, `verifySignalModuleBoundaries`, `SimpleSignals:check`, Bukkit shadowJar and Core/Basic/SimpleATO Dokka publication. Up-to-date results are from the current aligned worktree and source; no old-baseline2411 gate is used as current proof. Existing Dokka missing-description/offline external package-list and Gradle/JDK deprecation warnings did not fail the build.

**Tests: PASS.** Independently parsed final full XML in the aligned worktree, including the full Bukkit XML restored after focused execution:

- Core:505 tests,62 suites.
- Basic:390 tests,59 suites.
- SimpleATO:375 tests,48 suites.
- SimpleATOBukkit:2 tests,2 suites.
- BukkitPlugin:532 tests,122 suites.
- SimpleSignals:644 tests,59 suites.
- TunnelBridgeBuilder:31 tests,8 suites.
- WebUI JVM:12 tests,4 suites.
- **Total:2491 tests in364 suites; zero failures/errors/skips.**

The full Bukkit XML actually discovers both joint cases: official signals/s100 two-target completion5.573s and coupling continuity0.626s, both passing. The prior focused run also executed both, but is not added again to the total. Updated behavior guest/close/split regressions and SimpleSignals authoring/real-monitor matrix are part of current full suites.

Frontend108 tests/production build and independent docs-site check passed under root's recorded current alignment evidence (`research/upstream-final-evidence.json`; retained logs under `../simulation-alignment-snapshot-20261020/evidence`); this checker did not rerun them or claim their checks as JVM XML. No build was started by the checker: root maintained the single build token.

The earlier admission-retirement failure, Java constructor/test consumer compile fixes and stale retry counter output are validation history only; no pending failure remains in the final evidence. The old2411 signal gate and60-case A/B run remain historical old-baseline evidence, separate from this current2491-test result.
