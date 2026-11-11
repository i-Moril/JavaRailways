# Waypoint input/depot-origin batch — 2026-10-30

Same in-progress task/worktree, baseline5e674d12. User asks depot stabling starts and editable filtered waypoint controls. See frontend inventory and depot-simulation-start research for source ownership; root owns task/spec/check orchestration, backend worker only real depot regressions, frontend worker shared controls and wiring. Gradle serial under root.

Smallest gap: direct and local point selectors are inconsistent closed lists or raw JSON/text; function start discovery currently searches displayName only. Use shared controlled ID input with domain-specific choices; do not combine station/depot aggregate IDs, local berth IDs and full waypoint IDs. Existing metadata/HTTP/authoring models remain unchanged. Generic strings/ID_LIST retain their actual meaning. A typed unknown draft is not a runtime-valid reference.

Backend source confirms no role exclusion. Read-only public map and saved infrastructure reveal kasaidpt@1..6, each own directionBA, canonical displayName and usableLength120, with no rail direction override. Basic saved wayPoints is empty because destination-owned mirrors are rebuilt by SimpleATO restore. Real current-format restore tests must prove this rather than adding a speculative backend workaround. Root authenticated metadata probe was blocked by automatic approval because it would read a process-held bearer token; no token was read and that path was abandoned. Use existing saved values and real restore/execution regressions; live eligible response content remains unverified.

Changes do not alter server data, persistence versions, Core placement proof or ATO reversal sequence. No automatic commit/deploy/archive. Final evidence must distinguish actual browser input interaction, backend tests and live/game acceptance.

Final implementation, independent-review closure and validation evidence: [waypoint-input-validation.md](waypoint-input-validation.md). All current-batch gates passed; parent remains in_progress, no current-batch commit/deployment/archive.
