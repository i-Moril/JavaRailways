# Feedback round 1 independent review

Reviewed the round-1 frontend diff against `aa5cb68d`, using PRD/design/implementation and `research/feedback-round-1.md` as the scope boundary. Reviewed the WebUI frontend index, components/state, program blocks and studio contracts, backend packaging contract, runtime station contract, versioning and persistence policy. No source files were edited by this reviewer, to avoid concurrent ownership conflicts.

## Findings fixed during integration

- P2: `DutyEditor` graph-event modal deletion originally removed an event by ID without the unchanged-original guard used for Save. Root fixed this with `deleteTimetableEvent`, which checks the original station/tick/label and rejects replaced or missing events. Both Save and Delete now check the modal's captured owner/document field scope. Reviewer inspected the final helper, callers and regression assertions for replaced/missing/current events.

## Remaining verification boundary

- Actual dragging needs browser acceptance: `ProgramBlockEditor`'s existing change listener skips its single deferred synchronization while dragging. `DutyBlockEditor` now retries until dragging settles. This is an existing listener weakness rather than a demonstrated new regression; verify dragging/connection in the real browser before treating the toolbar reduction as accepted.

## Contracts checked

- No backend/runtime/API/wire model changes in this round. Station preparation remains the existing `preparationLeadTicks` field; only its editing surface moves into the entry block.
- Entry lead changes submit the definition and lead in one callback. Accepted lead metadata is tracked independently from semantic steps; recovery retains disconnected blocks, while an external accepted lead update cannot silently overwrite the current definition.
- Duty toolbox clones receive independent numeric element/call IDs. The conversion rejects disconnected roots, duplicate element IDs, disabled/mistyped blocks and duplicate RUN references. Missing existing elements require recorded explicit deletion.
- `applyDutyCanvasElements` changes the chosen duty's references only; deleting a RUN block retains `document.runs` and other duties. Cross-duty duplicate assignment is rejected, including stale toolbox payloads.
- A newly called library program pins its exact toolbox snapshot. Reordering does not adopt a newer library revision. Existing bound definitions remain authoritative until explicit adoption.
- Invalid/raw duty workspaces retain complete typed payloads and pinned snapshots through local recovery. They do not become partial domain sequences by exporting only the entry subtree.
- Graph coordinate inversion uses rendered SVG bounds and layout axes, preserving pan/zoom mapping. Events are stored in the existing `events` document field and referenced by ID; creation does not add a runtime task.
- Removing the run's infrastructure selector leaves the stored `run.lineId` unchanged. Simulation/line caps/routing therefore retain their prior contracts.
- All new translation keys have both Chinese and English values. The display action help explicitly distinguishes operational display data from an actual model/display adapter.
- Program library file management, archive/delete semantics, full action-catalog unification, formation UI replacement and broad naming policy remain deferred as documented.

## Verification ownership

- `git diff --check`: pass during review.
- Frontend type-check/build and full tests: root owns execution; not rerun concurrently by this reviewer.
- Real HTTP browser drag, delete, recovery, entry-lead roundtrip and event persistence: root owns acceptance.
- No commits, deployment or production changes performed by this reviewer.
