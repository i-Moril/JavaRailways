# Decorations foundation validation — 2026-11-10

Implementation is uncommitted on `codex/simpledisplays-train-integration`, HEAD baseline `dc52e1b8`, isolated `/home/user/.codex/worktrees/ddf5/JavaRailways`. No main-checkout edits/builds, deployment, existing-world conversion or independent SimpleDisplays source changes. User requested a module/change report before committing.

## Implemented modules

- Host `api.model.extension`: stable namespaced provider registration, immutable detached part/package/resources DTOs, owner retirement and late package validation. Internal registry generation invalidates prepared reloads across registration changes; callbacks run outside locks.
- Host config/model/persistence: generic EXTENSION/config schema, native transform/animation/children retained as a group, explicit UTF-8 extension directory capture, deterministic resources and both main/per-car source snapshots. Missing provider preserves raw data and logs a per-package/type diagnostic. Provider rejection is applied before composition source wiring; surviving compositions use retained source baselines. Unused native imports are not traversed by generic extension validation.
- Independent `Decorations`: `decorations:display` field/resource validation and migrated optional SimpleDisplays API-free adapter. Thin ordinary JAR, hard dependency JavaRailways, soft dependency SimpleDisplays; no copied host/provider/runtime classes. Host no longer has SimpleDisplays dependency, binding, listener or descriptor dependency.
- Schemas/examples/module map/specs/docs/CI artifact list reflect the new ownership. Existing unshipped screen/root displays shapes are replaced directly, without legacy runtime reader or version increment. CI includes Decorations check/artifact; API artifact provisioning still requires the documented reviewed local Maven publication (there is no configured public release repository).

This batch has no runtime mount API, mounted text backend, compiled XML cache, train/PAPI data bridge or distance commands. Independent SimpleDisplays XML/document JS remains `410af2e`. The deferred schema consistency child remains unstarted for pre-acceptance.

## Static and automated validation

Initial focused run `/tmp/jr-decorations-focused.log`: 43 host tests +18 Decorations tests passed. After review fixes, the same seven host classes contain **46 tests, all passing** in the final full run. Includes actual DummyModel tree/animation/transform preservation, JSON detach, fixed native/no-extension hashes, import/source snapshots, owner retirement, late validation and registration races. Three added regressions cover unused native import tolerance and provider-rejected source fallback/no-baseline failure isolation.

Final command:

```sh
./gradlew :BukkitPlugin:test :Decorations:check :BukkitPlugin:shadowJar :BukkitPluginDev:compileKotlin \
  --offline -g /workspace/minecraft/JavaRailways/.gradle-user-home \
  -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven \
  --max-workers=2 -I /tmp/simpledisplays-phase1-test-memory.gradle --continue --console=plain
```

Log `/tmp/jr-decorations-full.log`. BukkitPlugin: **758 tests, 1 failure, 0 errors, 1 skipped**. Sole failure remains `ProgramExpressionsTest.one string parameter composes into destination station depot and display fields`, expected `[central@1]`, actual `[]`, the same failure recorded in previous task slices. No expression/compiler source was changed; no fresh historical-checkout replay was performed here. Full suite is therefore not claimed green. Decorations: **18 tests, all passing**. `verifyPluginJar`, host shadowJar and Dev compile succeeded with `--continue`.

Temporary init script only constrains test process heap/forks/CPU. The shared external Gradle cache is reused; source/build cwd remains ddf5.

All host/Decorations JSON schemas pass Draft 2020-12 metaschema plus **30 positive/negative cases** for extension fields, native gates, malformed IDs, directory shape, display values and rail rejection. Script `/tmp/jr-decorations-schema-check.py`, validator environment `/tmp/jr-displays-schema-validator`. Relative overlay reference resolves to the real host schema. Editor associations point at Decorations schema. `git diff --check` and Trellis task context validation pass (preexisting large-spec truncation warnings remain).

Independent read-only check agent reviewed source, module ownership, serializer immutability, schemas and regression captures after the final freeze; no remaining blocker.

## Actual Paper smoke

Fresh isolated directories under `/tmp/jr-decorations-foundation-smoke/`, Paper 1.21.11 / Java25; each normal start and `stop` completed with exit0. Existing server binary/libraries are read-only inputs; no live server or existing world is modified. Test config disables managed WebUI/optional runtime services. A synthetic train package declares `decorations:display`, an offset/rotation and child, plus captured displays.yml/XML.

A test-only reflection probe checks the actual host-loaded package: EXTENSION type, non-null payload, x offset, child count and both raw resource files. All three scenarios log `FOUNDATION_PROBE_PASS`:

- `no-addon`: only JavaRailways + probe; model package loaded and missing-provider preservation diagnostic emitted.
- `addon-no-simpledisplays`: Decorations enables/registers, SimpleDisplays absent message, model/probe passes.
- `with-simpledisplays`: Decorations enables, logs `SimpleDisplays API service is available`, model/probe passes; normal disable order.

Runner `/tmp/jr-decorations-smoke.py`; each scenario has console.log. Standalone SimpleDisplays JAR reused from the prior XML/document smoke, unchanged; the compile-only Maven API artifact remains the established `21f7686` service baseline. Actual Folia, client visuals, dynamic provider replacement and mounted rendering are not acceptance claims here. Animation and actual runtime model subtree behavior are covered by the host's real ModelTreeBuilder test rather than the startup reflection probe.

## Artifact boundaries

Inspected generated descriptors and class lists:

- Host SHA256 `6270ea3092c701b06e8565588a8545508ec6812b9af28ece80833b1a506a97b9`; no SimpleDisplays dependency/API/old binding or Decorations classes.
- Decorations SHA256 `dc19fe15507841394d26a36fc4e86d389771834e110bda74d762df2be6f65116`; correct hard/soft dependencies, no host/SimpleDisplays API/Kotlin/serialization/YAML copies.

No commit, push or deployment performed. Parent task remains in progress; this foundation slice is ready for user review before commit.
