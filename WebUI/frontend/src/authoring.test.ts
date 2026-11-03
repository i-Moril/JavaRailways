import { describe,expect,it } from 'vitest';
import {
  authoringMinimumDwell,authoringDocumentForSave,authoringGraphTimetable,authoringSelectorKey,copyRunShifted,createRunFromLine,deleteRunFromDocument,generateTimetableSource as generateWithLines,moveRunToDuty,
  authoringResetProfile,renameAuthoringRun,retainAuthoringRunSelection,resolveAuthoringRunSelection,lineSimulationRoute,moveRunFromCall,resetRunTimings,resizeRunRoute,shiftRunFromDeparture,validateAuthoringDocument as validateWithLines,type TimetableAuthoringDocumentV1,
} from './authoring';
import {createDocumentHistory,redoDocumentHistory,undoDocumentHistory,updateDocumentHistory} from './documentHistory';
import {layoutGraph} from './graph';
import type { LineSimulationResult,LineV1,WorldMetadata } from './types';

const infrastructureLines=[{"id": "line-1", "displayName": "line-1", "upStationIds": ["a", "b"], "downStationIds": ["b", "a"], "maximumSpeedMetersPerSecond": 20}];
const generateTimetableSource=(document:TimetableAuthoringDocumentV1)=>generateWithLines(document,infrastructureLines);
const validateAuthoringDocument=(document:TimetableAuthoringDocumentV1)=>validateWithLines(document,infrastructureLines);
const metadata:WorldMetadata={stations:[
  {stationId:'a',berthId:'1',destinationId:'a@1'},
  {stationId:'b',berthId:'1',destinationId:'b@1'},
  {stationId:'c',berthId:'1',destinationId:'c@1'},
  {stationId:'d',berthId:'1',destinationId:'d@1'},
]};
const line:LineV1={id:'line-1',displayName:'Line 1',upStationIds:['a','b','c','d'],downStationIds:['d','c','b','a']};

const valid=():TimetableAuthoringDocumentV1=>({
  formatVersion:1,id:'l1',periodDays:1,
  diagramLines:[{id:'line-1',upStationIds:['a','b'],downStationIds:['b','a']}],arrivalOverrides:[],
  runs:[
    {id:'up-01',type:'PASSENGER',lineId:'line-1',diagramLineId:'line-1',lineDirection:'UP',calls:[
      {id:'up-a',stationId:'a',berthId:'1',status:'ORIGIN',targetId:'a@1',departureTick:100},
      {id:'up-b',stationId:'b',berthId:'1',status:'TERMINAL',targetId:'b@1',arrivalTick:800},
    ]},
    {id:'down-01',type:'PASSENGER',lineId:'line-1',diagramLineId:'line-1',lineDirection:'DOWN',calls:[
      {id:'down-b',stationId:'b',berthId:'1',status:'ORIGIN',targetId:'b@1',departureTick:1000},
      {id:'down-a',stationId:'a',berthId:'2',status:'TERMINAL',targetId:'a@2',arrivalTick:1800,doorSide:'NONE'},
    ]},
  ],
  duties:[{id:'duty-a',selector:{type:'TRAIN_NAME',name:'train-1'},initialPosition:'a@1',elements:[
    {id:'up-element',type:'RUN',runId:'up-01'},
    {id:'down-element',type:'RUN',runId:'down-01'},
  ]}],
});

describe('timetable editor document',()=>{
  it('renames a run atomically with duty references and arrival overrides while retaining call identity',()=>{
    const document=valid();document.arrivalOverrides=[{runId:'up-01',callId:'up-b',tick:750},{runId:'down-01',callId:'down-a',tick:1790}];
    const renamed=renameAuthoringRun(document,'up-01','express-1');
    expect(renamed.runs[0].id).toBe('express-1');expect(renamed.runs[0].calls).toBe(document.runs[0].calls);expect(renamed.runs[1]).toBe(document.runs[1]);
    expect(renamed.duties[0].elements.find((element)=>element.id==='up-element')).toEqual({id:'up-element',type:'RUN',runId:'express-1'});
    expect(renamed.arrivalOverrides).toEqual([{runId:'express-1',callId:'up-b',tick:750},{runId:'down-01',callId:'down-a',tick:1790}]);
    expect(validateAuthoringDocument(renamed)).toEqual([]);expect(generateTimetableSource(renamed).source).toContain('express-1');
    expect(document.runs[0].id).toBe('up-01');
  });

  it('renames unassigned runs and rejects blank or duplicate IDs without mutation or history',()=>{
    const document={...valid(),duties:[]};const before=JSON.stringify(document);
    expect(renameAuthoringRun(document,'up-01','up-01')).toBe(document);
    expect(renameAuthoringRun(document,'up-01','unassigned').runs[0].id).toBe('unassigned');
    for(const id of ['','   ','down-01'])expect(()=>renameAuthoringRun(document,'up-01',id)).toThrow();
    expect(JSON.stringify(document)).toBe(before);
  });

  it('one rename undo and redo retain selection on the same unchanged calls',()=>{
    const document=valid(),renamed=renameAuthoringRun(document,'down-01','second-service');
    const history=updateDocumentHistory(createDocumentHistory(document),renamed);
    const undone=undoDocumentHistory(history),redone=redoDocumentHistory(undone);
    const oldSelection=resolveAuthoringRunSelection(undone.present.runs,'second-service',renamed.runs[1]);
    expect(oldSelection?.id).toBe('down-01');
    expect(resolveAuthoringRunSelection(redone.present.runs,'down-01',oldSelection)?.id).toBe('second-service');
    expect(undone.present).toBe(document);expect(redone.present).toBe(renamed);expect(history.past).toHaveLength(1);
    const ambiguous=[document.runs[1],{...document.runs[1],id:'other-owner'}];
    expect(resolveAuthoringRunSelection(ambiguous,'second-service',renamed.runs[1])).toBeUndefined();
    const duplicateCallIds={...document.runs[1],id:'other-owner',calls:document.runs[1].calls.map((call)=>({...call}))};
    expect(resolveAuthoringRunSelection([duplicateCallIds,document.runs[1]],'second-service',renamed.runs[1])?.id).toBe('down-01');
    const differentPrefix={...document.runs[1],id:'other-owner',calls:document.runs[1].calls.map((call)=>({...call,id:`${call.id}-different`}))};
    expect(resolveAuthoringRunSelection([differentPrefix,document.runs[1]],'second-service',renamed.runs[1])?.id).toBe('down-01');
  });

  it('keeps an explicit empty run focus instead of auto-selecting a scoped run',()=>{
    const document=valid();
    expect(retainAuthoringRunSelection(document.runs,'',undefined)).toBeUndefined();
    expect(retainAuthoringRunSelection(document.runs,'',document.runs[0])).toBeUndefined();
    expect(retainAuthoringRunSelection(document.runs,'up-01')).toMatchObject({id:'up-01'});
  });

  it('uses current line settings for generation and reset, ignoring stale document caps',()=>{
    const document={...valid(),lineSpeedLimits:[{lineId:'line-1',maximumSpeedMetersPerSecond:99}]};
    expect(authoringDocumentForSave(document)).not.toHaveProperty('lineSpeedLimits');
    const profile={profileType:'TRAIN_TEMPLATE' as const,profileId:'vehicle'};
    const changed=infrastructureLines.map((line)=>({...line,maximumSpeedMetersPerSecond:25}));
    expect(generateWithLines(document,changed).source).toContain('timetable.lineSpeedLimit("line-1", 25)');
    expect(generateWithLines(document,changed).source).not.toContain('99');
    expect(generateWithLines(document,changed).documentHashInput).not.toBe(generateWithLines(document,infrastructureLines).documentHashInput);
    expect(authoringResetProfile(changed,document.runs[0],profile).maximumSpeedMetersPerSecond).toBe(25);
    expect(()=>authoringResetProfile([],document.runs[0],profile)).toThrow('No maximum speed');
    expect(validateWithLines(document,[]).some((issue)=>issue.message.includes('No maximum speed'))).toBe(true);
    expect(()=>authoringResetProfile(changed,{...document.runs[0],lineId:'missing'},profile)).toThrow('No maximum speed');
  });

  it('requires every run to have one unique duty owner',()=>{
    const document=valid();
    document.duties.push({id:'duty-b',selector:{type:'TRAIN_NAME',name:'train-2'},elements:[
      {id:'duplicate',type:'RUN',runId:'up-01'},
    ]});
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Run up-01 is already assigned to duty duty-a');
    document.duties[0].elements=document.duties[0].elements.filter((item)=>item.type!=='RUN'||item.runId!=='down-01');
    expect(validateAuthoringDocument(document).map((item)=>item.message)).toContain('Run down-01 is not assigned to a duty');
  });

  it('deletes a run and all duty and arrival-override references atomically',()=>{
    const document=valid();
    document.arrivalOverrides=[{runId:'up-01',callId:'up-b',tick:700}];
    const deleted=deleteRunFromDocument(document,'up-01');
    expect(deleted.runs.map((run)=>run.id)).toEqual(['down-01']);
    expect(deleted.duties.flatMap((duty)=>duty.elements).some((element)=>element.type==='RUN'&&element.runId==='up-01')).toBe(false);
    expect(deleted.arrivalOverrides).toEqual([]);
  });

  it('moves a run between duties atomically while preserving its element identity',()=>{
    const document=valid();
    document.duties.push({id:'duty-b',selector:{type:'TRAIN_NAME',name:'train-2'},elements:[]});
    const moved=moveRunToDuty(document,'up-01','duty-b','unused-new-id');

    expect(moved.duties[0].elements.some((item)=>item.type==='RUN'&&item.runId==='up-01')).toBe(false);
    expect(moved.duties[1].elements).toContainEqual({id:'up-element',type:'RUN',runId:'up-01'});
    expect(validateAuthoringDocument(moved).some((item)=>item.message.includes('already assigned'))).toBe(false);
  });

  it('requires named train selection for initial placement',()=>{
    const document=valid();document.duties[0].selector={type:'TAKE_FROM_WAYPOINT',wayPointIds:['a@1'],tick:0};
    expect(validateAuthoringDocument(document).map((item)=>item.message)).toContain('Initial placement requires a named-train selector');
  });

  it('deterministically generates one service per duty with origin trace scopes',()=>{
    const first=generateTimetableSource(valid());
    const second=generateTimetableSource(valid());
    expect(first.issues).toEqual([]);
    expect(first.source).toBe(second.source);
    expect(first.source).toContain('timetable.setInitialPosition(timetable.named("train-1"), timetable.named("a@1"))');
    expect(first.source).toContain('timetable.service("duty-a", "train-1", { diagramLineId: "line-1", lineId: "line-1" }, t => {');
    expect(first.source).toContain('t.origin("duty:duty-a/element:up-element/run:up-01", scoped => {');
    expect(first.source).toContain('runScope.at(100).destination("b@1", JSON.parse(');
    expect(first.source).toContain('runScope.at(1000).destination("a@2", JSON.parse(');
  });

  it('generates infrastructure line context and its all-train maximum speed separately',()=>{
    const document=valid();
    document.runs=document.runs.map((run)=>({...run,lineId:'line-1'}));

    const result=generateTimetableSource(document);

    expect(result.issues).toEqual([]);
    expect(result.source).toContain('timetable.lineSpeedLimit("line-1", 20)');
    expect(result.source).toContain('{ diagramLineId: "line-1", lineId: "line-1" }');
  });

  it('uses manual arrival overrides without changing runtime departure ticks',()=>{
    const document=valid();
    document.arrivalOverrides=[{runId:'up-01',callId:'up-b',tick:760}];
    const result=generateTimetableSource(document);
    expect(result.source).toContain('runScope.at(100).destination("b@1", JSON.parse(');
  });

  it('rejects stale duplicate and out-of-period arrival overrides',()=>{
    const document=valid();
    document.arrivalOverrides=[
      {runId:'missing',callId:'no-call',tick:24_000},
      {runId:'up-01',callId:'up-b',tick:700},
      {runId:'up-01',callId:'up-b',tick:710},
    ];
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Unknown run: missing');
    expect(messages).toContain('Arrival override tick must be inside the timetable period');
    expect(messages).toContain('Duplicate arrival override for up-01/up-b');
  });

  it('allows duties spanning multiple diagram lines with per-run presentation data',()=>{
    const document=valid();
    document.diagramLines.push({id:'line-2',upStationIds:['a','b'],downStationIds:['b','a']});
    document.runs[1].diagramLineId='line-2';
    expect(validateAuthoringDocument(document)).toEqual([]);
    expect(generateTimetableSource(document).source.match(/scoped.runSegment/g)).toHaveLength(2);
  });

  it('validates function targets and stable IDs',()=>{
    const document=valid();
    document.duties[0].initialPosition='';
    document.runs[0].calls[1].id=document.runs[0].calls[0].id;
    const messages=validateAuthoringDocument(document).map((item)=>item.message);
    expect(messages).toContain('Initial placement waypoint is required');
    expect(messages).toContain(`Duplicate call ID: ${document.runs[0].calls[0].id}`);
  });

  it('uses one stable identity for equivalent selector tag orderings',()=>{
    expect(authoringSelectorKey({type:'TAKE_FROM_DEPOT',depotId:'north',tick:10,tags:['z','a']}))
      .toBe(authoringSelectorKey({type:'TAKE_FROM_DEPOT',depotId:'north',tick:20,tags:['a','z']}));
  });

  it('generates sorted tag selectors for depot acquisition',()=>{
    const document=valid();
    document.duties[0].selector={type:'TAKE_FROM_DEPOT',depotId:'north',tick:20,tags:['z','metro']};
    delete document.duties[0].initialPosition;
    const result=generateTimetableSource(document);
    expect(result.issues).toEqual([]);
    expect(result.source).toContain('timetable.takeFromDepot("north", { tags: ["metro", "z"] }, 20)');
  });

  it('creates a complete route table from a line and keeps endpoints synchronized when resized',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    expect(run.calls.map((call)=>[call.stationId,call.status,call.targetId])).toEqual([
      ['a','ORIGIN','a@1'],['b','STOP','b@1'],['c','STOP','c@1'],['d','TERMINAL','d@1'],
    ]);
    run.calls[1].status='PASS';
    run.calls[1].arrivalTick=200;
    const shortened=resizeRunRoute(run,line,'UP','b','d',metadata,(station)=>`new-${station}`);
    expect(shortened.calls.map((call)=>[call.stationId,call.status])).toEqual([
      ['b','ORIGIN'],['c','STOP'],['d','TERMINAL'],
    ]);
    expect(shortened.calls[0].id).toBe('call-b');
    expect(shortened.calls[0].arrivalTick).toBeNull();
  });

  it('sends every selected berth destination to route simulation',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    run.calls[1]={...run.calls[1],berthId:'express',targetId:'b@express',status:'PASS'};
    expect(lineSimulationRoute(run)).toEqual({
      originStationId:'a',terminalStationId:'d',stopStationIds:['c','d'],
      destinationIds:['a@1','b@express','c@1','d@1'],viaIds:[[],[],[],[]],originDepartureMode:'AUTO',
    });
    run.originDepartureMode='REVERSE';
    expect(lineSimulationRoute(run).originDepartureMode).toBe('REVERSE');
  });

  it('resets all route times from simulated adjacent intervals and fixed stop dwell',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    run.calls[1].status='PASS';
    const simulation:LineSimulationResult={lineRevision:4,totalTravelSeconds:60,legs:[
      {fromStationId:'a',toStationId:'b',lengthMeters:100,travelSeconds:10,peakSpeedKmh:30,stoppingErrorMeters:0},
      {fromStationId:'b',toStationId:'c',lengthMeters:100,travelSeconds:20,peakSpeedKmh:40,stoppingErrorMeters:0},
      {fromStationId:'c',toStationId:'d',lengthMeters:100,travelSeconds:30,peakSpeedKmh:40,stoppingErrorMeters:0},
    ]};
    const reset=resetRunTimings(run,simulation,1_000,600);
    expect(reset.calls.map((call)=>({station:call.stationId,arrival:call.arrivalTick,departure:call.departureTick}))).toEqual([
      {station:'a',arrival:null,departure:1_000},
      {station:'b',arrival:1_200,departure:null},
      {station:'c',arrival:1_600,departure:2_200},
      {station:'d',arrival:2_800,departure:null},
    ]);
  });

  it('shifts only the selected departure and all later route times',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    run.calls[0].departureTick=100;
    run.calls[1]={...run.calls[1],arrivalTick:300,departureTick:400};
    run.calls[2]={...run.calls[2],arrivalTick:600,departureTick:700};
    run.calls[3]={...run.calls[3],arrivalTick:900};
    const shifted=shiftRunFromDeparture(run,run.calls[1].id,500,24_000,50);
    expect(shifted.deltaTicks).toBe(100);
    expect(shifted.run.calls.map((call)=>[call.arrivalTick,call.departureTick])).toEqual([
      [null,100],[300,500],[700,800],[1_000,null],
    ]);
    expect(run.calls[1].departureTick).toBe(400);
    expect(()=>shiftRunFromDeparture(run,run.calls[1].id,320,24_000,50)).toThrow(/minimum dwell/);
    expect(()=>shiftRunFromDeparture(run,run.calls[1].id,24_000,24_000)).toThrow(/period/);
  });

  it('moves a whole run by an offset or to an arrival time',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    run.calls[0].departureTick=100;
    run.calls[1]={...run.calls[1],arrivalTick:300,departureTick:400};
    run.calls[2]={...run.calls[2],arrivalTick:600};
    const offset=moveRunFromCall(run,run.calls[1].id,'DEPARTURE','OFFSET',40,24_000);
    expect(offset.deltaTicks).toBe(40);
    expect(offset.affectedTimeCount).toBe(4);
    expect(offset.run.calls.slice(0,3).map((call)=>[call.arrivalTick,call.departureTick])).toEqual([
      [null,140],[340,440],[640,null],
    ]);

    const absolute=moveRunFromCall(run,run.calls[1].id,'ARRIVAL','ABSOLUTE',500,24_000,50);
    expect(absolute.deltaTicks).toBe(200);
    expect(absolute.run.calls[0].departureTick).toBe(300);
    expect(absolute.run.calls[1].arrivalTick).toBe(500);
  });

  it('rejects a missing move timestamp and period overflow',()=>{
    const run=createRunFromLine('run-1',line,'UP',metadata,(station)=>`call-${station}`);
    run.calls[0].departureTick=100;
    run.calls[1]={...run.calls[1],arrivalTick:300,departureTick:400};
    expect(()=>moveRunFromCall(run,run.calls[0].id,'ARRIVAL','OFFSET',10,24_000)).toThrow(/arrival/);
    expect(()=>moveRunFromCall(run,run.calls[1].id,'DEPARTURE','OFFSET',23_700,24_000)).toThrow(/period/);
    expect(()=>moveRunFromCall(run,run.calls[1].id,'DEPARTURE','ABSOLUTE',-1,24_000)).toThrow(/non-negative/);
  });

  it('copies a run with fresh identities and shifts every route time without assigning a duty',()=>{
    const source=createRunFromLine('run-1',line,'UP',metadata,(station)=>`source-${station}`);
    const reset=resetRunTimings(source,{lineRevision:1,totalTravelSeconds:30,legs:[
      {fromStationId:'a',toStationId:'b',lengthMeters:1,travelSeconds:10,peakSpeedKmh:1,stoppingErrorMeters:0},
      {fromStationId:'b',toStationId:'c',lengthMeters:1,travelSeconds:10,peakSpeedKmh:1,stoppingErrorMeters:0},
      {fromStationId:'c',toStationId:'d',lengthMeters:1,travelSeconds:10,peakSpeedKmh:1,stoppingErrorMeters:0},
    ]},100,20);
    const copy=copyRunShifted(reset,'run-2',200,(station)=>`copy-${station}`);
    expect(copy.id).toBe('run-2');
    expect(copy.calls.map((call)=>call.id)).toEqual(['copy-a','copy-b','copy-c','copy-d']);
    expect(copy.calls[0].departureTick).toBe(300);
    expect(copy.calls[1].arrivalTick).toBe(500);
  });

  it('shows a newly created untimed run on the graph with presentation-only fallback times',()=>{
    const run=createRunFromLine('draft',line,'UP',metadata,(station)=>`draft-${station}`);
    const document=valid();
    document.runs=[run];
    document.diagramLines=[{id:line.id,upStationIds:line.upStationIds,downStationIds:line.downStationIds}];
    const graph=authoringGraphTimetable(document);
    const layout=layoutGraph(graph,1_000,58,170,metadata,line,line.id);
    expect(layout.paths).toHaveLength(3);
    expect(layout.paths.flatMap((path)=>path.points).every((point)=>point.tick>=0)).toBe(true);
    expect(run.calls.every((call)=>call.arrivalTick==null&&call.departureTick==null)).toBe(true);
  });

  it('builds a live graph service per run from exact route-table times',()=>{
    const run=createRunFromLine('express',line,'UP',metadata,(station)=>`express-${station}`);
    run.calls[0].departureTick=100;
    run.calls[1].status='PASS';
    run.calls[1].arrivalTick=200;
    run.calls[2].arrivalTick=300;
    run.calls[2].departureTick=400;
    run.calls[3].arrivalTick=500;
    const document=valid();
    document.runs=[run];
    document.diagramLines=[{id:line.id,upStationIds:line.upStationIds,downStationIds:line.downStationIds}];
    const graph=authoringGraphTimetable(document);
    expect(graph.services).toHaveLength(1);
    expect(graph.services[0].id).toBe('express');
    expect(graph.services[0].steps).toHaveLength(7);
    expect(graph.services[0].steps[3].action?.arrivalTick).toBe(300);
  });

  it('compiles pass rows into one routed destination chain',()=>{
    const document=valid();
    const run=createRunFromLine('express',line,'UP',metadata,(station)=>`express-${station}`);
    document.diagramLines=[{id:line.id,upStationIds:line.upStationIds,downStationIds:line.downStationIds}];
    run.calls[0].departureTick=100;
    run.calls[1].status='PASS';
    run.calls[1].arrivalTick=200;
    run.calls[2].status='STOP';
    run.calls[2].arrivalTick=300;
    run.calls[2].departureTick=400;
    run.calls[3].arrivalTick=500;
    document.runs=[run];
    document.duties[0].elements=[];
    document.duties[0].elements.push({id:'express-element',type:'RUN',runId:'express'});
    const result=generateTimetableSource(document);
    expect(result.issues).toEqual([]);
    expect(result.source).toContain('runScope.at(100).pass("b@1").destination("c@1", JSON.parse(');
    expect(result.source).toContain('runScope.at(400).destination("d@1", JSON.parse(');
  });
});


describe('run diagram color',()=>{
  it('preserves colors through save, copy and undo without altering generated execution',()=>{
    const original=valid(),colored={...original,runs:original.runs.map((run,index)=>index===0?{...run,color:'#12aBcD'}:run)};
    expect(validateAuthoringDocument(colored)).toEqual(validateAuthoringDocument(original));
    const saved=JSON.parse(JSON.stringify(authoringDocumentForSave(colored)));
    expect(saved.runs[0].color).toBe('#12aBcD');expect(saved.runs[1]).not.toHaveProperty('color');
    expect(copyRunShifted(saved.runs[0],'copy',100).color).toBe('#12aBcD');
    const history=updateDocumentHistory(createDocumentHistory(original),colored);
    expect(undoDocumentHistory(history).present.runs[0]).not.toHaveProperty('color');
    expect(redoDocumentHistory(undoDocumentHistory(history)).present.runs[0].color).toBe('#12aBcD');
    expect(generateTimetableSource(colored)).toEqual(generateTimetableSource(original));
    expect(authoringGraphTimetable(colored)).toEqual(authoringGraphTimetable(original));
  });
  it('validates only explicitly supplied six-digit hexadecimal colors',()=>{
    for(const color of ['red','#abc','#12345678','url(javascript:bad)','']){
      const document=valid();document.runs[0].color=color;
      expect(validateAuthoringDocument(document).some(issue=>issue.path==='runs[0].color')).toBe(true);
    }
  });
});
describe('timetable-wide minimum dwell',()=>{
  it('defaults missing minimum to zero and preserves the current document field on save',()=>{
    const document=valid();
    expect(authoringMinimumDwell(document)).toBe(0);
    expect(authoringDocumentForSave(document).formatVersion).toBe(1);
    document.minimumDwellTicks=80;
    expect(authoringDocumentForSave(document).minimumDwellTicks).toBe(80);
    expect(authoringMinimumDwell(document)).toBe(80);
    expect(generateTimetableSource(document).issues).toEqual([]);
  });

  it.each([-1,0.5,Number.MAX_SAFE_INTEGER+1,NaN,Infinity,null,'20'])('rejects invalid global minimum %s',(minimum)=>{
    const document={...valid(),minimumDwellTicks:minimum} as TimetableAuthoringDocumentV1;
    expect(validateAuthoringDocument(document).some((issue)=>issue.path==='minimumDwellTicks')).toBe(true);
    expect(generateTimetableSource(document).source).toBe('');
  });

  it('uses effective arrivals and the same minimum for every STOP while PASS needs no dwell',()=>{
    const document=valid();document.minimumDwellTicks=40;
    document.diagramLines=[{id:'line-1',upStationIds:['a','b','c','d','e'],downStationIds:['e','d','c','b','a']}];
    document.runs=[{id:'run',type:'PASSENGER',lineId:'line-1',diagramLineId:'line-1',lineDirection:'UP',calls:[
      {id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},
      {id:'b',stationId:'b',targetId:'b',status:'STOP',arrivalTick:200,departureTick:239},
      {id:'c',stationId:'c',targetId:'c',status:'PASS',arrivalTick:300},
      {id:'d',stationId:'d',targetId:'d',status:'STOP',arrivalTick:400,departureTick:439},
      {id:'e',stationId:'e',targetId:'e',status:'TERMINAL',arrivalTick:600},
    ]}];
    document.duties=[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'run',type:'RUN',runId:'run'}]}];
    expect(validateAuthoringDocument(document).filter((issue)=>issue.message.includes('minimum dwell')).map((issue)=>issue.path)).toEqual(['runs[0].calls[1]','runs[0].calls[3]']);
    document.runs[0].calls[1].departureTick=240;document.runs[0].calls[3].departureTick=440;
    expect(validateAuthoringDocument(document)).toEqual([]);
    document.arrivalOverrides=[{runId:'run',callId:'b',tick:210}];
    expect(validateAuthoringDocument(document).some((issue)=>issue.path==='runs[0].calls[1]')).toBe(true);
  });

  it('generates the global minimum on every station destination and origin, never on passes',()=>{
    const document=valid();document.minimumDwellTicks=40;
    document.diagramLines=[{id:'line-1',upStationIds:['a','b','c','d'],downStationIds:['d','c','b','a']}];
    document.runs=[{id:'run',type:'PASSENGER',lineId:'line-1',diagramLineId:'line-1',lineDirection:'UP',calls:[
      {id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},
      {id:'b',stationId:'b',targetId:'b',status:'STOP',arrivalTick:200,departureTick:240},
      {id:'c',stationId:'c',targetId:'c',status:'PASS',arrivalTick:300},
      {id:'d',stationId:'d',targetId:'d',status:'TERMINAL',arrivalTick:400},
    ]}];
    document.duties=[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'run',type:'RUN',runId:'run'}]}];
    const result=generateTimetableSource(document);expect(result.issues).toEqual([]);
    const origins:Record<string,unknown>[]=[],destinations:Record<string,unknown>[]=[],passes:string[]=[];
    const timed={pass:(id:string)=>{passes.push(id);return timed;},destination:(_:string,options:Record<string,unknown>)=>{destinations.push(options);return timed;}};
    const scope={origin:(_:string,callback:(value:unknown)=>void)=>callback(scope),runSegment:(_:unknown,callback:(value:unknown)=>void)=>callback(scope),check:()=>{},beginStationStop:(_:string,options:Record<string,unknown>)=>{origins.push(options);return scope;},at:()=>timed};
    const host={setPeriodDays:()=>{},setInitialPosition:()=>{},lineSpeedLimit:()=>{},diagramLine:()=>{},named:(id:string)=>id,service:(_:unknown,__:unknown,options:unknown,callback?:(value:unknown)=>void)=>(callback??options as (value:unknown)=>void)(scope)};
    new Function('timetable',`${result.source}\nmain(timetable)`)(host);
    expect(origins.map((options)=>options.minimumDwellTicks)).toEqual([40]);
    expect(destinations.map((options)=>options.minimumDwellTicks)).toEqual([40,40]);
    expect(passes).toEqual(['c']);
    expect(document.runs[0].calls.every((call)=>!Object.hasOwn(call,'minimumDwellTicks'))).toBe(true);
  });

  it('enforces the explicit document minimum for shift, copy and whole-run moves',()=>{
    const run=createRunFromLine('run',line,'UP',metadata);
    run.calls[0].departureTick=100;
    run.calls[1]={...run.calls[1],arrivalTick:200,departureTick:230};
    const minimum=authoringMinimumDwell({minimumDwellTicks:40});
    expect(()=>shiftRunFromDeparture(run,run.calls[1].id,239,24000,minimum)).toThrow('minimum dwell');
    expect(shiftRunFromDeparture(run,run.calls[1].id,240,24000,minimum).run.calls[1].departureTick).toBe(240);
    expect(()=>copyRunShifted(run,'copy',100,undefined,minimum)).toThrow('minimum dwell');
    expect(()=>moveRunFromCall(run,run.calls[0].id,'DEPARTURE','OFFSET',100,24000,minimum)).toThrow('minimum dwell');
    run.calls[1].departureTick=240;
    expect(copyRunShifted(run,'copy',100,undefined,minimum).calls[1].departureTick).toBe(340);
    expect(moveRunFromCall(run,run.calls[0].id,'DEPARTURE','OFFSET',100,24000,minimum).run.calls[1].departureTick).toBe(340);
  });
});

it('writes only current call fields while retaining the document minimum',()=>{
 const document=valid();document.minimumDwellTicks=40;
 Object.assign(document.runs[0].calls[1],{minimumDwellTicks:1});
 const saved=authoringDocumentForSave(document);
 expect(saved.minimumDwellTicks).toBe(40);
 expect(saved.runs[0].calls[1]).not.toHaveProperty('minimumDwellTicks');
 const generated=generateTimetableSource(document);
 expect(generated.issues).toEqual([]);
 const runSegments:string[]=[];
 for(const match of generated.source.matchAll(/runSegment\(JSON\.parse\(("(?:[^"\\]|\\.)*")\)/g))runSegments.push(JSON.parse(match[1]));
 expect(runSegments).toHaveLength(2);
 for(const segment of runSegments)for(const call of JSON.parse(segment).calls)expect(call).not.toHaveProperty('minimumDwellTicks');
});
