import { describe, expect, it } from 'vitest';
import { layoutGraph, snapTick } from './graph';
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
      id:'line', displayName:'Line', stationIds:['central','terminus'],
    });
    expect(layout.height).toBe(600);
    expect(layout.lanes.slice(0,2).map((lane) => lane.id)).toEqual(['central','terminus']);
  });

  it('snaps and clamps ticks', () => {
    expect(snapTick(31, 20)).toBe(40);
    expect(snapTick(-9, 20)).toBe(0);
  });
});
