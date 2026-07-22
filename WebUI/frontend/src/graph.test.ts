import { describe, expect, it } from 'vitest';
import { graphScreenXToTick, graphServiceColors, layoutGraph, snapTick, zoomGraphViewport } from './graph';
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
        { type: 'CHECK', wayPointId: 'alpha' },
        { type: 'AT', tick: 200, action: { type: 'DESTINATION', wayPointIds: ['beta'], requiredTags: [], requiredCapabilities: [], stablingPointIds: [], thenActions: [], elseActions: [] } },
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
    const layout = layoutGraph(value, 1000);
    expect(layout.lanes.map((lane) => lane.id)).toEqual(['alpha', 'beta']);
    expect(layout.points[0].location).toBe('alpha');
    expect(layout.points[0].y).toBe(layout.lanes[0].y);
    expect(layout.xToTick(layout.tickToX(80))).toBeCloseTo(80);
  });

  it('uses line station order and resolves berth destinations to station lanes', () => {
    const layout = layoutGraph(value, 1000, 58, 600, {
      stations:[{ stationId:'central', berthId:'one', destinationId:'beta' }],
    }, {
      id:'line', displayName:'Line', upStationIds:['central','terminus'], downStationIds:['terminus','central'],
    });
    expect(layout.height).toBe(600);
    expect(layout.lanes.slice(0,2).map((lane) => lane.id)).toEqual(['central','terminus']);
  });

  it('zooms each axis independently around its pointer anchor', () => {
    const initial={scaleX:1,scaleY:1,offsetX:0,offsetY:0};
    const horizontal=zoomGraphViewport(initial,'x',2,400,150);
    expect(horizontal).toMatchObject({scaleX:2,scaleY:1,offsetX:-250,offsetY:0});
    const vertical=zoomGraphViewport(initial,'y',2,300,54);
    expect(vertical).toMatchObject({scaleX:1,scaleY:2,offsetX:0,offsetY:-246});
  });

  it('maps dragged points through the inverse horizontal viewport', () => {
    const layout=layoutGraph(value,1000);
    const viewport=zoomGraphViewport({scaleX:1,scaleY:1,offsetX:0,offsetY:0},'x',2,layout.left,layout.left);
    const logical=layout.tickToX(200);
    const screen=layout.left+(logical-layout.left)*viewport.scaleX+viewport.offsetX;
    expect(graphScreenXToTick(layout,viewport,screen)).toBeCloseTo(200);
  });

  it('snaps and clamps ticks', () => {
    expect(snapTick(31, 20)).toBe(40);
    expect(snapTick(-9, 20)).toBe(0);
  });
});
