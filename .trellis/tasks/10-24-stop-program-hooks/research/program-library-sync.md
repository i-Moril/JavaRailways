# Program-library synchronization after deletion

User reported after deployment that program and duty pages no longer correspond after deleting a program. Continue existing in-progress task; no new task, schema change, automatic commit/deployment or archive.

## Confirmed boundary

StationProgramLibrary mutates its own server-resource list. DutyEditor remains mounted across App pages and independently fetches library resources keyed only by world/page; non-studio App views map to graph. Graph→programs→graph therefore need not refetch after deletion. Existing bound snapshots are authoritative document content and intentionally survive library deletion, while available-library options must reflect current server state.

Smallest change: propagate successful library mutations to the retained editor resource cache, discard stale list responses, and present missing-source bindings explicitly as retained snapshots. Do not silently alter saved calls, arguments, bindings, generated source, undo history or server persistence. Numeric display names are separate from stable program identity; name reuse is not identity reuse.

Implement owner: program_delete_sync sub-agent, frontend App/library/editor resource refresh and focused tests. Root owns documentation, independent check and final gates. Changes must cover create/save/rename/delete, failed requests, world/owner isolation, existing bound calls and stale responses. Browser validation should distinguish real interaction evidence from pure/static tests. No live server data mutation for QA.

## Read-only live confirmation (2026-10-28)

Dev-server station-program library contains0 JSON records after the user deletion; all6 server-saved editor documents contain0 boundPrograms. The UI can still recover an unsaved local authoring document with bound snapshots; do not infer from a page refresh that the entry exists in the shared library. Java VisualProgramRepository.delete checks revision then DraftRepository.delete calls Files.deleteIfExists. No archive runtime symbols remain in WebUI, SimpleATO, Basic, BukkitPlugin or SimpleATOBukkit production sources; legacy archive wording was confirmed in the prior frontend i18n strings and is removed in this fix. No live data changed during diagnosis.

New task/event selectors must use current library sources. A deleted-source snapshot can remain visible only as the current existing call selection and is disabled as a new choice. It must not retain a dead edit-source link. Existing document snapshots and source generation remain intact, including local-recovery content.

## Implementation and verification

Implemented App resource invalidation after all successful library writes, including completion after navigating away. Retained editor reloads via useStationProgramLibrary; invalidated cache clears, obsolete response tokens cannot reintroduce deleted entries, errors remain visible. New duty/event choices use live source entries; existing deleted-source selection is disabled and labelled, dead edit buttons/double-click source links removed. Existing snapshot/arguments/history are preserved. Updated owner frontend spec.

Root final verification: frontend301 tests across49 files passed; TypeScript/Vite build passed; git diff --check passed. Added mounted component tests for all4 mutations, failed removal, late deletion after unmount; resource-hook tests for invalidation, stale responses/world isolation and refresh failure; selection assertions for deleted snapshots. Independent trellis-check review found no blocker. These are unit/component/source-review results. Attempted local browser setup did not complete a usable interaction run; do not claim actual-browser or game acceptance from these checks. No commit/deploy/archive this round; live server still runs f8b80012.

## Commit and deployment authorized

User requested commit+deploy and explicitly keep task open. Committed5d588574 and deployed on2026-10-28; full backup/startup/publicresource verification in deployment-2026-10-28.md follow-up section. Earlier no-commit/no-deploy entries record historical stages only.
