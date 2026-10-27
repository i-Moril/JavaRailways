# Feedback round 2 — program library management

User approved selecting feedback 4/5 and related 7/8 on 2026-10-27, including implementation, commit and deployment. Existing task remains in_progress and unarchived.

## Boundary
Current library uses large ID/name forms and archival rather than actual deletion. Change to left file list, new/delete/rename and compact editor, numeric default display names and hidden stable IDs. Changes live in StationProgramLibrary, API, scoped CSS/i18n, VisualProgramRepository and authenticated routes/tests.

Real deletion removes the library file with revision checking. Existing editor-document boundPrograms and published runtime embedded snapshots remain unchanged and executable. New callers cannot pick a deleted library record. Rename changes display name only, not program identity; it must not save unrelated unsaved editor content.

Persisted library record shape remains formatVersion 1 including existing archived metadata; no migration or compatibility path is introduced. Already archived entries may be deleted. Internal fresh IDs must not be recycled after deletion, avoiding aliasing an old bound snapshot.

Do not change duty action model, initial positioning, formation events, train information expressions, graph event UX or existing snapshots this round.

## Ownership
- library_backend: repository/delete route and Java HTTP tests.
- library_frontend: file list/editor flow, API/i18n/scoped CSS/frontend tests.
- root: integration, documentation, tests/build/browser acceptance, commit and safe deployment.

## Acceptance
Verify create with numeric name, list selection, rename, valid and incomplete block draft handling, real delete/missing/stale revision/auth/world isolation, existing embedded snapshots preserved, world switch/late response isolation, HTTP reload persistence and visual spacing. Run full frontend tests/build and WebUI Java tests, then build both shadow JARs. Before deployment normal world save/stop, recovery snapshot, narrow replacement and live resource/health verification.

## Verification
- Full frontend: 234 tests / 37 files pass; TypeScript and Vite build pass.
- WebUI Java: 37 tests pass, including authenticated deletion, missing/stale/world isolation and embedded snapshots after deletion.
- Both shadow JARs built from current dist and sources. Online dependency refresh stalled; cancelled that local build and used existing dependency cache with --offline; no dependency/source changes.
- Real packaged Java WebUI on insecure HTTP with isolated QA fixtures: new numeric-name creation, name-only rename preserving detached invalid blocks and save gate, explicit discard clearing rawdraft, actual DELETE returning404 afterward, content save (WAIT_DURATION1→5), rename and page reload persistence, stale revision409 and explicit refresh/discard loading current remote revision all verified.
- Screenshot checked file/editor spacing. No captured errors during create/invalid-draft/rename/delete sequence. No live game behavior claim; real binding snapshot independence verified at HTTP repository boundary.
- Reviewer report: feedback-round-2-check.md. Existing task remains in_progress pending further user testing.
