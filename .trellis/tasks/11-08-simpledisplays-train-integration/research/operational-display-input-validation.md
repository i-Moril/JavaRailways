# Operational display input validation — 2026-11-10

## Baseline and scope

Dedicated worktree `/home/user/.codex/worktrees/ddf5/JavaRailways`, branch `codex/simpledisplays-train-integration`, committed baseline `09da5fbc` (dynamic sessions/basic train input). That previous slice was committed on explicit user approval; this operational-data slice remains uncommitted pending its own pre-commit report. No push/deployment or independent SimpleDisplays changes.

SimpleATO owns fresh fixed-unit/context/station proof capture. Bukkit owns detached projection, post-dispatcher sampling, same-stream sample retention and generic rendering generation invalidation. Decorations production is unchanged: its existing XML/document-JS adapter consumes the new generic data. PAPI, mounted per-viewer output, interior viewing distance/config/commands and style overrides are outside this slice.

## Checks

- Host focused 39 tests pass: PartExtensionAttachment, ExtensionPartModel, TrainPartRenderSnapshot, OperationalPartRenderProjection, PartRenderSamplingClock, TrainPartRenderSampleCache, PendingTrainFrames and CarFrameRevisionGate.
- Decorations 39 tests/check pass; host shadowJar, BukkitPluginDev compile and Signal API Java consumer compile pass.
- Decorations thin-JAR gate: no host/SimpleDisplays/Kotlin/Graal classes. Host shaded JAR contains no SimpleDisplays classes.
- Initial focused test compile found invalid generic casts; corrected tests use Java collection mutation calls, matching existing immutability tests. SimpleATO test compilation exceeded the default Kotlin compiler heap; validation uses temporary `-Pkotlin.daemon.jvmargs=-Xmx4g`, one Gradle worker, existing 2GiB test heap/fork isolation. Project build configuration unchanged.
- Full Core546 and Basic469 tests pass. Full host799: one known unrelated ProgramExpressionsTest failure, one skipped, zero errors; no new host failure. Final SimpleATO824 tests all pass.
- The new current-berth test initially assumed a scheduled future departure retained origin proof. Existing Dispatcher deliberately clears confirmedDoorStop when submitting that Mission, so WAITING_TIMETABLE_TIME/currentWayPoint is not proof. The corrected test observes an actual pending door operation before Mission submission, then actual departure. Capture stays conservative; no runtime fallback added.

## Review findings resolved

A sampled SET/reset envelope could be replaced by a later geometry-only envelope before its region callback ran. Each geometry envelope now carries the latest immutable sample from the same publication stream. The real LatestAffinityTaskDispatcher regression blocks a callback, coalesces sample A into geometry B, and verifies newest geometry plus retained sample. Foreign streams cannot borrow it; sampling/unload/stop releases old cache entries.

Terminal run confirmedDoorStop cannot supply a current berth. Current proof otherwise requires a stopped exact placement and the same active registered berth. No waypoint/approach/last-completed inference.

Read-only re-review found no remaining blocker after the sample-cache correction.

## Actual isolated Paper probe

Harnesses `/tmp/OperationalMountProbe.java` and `/tmp/jr-operational-extension-mount-smoke.py`; final console `/tmp/jr-operational-extension-mount-paper-smoke-v3/console.log`, summary `/tmp/jr-operational-paper-probe-v3.log`.

Real Paper 1.21.11-132 / Java25; copied final built plugins into a new `/tmp` world with loopback/ephemeral port and ticketed target chunk. No development server touched. Probe constructs real domain DTOs and calls production `projectOperationalPartRender` through the host classloader, then offers its detached unit/operational/alias values to actual production builder/session/XML rendering. Physical root movement and input changes are probe-driven; real dispatcher/fixed-unit lifecycles are exercised in module tests separately.

Both native and EntityLib packet backends emit `OPERATIONAL_MOUNT_BACKEND_PASS`, followed by `OPERATIONAL_MOUNT_PROBE_PASS`; server stops normally. Checks cover:

- Authored destination/runType reach XML string binding and document JS. Remaining STOP/PASS/TERMINAL order is C,D,E, with Central as proven next candidate.
- Cycle/arrival tick `9007199254740993` stays an exact decimal string inside actual JS evaluation.
- Routine update reuses actual text visual identity; grow/EMPTY/recover change and remove actual passenger IDs correctly.
- Context invalidation synchronously retires old generated labels. Intentional replacement JS failure leaves blank, then later valid input recovers with original elapsed clock origin.
- Source-package pinning despite conflicting imported geometry/default and overwritten source XML; current moving shared root; original subtree preservation; provider disable cleanup and true session/composite completion.

Probe v1/v2 initially waited for JS text on a node with `bind=destination`; existing library intentionally uses that binding over its fallback/textContent. Actual first frame correctly displayed authored `First`. Final probe clears bind on that JS node using supported `setAttribute` before constructing the diagnostic text. No library change was made. The two intentional new-context draw errors in the passing run are expected assertions of blank-on-failure, not unexplained runtime faults.

Artifact SHA256 (built artifacts exactly match final probe plugin copies):

- Host `b5852778aea35cdeb1d167f732a4cbb004f79e0678fdae02f5286a602e3c1d27`.
- Decorations `d981b85d02aacad0dea3f8eb5748091455c4ae40b5d2780fad6b04e94923db7b`.
- Independent SimpleDisplays unchanged `eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391`.

## Final quality gate

Full tests: Core546/Basic469/SimpleATO824 pass; host799 has only the previously known ProgramExpressionsTest failure and one skip, zero errors. Focused host39 and Decorations39 pass. Full verification log `/tmp/jr-operational-full.log`; corrected current-berth targeted log `/tmp/jr-operational-berth-test-final.log`; final full SimpleATO log `/tmp/jr-operational-simpleato-final.log`. The known host failure remains expected `[central@1]` versus actual `[]` at ProgramExpressionsTest.kt:130; no unrelated fix applied.

`git diff --check` and Trellis context validation pass. Current slice is ready for pre-commit report; still uncommitted.

## Acceptance limits

This proves actual server-side rendering with production projection and separately tested real domain lifecycle. It does not prove a complete spawned train/client experience, network viewer behavior or a real Folia runtime. No large-fleet performance claim. Mounted output still shares the train audience; PAPI stays excluded. Existing SimpleDisplays input budget is retained: oversized input fails the local screen draw rather than truncating stop facts or raising limits.
