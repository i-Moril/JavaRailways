# Independent SimpleDisplays JS validation — 2026-11-09

## Scope and provenance

- User approved the independent library JS slice first; JavaRailways rendering, script/package resource loading, operational train snapshots, visibility controls and the actual Bukkit PlaceholderAPI adapter remain pending.
- Source: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, branch `codex/js-rendering`, base `21f7686eb8f0d181a89bb7cb21f92f9bff0ef717`.
- Implementation, tests and library documentation committed locally as `db9c65e` (`feat: add viewer-aware JavaScript drawing`). No push/deployment to an existing server.
- JavaRailways production code remains at screen-definition commit `8cc6bf35`; this slice changes task documentation only. Neither original main checkout was used for implementation/build output.

## Delivered contract

`DisplayService.compileJavaScript(source, width, height, icons)` returns a closeable `JavaScriptDisplayTemplate`. `draw(DisplayContext)` returns the existing renderer-neutral `List<TextDraw>`. The API-only JAR exposes no Graal or implementation classes. XML compile/draw remains available.

Scripts define synchronous `render(ctx, draw)` and use `draw.text` / `draw.icon`. Context includes generic detached read-only structured `data`, explicit `viewerId` / `viewerData`, dimensions, caller-provided elapsed ticks and an optional injected `ctx.placeholder` resolver. JavaRailways data travels directly through this API. The injected callback is real, but this slice supplies no Bukkit PlaceholderAPI integration.

Engine/source reuse is paired with fresh invocation contexts to prevent cross-viewer global state. Invalid commands and resolver errors reject the complete frame, including errors caught by JS. Inputs/outputs are bounded; ordinary Java access, IO, process/thread/environment/native access are disabled. Template/service/plugin shutdown closes script resources.

Cold initialization uses a 3,000 ms JS budget; draw uses 250 ms including top-level evaluation. Input copying/context setup are outside the scheduled budget. Four simultaneous evaluations are allowed; overflow fails. This is not heap/process isolation, a high-frequency performance acceptance claim, or a guarantee that arbitrary blocked host callbacks can be interrupted. Resolver calls run on the draw caller's thread and must obey provider/thread ownership constraints. See the library README for exact data/command limits and lifecycle ownership.

## Automated validation

Cached Gradle 9.0.0 with Java 21 toolchain ran `test apiJar jar --offline` in the independent source worktree. Final JUnit reports show **13 tests, 0 failures, 0 errors, 0 skipped** (12 runtime tests and one cold-start test).

Coverage includes actual GraalJS execution, structured arrays and ordinary JS array methods, different viewers, invocation-local globals, icons/time, output validation, unsupported/mutable inputs, Java/IO denial, top-level/render infinite-loop cancellation, reuse after failure, close lifecycle, async/generator rejection and XML regression. Independent check-agent review passed after corrections to derived geometry validation, render return validation, resource cleanup and font validation.

`git diff --check` and staged diff checks passed. API consumer compilation used the API-only JAR, Paper/Adventure and Kotlin with Java `--release 21`, without implementation/Graal classes on its compile classpath.

## Actual Paper service probe

Isolated temporary server: `/tmp/simpledisplays-js-paper-smoke`; probe source/harness: `/tmp/simpledisplays-js-probe`. Java 25 ran cached Paper 1.21.11 build 132 (`c5eb079`), with only SimpleDisplays and the API consumer probe installed. The server bound to loopback with a fresh temporary world; no existing world/server was modified. These temporary paths are local evidence, not permanent repository fixtures.

Paper's library loader successfully resolved the actual Graal JS language/Truffle JAR dependencies declared in `plugin.yml`. The probe retrieved the registered public `DisplayService`, compiled JS, rendered two viewers, used a caller-injected test resolver and checked the existing XML API:

```text
JS_PROBE_OK alice:Central:1:Alice / bob:Park:1:Bob / XML_OK
ready= True probe_ok= True exit= 0
```

Each viewer sees its own stop/name; both counters are 1 because globals are fresh. The resolver is a test callback, not an installed PAPI expansion. The server reached `Done`, then shut down normally with exit 0. Console evidence: `/tmp/simpledisplays-js-paper-smoke/console.log`.

Final tested artifacts (the plugin JAR copied to the smoke server has the same hash):

- `build/libs/SimpleDisplays-0.1.0-SNAPSHOT.jar`: SHA-256 `3fc54ed90b02d528e5775a0cad8556a3a8349bfde2c936e3956a8d55f1dd517a`.
- `build/libs/simpledisplays-api-0.1.0-SNAPSHOT.jar`: SHA-256 `cc831fd69e46ef3b545adcc37e6b0c7c6baa5eaa8ff55cefb0ee890affa082d6`.

This verifies standalone Paper loading and actual library output. No Folia run, in-game visual acceptance, train-mounted rendering, actual PAPI adapter or rendering performance claim is included.
