# Original feedback7: parameter and snapshot spacing

User requested split commits then continue original feedback. Existing formation5be9d190 and train-info07678147 are committed; no push/deploy/archive. Continue same active task.

## Exact boundary

1. TimedOperationFields uses190px minimum grid tracks with18px gap, while ProgramJsonInput global min-width220px overflows190–219px tracks. At420px contentwidth2columns201px, JSON consumes gutter/neighbor. Fix only shrink-to-track behavior in shared timed-operation parameter controls, with narrow single-column behavior if needed. Sharedcomponent applies duty task and graph event call. Check ID_LIST controls where170px inline minimum may also require scoped shrink below190px; do not rewrite inputs/validation.
2. DutyManagement bound-program snapshot Collapse renders adjacent Flex rows with no parent verticalgap; long names can squeeze the adopt-update button. Add existing-design verticalspacing and wrapping of source name/version row. Preserve adopt callback and snapshot semantics.

Expectedfiles TimedOperationFields.css, DutyManagement.tsx and appropriate existing CSS (DutyTimeline.css orstudioPages.css). No model/API/persistence/source generation/condition changes. No operationalTrips removal: that existing UI still carries actual continuity/display semantics and needs its own design before removal.

## Acceptance

Actual production React components/browser geometry at420px contentwidth demonstrate JSON stayswithincolumn and18px gap, no collision or horizontaloverflow. Narrow view and long names wrap, two+update rows havevisibleverticalgap and workingadoptbutton. Preserve input edits/invalidJSON/commit and snapshotupdateclick callback. Run existingfrontendtests/build; no mirroredCSSunit tests needed. Browserfixture honest nofakebackend. Keep newbatchuncommittedforreview, no deployment.

## Verification

- Actual production components/browser before420px content: grid tracks201px; JSON220px ends410, neighbor starts409 (1px overlap). After: JSON201px ends391, neighbor starts409 (18pxgap).
- Bound snapshot rows before0px gap; after firstbottom644.875/nexttop656.875 gives12px gap. Long names wrap and166.64px updatebuttons remainusable; native clicks invoke expected callbacks1,2.
-360px viewport has320px parameter controls and document scrollWidth360;218px snapshotrows nooverflow.140px fixture JSON/STRING/ID_LIST all140px.
- InvalidJSON retains rawdraft and acceptedvalue unchanged; validCtrl+Enter commits. No handler/model change.
- Fullfrontend276tests/44files and TypeScript/Vite production build pass. Existing chunk warning only. Independent scoped static trellis-check and git diff --check pass. No new mirror CSS unit tests; actual browser geometry and interactions validate layout. No backend tests repeated for CSS/wrapper-only changes.
- Screenshots visually inspected: /tmp/jr-spacing-before.png, /tmp/jr-spacing-after.png, /tmp/jr-spacing-mobile.png. Dedicated browser and temporary18906 service cleaned. Component fixture does not simulate backend save/compile/execution.
- New spacing batch remains uncommitted. Earlier two batches committed5be9d190 and07678147; no push/deploy/archive.
