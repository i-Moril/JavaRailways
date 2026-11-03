import {it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {I18nProvider} from './i18n';
import {RunDisplayPlansEditor} from './RunDisplayPlansEditor';
import {DutyManagement} from './DutyManagement';
import {emptyAuthoringDocument} from './authoring';
const noop=()=>{};
function document(){return {...emptyAuthoringDocument(),runs:[{id:'run',type:'PASSENGER' as const,lineDirection:'UP' as const,calls:[]}],duties:[{id:'1',selector:{type:'TRAIN_NAME' as const,name:'train'},elements:[{id:'leg',type:'RUN' as const,runId:'run'}]}],operationalTrips:[{id:'old-id',defaults:{destination:'Central'},segments:[{serviceId:'1',runId:'run',unitSlots:[1]},{serviceId:'missing-duty',runId:'missing-run',unitSlots:[2]}]},{id:'orphan',defaults:{destination:'Orphan destination'},segments:[]}]};}
it('exposes shared defaults and all leg identities, including stale legs, without a standalone ID editor',()=>{
 const value=document(),before=JSON.stringify(value);
 const html=renderToStaticMarkup(<I18nProvider><RunDisplayPlansEditor document={value} serviceId="1" runId="run" fieldScope="test" onChange={noop} onViewRun={noop}/></I18nProvider>);
 expect(html).toContain('Continuous display plans');expect(html).toContain('Central');expect(html).toContain('missing-duty / missing-run');expect(html).toContain('Run reference is missing or unassigned');expect(html).toContain('Unit 2');expect(html).toContain('Delete entire display plan');expect(html).not.toContain('New trip ID');expect(JSON.stringify(value)).toBe(before);
});
it('keeps unattached defaults and references inspectable/removable after removing the independent graph editor',()=>{
 const value=document();value.operationalTrips[1].segments=[{serviceId:'gone',runId:'gone-run',unitSlots:[3]}];
 const html=renderToStaticMarkup(<I18nProvider><DutyManagement graph={null} document={value} onDocumentChange={noop} duties={value.duties} runs={value.runs} selectedId="1" metadata={{}} onSelect={noop} onAdd={noop} onDelete={noop} onPatch={noop} onRemove={noop} onViewRun={noop}/></I18nProvider>);
 expect(html).toContain('Some display plans have no valid run');expect(html).toContain('Orphan destination');expect(html).toContain('gone-run');expect(html).toContain('gone');expect(html).not.toContain('Formation duty graph');expect(html).not.toContain('Advanced: operational trips');
});
