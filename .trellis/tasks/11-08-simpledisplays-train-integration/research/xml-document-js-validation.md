# XML/document JavaScript replacement — 2026-11-09

## Source and approved boundary

The user requested abandoning the standalone JS template implementation and continuing directly from its parent commit, without creating a revert commit. The clean isolated branch `codex/js-rendering` at `/home/user/.codex/worktrees/ddf5/SimpleDisplays` was reset from abandoned `db9c65e` directly to `21f7686eb8f0d181a89bb7cb21f92f9bff0ef717`. The replacement is committed locally as `410af2e` (`feat: support embedded XML scripts with document API`), whose direct parent is `21f7686`. No revert commit was created; `db9c65e` is not an ancestor of the replacement branch. The original SimpleDisplays checkout remains clean at `21f7686`; neither original main checkout is an implementation/build target.

The new public path is `DisplayService.compile(xml, icons)` → `DisplayTemplate.draw(DisplayContext)` → `List<TextDraw>`. Old scalar `draw(Map<String,String>, elapsedTicks)` remains. There is no standalone JS template, `compileJavaScript` method or exposed draw-command API. XML owns dimensions/layout and direct inline script children. Script compilation parses without executing data-dependent top-level code; each draw exposes `document` and `ctx`, executes scripts in order and calls optional synchronous `render(ctx)` once.

The small document API implements lookup/creation, literal text, allowed attributes and append/remove/replace operations. Each invocation owns a separate actual W3C Document and a fresh Graal Context. Generic data/viewer data are detached/read-only; string entries also feed legacy XML bindings captured before guest execution. IDs, cycles, foreign nodes, root dimensions, allocations, depth, output and geometry are checked. Invalid host operations cannot be hidden with JS catch and publish a partial frame. Graal read-only array-slot deletion can be rejected by the engine before a host callback; the regression confirms the input remains unchanged rather than requiring an unobserved callback failure.

JS-free XML retains its compiled-node path. Graal facilities are created lazily, synchronized with service shutdown. Active invocation cancellation, template/service close and plugin shutdown logging are covered. README in SimpleDisplays owns the exact API, restrictions, thread contract and numeric limits.

## Automated and independent review

Final cached Gradle 9.0.0 run: `--offline test apiJar jar`, Java 21 toolchain. **15 tests, 0 failures, 0 errors, 0 skipped.** Final log: `/tmp/simpledisplays-dom-review-final.log`.

Tests use actual GraalJS and cover:

- Legacy XML coordinates, nested-span flattened text, scalar bindings and timed icon frames.
- Parse-only compile, multiple script ordering and top-level document edits.
- Structured stop lists, optional render, viewer data, repeated frames and parallel viewers.
- IDs, attributes, text, moving/removing/replacing elements and missing lookup.
- Illegal mutations, duplicate IDs, cycles, foreign objects, invalid styles/fonts/icons and geometry overflow.
- Detached/read-only input and bindings even if the injected resolver mutates caller data; denied Java/file access.
- Async/generator/non-void render rejection, top-level/render infinite-loop deadlines and subsequent reuse.
- Document/source/input/output limits; raw XML depth rejection before recursive traversal.
- Idempotent close, calls after scripted-template/service close and deterministic active template/service cancellation using a latch.

Independent final full-source check passed after the raw traversal guard, independent ownerDocument, snapshot binding, close race, deletion handling and shutdown throwable logging fixes. `git diff --check` passed. API JAR inventory contains only API classes/Kotlin metadata, with no implementation/Graal classes and no abandoned standalone JS type.

A Java consumer compiled with `--release 21` against only the API JAR plus Paper/Adventure/Kotlin and their compile dependencies; no implementation/Graal types were on its compile classpath. Harness: `/tmp/simpledisplays-xml-dom-probe`, including source and `compile-classpath.txt`.

Final tested artifacts:

- `build/libs/SimpleDisplays-0.1.0-SNAPSHOT.jar`: SHA-256 `eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391`.
- `build/libs/simpledisplays-api-0.1.0-SNAPSHOT.jar`: SHA-256 `72c37b2fd184814256b5f4bd0cf2471b44d29bd9d185debc01fbdfaae75916d6`.

## Limits and deferred work

Restricted in-process JS is not heap/process isolation. Context construction and host input copying are outside the scheduled JS budget; arbitrary blocked host callbacks can delay cancellation/shutdown. Per-call document/context allocation is documented, not claimed to satisfy high-frequency rendering performance requirements.

The placeholder resolver is an injected host callback, not an actual Bukkit PlaceholderAPI adapter. The caller owns provider/Folia thread requirements, frame scheduling and visual-node lifecycle. Existing standalone demo remains static XML. JavaRailways resource loading, mounting/render backends, train data, PAPI adaptation, view distances and player commands remain deferred. No actual Folia run, in-game visual acceptance or high-frequency performance acceptance is claimed. No existing server/world deployment occurred.

## Actual standalone Paper probe

A fresh temporary loopback server at `/tmp/simpledisplays-xml-dom-paper-smoke` ran cached Paper 1.21.11 build132 (`c5eb079`) on Java25. Only SimpleDisplays and the API consumer probe were installed, with a fresh temporary world. Paper loaded the actual JS language and Truffle runtime library JARs. The API-only consumer obtained the registry service, compiled XML with top-level and render-function scripts, created/attached a span, rendered two viewers and repeated the first viewer, then checked the unchanged XML scalar API:

```text
XML_DOM_PROBE_OK alice|Central:1:Alice / bob|Park:1:Bob / repeat=alice|Central:1:Alice / XML_OK
ready= True probe_ok= True exit= 0
```

The counter remains1 and output node count remains stable on repeat. Viewer-specific stops/names remain separate. The resolver is a caller-injected test callback, not PAPI. Server reached `Done`, then shut down normally. The copied plugin hash exactly matches final artifact `eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391`. Console: `/tmp/simpledisplays-xml-dom-paper-smoke/console.log`. Temporary harness/logs are local evidence, not permanent fixtures or client/Folia acceptance.
