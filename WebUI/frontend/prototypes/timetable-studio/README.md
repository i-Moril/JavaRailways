# Timetable Studio UI reference

This directory preserves the approved September 2026 WebUI redesign prototype. It is a standalone visual and interaction reference, not a second production frontend.

## Purpose

- Preserve the agreed application shell, ribbon navigation, timetable workspace, rail-map workspace, and management-page layouts.
- Provide a runnable reference while production components are migrated incrementally.
- Keep dummy data and speculative interactions isolated from real API and persistence contracts.

The production source of truth remains `WebUI/frontend/src/`. Do not import prototype models into production code or present a prototype-only control as a working capability.

## Run

```bash
npm install
npm run dev
```

The prototype accepts an optional `page` query parameter:

- `?page=timetable`
- `?page=map`
- `?page=lines`
- `?page=duties`
- `?page=templates`
- `?page=validate`

## Reference boundaries

Implemented production capabilities represented by the prototype include line-scoped timetables, run calls, duties, validation, simulation, rail-map pan/zoom, theme, and locale. The following remain visual placeholders until separate product decisions and domain work exist:

- persistent timetable/action templates;
- brick-style station-call programming;
- editable rail-map nodes, links, and layers;
- generalized bulk selection and transformation commands.

See [`../../WEBUI_REWRITE.md`](../../WEBUI_REWRITE.md) for the migration boundary and capability map.
