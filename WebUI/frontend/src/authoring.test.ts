import { describe,expect,it } from 'vitest';
import {
  authoringGraphTimetable,authoringSelectorKey,copyRunShifted,createRunFromLine,deleteRunFromDocument,generateTimetableSource,moveRunToDuty,
  lineSimulationRoute,resetRunTimings,resizeRunRoute,updateRunGraphTick,validateAuthoringDocument,type TimetableAuthoringDocumentV1,
} from './authoring';
import {layoutGraph} from './graph';
import type { LineSimulationResult,LineV1,WorldMetadata } from './types';

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
    {id:'up-01',type:'PASSENGER',diagramLineId:'line-1',lineDirection:'UP',calls:[
      {id:'up-a',stationId:'a',berthId:'1',status:'ORIGIN',targetId:'a@1',departureTick:100},
      {id:'up-b',stationId:'b',berthId:'1',status:'TERMINAL',targetId:'b@1',arrivalTick:800},
    ]},
    {id:'down-01',type:'PASSENGER',diagramLineId:'line-1',lineDirection:'DOWN',calls:[
      {id:'down-b',stationId:'b',berthId:'1',status:'ORIGIN',targetId:'b@1',departureTick:1000},
      {id:'down-a',stationId:'a',berthId:'2',status:'TERMINAL',targetId:'a@2',arrivalTick:1800,doorSide:'NONE'},
    ]},
  ],
  duties:[{id:'duty-a',selector:{type:'TRAIN_NAME',name:'train-1'},elements:[
    {id:'initial',type:'FUNCTION',call:{id:'initial-call',type:'PLACE_INITIAL',trainName:'train-1',wayPointId:'a@1'}},
    {id:'up-element',type:'RUN',runId:'up-01'},
    {id:'reverse',type:'FUNCTION',call:{id:'reverse-call',type:'REVERSE',tick:900}},
    {id:'down-element',type:'RUN',runId:'down-01'},
  ]}],
});

describe('timetable editor document',()=>{
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

  it('requires initial placement at the matching named duty start',()=>{
    const document=valid();
    const initial=document.duties[0].elements.shift()!;
    document.duties[0].elements.push(initial);
    expect(validateAuthoringDocument(document).map((item)=>item.message)).toContain('Initial placement must be the first duty element');
  });

  it('deterministically generates one service per duty with origin trace scopes',()=>{
    const first=generateTimetableSource(valid());
    const second=generateTimetableSource(valid());
    expect(first.issues).toEqual([]);
    expect(first.source).toBe(second.source);
    expect(first.source).toContain('timetable.setInitialPosition(timetable.named("train-1"), timetable.named("a@1"))');
    expect(first.source).toContain('timetable.service("duty-a", "train-1", { diagramLineId: "line-1" }, t => {');
    expect(first.source).toContain('t.origin("duty:duty-a/element:up-element/run:up-01", scoped => {');
    expect(first.source).toContain('scoped.at(100).destination("b@1", { arrivalTick: 800 })');
    expect(first.source).toContain('scoped.at(1000).destination("a@2", { arrivalTick: 1800, doorSide: "NONE" })');
  });

  it('uses manual arrival overrides without changing runtime departure ticks',()=>{
    const document=valid();
    document.arrivalOverrides=[{runId:'up-01',callId:'up-b',tick:760}];
    const result=generateTimetableSource(document);
    expect(result.source).toContain('scoped.at(100).destination("b@1", { arrivalTick: 760 })');
  });

  it('rejects duty functions that violate element order or cross the period',()=>{
    const document=valid();
    document.duties[0].elements.splice(2,0,{id:'store-early',type:'FUNCTION',call:{id:'store-call',type:'STORE_AT_DEPOT',tick:850,depotId:'north',stablingPointIds:[]}});
    document.duties[0].elements.push({id:'wait-late',type:'FUNCTION',call:{id:'wait-call',type:'WAIT',tick:23_990,durationTicks:20}});
    const messages=validateAuthoringDocument(document).map((item)=>item.message);

    expect(messages).toContain('Store at depot must be the last duty element');
    expect(messages).toContain('Wait crosses the timetable period');
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

  it('rejects duties spanning multiple diagram lines instead of dropping presentation data',()=>{
    const document=valid();
    document.diagramLines.push({id:'line-2',upStationIds:['a','b'],downStationIds:['b','a']});
    document.runs[1].diagramLineId='line-2';
    expect(validateAuthoringDocument(document).map((item)=>item.message))
      .toContain('A duty cannot contain runs from multiple diagram lines');
  });

  it('validates function targets and stable IDs',()=>{
    const document=valid();
    const initial=document.duties[0].elements[0];
    if(initial.type!=='FUNCTION'||initial.call.type!=='PLACE_INITIAL') throw new Error('invalid fixture');
    initial.call.wayPointId='';
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
    document.duties[0].elements.shift();
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
      destinationIds:['a@1','b@express','c@1','d@1'],originDepartureMode:'AUTO',
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
    expect(reset.calls.map((call)=>({station:call.stationId,arrival:call.arrivalTick,departure:call.departureTick,dwell:call.minimumDwellTicks}))).toEqual([
      {station:'a',arrival:null,departure:1_000,dwell:null},
      {station:'b',arrival:1_200,departure:null,dwell:null},
      {station:'c',arrival:1_600,departure:2_200,dwell:600},
      {station:'d',arrival:2_800,departure:null,dwell:null},
    ]);
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

  it('builds a live graph service per run and maps dragged points back to route cells',()=>{
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
    expect(updateRunGraphTick(run,4,'DEPARTURE',240).calls[1].arrivalTick).toBe(240);
    expect(updateRunGraphTick(run,3,'ARRIVAL',320).calls[2].arrivalTick).toBe(320);
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
    document.duties[0].elements=document.duties[0].elements.filter((element)=>element.type==='FUNCTION'&&element.call.type==='PLACE_INITIAL');
    document.duties[0].elements.push({id:'express-element',type:'RUN',runId:'express'});
    const result=generateTimetableSource(document);
    expect(result.issues).toEqual([]);
    expect(result.source).toContain('scoped.at(100).pass("b@1").destination("c@1", { arrivalTick: 300');
    expect(result.source).toContain('scoped.at(400).destination("d@1", { arrivalTick: 500');
  });
});
