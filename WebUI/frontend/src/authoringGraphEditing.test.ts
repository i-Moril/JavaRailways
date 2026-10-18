import { describe,expect,it } from 'vitest';
import { editAuthoringGraphTime,shiftAuthoringGraphSuffix } from './authoringGraphEditing';
import { emptyAuthoringDocument,type TimetableAuthoringDocumentV1 } from './authoring';
import { createDocumentHistory,undoDocumentHistory,updateDocumentHistory } from './documentHistory';
const draft=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),runs:[{id:'r',type:'PASSENGER',lineDirection:'DOWN',calls:[
  {id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},
  {id:'b',stationId:'b',targetId:'b',status:'STOP',arrivalTick:200,departureTick:300,minimumDwellTicks:40},
  {id:'c',stationId:'c',targetId:'c',status:'STOP',arrivalTick:400,departureTick:500,minimumDwellTicks:50},
  {id:'d',stationId:'d',targetId:'d',status:'TERMINAL',arrivalTick:600},
]}]});
const arrival={kind:'ARRIVAL',stepIndex:1} as const;
const departure={kind:'DEPARTURE',stepIndex:4} as const;
const times=(document:TimetableAuthoringDocumentV1)=>document.runs[0].calls.map((call)=>[call.arrivalTick,call.departureTick]);

describe('graph event suffix shifts',()=>{
  it('shifts the arrival event, its departure and all later events without moving earlier calls',()=>{
    const document=draft(),before=JSON.stringify(document);
    const shifted=shiftAuthoringGraphSuffix(document,'r',arrival,'OFFSET',50);
    expect(shifted).toMatchObject({delta:50,anchorTick:200,targetTick:250,callId:'b',kind:'ARRIVAL',affectedEvents:5});
    expect(times(shifted.document)).toEqual([[undefined,100],[250,350],[450,550],[650,undefined]]);
    expect(shifted.document.runs[0].calls[0]).toBe(document.runs[0].calls[0]);
    expect(JSON.stringify(document)).toBe(before);
  });
  it('shifts departure and later events while preserving its own arrival',()=>{
    const document=draft();
    const shifted=shiftAuthoringGraphSuffix(document,'r',departure,'ABSOLUTE',350);
    expect(shifted).toMatchObject({delta:50,anchorTick:300,targetTick:350,kind:'DEPARTURE',affectedEvents:4});
    expect(times(shifted.document)).toEqual([[undefined,100],[200,350],[450,550],[650,undefined]]);
    expect(shiftAuthoringGraphSuffix(document,'r',departure,'OFFSET',50)).toEqual(shifted);
  });
  it('anchors from effective overrides and materializes only affected arrivals once',()=>{
    const document=draft();document.arrivalOverrides=[{runId:'r',callId:'b',tick:230},{runId:'r',callId:'c',tick:420}];
    const shifted=shiftAuthoringGraphSuffix(document,'r',{kind:'ARRIVAL',stepIndex:3},'ABSOLUTE',450);
    expect(shifted.delta).toBe(30);expect(shifted.anchorTick).toBe(420);
    expect(times(shifted.document)).toEqual([[undefined,100],[200,300],[450,530],[630,undefined]]);
    expect(shifted.document.arrivalOverrides).toEqual([{runId:'r',callId:'b',tick:230}]);
    const again=shiftAuthoringGraphSuffix(shifted.document,'r',{kind:'ARRIVAL',stepIndex:3},'OFFSET',10);
    expect(again.anchorTick).toBe(450);expect(again.document.runs[0].calls[2].arrivalTick).toBe(460);
  });
  it('preserves the anchor arrival override during a departure shift',()=>{
    const document=draft();document.arrivalOverrides=[{runId:'r',callId:'b',tick:240}];
    const shifted=shiftAuthoringGraphSuffix(document,'r',departure,'OFFSET',20);
    expect(shifted.document.arrivalOverrides).toEqual(document.arrivalOverrides);
    expect(shifted.document.runs[0].calls[1].arrivalTick).toBe(200);
    expect(shifted.document.runs[0].calls[1].departureTick).toBe(320);
  });
  it('treats PASS arrival and projected departure as the same single event',()=>{
    const document=draft();document.runs[0].calls[1]={...document.runs[0].calls[1],status:'PASS',departureTick:null,minimumDwellTicks:null};
    const fromArrival=shiftAuthoringGraphSuffix(document,'r',arrival,'OFFSET',20);
    const fromDeparture=shiftAuthoringGraphSuffix(document,'r',departure,'OFFSET',20);
    expect(fromArrival).toEqual(fromDeparture);expect(fromArrival.kind).toBe('ARRIVAL');expect(fromArrival.affectedEvents).toBe(4);
    expect(fromArrival.document.runs[0].calls[1]).toMatchObject({arrivalTick:220,departureTick:null});
  });
  it('uses an override-only arrival as the actual anchor without inventing a base tick',()=>{
    const document=draft();document.runs[0].calls[1].arrivalTick=null;document.arrivalOverrides=[{runId:'r',callId:'b',tick:210}];
    const shifted=shiftAuthoringGraphSuffix(document,'r',arrival,'ABSOLUTE',250);
    expect(shifted.anchorTick).toBe(210);expect(shifted.document.runs[0].calls[1].arrivalTick).toBe(250);
    expect(shifted.document.arrivalOverrides).toEqual([]);
  });
  it.each([
    [arrival,'OFFSET',-101,'chronological'],
    [departure,'ABSOLUTE',239,'minimum dwell'],
    [arrival,'OFFSET',23_400,'period'],
    [arrival,'ABSOLUTE',-1,'period'],
    [arrival,'OFFSET',1.5,'safe integer'],
    [arrival,'OFFSET',Number.MAX_SAFE_INTEGER,'safe integer'],
  ] as const)('rejects illegal shifts atomically (%j %s %s)',(point,mode,value,message)=>{
    const document=draft(),before=JSON.stringify(document),history=createDocumentHistory(document);
    expect(()=>shiftAuthoringGraphSuffix(document,'r',point,mode,value)).toThrow(message);
    expect(JSON.stringify(document)).toBe(before);expect(history.past).toEqual([]);expect(history.present).toBe(document);
  });
  it('rejects departure shifts against effective arrival minimum dwell',()=>{
    const document=draft();document.arrivalOverrides=[{runId:'r',callId:'b',tick:280}];
    expect(()=>shiftAuthoringGraphSuffix(document,'r',departure,'OFFSET',0)).toThrow('minimum dwell');
  });
  it('maps origin departure and terminal arrival to the complete run and final event respectively',()=>{
    const document=draft();
    const all=shiftAuthoringGraphSuffix(document,'r',{kind:'DEPARTURE',stepIndex:2},'OFFSET',10);
    expect(all.affectedEvents).toBe(6);expect(times(all.document)).toEqual([[undefined,110],[210,310],[410,510],[610,undefined]]);
    const last=shiftAuthoringGraphSuffix(document,'r',{kind:'ARRIVAL',stepIndex:5},'ABSOLUTE',620);
    expect(last.affectedEvents).toBe(1);expect(times(last.document)).toEqual([[undefined,100],[200,300],[400,500],[620,undefined]]);
    expect(()=>shiftAuthoringGraphSuffix(document,'r',{kind:'ARRIVAL',stepIndex:5},'ABSOLUTE',499)).toThrow('chronological');
  });
  it('rejects a missing original event instead of using the graph fallback',()=>{
    const document=draft();document.runs[0].calls[1].arrivalTick=null;
    expect(()=>shiftAuthoringGraphSuffix(document,'r',arrival,'ABSOLUTE',250)).toThrow('no time');
    document.runs[0].calls[1].departureTick=null;
    expect(()=>shiftAuthoringGraphSuffix(document,'r',departure,'OFFSET',10)).toThrow('no time');
    expect(()=>shiftAuthoringGraphSuffix(document,'r',{kind:'ARRIVAL',stepIndex:2},'OFFSET',10)).toThrow('Unknown graph point');
  });
  it('preserves downstream travel/dwell durations and creates exactly one undo entry',()=>{
    const document=draft(),shifted=shiftAuthoringGraphSuffix(document,'r',arrival,'OFFSET',50);
    const history=updateDocumentHistory(createDocumentHistory(document),shifted.document);
    expect(history.past).toHaveLength(1);expect(undoDocumentHistory(history).present).toBe(document);
    const calls=shifted.document.runs[0].calls;
    expect(calls[2].departureTick!-calls[2].arrivalTick!).toBe(100);
    expect(calls[3].arrivalTick!-calls[2].departureTick!).toBe(100);
    const unchanged=shiftAuthoringGraphSuffix(document,'r',arrival,'OFFSET',0);
    expect(unchanged.document).toBe(document);expect(updateDocumentHistory(createDocumentHistory(document),unchanged.document).past).toHaveLength(0);
  });
});


describe('explicit single-node toolbar commits',()=>{
  it('retains arrival overrides and changes only the selected event',()=>{
    const document=draft(),next=editAuthoringGraphTime(document,'r',arrival,230);
    expect(next.runs).toBe(document.runs);
    expect(next.arrivalOverrides).toEqual([{runId:'r',callId:'b',tick:230}]);
    const departureEdit=editAuthoringGraphTime(next,'r',departure,320);
    expect(departureEdit.runs[0].calls[1].departureTick).toBe(320);
    expect(departureEdit.runs[0].calls[2]).toBe(document.runs[0].calls[2]);
    expect(departureEdit.arrivalOverrides).toEqual(next.arrivalOverrides);
    expect(editAuthoringGraphTime(next,'r',arrival,230)).toBe(next);
  });
  it('enforces period and effective-arrival minimum dwell without mutating on failure',()=>{
    const document=draft();document.arrivalOverrides=[{runId:'r',callId:'b',tick:250}];
    const before=JSON.stringify(document);
    expect(()=>editAuthoringGraphTime(document,'r',departure,280)).toThrow('minimum dwell');
    expect(()=>editAuthoringGraphTime(document,'r',arrival,270)).toThrow('minimum dwell');
    expect(()=>editAuthoringGraphTime(document,'r',arrival,24_000)).toThrow('period');
    expect(JSON.stringify(document)).toBe(before);
  });
  it('edits the PASS departure projection as the canonical arrival',()=>{
    const document=draft();document.runs[0].calls[1]={...document.runs[0].calls[1],status:'PASS',departureTick:null,minimumDwellTicks:null};
    const next=editAuthoringGraphTime(document,'r',departure,260);
    expect(next.runs).toBe(document.runs);expect(next.arrivalOverrides).toEqual([{runId:'r',callId:'b',tick:260}]);
  });
});
