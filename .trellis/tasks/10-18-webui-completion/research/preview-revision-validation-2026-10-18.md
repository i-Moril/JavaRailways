# Preview revision validation —2026-10-18

## Scope and source
Six user requests implemented in the same isolated UI worktree, with explicit confirmation that only standalone JS timetables/Simulation pages are removed. Validation full simulation, backend APIs and formal JS remain. No main-worktree product writes, schema/dependency changes, deployment or push. Prior89-test evidence is historical; final revision has104 tests.

## Checks and independent review
Implementer ran104 tests, production build and diff check successfully. Existing bundle size warning remains. Reviewer independently ran25 focused CommittedNumberInput/graph transaction/auth tests and diff check; all passed. Reported Copy validity flag masking and stale positional node identity were fixed: independent validity flags are ORed; ordered call-ID/status signature invalidates stale menu/Shift. Escape and raw minus preservation were verified in final code. No remaining concrete reviewer blocker.

## Browser evidence on final frozen source
Dedicated Chromium session `jr-ui-revision`,1600×1000, local dev-demo API (not real Bukkit runtime):
- Navigation exactly Timetable/RailMap/Lines/Duties/Validation. No standalone JS/Simulation or MoveRun tool; dedicated CopyRun present. Validation still renders full profile setup/report.
- Origin input typed1200→1300: graph remains1200, Saved, Undo disabled until Enter. Enter updates1300, Unsaved, Undo enabled; switching page (blur) then one Undo restores1200 and Saved with Undo disabled. Escape after typing1350 then blur leaves1200 unchanged. Plain blur separately commits1200→1400 only after clicking elsewhere.
- Dragging OldTown node20px leaves time2000 and Undo state unchanged. Point activation opens floating menu; Enter/Space activation supported. First selection of AP102 while UP selected switches run/direction correctly. Node toolbar typing5600→5700 keeps graph5600 until Enter; one Undo restores5600.
- Negative offset typed key-by-key preserves '-' and '-1000'. Applying anchor arrival2000 with -1000 is blocked by chronology, origin remains1200.
- Arrival suffix +200 preview reports6 effective events: origin stays1200, OldTown2000/2100→2200/2300, Central2900/3020→3100/3220, PASS3700→3900, terminal4700→4900. One Undo restores all suffix times.
- Departure absolute2050 from2100 is rejected for minimum dwell; absolute2200 accepted with5 effective events: same-station arrival stays2000, origin1200, departure2200 and only later events+100.
- With a node menu open, trimming origin to OldTown closes the stale menu. Undo restores route. Reset with chosen existing profile retains original origin1200; regeneration section has no editable first-departure or copy controls.
- Copy invalid interval '-' plus valid count2 stays blocked. Interval500/count2 generates fresh unassigned copies with origin1700 and2200 from original1200; one Undo removes both.
- Clicking another page with uncommitted node5600→5650 commits it on blur before page change; returning shows5650. Copy confirmation without Enter uses the current interval800/count2, generating6400/7200 from5600 (after popup entrance animation settles).
- Final independent browser session reports zero page exceptions. Existing Ant Design Button.Group deprecation remains unchanged.

## Limits and review
Browser responses use dev-demo data; no live server compile/simulation/publish claim. Earlier initial-batch browser world/async guards remain part of regression evidence and code review; not rerun exhaustively in this revision because owners are retained. Tight adjacent arrival/departure circles can overlap at low zoom; both event identities remain keyboard-activatable and the graph can be zoomed. No new node collision-layout feature added.
