# WebUI rewrite preparation

## Objective

Recompose the current React frontend around the approved Ant Design prototype without replacing working timetable, line, map, validation, simulation, recovery, or API contracts. The prototype is stored in [`prototypes/timetable-studio`](./prototypes/timetable-studio/README.md).

The rewrite is a presentation migration. `TimetableAuthoringDocumentV1`, generated JavaScript, compiled timetables, rail-map payloads, and backend validation remain separate contracts with their existing owners.

## Accepted product structure

The compact top strip owns world, authentication state, save state, theme, and locale. The ribbon is global navigation plus contextual commands. Its workspaces are:

1. **Timetable** — one selected line, that line's runs, a pannable running-diagram canvas, and an editable station-call inspector.
2. **Rail map** — the live infrastructure/train map.
3. **Lines** — line and station-sequence management.
4. **Duties** — vehicle/run chains and duty functions.
5. **Templates** — reserved page; not a production capability yet.
6. **Validation** — authoring, compile/world, and simulation results presented together without weakening backend authority.

Timetable invariants from the prototype review:

- Never mix runs or station rows from different diagram lines in one timetable workspace.
- A run's line/direction is chosen when the run is created. Moving it to another line means delete/recreate or a future explicit conversion transaction, not an inline field mutation.
- Tick values are authoritative; translated day/time is a display mode.
- Call status, berth, arrival, and departure are editable in the calls table.
- Station actions open as a dedicated inspector subpage so richer authoring can be added later.
- Both timetable side panels are collapsible; the diagram is a pannable/zoomable canvas rather than a stretched chart.

## Current capability map

### Reuse and recompose

| Prototype area | Existing production owner | Rewrite treatment |
| --- | --- | --- |
| Authentication, world selection, guest map-only policy | `App.tsx`, `api.ts` | Preserve behavior; move controls into compact header. |
| Locale and light/dark theme | `i18n.tsx`, `theme.tsx` | Bridge into Ant Design `ConfigProvider`; keep existing storage and SSR guards. |
| Rail map data, live trains, pan/zoom | `RailMap.tsx`, `railMap.ts` | Restyle and compose as the Rail Map workspace; keep canvas math and EventSource ownership. |
| Line CRUD and ordered stations | `LinesPage` in `App.tsx`, line API/types | Extract into a page component and restyle. |
| Run/duty authoring document, recovery, undo/redo | `DutyEditor.tsx`, `authoring.ts`, `authoringRecovery.ts`, `documentHistory.ts` | Preserve as the authoritative editor state; split rendering into workspace components. |
| Running diagram layout and interaction | `Graph.tsx`, `graph.ts` | Keep pure projection/viewport functions; host them in the canvas-style center panel. |
| Run route and station calls | `RunRouteEditor.tsx`, `authoring.ts` | Present calls as a line-scoped editable table and a station-detail subpage. |
| Validation, compile, publish, simulation | `DutyEditor.tsx`, `App.tsx`, `api.ts` | Recompose results into Validation and Issues views; server validation remains authoritative. |

### Deferred capability gaps

These are intentionally not part of the shell rewrite:

- **Persistent templates and apply-template transactions.** This needs a domain model, ownership rules, API/persistence boundaries, validation, and tests.
- **Brick-style station-call programming.** The current authoring call model does not own arbitrary per-call function graphs. This needs a structured model and deterministic compilation before a visual editor.
- **Editable rail-map topology/layers.** The current map is a live read-only projection. Editing requires explicit infrastructure APIs and world-side validation.
- **Generalized bulk operations.** Copy/shift behavior exists, but arbitrary multi-selection transforms need atomic document transactions, previews, undo semantics, and validation.

Do not introduce placeholder persistence or encode these features only in component state.

## Small decision gates

During implementation, stop and ask before adding a missing but bounded feature. Likely gates include:

- whether panel widths/collapse state should persist locally;
- whether Validation is a global page, a timetable-local tab, or both;
- whether the ribbon supports keyboard shortcuts in the first migration;
- whether line selection should restore the last selected run per line;
- whether tick/day-time display preference persists per user or only per session.

If a newly discovered gap requires new backend endpoints, a new persistent schema, a new authoring model, or cross-module runtime behavior, defer it and record the integration point instead of expanding the rewrite.

## Migration sequence

1. **Foundation:** add pinned Ant Design dependencies; bridge existing theme and locale providers; add the compact shell and authenticated navigation contract tests.
2. **Page extraction:** extract current Lines, Scripts, and Simulation rendering from `App.tsx` without behavior changes. Keep guest navigation map-only.
3. **Timetable composition:** split `DutyEditor` into shell, run list, diagram viewport, calls inspector, duty page, and output/validation components while retaining one reducer/history/recovery owner.
4. **Line scoping:** make the selected diagram line mandatory in the Timetable workspace and derive runs/calls/graph strictly from it. Remove the current `All lines` authoring view.
5. **Calls table:** route existing call updates through pure authoring transactions, then add the station-detail subpage. Do not add block programming in this phase.
6. **Rail map and management pages:** restyle existing live map and line/duty pages. Add reserved Templates UI only after its domain contract is approved.
7. **Validation workspace:** aggregate existing authoring, compile/world, and simulation results with links back to their owning item.
8. **Retirement:** remove superseded CSS and legacy page composition only after equivalent tests and browser checks pass.

Each step must keep `npm test` and `npm run build` passing. API or wire changes additionally require `:WebUI:test` and their Java contract tests.

## First implementation slice

The safest first slice is **Foundation + compact shell only**. It changes no authoring document, API payload, recovery shape, or backend route. Acceptance criteria:

- Ant Design is pinned in the production package manifest.
- Existing `zh-CN`/`en-US` and light/dark state drive Ant Design tokens.
- Guest users still see only Rail Map.
- Authenticated navigation exposes the existing production pages under the new ribbon.
- The current page bodies remain mounted with their existing behavior.
- Existing frontend tests and build pass, with new navigation/theme/locale coverage.

Status on 2026-09-18: implemented. Ant Design is pinned, the compact shell and ribbon navigation are active, the existing providers drive Ant Design locale/theme configuration, and legacy page bodies remain unchanged. The next migration slice is page extraction, followed by timetable composition.
