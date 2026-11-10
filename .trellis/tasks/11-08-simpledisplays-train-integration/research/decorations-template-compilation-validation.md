# Decorations template compilation validation — 2026-11-10

Foundation was committed with user authorization as **95935639** (`feat(decorations): extract display integration into add-on`). It was not pushed/deployed. New template-compilation work is a separate, currently uncommitted slice in the same isolated ddf5 worktree.

## Actual changes

Decorations discovers the optional compiler before registering its provider, so existing package validation can compile declared captured templates. DisplayPartProvider parses captured resources and invokes an API-free callback. Only the reflectively loaded binding/cache references SimpleDisplays API. Missing service remains metadata/source-only operation; actual XML/layout/script syntax errors propagate with package/template/file and original cause, preserving the host reload baseline.

Per-connection exact-source LRU retains at most64 compiled templates. Changed sources compile separately; reuse does not depend on package/template names. Compiler and close callbacks execute outside the cache monitor. Concurrent duplicate/late-after-close results are closed, cache eviction closes owned entries, and retirement attempts every cleanup and aggregates failures. No shared DisplayService.close, draw/render calls, entities, train/viewer/PAPI data or runtime mounting API were added. Cache entries are validation memoization, not a source of live mounted-template leases.

Host code change in this slice is only removing one trailing blank line missed by the prior staged whitespace gate. No host model/config/schema behavior changed. Documentation/spec/task context reflects the new compile boundary.

## Reviewed independent API

Independent SimpleDisplays remains clean at **410af2e**, with no source modifications. Published only its public API to a separate local Maven repository:

```sh
./gradlew -p ../SimpleDisplays publishToMavenLocal --offline \
  -g /workspace/minecraft/JavaRailways/.gradle-user-home \
  -Dmaven.repo.local=/tmp/javarailways-simpledisplays-xml-dom-maven --max-workers=2
```

Log `/tmp/jr-decorations-api-publication.log`; successful. Public API SHA256 **790cc2aa5a599b96db309c311d74803f9e489859924c5e905fb4c7c8e41b328a**. Current binding checks template AutoCloseable/close ABI before retaining a service. No API sources/classes are copied into the addon.

## Automated gates

```sh
./gradlew :Decorations:check :BukkitPlugin:test \
  --tests '*DefaultPartExtensionServiceTest' --tests '*ModelManagerReloadTest' \
  --tests '*TrainPackageSnapshotTest' :BukkitPlugin:shadowJar --offline \
  -g /workspace/minecraft/JavaRailways/.gradle-user-home \
  -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-xml-dom-maven \
  --max-workers=2 -I /tmp/simpledisplays-phase1-test-memory.gradle --console=plain
```

Log `/tmp/jr-decorations-template-tests.log`; **BUILD SUCCESSFUL**. Decorations **28 tests, 0 failures/errors/skips**. Host targeted regressions **28 tests, 0 failures/errors/skips**. Packaging gate and host shadowJar pass. No repeated full host suite was needed for this addon-only behavior change; the prior758-test result with the known ProgramExpressionsTest failure/1skip remains recorded in the foundation validation and is not claimed fixed.

Six new cache tests cover reuse/change/LRU64, failed compile retry and partial success, latch-controlled duplicates, retirement during compile, cleanup aggregation/eviction, and callback reentry outside lock. Optional bridge tests cover compile/close ABI, API-hidden invocation of the new API-free method, exact context/cause, and closing templates on host/provider/service retirement without closing service. Provider callback is tested on the real detached host context DTO. Independent final read-only reviewer reports no blocker.

## Actual Paper integration

Paper1.21.11 / Java25 in fresh `/tmp/jr-decorations-template-smoke-run2/` directories, real host and addon artifacts plus the previous reviewed410af2e SimpleDisplays plugin JAR. No existing server/world or main-checkout source/build is modified. Existing Paper runtime/libraries are read-only inputs, cloned temporary worlds/configs are used.

Runner `/tmp/jr-decorations-template-smoke.py`, logs `/tmp/jr-decorations-template-paper.log`, `/tmp/jr-decorations-template-no-addon.log`, `/tmp/jr-decorations-template-addon-no-simpledisplays.log` plus per-run console.log. An initial temporary probe Java-string escaping error was corrected before server launch; this did not involve product changes. Final scenarios:

- With SimpleDisplays: actual XML with a top-level JS throw compiles successfully, proving compile does not execute script. Existing-package startup validation reports no failures.
- Invalid `<unknown>` XML visual element and invalid embedded JS syntax are separately introduced on disk. Actual host prepare returns contextual failures; publish retains the identical old package and resourceVersion. Probe logs two `TEMPLATE_REJECT_PASS` messages.
- Replacing with valid changed source prepares/publishes a new version; old snapshot still has the original captured XML. Logs `TEMPLATE_COMPILE_PROBE_PASS`.
- No Decorations and Decorations without SimpleDisplays retain extension config, offset, child and both raw resources; normal startup/shutdown expected and checked by the same foundation probe.

No rendered client screen, runtime JS data/draw result, Folia runtime or dynamic replacement acceptance is claimed.

## Artifacts

- Host SHA256 **0a30baa0033838b22abac81966ad5490489052eb80c94b36980b12a43e8e0571**.
- Decorations SHA256 **acf7b7e90ef1d4427846dc52384ad579bf920dde4cadb437fa5e9a1a1ddb69ec**; class-list check confirms no host/SimpleDisplays API/Kotlin/serialization copies.
- Independent runtime SimpleDisplays SHA256 **eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391**.

Next stage remains generic train-part runtime mounting and concrete native/EntityLib text rendering. Current slice stops before those interfaces rather than adding no-op mount/render types. Deferred config-schema consistency task stays planning until the rest of display work is complete.
