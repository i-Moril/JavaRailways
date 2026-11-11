# Screen-part definition slice — 2026-11-09

## Scope and workspace

Implemented only the reviewed `type: screen` part definition, official schema, strict parser and
metadata-preserving tree/model behavior. Worktree: `/home/user/.codex/worktrees/ddf5/JavaRailways`, branch
`codex/simpledisplays-train-integration`, base `ce2bcd44`. Source, project caches and build outputs are
local to this worktree. Only the existing Gradle dependency cache is shared; no main-checkout source or
build output was used as this slice's build target.

Screen parts support standalone files/imports and inline/nested definitions. Required `screen` fields:
`category`, `interior`, finite positive `width` and `height`. Public part transforms/names/children/animations
are reused; no root `screens` list or screen-specific IDs. Tree copies preserve metadata and resolve
inline definitions without doubling their transforms. Screen construction currently creates only a
transform/group node with its children and animations, with no screen visuals.

No style/XML loading, category-default resolver, new package/snapshot layout, screen rendering,
visibility algorithm, config/command implementation, migration or schema version bump is included.
The existing snapshot DTO naturally retains the new nullable field; its canonical serializer omits null.
A fixed screen-free fixture verifies canonical model hash
`0d53618f5ed083e98b27e477e4b7af284a589189ee8a2f3c441ee0952d990b84`, and new screen metadata round-trips
and changes the hash when modified.

## Checks

Focused command:

```sh
./gradlew :BukkitPlugin:test --tests '*ScreenPartDefinitionTest' --tests '*SavedCarModelTreeTest' --tests '*ModelTreeBuilderTest' --tests '*ModelConfigStrictValidationTest' --tests '*PartRegistry*Test' :BukkitPluginDev:compileKotlin -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven --offline --max-workers=2 --console=plain
```

Result: **29 tests pass**, BukkitPlugin and BukkitPluginDev compile successfully. Log:
`/tmp/jr-screen-focused-final.log`. Coverage includes required/type/unknown-field validation, invalid
and nonfinite dimensions (including real YAML `.nan`/`.inf`), real file imports, nested inline nodes,
nonzero transforms/namespace, metadata copies, snapshot roundtrip/hash, and transform-only model behavior
with children and animation. The first model-tree assertion was corrected before the final passing run.

Full gate:

```sh
./gradlew --offline -I /tmp/simpledisplays-phase1-test-memory.gradle -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :BukkitPlugin:test :BukkitPlugin:shadowJar :BukkitPluginDev:compileKotlin --continue --max-workers=2 --console=plain
```

The temporary Test configuration uses heap 2 GiB, one fork, forkEvery=10 and ActiveProcessorCount=2;
repository test settings are unchanged. Result: **747 tests, 1 failure, 0 errors, 1 skipped**.
Only `ProgramExpressionsTest.one string parameter composes into destination station depot and display fields`
at line 130 fails, expected `[central@1]`, actual `[]`. This is the same expression-expansion failure
recorded during stage 1, outside this slice's modified files. No unrelated fix or fresh baseline replay
was performed. Full-suite success is not claimed. Log: `/tmp/jr-screen-full.log`; XML reports are in
this worktree's `BukkitPlugin/build/test-results/test`. The isolated test inventory differs from the prior
main-checkout run; these counts apply to this worktree only.

`--continue` allowed packaging to finish despite the unrelated test failure. Shadow JAR contains
`PartScreenConfig.class` and no `simpledisplays/api/` classes. Host SHA-256:
`387a80048b0c79bff44d741bf80d849fc427fb48aa05ad6bf82de11d5a78d8e2`.

JSON Schema verification used jsonschema 4.26.0 / Draft202012Validator and PyYAML 6.0.3 installed only
under `/tmp/jr-screen-schema-validator`. All 6 official schemas pass metaschema checks; standalone,
inline, import and nested examples validate; 14 invalid declarations are rejected. No validator was
added to project dependencies. No claim is made that JSON Schema resolves imported model files;
those are exercised by the real Kotlin loader tests.

Static review found no blocker; all PartType consumers were inspected and the ordinary public model
compiler remains an independent restricted API. `git diff --check` passes. No server/game session or
visual acceptance test was run, because this slice has no screen renderer.

## Remaining work

Review this focused slice before proceeding. Style resources/default binding, fixed template assets,
actual native/EntityLib text output, and interior-screen distance config/player commands remain pending.
