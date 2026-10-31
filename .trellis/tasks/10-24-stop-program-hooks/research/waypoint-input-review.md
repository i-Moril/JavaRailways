# Research: editable waypoint inputs and depot simulation origins review

- Query: Independently review this batch against user requirements: depot stabling points are valid directed simulation origins, and waypoint ID controls allow manual entry with filtered suggestions. Focus on commit/history, cancel/owner boundaries, authoring unknown values versus strict simulation eligibility, and immediate stale-result invalidation.
- Scope: internal static review; parent-supplied baseline `5e674d12` in `/tmp/javarailways-blockly-deploy`. Research role performs no Git operation, Gradle invocation, implementation edit, commit, deployment or task lifecycle change.
- Date: 2026-10-30

## Findings

### Backend depot regressions: reviewed, no confirmed defect

`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt:205-329` adds four JUnit instances: a two-value reversal parameterization and two ordinary tests.

- The parameterized `depot stabling points with own or rail direction are real standalone simulation starts` uses actual `OperationalDestinationService.register` and real depot roles, checks the Basic waypoint mirror and depot point identity, verifies own BA overriding rail AB and an undirected point using rail AB, then captures/restores/runs the actual isolated factory/dispatcher. It asserts Completed, actual 7-tick task timing, actual train reversal state, unchanged occupied intervals and unchanged source destination/depot ledger/trains. It does not mock a candidate metadata list.
- `saved directed depot destinations restore Basic mirrors before standalone simulation capture` decodes a manually authored current-format Basic snapshot with empty waypoints plus six current-format SimpleATO depot destinations. It restores before capture, checks all six canonical waypoint mirrors and point-owned BA direction, then checks actual isolated restore/standalone completion and 7-tick measurement. This addresses the actual persistence ownership where Basic intentionally does not persist destination-owned mirrors.
- `undirected or removed depot stabling points cannot bypass explicit simulation start eligibility` checks resolver and capture rejection when neither point nor rail direction exists, then verifies rail-direction fallback, actual destination removal and rejection of the removed point. It preserves the no-fallback eligibility contract.

No backend production change exists in this batch at the time of this preparatory review. Earlier source analysis found no depot-role exclusion in metadata/resolver/factory. These tests therefore establish the intended contract and guard against regression; they are not evidence that a nonexistent backend role filter was fixed. The parent's live authenticated metadata content remains unverified.

### Frontend status

Pending parent freeze notification and exported complete diff `/tmp/jr-waypoint-input-review.diff`. No conclusion about partially implemented frontend code is made here.

### Files/specs reviewed so far

- `.trellis/tasks/10-24-stop-program-hooks/research/{waypoint-input-batch,depot-simulation-start,waypoint-input-inventory}.md`: scope, existing identity domains, actual-data shape and explicit verification limits.
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt`: real depot/restoration/execution tests described above.
- `.trellis/spec/javarailways/simpleato/simulation.md`: isolate static infrastructure, shared dispatcher, baseline directed placement and actual reversal, truthful measured completion.
- `.trellis/spec/webui/frontend/{components-state-contracts,studio-authoring}.md`: single document/history owner, commit once, raw input invalidation and stale owner protection.
- `.agents/skills/javarailways-code-review/SKILL.md` and `references/missed-findings.md`: bounded source-grounded review, complete state path and symmetric lifecycle/input checks.

External references: none required or consulted.

## Caveats / Not Found

- Source review only. Parent owns test/build/browser/live evidence; no test pass is implied here.
- Frontend review is not yet complete; final actionable findings will be appended after freeze.
- Tests and read-only source investigation cannot establish current development-server metadata response or game/client acceptance.

## Frozen frontend review findings

The parent supplied `/tmp/jr-waypoint-input-review.diff` and confirmed product/test freeze. The following two actionable issues were found in that candidate; original findings are retained if subsequently fixed.

### P1: controlled multiple-ID search remains after selection and can be committed as an extra waypoint

- Location: `WebUI/frontend/src/EditableIdSelect.tsx:39-44`, `IdList`.
- `showSearch.searchValue` is controlled by local `search`; `onSearch` updates it while typing. Accepted `onChange`/option selection never clear it.
- The installed real Ant Select implementation (`@rc-component/select/es/Select.js:373-392`) clears only its internal search state after selection; it does not invoke the supplied external `onSearch('')`. Its `useControlledState('', searchValue)` therefore continues to read the unchanged controlled query.
- On blur, the real `BaseSelect/index.js:337-343` explicitly submits the remaining search query as a tag. Concrete sequence: type a display name such as `Central`, choose canonical option `station@1`, then click elsewhere. The selected waypoint is committed, but the leftover name can subsequently be added as a second unknown waypoint and produce another history write. Even without blur, the stale query wrongly keeps candidate filtering active for the next selection.
- The same shared component is used for duty origin waypoint lists, run `viaIds` and Blockly scoped ID lists, so the unwanted extra tag can change route semantics.
- The mocked Select in `EditableIdSelect.test.tsx:8` explicitly calls `onSearch('')` after its synthetic Enter acceptance, hiding the real library behavior; its candidate button does not assert that the query is cleared before blur.
- Minimum fix: clear controlled search on accepted change/selection (and any accepted duplicate selection if needed), retaining existing ordered values and Escape behavior. Add a faithful event-sequence regression or a real Ant browser/component test that selecting a named candidate followed by blur leaves exactly the canonical ID and one accepted history mutation.
- Evidence level: source-confirmed reachable real component lifecycle; parent notified to reproduce in the existing real-browser duties multi-input flow. This reviewer did not execute a browser or tests.

### P2: formation rendezvous controls cannot filter known waypoint display names

- Location: `WebUI/frontend/src/FormationEventsEditor.tsx:10,18,29`; propagation through `DutyTimeline.tsx:125` and `DutyManagement.tsx:58`.
- The two actual waypoint fields `approachDestinationId` and `stationaryDestinationId` now allow manual input, but their options still come from `options(p.destinations)`, where both value and label are the raw ID. The component only receives `destinations:string[]`; it never sees metadata or the shared `waypointOptions` names.
- If a known waypoint has ID `depot@1` and display name `Depot reception`, typing `Depot reception` yields no matching suggestion in these fields, although other waypoint controls correctly match that metadata name. This misses the batch PRD's requirement that all specific waypoint selectors filter by ID and name.
- Minimum fix: supply domain-correct waypoint `IdOption[]` from the same shared metadata helper, or propagate metadata through the existing controlled chain and compute the options there. Continue committing canonical waypoint IDs; leave duty IDs and local berth/depot point identities unchanged. Add a rendezvous name-filter test.
- Evidence level: complete static caller/producer/consumer inspection. No backend change or new metadata endpoint is needed.

### Other reviewed frontend boundaries

- Single-ID owner reset, Escape, explicit Enter/blur deduplication and option canonical-ID commits were traced through `EditableIdSelect`, its mounted tests and actual rc-select event ordering.
- `ProgramSimulationDialog` raw start-point typing invokes `onEditingChange` before commit, clears a completed measurement and invalidates pending request tokens; unknown or undirected manual IDs remain blocked by the existing request builder/server eligibility. Escape restores the committed point while leaving the old result invalid.
- Metadata reaches typed DESTINATION call arguments, the program library Blockly editor and standalone argument editor. Literal parameter references remain their original parameter selector; unknown/invalid ID_LIST JSON falls back to the raw editor instead of being overwritten. Generic ID_LIST and arbitrary display keys do not acquire waypoint suggestions.
- `MOVE.viaIds` receives full waypoint IDs; `SELECT_STOP.berthIds` and `STORE_AT_DEPOT.stablingPointIds` receive station/depot-local IDs from their selected literal context. Unknown values remain authored values. Run berth changes resolve targets only within the selected station and keep an unknown berth as an invalid draft rather than choosing another station's target.
- The `WorldMetadata.destinations` optional typing describes a field already produced by the bridge. The diff contains backend test additions but no backend production mutation, schema change, executor change, placement relaxation or actual-data migration.

### Validation evidence boundary

The parent reports backend667 + WebUI49 passing and frontend334 across56 files plus TypeScript/Vite build. Those results precede the two review corrections and do not close these findings. Parent real-browser work had verified filtered canonical depot IDs with names/context and was continuing Enter/blur/Escape/unknown/multiple flows. This review itself remains static and does not certify live server candidate metadata or game behavior.

## Corrections locally re-reviewed: P1/P2 addressed

The original findings above remain the audit record. The parent requested a narrow re-review of the frozen corrections; no tests or browser commands were run by this reviewer.

### P1 addressed

- Re-read `EditableIdSelect.tsx:39-44`: accepted multi-ID `onChange` now clears controlled `search` before forwarding the value array, and `onSelect` clears it as well. This covers both changed values and an already-selected option while preserving the original array order and active-owner guard.
- Re-read the updated `EditableIdSelect.test.tsx`: the Select test double no longer clears external search on behalf of the library. It now models blur submission of an uncleared query. The multiple-ID case checks that clicking a display-name candidate clears the search and that a subsequent blur does not add another value/history callback.
- Parent-provided real-browser evidence independently confirms the original defect and its correction. Old bundle: typing `Storage`, selecting `Storage one · kasaidpt@1`, then blurring produced two tags (`kasaidpt@1`, `Storage`), recorded at `/tmp/jr-waypoint-input-browser/multi-before.png`. Corrected bundle `index-CjPiqCNe.js`: the identical flow leaves only `kasaidpt@1` and an empty query; Undo once removes that point while retaining the acquisition source, Redo restores it. Manual `manual-waypoint` entry then produces ordered values `[kasaidpt@1, manual-waypoint]` with an empty query. Corrected screenshot: `/tmp/jr-waypoint-input-browser/multi-after.png`.
- Status: addressed by source correction, faithful mounted regression and parent-observed real Ant browser behavior. The screenshots/browser observations were supplied by the parent, not independently generated by this reviewer.

### P2 addressed

- Re-read `DutyTimeline.tsx:125`: the formation inspector now receives `metadata={p.programSimulation?.metadata}` from the same production context that `DutyManagement` obtains from the actual world metadata.
- Re-read `FormationEventsEditor.tsx:12,21,32`: the component accepts optional `WorldMetadata` and builds `waypointOptions({...p.metadata, wayPoints:p.destinations})`. Both rendezvous controls use those ID/name options; their accepted values remain the original canonical waypoint IDs. Overriding the actual candidate ID list with `p.destinations` preserves the existing scope and avoids adding unrelated references merely because metadata has a display name.
- Re-read the new mounted `FormationTaskFields.input.test.tsx` and caller wiring: the test verifies name-based filtering and canonical-ID submission through the real formation field component. Duty/source selectors remain their original identity domain.
- Status: addressed by the bounded metadata propagation and mounted field regression. No backend producer, schema or runtime change is needed.

### Updated conclusion and evidence boundary

- No open confirmed finding remains from this batch's independent static review.
- The parent reports final frontend335 across57 files plus TypeScript/Vite build for the corrected candidate; backend667 and WebUI49 were already passing with no backend production changes in this batch.
- This remains a bounded diff review and narrow correction re-review, not a full-repository audit. Real development-server authenticated metadata contents and game/client acceptance remain separate and unverified here.
- No implementation edit, Git operation, Gradle invocation, commit, deployment or task ending/archiving was performed by this reviewer. The original task remains in progress under the parent.
