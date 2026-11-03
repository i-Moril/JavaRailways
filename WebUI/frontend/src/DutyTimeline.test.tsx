import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {I18nProvider} from './i18n';
import {DutyTimeline} from './DutyTimeline';
import type {TimetableAuthoringDocumentV1} from './authoring';
const noop=()=>{};
function fixture():TimetableAuthoringDocumentV1 {return {formatVersion:1,id:'doc',periodDays:1,minimumDwellTicks:20,diagramLines:[],arrivalOverrides:[],runs:[{id:'run-1',type:'PASSENGER',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200}]}],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'r',type:'RUN',runId:'run-1'},{id:'p',type:'PROGRAM',tick:300,program:{programId:'1',arguments:{}},duration:{type:'SIMULATED'}}]}]};}
function html(document=fixture()) {return renderToStaticMarkup(<I18nProvider><DutyTimeline document={document} duty={document.duties[0]} fieldScope="qa" library={[]} stationNames={new Map()} stationIds={[]} onPatch={noop} onRemove={noop} onViewRun={noop}/></I18nProvider>);}
function interval(rendered:string,tick:number) {
 const markup=rendered.match(new RegExp(`<span[^>]*class="duty-timeline-interval[^\"]*"[^>]*data-start-tick="${tick}"[^>]*>`))?.[0];
 expect(markup).toBeDefined();return markup!;
}
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
 it('places all tasks in one shared lane and keeps compact selectable labels outside occupancy',()=>{
  const document=fixture();document.duties[0].elements.push({id:'point',type:'PROGRAM',tick:400,program:{programId:'1',arguments:{}}});
  const rendered=html(document);
  expect(rendered.match(/class="duty-timeline-lane"/g)).toHaveLength(1);
  expect(rendered).not.toContain('duty-timeline-row');
  expect(rendered.match(/class="duty-timeline-item"/g)).toHaveLength(3);
  expect(rendered).toContain('class="duty-timeline-labels"');
  expect(rendered).toContain('aria-label="1 · 400–400"');
 });
 it('draws even subpixel durations proportionally without expanding their occupied interval',()=>{
  const document=fixture();document.duties[0].elements=[
   {id:'short',type:'PROGRAM',tick:100,program:{programId:'1',arguments:{}},duration:{type:'MANUAL',ticks:1}},
   {id:'longer',type:'PROGRAM',tick:200,program:{programId:'1',arguments:{}},duration:{type:'MANUAL',ticks:10}},
  ];
  const rendered=html(document),short=interval(rendered,100),longer=interval(rendered,200);
  const width=(markup:string)=>Number(markup.match(/width:([\d.]+)px/)?.[1]);
  expect(width(short)).toBeGreaterThan(0);expect(width(short)).toBeLessThan(1);
  expect(width(longer)/width(short)).toBeCloseTo(10);
  expect(short).toContain('data-end-tick="101"');
  // The wider transparent control is not the duration shape.
  expect(rendered).toMatch(/aria-label="1 · 100–101"[^>]*style="[^"]*width:24px"/);
 });
 it('distinguishes a known point from an unknown duration without claiming an occupied interval',()=>{
  const document=fixture();document.duties[0].elements.push({id:'point',type:'PROGRAM',tick:400,program:{programId:'1',arguments:{}}});
  const rendered=html(document),unknown=interval(rendered,300),point=interval(rendered,400);
  expect(unknown).toContain('duty-timeline-interval unknown');expect(unknown).not.toContain('data-end-tick');
  expect(point).toContain('duty-timeline-interval point');expect(point).toContain('data-end-tick="400"');
  expect(unknown).not.toContain('width:');expect(point).not.toContain('width:');
  expect(rendered).toContain('aria-label="1 · 300 ?"');
 });
 it('marks the exact shared overlap instead of hiding conflicts in separate rows',()=>{
  const document=fixture();document.duties[0].elements.push({id:'conflict',type:'PROGRAM',tick:150,program:{programId:'1',arguments:{}},duration:{type:'MANUAL',ticks:100}});
  const rendered=html(document);
  expect(rendered.match(/class="duty-timeline-conflict"/g)).toHaveLength(1);
  expect(rendered).toContain('run-1 · 100–220; 1 · 150–250');
  expect(rendered).toMatch(/class="[^"]*duty-timeline-task run selected conflict"/);
  expect(rendered).toMatch(/class="[^"]*duty-timeline-task program  conflict"/);
  expect(rendered.match(/class="duty-timeline-lane"/g)).toHaveLength(1);
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

describe('direct duty formation tasks',()=>{
 function splitDocument(){const document=fixture();document.formationEvents=[{id:'split-internal',kind:'SPLIT',tick:400,duration:{type:'MANUAL',ticks:2},coordinatorDutyId:'d',partition:{from:'HEAD',firstUnitCount:2},outputs:[{id:'front-internal',group:'FIRST',disposition:{type:'DUTY',dutyId:'d'}},{id:'rest-internal',group:'REST',disposition:{type:'DUTY',dutyId:'5'}}]}];document.duties[0].elements=[{id:'split-task',type:'FORMATION',eventId:'split-internal'}];document.duties.push({id:'5',selector:{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split-internal',outputId:'rest-internal'},elements:[]});return document;}
 it('edits a split directly without a reusable program or raw identity fields',()=>{
  const rendered=html(splitDocument());
  expect(rendered).toContain('Complete units from operating head');expect(rendered).toContain('aria-valuemax="2147483647"');
  expect(rendered).toContain('Remaining units');
  expect(rendered).toContain('Create the receiving duty yourself');
  expect(rendered).toContain('400–402');
  expect(rendered).not.toContain('timed-operation-fields');
  expect(rendered).not.toContain('Output ID');
  expect(rendered).not.toContain('split-internal');
  expect(rendered.match(/class="duty-timeline-lane"/g)).toHaveLength(1);
  const span=interval(rendered,400);expect(Number(span.match(/width:([\d.]+)px/)?.[1])).toBeLessThan(2);
 });
 it('shows another coupling participant read-only with a coordinator link',()=>{
  const document=splitDocument();document.duties[1].selector={type:'TRAIN_NAME',name:'other'};
  document.formationEvents=[{id:'coupling',kind:'COUPLE',tick:400,duration:{type:'SIMULATED'},coordinatorDutyId:'5',approachingDutyId:'d',stationaryDutyId:'5',stationaryEnd:'HEAD',rendezvous:{approachDestinationId:'a',stationaryDestinationId:'b'},maximumApproachSpeedMetersPerSecond:.5,output:{id:'combined',disposition:{type:'DUTY',dutyId:'5'}}}];
  document.duties[0].elements=[{id:'couple-task',type:'FORMATION',eventId:'coupling'}];
  const rendered=html(document);expect(rendered).toContain('Shared coupling operation. Edit it in the coordinator duty.');expect(rendered).toContain('View source operation');expect(rendered).toContain('disabled=""');expect(rendered).not.toContain('The referenced graph operation is missing');expect(rendered).not.toContain('timed-operation-fields');
 });
});
