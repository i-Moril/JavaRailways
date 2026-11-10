# Cabin template implementation — 2026-11-10

User approved completing metadata conventions, historical call inputs, real door-side inputs and an actual XML/document-JS template based on the reviewed mock. Existing work remains uncommitted. Compile production/test sources only; user deferred test suites and server/game acceptance until all work is complete. No commit/push/deploy/live package replacement. Work in ddf5 only; preserve unrelated changes.

## Boundaries

- SimpleATO owns proven current berth/effective door-side facts. Capture detached facts on simulation owner; never infer open-door state or a future platform side from the final stop or station metadata. Unknown facts remain null. Do not modify operation decisions or scheduling.
- User revised the input direction: expose the complete ordered operational-trip call sequence plus exact occurrence cursor and at-station/in-transit phase, rather than adding completedStops alongside remainingStops. Preserve repeated visits and STOP/PASS. Runtime reached currently includes arrival, so completed cannot mean physically passed. The ordered sequence/cursor/phase interface is implemented in the subsequent stop-sequence slice; see stop-sequence-contract.md and stop-sequence-validation.md. The superseded two-list proposal was not implemented.
- ATO door side uses actual confirmed stop/program side; preserve correct per-car installed orientation. No side guessed from screen transform or train index. Decorations adds optional installation door-side (left/right relative to installed car/model), default unspecified, to decide this-side versus opposite-side from exact car facts. Unspecified suppresses directional text. No claims about actual animated door aperture; display is confirmed stop's effective opening side.
- Metadata remains arbitrary bounded JSON. Template convention: line.metadata.name.{zh,en}, themeColor and badge; station.metadata.name.{zh,en}, transfers:[lineId]; run.metadata.class.{zh,en}. Destination uses explicitly authored operational.values.destination localized object or existing exact string; never infer from last remaining known stop. Missing translation uses explicit documented fallback, never executes metadata.
- Actual XML/JS and resource-pack glyphs implement fixed-size bilingual layout, left line/class/destination/next/door/transfer; right at most two completed calls + future calls including PASS, gray history, hollow PASS/no center, blink next STOP/TERMINAL center, stubs for omitted calls, vertical interchange logos above rail, no right heading/count/next label, no45degree text. Backgrounds/geometry must use supported SDL drawing contract/resources, not browser-only CSS.
- Decorations owns config/schema and template adaptation; host retains generic part API. Independent SDL is read-only unless a minimal required capability is found and separately reported. Add examples and auditable package candidates; do not deploy to test-room server or replace live package archives.

## Expected edits

SimpleATO display context/capture + focused deferred tests for door proof invalidation/orientation; host projection/snapshot + deferred tests for history metadata/PASS/repeated occurrences and per-car facts; Decorations display config/schema/bridge, deferred tests, example XML and pack glyph assets; docs/task/spec describe actual contracts and limits.

No persistence version bump: optional configuration field defaults absent; display snapshot fields are transient projection facts. Metadata conventions do not alter schema. Rendering resources retain their existing pack boundary.


## Display timing decision (user confirmed)

For now, displays follow actual currentRun during its entered execution scope. No separate displayRun, proactive next-run binding, guessed switch on arrival, or new switching action. Outside actual scope, preserve current unknown/null behavior. Discussion of vehicle-information switching timing is a future TODO in the parent PRD, covering through-running, turnback and next departure; not required for this implementation batch.

## Shared timed language rotation (user confirmed)

Interior and exterior templates alternate Chinese/English on a fixed interval, never choose by player locale/viewerData. User specified15 seconds per page (300source ticks at20ticks/s), adjustable inside the template. Compute page from ctx.elapsedTicks; no browser timer or retained JS global, new per-screen scheduler, or per-viewer requirement. Both languages share identical logical and physical dimensions. Existing source-clock behavior applies while paused/hidden/resumed. Exterior layout is only service-class badge with distinct background + destination, with no route map/next-station/door block. These are requirements for the forthcoming actual templates, not a claim that they are implemented.


Exterior browser preview: thread visualization exterior-destination.html shows a colored service-class badge plus destination, alternating Chinese/English every15 seconds. Both pages share the same physical preview bounds; a manual preview button is outside the depicted screen. This is a layout demo, not a claim of deployed SimpleDisplays XML or resource-pack acceptance. Runtime templates still use the existing source tick clock; the browser preview uses a monotonic wall clock to demonstrate the interval.

Exterior preview checks: actual elapsed-time check confirmed Chinese remains before15 seconds and switches to English after15 seconds; page dimensions equal. Follow-up layout checks passed both languages and short/long destination fixtures at780/360/320 viewport widths with no page errors or content crossing screen bounds. Font ink may exceed its own CSS line box but stays inside the screen; checks use actual screen bounds. Browser mock checks only, no repository suites or server/client acceptance.

## Exterior alignment, absent class and readability refinement

User requested destination centered in its right-hand area. If no service class is configured, omit the badge and its reserved column entirely, center destination over the whole screen. Enlarge typography: preview short destination targets66px at desktop; class56px (English30px). Long destinations use available area with wrapping and bounded fit, rather than forcing all labels to the smaller long-name size. Physical screen bounds remain fixed across languages and absent/present class.15-second language rotation unchanged. Preview now includes an explicit unset-class option. Exact Minecraft legibility still needs client acceptance; CSS font sizes do not establish game readability.
