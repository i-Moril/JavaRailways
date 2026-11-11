import { describe, expect, it } from 'vitest';
import { filterTimetableForDiagramLine, graphServiceColor, graphServiceColors, layoutGraph } from './graph';
import type { SavedTimetableV1 } from './types';

const value: SavedTimetableV1 = {
  id: 't',
  periodDays: 1,
  services: [
    {
      id: 'a',
      selectorType: 'TRAIN_ID',
      trainId: 'train-a',
      activation: { type: 'SCHEDULED' },
      steps: [
        { type: 'CHECK', wayPointId: 'alpha@1' },
        { type: 'AT', tick: 200, action: { type: 'STATION_DESTINATION_OPTIONS', stationId:'beta', berthId:'1', arrivalTick:800, wayPointIds: [], requiredTags: [], requiredCapabilities: [], stablingPointIds: [], thenSteps: [], elseSteps: [] } },
        { type: 'AT', tick: 200, action: { type: 'DESTINATION', wayPointIds: ['beta@1'], requiredTags: [], requiredCapabilities: [], stablingPointIds: [], thenSteps: [], elseSteps: [] } },
      ],
    },
  ],
};

describe('graph layout', () => {
  it('uses light-theme service colors with graphical contrast',()=>{
    const luminance=(hex:string)=>{const channels=[1,3,5].map((offset)=>parseInt(hex.slice(offset,offset+2),16)/255).map((value)=>value<=.04045?value/12.92:((value+.055)/1.055)**2.4);return .2126*channels[0]+.7152*channels[1]+.0722*channels[2];};
    const background=luminance('#f7fafc');
    expect(graphServiceColors('light').every((color)=>(background+.05)/(luminance(color)+.05)>=3)).toBe(true);
  });
  it('maps time to X and operational locations to Y', () => {
    const layout = layoutGraph(value, 1000,58,170,{stations:[
      {stationId:'alpha',berthId:'1',destinationId:'alpha@1'},
      {stationId:'beta',berthId:'1',destinationId:'beta@1'},
    ]});
    expect(layout.lanes.map((lane) => lane.id)).toEqual(['alpha', 'beta']);
    expect(layout.points.map((point)=>[point.location,point.tick,point.kind,point.stepIndex])).toEqual([
      ['alpha',200,'DEPARTURE',2],['beta',800,'ARRIVAL',1],
    ]);
    expect(layout.points[0].y).toBe(layout.lanes[0].y);
    expect(layout.xToTick(layout.tickToX(80))).toBeCloseTo(80);
  });

  it('uses line station order and resolves berth destinations to station lanes', () => {
    const layout = layoutGraph(value, 1000, 58, 600, {
      stations:[
        { stationId:'alpha', berthId:'one', destinationId:'alpha@1' },
        { stationId:'central', berthId:'one', destinationId:'beta@1' },
      ],
    }, {
      id:'line', displayName:'Line', upStationIds:['central','terminus'], downStationIds:['terminus','central'],
    });
    expect(layout.height).toBe(600);
    expect(layout.lanes.slice(0,2).map((lane) => lane.id)).toEqual(['central','terminus']);
  });

  it('omits depot and ordinary-destination movements from station graphs', () => {
    const nonStation:SavedTimetableV1={...value,services:[{...value.services[0],steps:[
      {type:'CHECK',wayPointId:'alpha@1'},
      {type:'AT',tick:100,action:{type:'DESTINATION_OPTIONS',arrivalTick:300,wayPointIds:['siding'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
      {type:'AT',tick:100,action:{type:'DESTINATION',wayPointIds:['siding'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
      {type:'AT',tick:400,action:{type:'STORE_AT_DEPOT',depotId:'depot',wayPointIds:[],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
    ]}]};
    const layout=layoutGraph(nonStation,1000,58,170,{stations:[{stationId:'alpha',berthId:'1',destinationId:'alpha@1'}]});
    expect(layout.paths).toEqual([]);
    expect(layout.points).toEqual([]);
  });

  it('filters script rendering to the selected diagram line', () => {
    const multi:SavedTimetableV1={...value,services:[
      {...value.services[0],id:'l1',diagramLineId:'line-1'},
      {...value.services[0],id:'l2',diagramLineId:'line-2'},
    ]};
    expect(filterTimetableForDiagramLine(multi,'line-2').services.map((service)=>service.id)).toEqual(['l2']);
  });

  it('uses script diagram stations and visual arrival ticks without adding platform lanes', () => {
    const visual:SavedTimetableV1={
      ...value,
      diagramLines:[{id:'line',upStationIds:['alpha','central'],downStationIds:['central','alpha']}],
      services:[{...value.services[0],diagramLineId:'line',steps:[
        {type:'CHECK',wayPointId:'alpha@1'},
        {type:'AT',tick:200,action:{type:'STATION_DESTINATION_OPTIONS',stationId:'central',berthId:'2',arrivalTick:900,wayPointIds:[],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
        {type:'AT',tick:200,action:{type:'DESTINATION',wayPointIds:['central@2'],requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenSteps:[],elseSteps:[]}},
      ]}],
    };
    const layout=layoutGraph(visual,1000,58,170,{stations:[
      {stationId:'alpha',berthId:'1',destinationId:'alpha@1'},
      {stationId:'central',berthId:'2',destinationId:'central@2'},
    ]},undefined,'line');
    expect(layout.lanes.map((lane)=>lane.id)).toEqual(['alpha','central']);
    expect(layout.paths[0].points.map((point)=>[point.tick,point.location])).toEqual([
      [200,'alpha'],[900,'central'],
    ]);
  });

});


it('resolves run, line and automatic colors consistently for both themes and palette indices',()=>{
  for(const theme of ['light','dark'] as const)for(const index of [0,1,5,6,10]){
    expect(graphServiceColor(theme,String(index),Array.from({length:11},(_,i)=>String(i)))).toBe(graphServiceColors(theme)[index%graphServiceColors(theme).length]);
    expect(graphServiceColor(theme,String(index),Array.from({length:11},(_,i)=>String(i)),'#123456')).toBe('#123456');
    expect(graphServiceColor(theme,String(index),Array.from({length:11},(_,i)=>String(i)),'#123456','#abcdef')).toBe('#abcdef');
  }
});
