import {describe,expect,it} from 'vitest';
import {authoringLineSimulationRequest,emptyAuthoringDocument,resetRunTimings,type TimetableAuthoringDocumentV1} from './authoring';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory} from './documentHistory';
import {defaultStationStopProgram} from './stationPlans';
import type {LineSimulationResult,LineV1} from './types';

const lines:LineV1[]=[{id:'line',displayName:'Line',maximumSpeedMetersPerSecond:20,upStationIds:['a','b','c','d'],downStationIds:['d','c','b','a']}];
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'timetable',minimumDwellTicks:40,
  diagramLines:[{id:'diagram',upStationIds:['a','b','c','d'],downStationIds:['d','c','b','a']}],
  runs:[{id:'run',type:'PASSENGER',lineId:'line',diagramLineId:'diagram',lineDirection:'UP',originDepartureMode:'REVERSE',calls:[
    {id:'a-call',stationId:'a',targetId:'a@1',status:'ORIGIN',departureTick:100,stoppingOffsetMetersBefore:3},
    {id:'b-call',stationId:'b',targetId:'b@2',status:'STOP',arrivalTick:null,departureTick:null,viaIds:['junction'],stoppingOffsetMetersBefore:2,doorSide:'LEFT'},
    {id:'c-call',stationId:'c',targetId:'c@1',status:'PASS'},
    {id:'d-call',stationId:'d',targetId:'d@1',status:'TERMINAL'},
  ]}],duties:[{id:'real-duty',selector:{type:'TRAIN_NAME',name:'live-train'},elements:[{id:'before',type:'PROGRAM',tick:0,program:{programId:'missing',arguments:{}}},{id:'run-task',type:'RUN',runId:'run'}]}],
});
const profile={profileType:'TRAIN_TEMPLATE' as const,profileId:'EMU'};
const simulation=():LineSimulationResult=>({lineRevision:4,totalTravelSeconds:15.45,calls:[
  {callId:'a-call',kind:'ORIGIN',departureTick:100},
  {callId:'b-call',kind:'STOP',arrivalTick:201,departureTick:241},
  {callId:'c-call',kind:'PASS',arrivalTick:344},
  {callId:'d-call',kind:'TERMINAL',arrivalTick:449},
],legs:[
  {fromStationId:'a',toStationId:'b',lengthMeters:100,travelSeconds:5.05,peakSpeedKmh:30,stoppingErrorMeters:0.1},
  {fromStationId:'b',toStationId:'c',lengthMeters:100,travelSeconds:5.15,peakSpeedKmh:40,stoppingErrorMeters:0},
  {fromStationId:'c',toStationId:'d',lengthMeters:100,travelSeconds:5.25,peakSpeedKmh:30,stoppingErrorMeters:0.2},
]});

describe('real line measurement request',()=>{
  it('uses only the selected run and normal generator, retaining exact context and absolute waits',()=>{
    const value=document(),station=defaultStationStopProgram('b');
    station.arrival.steps.unshift({id:'event',type:'WAIT_EVENT',eventId:'event',offsetTicks:5});
    value.stationPrograms=[station];value.events=[{id:'event',tick:250,program:{programId:'unrelated-missing',arguments:{}}}];
    value.arrivalOverrides=[{runId:'run',callId:'b-call',tick:900}];
    const before=JSON.stringify(value),request=authoringLineSimulationRequest(value,lines,value.runs[0],profile,60);
    expect(request).toMatchObject({firstDepartureTick:100,fixedDwellTicks:60,minimumDwellTicks:40,originDepartureMode:'REVERSE',maximumSpeedMetersPerSecond:20});
    expect(request).not.toHaveProperty('timetableId');
    expect(request.calls[0]).toMatchObject({callId:'a-call',destinationId:'a@1',stoppingOffsetMetersBefore:3});
    expect(request.calls[1]).toMatchObject({callId:'b-call',kind:'STOP',viaIds:['junction'],stoppingOffsetMetersBefore:2});
    const origins:Record<string,unknown>[]=[],destinations:Record<string,unknown>[]=[],services:string[]=[];
    const timed={pass:()=>timed,destination:(_:string,options:Record<string,unknown>)=>{destinations.push(options);return timed;}};
    const scope={origin:(_:string,callback:(value:unknown)=>void)=>callback(scope),runSegment:(_:unknown,callback:(value:unknown)=>void)=>callback(scope),check:()=>{},beginStationStop:(_:string,options:Record<string,unknown>)=>{origins.push(options);},at:()=>timed};
    const host={setPeriodDays:()=>{},setInitialPosition:()=>{},lineSpeedLimit:()=>{},diagramLine:()=>{},named:(id:string)=>id,service:(id:string,_:unknown,__:unknown,callback:(value:unknown)=>void)=>{services.push(id);callback(scope);}};
    new Function('timetable',`${request.measurementSource}\nmain(timetable)`)(host);
    expect(services).toEqual(['real-duty']);
    expect(destinations[0]).toMatchObject({arrivalTick:100,departureTick:100,minimumDwellTicks:0,doorSide:'LEFT',stoppingOffsetMetersBefore:2,plannedContext:{dutyId:'real-duty',runId:'run',runType:'PASSENGER',lineDirection:'UP'},stationProgram:{arrival:{steps:expect.arrayContaining([{id:'event',type:'WAIT_UNTIL',tick:{value:255}}])}}});
    expect(origins[0]).toMatchObject({departureTick:100,minimumDwellTicks:0});
    expect(destinations[1]).toMatchObject({callRole:'TERMINAL',minimumDwellTicks:0});
    expect(request.measurementSource).not.toContain('unrelated-missing');
    expect(request.measurementSource).not.toContain('live-train');
    expect(JSON.stringify(value)).toBe(before);
  });
  it('supports unassigned runs and refuses insufficient fixed dwell without changing the document',()=>{
    const value=document();value.duties=[];
    const before=JSON.stringify(value);
    expect(()=>authoringLineSimulationRequest(value,lines,value.runs[0],profile,39)).toThrow('b is 39 ticks');
    expect(JSON.stringify(value)).toBe(before);
    expect(authoringLineSimulationRequest(value,lines,value.runs[0],profile,40).measurementSource).toContain('line-simulation');
    value.runs[0].type='DEADHEAD';
    expect(authoringLineSimulationRequest(value,lines,value.runs[0],profile,0)).toMatchObject({fixedDwellTicks:0,minimumDwellTicks:0});
    value.runs[0].type='PASSENGER';value.runs[0].calls[1].status='PASS';
    expect(authoringLineSimulationRequest(value,lines,value.runs[0],profile,0).fixedDwellTicks).toBe(0);
  });
});

describe('authoritative ordered Reset timings',()=>{
  it('uses integer ticks directly even if display seconds would round differently, in one undoable transaction',()=>{
    const value=document(),result=simulation();result.legs[0].travelSeconds=999.9;
    const next=resetRunTimings(value.runs[0],result,100,40);
    expect(next.calls.map((call)=>[call.arrivalTick,call.departureTick])).toEqual([[null,100],[201,241],[344,null],[449,null]]);
    const history=updateDocumentHistory(createDocumentHistory(value),{...value,runs:[next]});
    expect(history.past).toHaveLength(1);expect(undoDocumentHistory(history).present).toBe(value);
  });
  it.each(['missing','partial','swapped','fractional','changed-origin','stretched-dwell','pass-departure','backwards'] as const)('rejects %s result without a document or history mutation',(kind)=>{
    const value=document(),result=simulation(),before=JSON.stringify(value),history=createDocumentHistory(value);
    if(kind==='missing')delete (result as Partial<LineSimulationResult>).calls;
    if(kind==='partial')result.calls.pop();
    if(kind==='swapped')[result.calls[1],result.calls[2]]=[result.calls[2],result.calls[1]];
    if(kind==='fractional')result.calls[1].arrivalTick=200.5;
    if(kind==='changed-origin')result.calls[0].departureTick=101;
    if(kind==='stretched-dwell')result.calls[1].departureTick=242;
    if(kind==='pass-departure')result.calls[2].departureTick=344;
    if(kind==='backwards')result.calls[3].arrivalTick=343;
    expect(()=>resetRunTimings(value.runs[0],result,100,40)).toThrow();
    expect(JSON.stringify(value)).toBe(before);expect(history.present).toBe(value);expect(history.past).toEqual([]);
  });
  it('keeps an explicit terminal departure, refusing to silently move it before the measured arrival',()=>{
    const value=document(),run=value.runs[0];run.calls[3].departureTick=450;
    expect(resetRunTimings(run,simulation(),100,40).calls[3].departureTick).toBe(450);
    run.calls[3].departureTick=448;
    expect(()=>resetRunTimings(run,simulation(),100,40)).toThrow('explicit terminal departure');
  });
  it('matches repeated stations by ordered call identity, not by a station-id map',()=>{
    const value=document(),run=value.runs[0],result=simulation();run.calls[2].stationId='b';
    result.legs[1].toStationId='b';result.legs[2].fromStationId='b';
    expect(resetRunTimings(run,result,100,40).calls[2].arrivalTick).toBe(344);
  });
});
