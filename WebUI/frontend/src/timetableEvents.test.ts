import {describe,expect,it} from 'vitest';
import {layoutGraph} from './graph';
import {graphEventAtPointer,nextTimetableEventId} from './timetableEvents';
import type {SavedTimetableV1} from './types';

const value:SavedTimetableV1={id:'events',periodDays:1,services:[],diagramLines:[{id:'L',upStationIds:['A','B'],downStationIds:['B','A']}]};
const stations=new Set(['A','B']);

describe('graph event placement',()=>{
  it('roundtrips existing event coordinates after pan, axis zoom and rendered scaling',()=>{
    for(const width of [1000,1563])for(const rowHeight of [88,137.5]){
      const layout=layoutGraph(value,width,rowHeight,620,undefined,undefined,'L');
      const bounds={left:-347,top:169,width:width*.8,height:layout.height*.8};
      for(const tick of [0,1234,23999])for(const lane of layout.lanes){
        const pointer={x:bounds.left+layout.tickToX(tick)*.8,y:bounds.top+lane.y*.8};
        expect(graphEventAtPointer(layout,bounds,pointer,stations,24000)).toEqual({stationId:lane.id,tick});
      }
    }
  });
  it('chooses the nearest real lane and clamps rounding at the final tick',()=>{
    const layout=layoutGraph(value,1000,88,620,undefined,undefined,'L');
    const bounds={left:0,top:0,width:1000,height:layout.height};
    expect(graphEventAtPointer(layout,bounds,{x:layout.width-layout.right,y:layout.lanes[1].y-10},stations,24000)).toEqual({stationId:'B',tick:23999});
  });
  it('ignores labels, outside the sheet, hidden canvases, invalid coordinates and placeholder lanes',()=>{
    const layout=layoutGraph(value,1000,88,620,undefined,undefined,'L');
    const bounds={left:0,top:0,width:1000,height:layout.height};
    for(const pointer of [{x:100,y:54},{x:200,y:20},{x:1001,y:54},{x:200,y:621},{x:NaN,y:54}])expect(graphEventAtPointer(layout,bounds,pointer,stations,24000)).toBeNull();
    expect(graphEventAtPointer(layout,{...bounds,width:0},{x:200,y:54},stations,24000)).toBeNull();
    expect(graphEventAtPointer(layout,bounds,{x:200,y:54},new Set(),24000)).toBeNull();
  });
});

describe('numeric event identities',()=>{
  it('allocates the first available numeric identity without renumbering existing references',()=>{
    const events=[{id:'1'},{id:'3'},{id:'event-old'}];
    expect(nextTimetableEventId(events)).toBe('2');
    expect(nextTimetableEventId([...events,{id:'2'}])).toBe('4');
    expect(events.map((event)=>event.id)).toEqual(['1','3','event-old']);
    expect(nextTimetableEventId([])).toBe('1');
  });
});

import {deleteTimetableEvent} from './timetableEvents';
it('does not delete a time point replaced while its editor was open',()=>{
 const original={id:'1',stationId:'a',tick:200,label:'turn'};
 expect(()=>deleteTimetableEvent([{...original,tick:300}],original)).toThrow('Event changed');
 expect(()=>deleteTimetableEvent([],original)).toThrow('Event changed');
 const other={id:'2',stationId:'b',tick:400};
 expect(deleteTimetableEvent([original,other],original)).toEqual([other]);
});
