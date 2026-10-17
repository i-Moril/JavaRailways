# Boundary display names: independent check

Date: 2026-10-17. Role: dispatched trellis-check. No commit, deployment or remote save changes.

## Scope and method

Read check.jsonl context, PRD/design/implementation plan, loaded hook/spec context, persistence release policy and actual storage → transaction → authoring → display paths. Preserve pre-existing dirty work against `/tmp/boundary-names-preimplementation` rather than treating all Git differences as this feature. Initial review was read-only during implementation final2; edits began only after coordinator confirmed that build had finished and implementation ownership was frozen.

Reviewed the 27 product/test paths supplied by the implementer plus the new real Route Preview backend regression file (28 total below), and `boundary-names.md` with linked authoring/index/edit-impact specs. No Core/Basic/public service API changes were introduced by this review.

## Findings fixed

1. `authoring/BoundaryNames.kt`: a boundary authored only on one side of a genuine ordinary rail joint could not be selected by clicking the exact connected peer endpoint. `nearest` now resolves an exact physical group first, returns a real raw authored anchor, then uses the existing same-rail snap selection. Regression includes the sole authored peer, a non-exact near-joint hit, disconnected rails and turnout branches. It does not add world-coordinate proximity matching.
2. `authoring/CurrentRoutePreview.kt`: every boundary label rebuilt the whole-world name index, including twice per route row in deletion menus. Added a selection-local cache keyed by exact definitions and topology identities, with per-preparation curve caching. Real backend regression proves repeat labels reuse geometry and subsequent name publication/topology replacement refresh it.
3. `authoring/RoutePreviewWandHandler.kt`: the route deletion page rebuilt the complete boundary-ID lookup inside each row. Hoisted it once per captured page; route callbacks still close over exact selected route records.
4. `runtime/SimpleSignalsWorldRuntimeManagerTest.kt`: added the missing shared-boundary one-owner removal → last-owner removal → recreation regression. Expanded the existing real region repair test to name a surviving cut and verify metadata through the draft and repaired region while retaining the manually selected saved route.

## Coverage and conclusions

- All naming anchor producers are included: pending cuts, region effective cuts (draft authoritative), fixed block first/last traversal endpoints. Names remain separate from geometry equality, IDs, facing and operational definitions.
- Shared grouping uses captured ordinary endpoint/degree-two LoopAnchor identity, not spatial distance, crossing conflict edges or current turnout selection. Tests cover all ordinary endpoint direction pairs, loops, crossing/disconnected/turnout negatives, split retention, conflicting merges and explicit resolution.
- Metadata has an empty default within existing schemaVersion 1. Fixed pre-name JSON reads through the real world store; Unicode/duplicate names round-trip, full-document draft export/import/diff and runtime copy retain names. Strict reader behavior and current writer remain unchanged; no runtime compatibility version path was added.
- Rename and clear use the sole revision-checked definition transaction and existing edit permit/staged disk/source-publication commit. Tests exercise actual save failure and permit denial without disk/publication/revision changes; metadata impact is limited to changed raw name-anchor rails. A rename preserves region/table/indicator definitions; all geometry and device revisions remain source-identical because names are separate metadata.
- Candidate normalization preserves pending → block/region completion, surviving repair cuts and shared ownership; explicit final deletion cleans the metadata, recreation cannot inherit an orphan. Missing topology/curve data do not themselves remove still-authored anchors.
- Owner callbacks check player identity/world/permission/tool and session/token plus one-use submission; worker backend validates captured runtime/publication/topology and manager rechecks before commit. Invalid/empty/cancel/stale handling and original gestures pass existing and focused regressions. Rendering uses plain text; input is trimmed, bounded to 64 Unicode codepoints and rejects control/line separators.
- Overlay, Signal Wand entrance summaries/choices, indicator per-route endpoints and Route Preview labels resolve the shared metadata. XYZ coordinates accompany labels; action identity remains the exact route ID even under duplicate labels/multiple routes. No new physics tick scan or graph search was introduced. Name indices are prepared once per relevant capture/selection, and overlay shares curve reads with its geometry preparation.

## Findings not fixed / acceptance limits

No remaining concrete in-scope product defect was found. Real Paper/Folia client input behavior, visible TextDisplay layout/readability and gameplay acceptance were not run; these remain a separate client/server acceptance step. Offline tests do not prove live plugin classloader or client rendering behavior. This task does not deploy or change route-indicator/main-signal permission logic.

## Verification

Final command (after reviewer changes):

```sh
GRADLE_USER_HOME=/workspace/.gradle ./gradlew --offline --no-build-cache --no-parallel --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8' -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
```

- BUILD SUCCESSFUL in 1m20s; 37 actionable tasks, 9 executed and 28 up-to-date.
- Kotlin/Java compilation and public signal API consumer compilation passed.
- SimpleSignals: **59 suites, 622 tests, 0 failures, 0 errors, 0 skipped** (read actual JUnit XML).
- `:SimpleSignals:check`, JAR descriptor/forbidden package verification, root module-boundary check passed.
- `git diff --check` passed. No dedicated formatter/linter task is configured in this module; whitespace validation and configured Gradle quality gates passed.
- Log: `/tmp/boundary-check-final.log`.
- Final product/test path list: `/tmp/boundary-check-feature-paths.json`; hashes: `/tmp/boundary-check-feature-hashes.json`. All 28 source hashes matched after the final checks; sources frozen for coordinator review.
- Existing warnings include Graal POM ZIP inspection, deprecated Java/Paper/Gradle APIs, Java installation auto-detection and JVM native/Unsafe warnings; no check failure resulted.

## Final product/test manifest

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/BoundaryNames.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/CurrentRoutePreview.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/RouteIndicatorWandEditor.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/RoutePreviewWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayDrawing.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandSessions.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandBackend.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/WandDialogs.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/BoundaryName.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsDefinitionDrafts.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitions.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsDefinitionEditImpact.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`
- `SimpleSignals/src/main/resources/i18n/wands_en_US.properties`
- `SimpleSignals/src/main/resources/i18n/wands_zh_CN.properties`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/BoundaryNamesTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/RuntimeRoutePreviewBackendTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionOverlayDrawingTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandlerTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandlerTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsDefinitionDraftsTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitionStoreTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntimeManagerTest.kt`
