# Original feedback10: timeline-owned display plan editor

User chose retain cross-run continuous display and per-unit future stops, with configuration owned by program or timeline. Choose timeline RUN task inspector as the smallest existing owner; keep runtime semantics and current document/catalog shape unchanged. No new runtime actions, hidden synthetic trips, migration or compatibility path. Existing operationalTrips remains active display-plan data, not deprecated data. Remove the separate old graph/trip-management panel once every current configuration is editable/removable through new entry.

## UI contract

Selected RUN task inspector shows its continuous display plans. Create a plan for this actual bound run or attach it to an existing plan; choose complete unit slots using existing UnitSlotsInput (default1, no guessed actual unit total). New plans get simple numeric internal IDs from existing trip-ID namespace. No manual trip-ID field or standalone trip creation/editor. Refer to display plan/continuous display, not a second operating train/duty.

Each attached plan exposes shared default display JSON plus ordered run legs with duty/run labels, complete unit slots, existing run navigation, reorder and remove. All legs remain visible/editable so continuity/order can be configured, including missing-reference diagnostics rather than dropping stale entries. Add only actual RUN tasks from document.duties/elements (not unassigned run definitions). A plan may cover multiple duties after formation handoff; runtime continuity validator remains authority. Help explains shared defaults and SET_DISPLAY/RESET_DISPLAY relationship.

A RUN may contain several plans on disjoint units. Never steal/overwrite another plan's claims. New/edited leg rejects duplicate plan/run and overlapping unit claims; use explicit slots prior to attach/create, no auto-invented remaining units. Removing a last leg removes the now-empty plan in same transaction; explicit delete entire plan removes all its legs and shared defaults. This is reversible existing document history. Preserve old IDs/defaults/order when simply opening/saving. Do not sort/merge/rebuild catalog as a render side effect.

Plans with no resolvable actual RUN must not become hidden when old editor is removed: a compact invalid/unattached-plan warning on duty page exposes exact references/defaults and explicit delete; such plans also remain eligible for attach from a RUN inspector. Partially missing legs remain visible inside a reachable plan with removal. No second full global editor.

## Model helper API (frontend/displayPlans.ts)

- displayPlanRuns(document): actual RUN task references with {serviceId,runId,label}.
- displayPlansForRun(document,serviceId,runId): existing OperationalTrip[] preserving order.
- orphanDisplayPlans(document): plans without any resolvable actual RUN task.
- createDisplayPlan(document,leg): document; leg OperationalTripSegment explicit slots; allocate numeric stable internalID.
- attachDisplayPlanRun(document,planId,leg): document; add to end only after duplicate/claim guard.
- updateDisplayPlanDefaults(document,planId,defaults): document.
- updateDisplayPlanLeg(document,planId,index,leg): document; same guards for changed leg.
- moveDisplayPlanLeg(document,planId,index,delta:-1|1): document.
- removeDisplayPlanLeg(document,planId,index): document; drop plan if last.
- removeDisplayPlan(document,planId): document.

All pure immutable, preserve unrelated metadata/duties/formation/programs, no stale whole-document callback write after changing identity. Validation considers target leg claims while allowing unrelated invalid references to remain repairable. Existing global authoring issues/compile gate remain. Errors meaningful UI text without silent coercion.

## Ownership

Model worker owns new displayPlans.ts and helpertests only. UI worker owns new RunDisplayPlansEditor.tsx/css, DutyTimeline.tsx integration(onDocumentChange), DutyManagement.tsx wiring+orphanwarning/removal ofold advancedpanel, DutyGraphEditor.tsx removal ifno remainingproductionconsumer, i18n and adaptedexistingfrontendtests. Remove obsolete dutyGraph projections when no production consumer remains; tests alone do not justify retaining unused production code. Rootowns docs/spec/status/finalgates. BothworkerscoordinatedfrozenAPI, no recursiveagents.

## Gates

Meaningful helper tests: preserve existing catalog source byte/semantic shape onview, create/attach/defaults/order/slots/remove/cross-duty metadata, duplicates/claims, missing references, orphan deletion/repair, history/recovery roundtrip, generatedsource still emits same trip/begin facts and unitfuture semantics. Existingfullfrontendtests/build. Browser actual RUN inspector create/attach across two RUNs, unit slots/defaultJSON, remove/move/undo/reload; old independent panel absent and invalid config visible/removable. No fake compile/backend/game success. Backend/runtime unchanged; no redundant full Gradle needed unless source contracts change. User authorized separate local batch commits; root commits after local gates, no deployment/archive.

## Completed validation

- Purehelper10 tests cover numericstableIDs, actualtaskmembership, shareddefaults immutability, cross-duty formation references preserved, disjointunitclaims, duplicate/overlap rejection, orphan/partialmissing repair, exactorder/removal/history/recovery and unchanged generated movement/run lines with current trip declarations.
- Latest fullfrontend287tests/46files, TypeScript/Vite production build and git diff --check pass. Existing chunk warning remains. Legacy draft test updated to actual JSON/helper boundary; real DutyEditor invalid-save propagation separately browserverified.
- Scoped independent trellis-check found no remaining blocker: all original fields reachable, old/partial/orphan configs retained andeditable/removable, identity/history/stalecallbackguard, no schema/generator/runtime changes. Old editor/graph helper/styles/unusedvaliditycallback removed, simulation display label reflects current plan wording.
- Actual frontend-demo App/DutyEditor browser: createplan1 from AP101; shareddefaultsJSON; add AP102; editunits; reorder/remove/Undo; ownerlocalrecovery/reload. Existing orphan defaults/refs visible; attach via AP102 and explicitunit2, thenremove missingleg. InvalidJSON disables actual Save; Escape restores. No consoleerrors. No fake backend save/compile or livegame success.
-360px actual viewport: body360/editor264/plan262 with matching scrollwidth, no horizontaloverflow. Screenshots visually inspected `/tmp/jr-run-display-plans-inspector.png` and `/tmp/jr-run-display-plans-mobile.png`. Dedicated browser/service18908/temp copy cleaned. Final copy-only help uses visible localized action names instead of protocol tokens; finalfullgate rerun covers this.
- No redundant backend/Gradle run: document fields, sourcegenerator, worker and runtime all unchanged. Same active schema/version; no conversion or compatibilityreader.
- Committed as a separate local batch under user's continuing authorization after spacing0900f865; exact commit reported in chat/Git history. No push/deploy/archive; user game verification deferred.
