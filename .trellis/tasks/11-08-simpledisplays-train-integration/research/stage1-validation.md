# Stage 1 validation — 2026-11-09 UTC

## Scope and result

Optional SimpleDisplays API discovery is implemented; no package declarations, screens, operational projection,
persistent-format changes or runtime style switching are included. Task remains `in_progress`; stop after
this focused commit for review. Stages 2–7 remain pending.

The API-free holder is loaded during JavaRailways enable. Only an enabled named SimpleDisplays provider
allows loading the typed adapter; absent/mismatched API, missing service and wrong service owner produce
an unavailable integration. Exact service class plus provider identity control unregister retirement.
Provider and host disable release retained references. No reconnect/retry mechanism was added.

## Dependency and packaging

- Independent source: `/workspace/minecraft/SimpleDisplays`, clean HEAD `21f7686eb8f0d181a89bb7cb21f92f9bff0ef717`.
- Consumed coordinate: `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT`, compile-only; test classpath includes API explicitly.
- Explicit local Maven repository: `/tmp/javarailways-simpledisplays-maven`; no API copied into source or host JAR.
- API artifact SHA-256: `5b3530cfa4024abf391c0f5759482e57f78ba0a182e3c07d8a0322be55f60467`.
- Final host shadow JAR SHA-256: `9492d3a3eefa26812906759eaab82a782eb860fbbfd9a6a68a9455e3c8dddf90`.
- Independent provider JAR SHA-256: `f9589b1506094b96a6d180c68680d66effa96efea00d1e8144b0a18c3aa84fb4`.
- Inspected generated `plugin.yml`: `SimpleDisplays` appears under `softdepend`.
- Inspected shadow JAR: zero `simpledisplays/api/` entries.

Build setup and publication command: [Bukkit build guide](../../../../BukkitPlugin/docs/simpledisplays-integration.md).
Lifecycle contract: [optional service spec](../../../spec/bukkit-plugin/backend/optional-simpledisplays.md).

## Automated validation

Focused command (final production sources):

```sh
./gradlew --offline -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :BukkitPlugin:test --tests '*OptionalSimpleDisplaysTest' :BukkitPlugin:shadowJar --max-workers=2 --console=plain
```

Result: **7 tests, 0 failures** and successful shadowJar. Tests use actual production bytecode in an
isolated classloader to deny API classes, mutate the real API compile member to test incompatible ABI,
and assert exact registration ownership and service/plugin retirement. Discovery never compiles XML.
Two initial lifecycle tests failed in Bukkit event constructors because their test server was unset;
they now reuse the repository's BukkitServiceTestSupport and pass. Independent review also corrected
an unregister case where one provider object is registered under another service key.

Independent SimpleDisplays `compileKotlin`, `apiJar`, publication and `test` succeeded; `test` has NO-SOURCE.

Full Bukkit suite:

```sh
./gradlew --offline -I /tmp/simpledisplays-phase1-test-memory.gradle -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :BukkitPlugin:test --max-workers=2 --console=plain
```

The temporary init script gives Test workers `maxHeapSize='2g'`, `maxParallelForks=1`, `forkEvery=10`, and
`-XX:ActiveProcessorCount=2`. No repository test configuration changed. The default-heap attempt exhausted
heap in `IsolatedTimetableSimulationFactoryTest`; the 2 GiB run completed **749 tests, 1 failure,
0 errors, 1 skipped** across 148 suites. Sole failure is the unmodified
`ProgramExpressionsTest.one string parameter composes into destination station depot and display fields`
at line 130: expected `[central@1]`, actual `[]`. This stage does not change its compiler/expression logic;
Standalone class rerun reproduced the same failure (11 tests, 1 failure); test and expansion implementation are unchanged from HEAD, but no baseline checkout replay was performed. Log: `/tmp/simpledisplays-phase1-existing-failure.log`. No out-of-scope fix was made. Full-suite success is **not** claimed. The 7 integration tests passed in
this full run as well. Raw local logs: `/tmp/simpledisplays-phase1-build-final.log`,
`/tmp/simpledisplays-phase1-full-tests.log`, `/tmp/simpledisplays-phase1-full-tests-2g.log`.

## Real Paper startup

Both cases ran in fresh, isolated `/tmp/simpledisplays-stage1-smoke/` directories using Java 25.0.4 and
cached Paper `1.21.11-132-c5eb079`, loopback random server port, WebUI/bridge disabled, empty railway
resources/worlds. EntityLib was the configured backend. No existing run directories or worlds were modified.

- Absent, 16:16:53–55 UTC: `SimpleDisplays is absent or disabled; train displays are unavailable`, then
  `Model packages loaded: 0 train, 0 rail`, both Wands initialized, server `Done (10.064s)`; normal stop, exit 0.
- Present, 16:17:24–26 UTC: SimpleDisplays enabled first, `SimpleDisplays API service is available`,
  model registry and Wands initialized, server `Done (18.886s)`; both plugins disabled normally, exit 0.
- Both copied host JARs match the final host hash above. No JavaRailways enable exception in the final runs.

Local harness: `/tmp/simpledisplays-smoke.py`; final logs: `/tmp/simpledisplays-stage1-smoke/absent/console.log`
and `/tmp/simpledisplays-stage1-smoke/present/console.log`; each directory records `classpath.txt`.
Initial harness attempts mixed old library versions and plugin-only Kotlin/serialization libraries from
the existing development cache into the server parent classpath. Those attempts failed and are preserved
in sibling directories with explanatory suffixes. Final harness selects one latest cached library per
module and excludes plugin-only Kotlin/serialization/Graal/Koin libraries so the host uses its own shaded
runtime. A sandbox-only attempt failed to create a socket; final startup ran with reviewed permissions.
The flat-world generator also logged missing-layer settings; neither final run had plugin enable errors.

This proves startup and optional service discovery only. No train models were loaded, no players joined,
no display placement was tested. Folia and both-renderer visual/game acceptance remain later work.

## Review and commit scope

Review found and fixed exact-service unregister matching. Source diff whitespace check passes.
Commit only phase-1 host build/lifecycle code, integration tests, the new build guide/spec, and this task's
planning/validation records. Preserve all unrelated dirty and staged changes; no push or deployment.
