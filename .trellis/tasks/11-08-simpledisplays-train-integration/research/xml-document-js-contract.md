# XML embedded JavaScript / document revision — approved 2026-11-09

The user rejected the standalone JS template/draw-command model, approved XML-embedded `<script>` and a small document-style API, and explicitly requested continuing directly from pre-JS commit `21f7686`, without a revert commit. The isolated SimpleDisplays branch was clean and reset to that commit. `db9c65e` is superseded and must not be presented as the current implementation.

## Authorized scope

- Independent SimpleDisplays worktree only: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, branch `codex/js-rendering`. Do not touch either original main checkout.
- Preserve `DisplayService.compile(xml, icons)` and existing scalar `DisplayTemplate.draw(values,ticks)`. Add generic per-viewer `DisplayContext` through the same XML template interface; no `compileJavaScript`, separate JS template, or exposed `draw.text/icon` API.
- XML owns canvas, layout and static content. Direct child inline `<script>` elements contain JS (CDATA supports XML special characters). No external script loading. Scripts are excluded from visual layout and DOM mutation. Existing div/span/icon layout, binding, icon rendering and logical coordinates remain the output owner.
- Each scripted draw gets a fresh document copied from its compiled XML and an isolated JS Context. Execute inline scripts in source order after exposing `document` and `ctx`; ordinary top-level document edits are supported. If the combined script defines `render`, invoke it once with ctx after top-level execution; it must be synchronous and return undefined/null. No persistent cross-viewer/frame globals. Return one complete validated frame or fail.
- Small document API: `document.getElementById`, `document.createElement`, `document.documentElement`; element `textContent`, `id`, `tagName`, `getAttribute`, `setAttribute`, `appendChild`, `remove`, `replaceChildren`. Creation supports visual div/span/icon only. Root dimensions are fixed. Reject invalid foreign nodes, cycles, duplicate IDs, illegal attributes/tree operations, nonfinite/invalid layouts and unknown icons. No browser DOM/CSS emulation, innerHTML, events, network or timers.
- ctx retains structured detached read-only data, viewerId/viewerData, elapsedTicks, width/height and optional synchronous caller-injected placeholder resolver. No PAPI plugin adapter or JR integration. Host thread ownership remains caller responsibility.
- Bounded source/document/input/output and cancellable JS deadlines; restricted Graal in-process execution, not heap/process sandbox. Compilation parses/validates scripts without running data-dependent top-level code against invented inputs. Shared engine/source acceptable; fresh execution globals/doc per draw. JS-free XML should retain its cheap compiled-node path.
- Close scripted templates/service/plugin resources. Shared registry service belongs to plugin, templates to consumer. Runtime dependencies via actual JAR library coordinates in plugin.yml; API-only artifact must not expose implementation/Graal types.

## Acceptance

Actual Graal tests for existing XML semantics; embedded top-level and render-style DOM changes; structured lists; viewer/frame isolation; ID/text/attribute/structure edits; missing lookup; invalid operations/cycles/foreign nodes/duplicate IDs; restricted host access; source and draw infinite-loop deadlines; output limits and complete-frame failure; template/service close. API-only consumer compilation and standalone Paper service loading probe. Folia/client/performance acceptance remains unverified unless actually run.

This revision replaces earlier standalone-JS sections of task notes. Package template schema/loading is deferred until this library contract is correct.
