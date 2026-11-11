# Depot simulation regression integration — 2026-10-31

Baseline: `/tmp/javarailways-blockly-deploy`, branch `codex/timetable-station-programs`, HEAD `bc7d6db1`, after merging the deployed operating-direction enforcement changes. This correction owns only the Bukkit factory test and this record. Production behavior and persistence are unchanged.

## Failure and source evidence

- The pre-correction `BukkitPlugin/build/test-results/test/TEST-work.art1st.javarailways.bukkit.http.IsolatedTimetableSimulationFactoryTest.xml` reports 30 tests and one failure. The failed boolean instance reached `home@rail` at tick 17 and returned real `Failed` with `OperatingDirectionViolation: reversed train body violates operating directions`. The old assertion required every fixture to complete, including reversing rail-fallback AB to BA on an AB-only rail.
- `ProgramSimulation.kt:30` resolves an origin's own approach direction before its rail direction. This selects physical placement; it does not grant operating permission for a subsequent reversal.
- `TimetableDispatcher.beginReverse` checks the complete reversed body with `routeDirectionLimit(...).requireLegalThrough(...)` before any physical reversal. A failed check stalls the run and returns before reversal or the function's task boundary.
- `.trellis/spec/javarailways/simpleato/operating-direction-enforcement.md` explicitly requires validation of the reversed body and rejects forbidden direction spans. `OperatingDirectionStore` permits both directions when no entry exists. The prior depot research records development-server `kasaidpt@1..6` as own-BA points with no rail restriction; that historical server observation is not re-verified by this test correction.

## Small correction and retained acceptance

The existing two boolean instances still execute four isolated simulations in total:

- Own-BA depot point on a separate rail with no operating-direction restriction: both `reverseTrain=false` and `true` must complete the real 7-tick function. The test independently checks own-BA resolution against a temporary conflicting rail-AB declaration, then clears that declaration before capture so both execution directions are legal.
- Rail-fallback AB depot point on an AB-only rail: `false` completes the same function; `true` must return actual `Failed` with `OperatingDirectionViolation`, record no task timing, retain unreversed head/tail and zero speed, and preserve body occupancy.
- Both cases retain canonical point identity, restored target destination, source object identity, source depot points/stock, no source trains, and unchanged source operating directions across simulation. Target direction declarations are checked against the captured source declarations.

No production guard is relaxed, no negative case is removed, and no extra simulation is added.

## Checks and boundary

The implementer inspected the existing XML and current resolver/reversal/store sources, then ran `git diff --check` for these two files. Gradle/npm, commit, deployment and archive are reserved for the parent session; this record does not claim the corrected test has run. The parent must rerun Bukkit and the remaining serialized release gates before treating the candidate as validated. Live game/client acceptance remains separate.
