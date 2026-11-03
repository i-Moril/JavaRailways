import {describe,expect,it} from 'vitest';
import {copyTimetableEvents,deleteTimetableEvent,nextTimetableEventId,referencedTimetableEventIds,saveTimetableEvent,timetableEventUnchanged} from './timetableEvents';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory} from './documentHistory';
import type {StationStopProgram,TimetableEventPoint} from './types';

const original:TimetableEventPoint={id:'1',stationId:'a',tick:200,label:'turn'};
const other:TimetableEventPoint={id:'2',stationId:'b',tick:400};

describe('time point transactions',()=>{
  it('adds a validated time point as one undoable transaction, preserving existing points',()=>{
    const before=[original];
    const after=saveTimetableEvent(before,{id:'',stationId:'b',tick:23999,label:'Depot'},24000,['a','b']);
    expect(after).toEqual([original,{id:'2',stationId:'b',tick:23999,label:'Depot'}]);
    expect(before).toEqual([original]);
    const history=updateDocumentHistory(createDocumentHistory(before),after);
    expect(history.past).toHaveLength(1);
    expect(undoDocumentHistory(history).present).toEqual(before);
  });
  it('rejects invalid or out-of-period time and unknown stations without changing events',()=>{
    for(const tick of [-1,24000,Infinity,NaN,1.5,Number.MAX_SAFE_INTEGER+1]){
      expect(()=>saveTimetableEvent([original],{...original,tick},24000,['a'],original)).toThrow('Invalid event');
    }
    expect(()=>saveTimetableEvent([original],{...original,stationId:'unknown'},24000,['a'],original)).toThrow('Invalid event');
    expect(original.tick).toBe(200);
  });
  it('edits the original identity atomically and rejects every stale menu snapshot',()=>{
    const updated={...original,tick:300,label:'New mark'};
    expect(saveTimetableEvent([original,other],{...updated,id:'ignored'},24000,['a','b'],original)).toEqual([updated,other]);
    for(const changed of [{...original,tick:300},{...original,stationId:'b'},{...original,label:'changed'}]){
      expect(timetableEventUnchanged([changed],original)).toBe(false);
      expect(()=>deleteTimetableEvent([changed],original)).toThrow('Event changed');
      expect(()=>saveTimetableEvent([changed],updated,24000,['a','b'],original)).toThrow('Event changed');
    }
    expect(()=>deleteTimetableEvent([],original)).toThrow('Event changed');
    expect(()=>saveTimetableEvent([],updated,24000,['a','b'],original)).toThrow('Event changed');
    expect(deleteTimetableEvent([original,other],original)).toEqual([other]);
  });
});

describe('timed operation copies',()=>{
  const source:TimetableEventPoint={id:'1',stationId:'a',tick:200,label:'turn',duration:{type:'MANUAL',ticks:50},program:{programId:'p',arguments:{destination:'x'}}};
  it('creates fresh identities and cumulative shifts while preserving program data and the source event',()=>{
    const events=copyTimetableEvents([source],'1',300,3,24000,['2']);
    expect(events.map((event)=>[event.id,event.tick])).toEqual([['1',200],['3',500],['4',800],['5',1100]]);
    expect(events.slice(1)).toEqual(Array.from({length:3},(_,index)=>({...source,id:String(index+3),tick:500+300*index})));
    expect(events[1]).not.toBe(source);expect(events[1].program).not.toBe(source.program);expect(events[1].program!.arguments).not.toBe(source.program!.arguments);
    expect(source.tick).toBe(200);
  });
  it('rejects invalid intervals, counts, identities and period overflows without mutating the input',()=>{
    const events=[source];
    for(const [offset,count] of [[1.5,1],[0,0],[0,101]])expect(()=>copyTimetableEvents(events,'1',offset,count,24000)).toThrow(/Copy/);
    expect(()=>copyTimetableEvents(events,'missing',100,1,24000)).toThrow('Unknown timed event');
    expect(()=>copyTimetableEvents(events,'1',24_000,1,24_000)).toThrow('outside the timetable period');
    expect(()=>copyTimetableEvents(events,'1',-201,1,24_000)).toThrow('outside the timetable period');
    expect(events).toEqual([source]);
  });
});

describe('numeric event identities',()=>{
  it('allocates available numeric identities without changing existing references',()=>{
    const events=[{id:'1'},{id:'3'},{id:'event-old'}];
    expect(nextTimetableEventId(events)).toBe('2');
    expect(nextTimetableEventId([...events,{id:'2'}])).toBe('4');
    expect(nextTimetableEventId([])).toBe('1');
  });
  it('reserves dangling WAIT_EVENT references in nested station and bound programs',()=>{
    const wait={id:'wait',type:'WAIT_EVENT' as const,eventId:'1',offsetTicks:0};
    const program:StationStopProgram={stationId:'a',preparationLead:{value:0},arrival:{parameters:[],steps:[{id:'task',type:'ASYNC',taskId:'task',steps:[wait]}]},departurePreparation:{parameters:[],steps:[]}};
    const reserved=referencedTimetableEventIds({stationPrograms:[program],boundPrograms:[{programId:'lib',revision:1,contentIdentity:'id',definition:{parameters:[],steps:[{id:'branch',type:'BRANCH',typeId:'test:condition',version:1,parameters:{value:{}},thenSteps:[{...wait,eventId:'2'}],elseSteps:[{...wait,eventId:'3'}]}]}}]});
    expect(reserved).toEqual(['1','2','3']);
    expect(nextTimetableEventId([],reserved)).toBe('4');
    const after=saveTimetableEvent([],{id:'',stationId:'a',tick:0},24000,['a'],undefined,reserved);
    expect(after[0].id).toBe('4');
    expect(program.arrival.steps[0]).toMatchObject({steps:[{eventId:'1'}]});
  });
});
