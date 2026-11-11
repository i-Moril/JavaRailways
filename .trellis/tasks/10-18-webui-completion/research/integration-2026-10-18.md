# Integration into dev/signal-system — 2026-10-18

The user requested integration of the completed coordinate and UI changes before work on
isolated simulation and epoch events. Coordinate implementation `6548a850` and its
migration follow-ups through `401f239c` were already on `dev/signal-system`.

Merged `codex/webui-completion` at `12589555` (work commits `7366d1b4` and
`4bc43a63`) into that baseline. Resolved conflicts only in `App.tsx` and
`auth.test.ts`: retain the current World issues page alongside Timetable, Rail map,
Lines, Duties and Validation; keep the shared DutyEditor owner and the map's
world-keyed remount. Translation changes merged automatically and type-check passed.

Validation on the combined source:

- Frontend: 21 test files, 108 tests passed.
- Production build: TypeScript and Vite passed; existing bundle-size warning remains.
- Staged diff whitespace check passed before the merge commit.
- All 78 pre-existing modified/untracked file contents matched their pre-merge
  SHA-256 inventory. Temporary recovery copy: `/tmp/jr-pre-webui-merge-ug92u_gw`.
  Unrelated changes and the dirty docs-site submodule were not staged.

No backend behavior was changed by this merge. No new browser/live-server test,
deployment, migration or push was performed in this integration step. Historical
browser and coordinate validation remain the evidence of their original batches.
