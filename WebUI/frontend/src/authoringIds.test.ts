import {describe,expect,it} from 'vitest';
import {allocateAuthoringIds} from './authoringIds';
import {copyRunShifted,createRunFromLine,emptyAuthoringDocument,type AuthoringRunV1,type TimetableAuthoringDocumentV1} from './authoring';
import {createDocumentHistory,redoDocumentHistory,undoDocumentHistory,updateDocumentHistory} from './documentHistory';

const run=(id:string):AuthoringRunV1=>({id,type:'PASSENGER',lineDirection:'UP',calls:[]});
const duty=(id:string)=>({id,selector:{type:'TRAIN_NAME' as const,name:''},elements:[]});
const split=(coordinatorDutyId:string,outputDutyId:string)=>({
  id:'event',tick:10,kind:'SPLIT' as const,coordinatorDutyId,partition:{from:'HEAD' as const,firstUnitCount:1},
  outputs:[{id:'first',group:'FIRST' as const,disposition:{type:'DUTY' as const,dutyId:outputDutyId}},{id:'rest',group:'REST' as const,disposition:{type:'HOLD' as const}}],
});

describe('visible authoring IDs',()=>{
  it('starts each independent namespace at 1 and keeps custom and internal identities unchanged',()=>{
    const document:TimetableAuthoringDocumentV1={...emptyAuthoringDocument(),runs:[run('express-42'),run('run-old')],duties:[{
      ...duty('depot-duty'),elements:[{id:'999',type:'RUN',runId:'express-42'},{id:'1000',type:'PROGRAM',tick:0,program:{programId:'1002',arguments:{}}}],
    }]};
    const before=JSON.stringify(document);
    expect(allocateAuthoringIds(document,'RUN',3)).toEqual(['1','2','3']);
    expect(allocateAuthoringIds(document,'DUTY')).toEqual(['1']);
    expect(JSON.stringify(document)).toBe(before);
  });
  it('increments the maximum numeric ID without rounding, including leading zeros',()=>{
    const document={...emptyAuthoringDocument(),runs:[run('1'),run('0007'),run('12'),run('R999'),run('3e4'),run('-99')]};
    expect(allocateAuthoringIds(document,'RUN',2)).toEqual(['13','14']);
    expect(allocateAuthoringIds({...document,runs:[run('9007199254740993')]},'RUN',3)).toEqual(['9007199254740994','9007199254740995','9007199254740996']);
    expect(allocateAuthoringIds({...document,runs:[run('9'.repeat(256))]},'RUN')).toEqual([`1${'0'.repeat(256)}`]);
  });
  it.each([
    ['duty run reference',(document:TimetableAuthoringDocumentV1)=>{document.duties=[{...duty('custom'),elements:[{id:'element',type:'RUN',runId:'6'}]}];}],
    ['arrival override',(document:TimetableAuthoringDocumentV1)=>{document.arrivalOverrides=[{runId:'6',callId:'missing-call',tick:10}];}],
    ['operational trip',(document:TimetableAuthoringDocumentV1)=>{document.operationalTrips=[{id:'trip',defaults:{},segments:[{runId:'6',serviceId:'custom',unitSlots:[1]}]}];}],
  ] as const)('reserves a dangling RUN identity in %s',(_,reserve)=>{
    const document=emptyAuthoringDocument();reserve(document);
    expect(allocateAuthoringIds(document,'RUN')).toEqual(['7']);
  });
  it.each([
    ['operational trip',(document:TimetableAuthoringDocumentV1)=>{document.operationalTrips=[{id:'trip',defaults:{},segments:[{runId:'custom',serviceId:'8',unitSlots:[1]}]}];}],
    ['split coordinator',(document:TimetableAuthoringDocumentV1)=>{document.formationEvents=[split('8','custom')];}],
    ['formation output',(document:TimetableAuthoringDocumentV1)=>{document.formationEvents=[split('custom','8')];}],
    ['couple participants',(document:TimetableAuthoringDocumentV1)=>{document.formationEvents=[{
      id:'event',kind:'COUPLE',tick:10,coordinatorDutyId:'custom',approachingDutyId:'7',stationaryDutyId:'8',stationaryEnd:'HEAD',
      rendezvous:{approachDestinationId:'a',stationaryDestinationId:'b'},maximumApproachSpeedMetersPerSecond:1,
      output:{id:'joined',disposition:{type:'HOLD'}},
    }];}],
  ] as const)('reserves a dangling DUTY identity in %s',(_,reserve)=>{
    const document=emptyAuthoringDocument();reserve(document);
    expect(allocateAuthoringIds(document,'DUTY')).toEqual(['9']);
  });
  it('creates a numeric run and duty without renaming saved custom IDs',()=>{
    const document={...emptyAuthoringDocument(),runs:[run('custom-run')],duties:[duty('custom-duty')]};
    const [runId]=allocateAuthoringIds(document,'RUN'),[dutyId]=allocateAuthoringIds(document,'DUTY');
    const created=createRunFromLine(runId,{id:'line',metadata:{name:'Line'},upStationIds:['a','b'],downStationIds:['b','a']},'UP',{stations:[]},(station)=>`call-${station}`);
    const next={...document,runs:[...document.runs,created],duties:[...document.duties,duty(dutyId)]};
    expect(next.runs.map((entry)=>entry.id)).toEqual(['custom-run','1']);
    expect(next.duties.map((entry)=>entry.id)).toEqual(['custom-duty','1']);
    expect(created.calls.map((call)=>call.id)).toEqual(['call-a','call-b']);
    expect(allocateAuthoringIds(next,'RUN')).toEqual(['2']);expect(allocateAuthoringIds(next,'DUTY')).toEqual(['2']);
  });
  it('allocates distinct IDs for a whole copy batch before one history update',()=>{
    const source:AuthoringRunV1={...run('custom-run'),calls:[{id:'source-a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'source-b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200}]};
    const document={...emptyAuthoringDocument(),runs:[source,run('3')]};
    const copies=allocateAuthoringIds(document,'RUN',3).map((id,index)=>copyRunShifted(source,id,1000*(index+1),(station)=>`copy-${id}-${station}`));
    const history=updateDocumentHistory(createDocumentHistory(document),{...document,runs:[...document.runs,...copies]});
    expect(copies.map((copy)=>copy.id)).toEqual(['4','5','6']);
    expect(copies.map((copy)=>copy.calls[0].departureTick)).toEqual([1100,2100,3100]);
    expect(new Set(copies.flatMap((copy)=>copy.calls.map((call)=>call.id))).size).toBe(6);
    expect(history.past).toHaveLength(1);expect(undoDocumentHistory(history).present).toBe(document);
    expect(redoDocumentHistory(undoDocumentHistory(history)).present).toBe(history.present);
    expect(history.present.runs[0]).toBe(source);
  });
});
