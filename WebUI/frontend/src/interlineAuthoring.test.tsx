import {describe,expect,it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {RunDisplayPlansEditor} from './RunDisplayPlansEditor';
import {I18nProvider} from './i18n';
import {filterTimetableForDiagramLine,layoutGraph} from './graph';
import {deleteRunFromDocument,editRunConnection,generateTimetableSource as generateWithLines,moveRunToDuty,renameAuthoringRun,validateAuthoringDocument as validateWithLines,type TimetableAuthoringDocumentV1} from './authoring';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory,redoDocumentHistory} from './documentHistory';
import {displayValuesError,validateStationProgram} from './stationPrograms';
import type {SavedTimetableV1,TimetableServiceV1} from './types';
const infrastructureLines=[{"id": "main", "displayName": "main", "upStationIds": ["a", "b"], "downStationIds": ["b", "a"], "maximumSpeedMetersPerSecond": 20}, {"id": "branch", "displayName": "branch", "upStationIds": ["a", "b"], "downStationIds": ["b", "a"], "maximumSpeedMetersPerSecond": 10}];
const generateTimetableSource=(document:TimetableAuthoringDocumentV1,programs=[] as import('./types').StationProgramRecord[])=>generateWithLines(document,infrastructureLines,programs);
const validateAuthoringDocument=(document:TimetableAuthoringDocumentV1,programs=[] as import('./types').StationProgramRecord[])=>validateWithLines(document,infrastructureLines,programs);
const document=():TimetableAuthoringDocumentV1=>({formatVersion:1,id:'through',periodDays:1,minimumDwellTicks:20,arrivalOverrides:[],diagramLines:[{id:'main',upStationIds:['A','B','C','D'],downStationIds:['D','C','B','A']},{id:'branch',upStationIds:['C','E'],downStationIds:['E','C']}],runs:[
 {id:'M01',lineId:'main',diagramLineId:'main',lineDirection:'UP',type:'PASSENGER',calls:[{id:'a',stationId:'A',status:'ORIGIN',targetId:'A@1',departureTick:100},{id:'b',stationId:'B',status:'PASS',targetId:'B@1',arrivalTick:200},{id:'c',stationId:'C',status:'TERMINAL',targetId:'C@1',arrivalTick:300,doorSide:'LEFT'}]},
 {id:'B01',lineId:'branch',diagramLineId:'branch',lineDirection:'UP',type:'PASSENGER',calls:[{id:'c2',stationId:'C',status:'ORIGIN',targetId:'C@1',departureTick:350},{id:'e',stationId:'E',status:'TERMINAL',targetId:'E@1',arrivalTick:500}]},
],duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'m',type:'RUN',runId:'M01'},{id:'b',type:'RUN',runId:'B01'}]}],operationalTrips:[{id:'to-E',defaults:{destination:'E'},segments:[{serviceId:'duty',runId:'M01',unitSlots:[1]},{serviceId:'duty',runId:'B01',unitSlots:[1]}]},{id:'to-D',defaults:{destination:'D'},segments:[{serviceId:'duty',runId:'M01',unitSlots:[2]}]}]});

describe('one-document through running and unit trips',()=>{
 it('generates one executor with exact segment scopes, one origin check and one shared C destination',()=>{
  const d=document(),generated=generateTimetableSource(d);expect(generated.issues).toEqual([]);
  const segments:{options:Record<string,unknown>;start:number;end:number}[]=[],actions:string[]=[],trips:string[]=[];
  const timed={beginStationStop:()=>timed,pass:(_:string)=>timed,destination:(id:string,options:unknown)=>{actions.push(`destination:${id}:${JSON.stringify(options)}`);return timed;}};
  const scope={beginStationStop:()=>scope,origin:(_:string,cb:(scope:unknown)=>void)=>cb(scope),check:(id:string)=>actions.push(`check:${id}`),at:(tick:number)=>{actions.push(`at:${tick}`);return timed;},runSegment:(options:Record<string,unknown>,cb:(scope:unknown)=>void)=>{const start=actions.length;cb(scope);segments.push({options,start,end:actions.length});}};
  let executors=0;const host={setPeriodDays:()=>{},diagramLine:()=>{},lineSpeedLimit:()=>{},operationalTrip:(id:string)=>trips.push(id),service:(_:string,__:string,options:unknown,cb?:(scope:unknown)=>void)=>{executors++;(cb??options as (scope:unknown)=>void)(scope);}};
  new Function('timetable',generated.source+'\nmain(timetable)')(host);
  expect(executors).toBe(1);expect(segments.map((s)=>s.options.runId)).toEqual(['M01','B01']);expect(segments[0].end).toBe(segments[1].start);
  expect(actions.filter((a)=>a.startsWith('check:'))).toEqual(['check:A@1']);expect(actions.filter((a)=>a.startsWith('destination:C@1:'))).toHaveLength(1);
  const destination=JSON.parse(actions.find((action)=>action.startsWith('destination:C@1:'))!.slice('destination:C@1:'.length));expect(destination).toMatchObject({arrivalTick:300,minimumDwellTicks:20,doorSide:'LEFT',callRole:'TERMINAL',stationId:'C'});expect(actions).toContain('at:350');
  expect((segments[1].options.calls as {destinationId:string}[])[0].destinationId).toBe('C@1');expect(trips).toEqual(['to-E','to-D']);
 });
 it('rejects unsafe direct C offset, time, missing line and overlapping unit claims',()=>{
  const d=document();d.runs[1].calls[0].stoppingOffsetMetersBefore=5;expect(validateAuthoringDocument(d).some((i)=>i.message.includes('offset discontinuity'))).toBe(true);
  d.runs[1].calls[0].stoppingOffsetMetersBefore=0;d.runs[1].calls[0].departureTick=310;expect(validateAuthoringDocument(d).some((i)=>i.message.includes('overlaps'))).toBe(true);
  d.runs[1].lineId=null;expect(validateAuthoringDocument(d).some((i)=>i.message.includes('requires an infrastructure'))).toBe(true);
  d.operationalTrips![1].segments[0].unitSlots=[1];expect(validateAuthoringDocument(d).some((i)=>i.message.includes('multiple operational trips'))).toBe(true);
 });
 it('establishes a named train origin before its initial display program without a second run check',()=>{
  const d=document();
  const programs=[{formatVersion:1 as const,id:'display',name:'display',definition:{parameters:[],steps:[{id:'set' as const,type:'SET_DISPLAY' as const,values:{value:{destination:'E'}}}]}}];
  d.duties[0].elements.unshift({id:'initial-display',type:'PROGRAM',tick:50,program:{programId:'display',arguments:{}}});
  const generated=generateTimetableSource(d,programs);
  expect(generated.issues).toEqual([]);
  expect(generated.source.match(/\.check\(/g)).toHaveLength(1);
  expect(generated.source.indexOf('.check("A@1")')).toBeLessThan(generated.source.indexOf('.program("display"'));
  expect(generated.source.indexOf('.program("display"')).toBeLessThan(generated.source.indexOf('.runSegment('));
 });
 it('uses the effective overridden C arrival when checking departure and minimum dwell',()=>{
  const d=document();d.arrivalOverrides=[{runId:'M01',callId:'c',tick:340}];
  expect(validateAuthoringDocument(d).some((issue)=>issue.message.includes('overlaps'))).toBe(true);
  d.runs[1].calls[0].departureTick=360;
  expect(validateAuthoringDocument(d)).toEqual([]);
 });
 it('edits both C halves in one history transaction and rejects stale links and dwell violations',()=>{
  const d=document();d.arrivalOverrides=[{runId:'M01',callId:'c',tick:305}];
  const next=editRunConnection(d,'M01','B01',320,370),history=updateDocumentHistory(createDocumentHistory(d),next);
  expect(next.runs[0].calls.at(-1)?.arrivalTick).toBe(320);expect(next.runs[1].calls[0].departureTick).toBe(370);expect(next.arrivalOverrides).toEqual([]);
  expect(history.past).toHaveLength(1);expect(undoDocumentHistory(history).present).toBe(d);expect(redoDocumentHistory(undoDocumentHistory(history)).present).toBe(next);
  expect(()=>editRunConnection(d,'M01','B01',320,330)).toThrow('minimum dwell');
  d.duties[0].elements.reverse();expect(()=>editRunConnection(d,'M01','B01',320,370)).toThrow('no longer current');
 });
 it('maintains trip run references when renaming, moving and deleting; copies remain unassigned',()=>{
  const d=document();d.duties.push({id:'other',selector:{type:'TRAIN_NAME',name:'other'},elements:[]});
  const renamed=renameAuthoringRun(d,'M01','new-M'),moved=moveRunToDuty(renamed,'new-M','other','new-element');
  expect(moved.operationalTrips?.[0].segments[0]).toEqual({serviceId:'other',runId:'new-M',unitSlots:[1]});
  const deleted=deleteRunFromDocument(moved,'new-M');expect(deleted.operationalTrips?.[0].segments.map((s)=>s.runId)).toEqual(['B01']);expect(deleted.operationalTrips?.[1].segments).toEqual([]);
 });
 it('edits continuous display plans from a real assigned run',()=>{
  const d=document();
  const html=renderToStaticMarkup(<I18nProvider><RunDisplayPlansEditor document={d} serviceId="duty" runId="M01" onChange={()=>{}} onViewRun={()=>{}} fieldScope="test"/></I18nProvider>);
  expect(html).toContain('Continuous display plans');expect(html).toContain('to-E');expect(html).toContain('to-D');expect(html).toContain('Unit 2');expect(html).not.toContain('Formation duty graph');
 });
 it('projects compiled segments on each diagram while retaining physical service and step addresses',()=>{
  const d=document(),service:TimetableServiceV1={id:'duty',selectorType:'ANY',activation:{type:'SCHEDULED'},steps:[{type:'CHECK',wayPointId:'A@1'},{type:'AT',tick:100,action:{type:'DESTINATION',wayPointIds:['C@1'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},{type:'AT',tick:350,action:{type:'DESTINATION',wayPointIds:['E@1'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}}],runSegments:d.runs.map((r,i)=>({runId:r.id,lineId:r.lineId!,diagramLineId:r.diagramLineId,startStepIndex:i?2:0,endStepIndexExclusive:i?3:2,calls:r.calls.map((c)=>({...c,destinationId:c.targetId}))}))};
  const table:SavedTimetableV1={id:d.id,periodDays:1,diagramLines:d.diagramLines,services:[service]},filtered=filterTimetableForDiagramLine(table,'branch');
  expect(filtered.services).toHaveLength(1);expect(filtered.services[0].id).toBe('duty');expect(filtered.services[0].steps).toBe(service.steps);
  const layout=layoutGraph(filtered,1000,58,170,undefined,undefined,'branch');expect(layout.paths).toHaveLength(1);expect(layout.paths[0].runId).toBe('B01');expect(layout.points.every((p)=>p.stepIndex===2)).toBe(true);expect(layout.points.map((p)=>p.location)).toEqual(['C','E']);
 });
 it('supports whole-train display edits and bounded display values',()=>{
  const step={id:'display',type:'SET_DISPLAY' as const,values:{value:{destination:'E'}},removeKeys:{value:['old']}};
  expect(validateStationProgram({parameters:[],steps:[step]})).toEqual([]);
  expect(displayValuesError({' ':1})).toContain('nonblank');expect(displayValuesError(Object.fromEntries(Array.from({length:129},(_,i)=>['key'+i,1])))).toContain('128');
 });
});
