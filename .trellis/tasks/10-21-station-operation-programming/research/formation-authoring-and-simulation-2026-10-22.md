# Fixed formation authoring and simulation checkpoint

Root-owned work in the isolated station-operation-programming worktree; no commit/deployment.

## Visual and compiler path

- Shared programs support COUPLE/SPLIT with literal or statically bound FORMATION_EVENT parameters. STRING parameters cannot substitute for event references. Strict expression shape and normal step attribution remain in ScriptWorker.
- Authoring document has optional default-empty formationEvents and TAKE_FROM_FORMATION_OUTPUT duty selectors. Catalog shape matches the existing compiler DSL, with fixed participants, complete HEAD unit count, FIRST/REST outputs, rendezvous endpoints and capped coupling approach.
- Duty page renders Ant Design event fields under the existing document/history owner. Complete-unit counts commit via existing blur/Enter input. Duty rename updates participant/output duty references in the same transaction. Event removal/ID edits leave contextual validation errors for broken references instead of guessing replacements in immutable program definitions.
- Generation emits quoted JSON.parse event definitions and exact output acquisition calls. Local validation checks participant invocations, consumed participant termination and reciprocal output ownership. Server compiler remains authoritative for event cycles and physical proof.
- Java editor/library validators and local recovery readers recognize the same current shapes, with no version bump or development compatibility reader.
- Output duty profiles are inherited from actual formation outputs; the UI does not ask for a separate simulation vehicle.

## Evidence

- Frontend 147 tests / 27 files green; production TypeScript/Vite build green.
- WebUI backend 24 tests green, including strict event/library structure. This predates the later simulator-only change.
- Actual JS ScriptWorker program suite 9 green; this also compiled current Bukkit command and infrastructure-wrapper changes.
- Dedicated agent-browser session station-program-ui against frontend demo on 5181: added split event, observed compile invalidation, edited complete-unit count with Enter, Undo restored one. Screenshot /tmp/station-formation-editor.png. This is local UI behavior, not real server save/publish or game acceptance.

## Simulation work in progress

Factory now excludes output duties from independent profile selection and physical seeding. Structural initial WORLD_TRAIN profiles capture only registered permanent units/classification, remapping to new simulation car identities and preserving operating order; no live tickets/assignments are cloned. Sources receive fresh local ledger claims. Template sources lacking explicit unit membership reject instead of inventing units. Source depot definitions must be canonical persisted destinations.

New real factory split test now passes: one source is seeded, actual split produces two physical trains, delayed child Take100 and both real ATO movements complete, two permanent units/claims remain and the live source world stays unchanged. The test exposed and fixed named-selector lookup (name differs from Core ID) and missing formation forwards in the isolated admission wrapper; simulation now invokes the same Basic formation path. Focused factory/control-profile gate10908 passed; current tests do not yet establish repeated cycles or coupling simulation.

## Outstanding

Scheduled coupling retained pair proof/runtime, arbitrary supported station branches and dynamic target choice, integrated circulation diagnostics, complete visual branch editor, repeating-cycle timing/capacity proof, full affected backend gates and live game acceptance remain open.

## Circulation diagnostic integration checkpoint

The compiler validation response now carries independent inventory/timing/capacity conclusions and contextual findings. The existing validation page renders these under the same compiled-document identity; no new document owner or persistence version. Missing summary remains absent rather than invented success. UI explicitly distinguishes inventory closure from completion timing and depot capacity. Frontend view suite10 tests and production build pass.

Combined gate3658 compiled the HTTP projection and all affected dependents. Static geometry16 and circulation21 passed; dispatcher existing9 passed, but new coupling fixture2 failed before runtime due duplicate car IDs (subsequently corrected by runtime owner). Factory existing8 passed; new split-output StoreAtDepot case exposed a real frozen-route continuation mismatch at actual head179.910 versus nominal stop180. Overall gate failed; no combined-success claim.

Gate99290: Basic contact8 green (including zero-connector boundary and unrelated occupancy rejection), static structural16 green (including straight future-window extrema), cursor8 green, Bukkit factory9 green. Real split output now drives to B and commits AVAILABLE storage at B while permanent home remains A; child continues separately, unit count conserved. Fixes: physical-origin append aligns exact committed occurrence; storage envelope uses operational stop after subtracting retained-route terminal offset. Dispatcher10/11 passed: actual coupled output continues successfully; HOLD case retained an obsolete contact controller and is being repaired by runtime owner. Overall gate still failed on that HOLD case.

## Timed branch transport/editor checkpoint (in progress)

Current unpublished Conditional wire shape uses thenSteps/elseSteps with timed At children. Compilation trace carries typed branchPath, defaultempty at top level. Visual BRANCH has provider condition/parameters and independent thenSteps/elseSteps, recursive fixed-event rejection,256 total steps/depth16. Expansion joins authored suffix tick by maximum arm end tick and preserves full nested step origins. Shared library Java validator, frontend validation/recovery and nested editor use the same shape; arbitrary JS reverse import remains unsupported. Nested source navigation matches full branch path, including delay reports from HTTP, and displays the actual bound child step.

Gate33644: main compilation, ScriptWorker/StationProgram30 tests green. SimpleATO75 tests had2 failures (static negative test host cast and a physical-acquisition fixture with genuine recurring A deficit); new timed nested condition/WaitUntil/Stop/extension execution passed. WebUI repository tests passed prior gate3827. Full frontend151/27 green, production build green. Full movement branch propagation and dynamic station choice are still being implemented; these checkpoints do not establish them.

## Ordinary movement branch and physical coupling gate

Gate38873 compiled main/test sources and ran88 SimpleATO tests;87 passed. Ordinary branch validation3, actual branch movement/continuation in Dispatcher47, formation dispatcher13, station allocator2 and selection7 passed. One static structural rendezvous fixture failed onward-origin proof and remains under its owner. Gate34032 factory9/10: new real-physics couple→move→split fails at tick17 on the actual approach-speed cap; the stationary peer has not moved. Investigation targets the progressive physical actuator, retaining Basic admission cap rather than relaxing it. Previous kinematic dispatcher coupling success did not establish this real-dynamics path.

Browser mounted real ProgramDefinitionEditor: malformed nested JSON disables validation, deleting the entire invalid branch restores enabled validation. Temporary branch-check harness removed and browser returned to preview root. No authenticated live-world acceptance claimed.

Dynamic SELECT_STOP authoring now uses typed STATION + ordered distinct nonempty ID_LIST with FIXED_PREFERENCE/AVAILABLE_PRIORITY; expands to one timed movement/station action. Runtime/static integration remains in progress. Frontend targeted27 passed including selected/admitted display and full active branch address. HTTP activeStepIndex/activeStepBranchPath use actual movement address for delay navigation. WebUI authenticated test expanded to two independent timetable snapshots; current gate pending.

## Local persisted browser acceptance

Started isolated real WebUiApplication JAR at18767, temporary disk repository /tmp/jr-program-browser-data, local auth/world-metadata fixture at18766. The bridge does not execute or pretend successful game operations. Through browser UI created platform-test with ordered long/short candidates, saved r1, reloaded page and reopened persisted definition, added a BRANCH with nested WAIT_DURATION, saved r2, reloaded and reopened. Confirmed Store/Couple/Split disabled inside branch menu. Disk record preserves order, IDs, complete nested AST and new contentIdentity. Both temporary servers stopped after check. Screenshot /tmp/jr-station-program-branch.png. Old executionHint still claimed branches unavailable; corrected both locales after screenshot. This is actual local WebUI persistence, not game-world acceptance.

Frontend full153/27 passed. WebUI library/HTTP tests passed including two persisted document snapshots. Basic TrainDynamics22 passed with physical bounded-cap preview and budget-exhaustion behavior. Gate3055 SimpleATO86/88: structural previous regression fixed; two new selection fixtures missed canonical endpoint projections. Gate59756 Bukkit43/44: all JS/visual-program compiler tests passed, factory coupling now reaches physical contact (tick738) with cap preserved, then fails publication/derail guard; detailed Core/Basic state investigation remains underway. Sources are not yet globally green.

## Integrated retained-route and selection gate74101

- Core PhysicsCoordinator37/37 passed after correcting the hold-release test fixture to issue a real ongoing actuator command.
- SimpleATO99/99 passed: Dispatcher50, Formation14, StationCallResolver8, BranchValidation6, StructuralValidation21. This includes actual ordinary length selection, physical occupied preferred berth fallback, branch-specific structural lineage and retained GoVia intermediates.
- Bukkit factory10/11 passed. Dynamic selection picked the long marker for the actual train, stopped, reversed and returned. Coupling now completes actual Core contact and moves onward; the sole failure was the test's1200-tick delay budget near the target (117.84). Fixture budget changed to4000, pending rerun; no operating cap relaxed.
- Frontend153/27 passed again after latest provider parameter help and candidate-limit UI changes. Latest production build passed gate74101.
- Deferred output first branch/selection, stationary output pose invalidation and retained destination metadata drift fixes are subsequent changes awaiting the next combined gate. Nested visual compilation failure now preserves the deepest child step identity, with regression pending.

## Full SimpleATO gate32872 (not green)

Basic45 and WebUI26 passed. SimpleATO562/570 passed; six formation handoff regressions traced to a new activation/fence wait cycle and an overly broad captured-frame comparison, with two validator fixture/key cases under review. Bukkit20/23 passed; both prior split factory tests hit that same activation wait and the coupling factory hit the captured-frame comparison. Nested-child compile-error attribution regression passed. Retirement serialized-command tests passed. Do not interpret the focused prior99green as the current full-suite result. Fixes and bounded normal-validator memo/budget work continue before rerun.

Gate13008: deferred activation deadlock fixed (delayed output, both orientations and new-duty cap cases pass again); real split/storage factory cases recover. Remaining coupling guard mismatch is segmented-versus-normalized body traversal with stopped/direction/head/tail/car poses identical. New unsafe preparation regression exposes cached terminal result hidden after recovery; resource claims remain held. New validator memoization exposed route/offset list association bugs, being corrected. Gate remains red: Basic7/8, Simple29/40, Bukkit9/11. These are explicit intermediate regressions, not completed acceptance.

Gate95646: Basic8/8 (including safe/unsafe terminal cache), validator/branch24/24 pass. Formation13/16: all earlier acquisition/orientation/line-cap regressions fixed; deferred first-action branches hit Basic's full-route-only output admission contract. Coupling still rejects reconstructed body endpoint differing by~5e-15, confirmed by diagnostic gate79136 (held11.499999999999739 versus current11.499999999999744, same exact head/tail/car poses). Fixes scoped to existing body geometry precision and safe body-only prefix extension are in progress. Factory10/11: dynamic station stop/reverse/return restored; coupling is sole failure.

Baseline preservation audit:15 of22 inherited changed files remain byte-identical to recorded baseline. Seven are intentionally extended by this task: TimetableHttpBridge, DutyEditor, authoring, authoringViews tests, i18n, studioPages CSS and frontend types. No primary worktree edits or deployment performed.

## Broad gate46029 plus Basic correction gate29708

Core524/524, SimpleATO574/574, Bukkit575/575 and WebUI26/26 passed. Basic424/425 initially failed the new second-deferred-output fixture: the first train's complete acquired interval was correctly still held. Regression now explicitly requires RESOURCE_CONFLICT before actual tail clearance, extends the first train's ordinary authority, then verifies rear output departure only after the whole interval clears. Basic425/425 passes gate29708; production unchanged between these gates. Combined backend2124 passing tests; frontend153/27 and production build pass.

Both first-output condition arms now complete actual movement, true arm selects the short marker from the published one-car output, and onward route progresses. Real factory coupling→movement→split completes twice deterministically with unchanged live world and permanent-unit conservation. Final braking frame waits one stationary publication before requesting split. Final uncovered combination under targeted review: first runtime branch child Reverse while structural body grant/fence is still held. Do not mark that combination accepted until its regression is run.

## Held output first-branch reversal follow-up

Focusedgate37407 confirms runtime branch Reverse before output departure was rejected by the ordinary source-grant freeze. Implementing explicit held-output use of existing suspend/rebase APIs, rather than bypassing authority. Handoff captures exact pending owner, skips only that output while reversing, persists the new owner direction immediately in the same callback as Core reverse, and resumes handoff only after Basic validates/rebases the exact body. Depot regression covers stale/copied token, missing flip, duplicate physical membership, retained unit/stock/peer ownership and recovery requiring explicit handling. No saved shape/version change. Basic and integrated regression gate pending; prior2124 passing count predates this final follow-up.

Gate63147: held reversal reached the first stop; the next movement exposed the formation grant's complete-route-only extension restriction. Basic negative fixture also exceeded DTO bounds and was corrected. Exact-prefix append now preserves owner/committed prefix, compiles and reserves normal delta, and installs cursor/boundaries only after READY. Gate51036: Basic FormationOutput11/11 and SimpleATO FormationDispatcher17/17 plus FixedFormationChange12/12 passed, including actual held-child reverse then two onward destinations, safe abort/retry and ledger direction persistence. Final monotonic retained-clearance invariant and broad regression follow.

## Final local verification checkpoint

Gate12378: full SimpleATO576/576 and Bukkit575/575 pass with the final production code. Basic426/427 exposed the newly added negative fixture injecting shortened clearance into the wrong provider expression. Corrected only the fixture; gate47008 full Basic427/427 passes. No production changes between those gates. Current affected-module total1578; previously verified unchanged Core524 and WebUI26 bring recorded backend coverage to2128 passing tests, zero skips. Frontend153/27 and production build remain green from the recorded frontend gate. Final git diff --check passes.

Supported held-output continuation now includes first runtime branch Reverse, exact same-callback owner direction persistence, Basic stopped-body rebase, real onward movement and safe exact-prefix route append. Append cannot shorten existing retained release evidence; pending/safely aborted preparation leaves old cursor/claims unchanged. Re-entry into protection used by a moving sibling remains rejected.

B1–B5 code is integrated in codex/station-operation-programming (base a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2), uncommitted. Inherited UI/backend baseline is preserved. No deployment or primary-worktree product edits. Local browser persistence uses the actual WebUI repository but fixture authentication/world metadata; isolated simulations use actual Core physics/admission and do not mutate the live world. Paper/Folia client/game acceptance, deployment and production-world migration remain outside this local verification checkpoint. Missing circulation timing/capacity evidence is explicitly UNPROVEN, never a guarantee.
