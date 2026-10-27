import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {I18nProvider} from './i18n';
import {DutyTimeline} from './DutyTimeline';
import type {TimetableAuthoringDocumentV1} from './authoring';
const noop=()=>{};
function fixture():TimetableAuthoringDocumentV1 {return {formatVersion:1,id:'doc',periodDays:1,diagramLines:[],arrivalOverrides:[],runs:[{id:'run-1',type:'PASSENGER',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200,minimumDwellTicks:20}]}],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'r',type:'RUN',runId:'run-1'},{id:'p',type:'PROGRAM',tick:300,program:{programId:'1',arguments:{}},duration:{type:'SIMULATED'}}]}],boundPrograms:[{programId:'1',revision:1,contentIdentity:'hash',definition:{parameters:[],steps:[]}}]};}
function html(document=fixture()) {return renderToStaticMarkup(<I18nProvider><DutyTimeline document={document} duty={document.duties[0]} fieldScope="qa" library={[]} stationNames={new Map()} stationIds={[]} onAssign={noop} onPatch={noop} onRemove={noop} onViewRun={noop}/></I18nProvider>);}
describe('duty timeline presentation',()=>{
 it('renders derived run intervals and unknown simulated durations without a scratch workspace',()=>{
  const rendered=html();expect(rendered).toContain('run-1 · 100–220');expect(rendered).toContain('Duration not measured');expect(rendered).toContain('Remove task reference');expect(rendered).not.toContain('blockly');
 });
 it('preserves point tasks and exposes manual resize only for manual durations',()=>{
  const document=fixture();document.duties[0].elements=[{id:'manual',type:'PROGRAM',tick:100,program:{programId:'1',arguments:{}},duration:{type:'MANUAL',ticks:30}},{id:'point',type:'PROGRAM',tick:200,program:{programId:'1',arguments:{}}}];
  const rendered=html(document);expect(rendered).toContain('100–130');expect(rendered).toContain('200–200');expect(rendered).toContain('Resize duration');expect(rendered).toContain('Planned duration (ticks)');
 });
 it('announces overlap and provides accessible start/duration alternatives to dragging',()=>{
  const document=fixture();document.duties[0].elements=[{id:'p',type:'PROGRAM',tick:150,program:{programId:'1',arguments:{}},duration:{type:'MANUAL',ticks:100}},document.duties[0].elements[0]];
  const rendered=html(document);expect(rendered).toContain('Planned tasks overlap');expect(rendered).toContain('aria-label="Start tick"');expect(rendered).toContain('aria-label="Planned duration (ticks)"');
 });
});

describe('graph operation references',()=>{
 it('keeps graph-owned timing read-only and removes only the reference',()=>{
  const document=fixture();document.events=[{id:'event',tick:300,label:'Turnback',duration:{type:'MANUAL',ticks:50},program:{programId:'1',arguments:{}}}];
  document.duties[0].elements=[{id:'reference',type:'EVENT',eventId:'event'}];
  const rendered=html(document);expect(rendered).toContain('Turnback · 300–350');expect(rendered).toContain('Graph-owned operation');expect(rendered).toContain('Edit on running graph');
  expect(rendered).toContain('disabled=""');expect(rendered).not.toContain('duty-timeline-resize');expect(rendered).toContain('Remove task reference');
 });
 it('does not fabricate an executable program for a missing or marker reference',()=>{
  const document=fixture();document.events=[{id:'marker',tick:300}];document.duties[0].elements=[{id:'reference',type:'EVENT',eventId:'marker'}];
  const rendered=html(document);expect(rendered).toContain('The referenced graph operation is missing or has no program.');expect(rendered).not.toContain('timed-operation-fields');
 });
});
