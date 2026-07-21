import { describe, expect, it } from 'vitest';
import { assignLineToService, availableStationStatuses, serviceStationRows, updateStationStatus } from './schedule';
import type { LineV1, TimetableServiceV1, WorldMetadata } from './types';

const metadata:WorldMetadata = { stations:[
  { stationId:'alpha', berthId:'1', destinationId:'alpha@1', stationDisplayName:'Alpha' },
  { stationId:'beta', berthId:'1', destinationId:'beta@1', stationDisplayName:'Beta' },
  { stationId:'gamma', berthId:'1', destinationId:'gamma@1', stationDisplayName:'Gamma' },
] };
const line:LineV1 = { id:'line', displayName:'Line', stationIds:['alpha','beta','gamma'] };
const service:TimetableServiceV1 = {
  id:'s', selectorType:'TRAIN_ID', trainId:'train', activation:{type:'SCHEDULED'}, lineId:'line',
  steps:[
    { type:'CHECK', wayPointId:'alpha@1' },
    { type:'AT', tick:100, action:{ type:'DESTINATION', wayPointIds:['beta@1','gamma@1'], requiredTags:[], requiredCapabilities:[], stablingPointIds:[], thenActions:[], elseActions:[] } },
    { type:'AT', tick:200, action:{ type:'REVERSE', wayPointIds:[], requiredTags:[], requiredCapabilities:[], stablingPointIds:[], thenActions:[], elseActions:[] } },
  ],
};

describe('station schedule projection', () => {
  it('initializes an empty service from the selected line', () => {
    const empty={...service,steps:[{type:'CHECK' as const,wayPointId:''},{...service.steps[1],action:{...service.steps[1].action!,wayPointIds:['']}}]};
    const assigned=assignLineToService(empty,line,metadata);
    expect(assigned.lineId).toBe('line');
    expect(assigned.steps[0].wayPointId).toBe('alpha@1');
    expect(assigned.steps[1].action?.wayPointIds).toEqual(['beta@1','gamma@1']);
  });

  it('derives origin pass terminal times and exact action indexes', () => {
    const rows=serviceStationRows(service,metadata,line);
    expect(rows.map((row)=>row.status)).toEqual(['ORIGIN','PASS','TERMINAL']);
    expect(rows[0]).toMatchObject({ departureTick:100, departureStepIndex:1 });
    expect(rows[2].actionStepIndices).toEqual([2]);
  });

  it('splits a pass into a stop without losing unrelated actions', () => {
    const changed=updateStationStatus(service,'beta','STOP',metadata);
    expect(changed.steps.map((step)=>step.action?.type || step.type)).toEqual(['CHECK','DESTINATION','DESTINATION','REVERSE']);
    expect(changed.steps[1].action?.wayPointIds).toEqual(['beta@1']);
    expect(changed.steps[2].action?.wayPointIds).toEqual(['gamma@1']);
    expect(changed.steps[3]).toBe(service.steps[2]);
  });

  it('merges adjacent stops into a pass but refuses to cross an ordered action', () => {
    const stopped=updateStationStatus(service,'beta','STOP',metadata);
    expect(updateStationStatus(stopped,'beta','PASS',metadata).steps[1].action?.wayPointIds).toEqual(['beta@1','gamma@1']);
    const currentOptions={...stopped.steps[1],action:{...stopped.steps[1].action!,type:'DESTINATION_OPTIONS' as const,wayPointIds:['beta@1']}};
    const withCurrentOptions={...stopped,steps:[stopped.steps[0],currentOptions,...stopped.steps.slice(1)]};
    expect(updateStationStatus(withCurrentOptions,'beta','PASS',metadata).steps.map((step)=>step.action?.type || step.type)).toEqual(['CHECK','DESTINATION','REVERSE']);
    const nextOptions={...currentOptions,tick:200,action:{...currentOptions.action!,wayPointIds:['gamma@1']}};
    const withNextOptions={...stopped,steps:[...stopped.steps.slice(0,2),nextOptions,...stopped.steps.slice(2)]};
    const merged=updateStationStatus(withNextOptions,'beta','PASS',metadata);
    expect(merged.steps.map((step)=>step.action?.type || step.type)).toEqual(['CHECK','DESTINATION_OPTIONS','DESTINATION','REVERSE']);
    expect(merged.steps[1]).toMatchObject({tick:100,action:{wayPointIds:['gamma@1']}});
    const withAction={...stopped,steps:[...stopped.steps.slice(0,2),service.steps[2],...stopped.steps.slice(2)]};
    expect(updateStationStatus(withAction,'beta','PASS',metadata)).toBe(withAction);
  });

  it('offers only status transitions that can be applied without reordering actions', () => {
    const rows=serviceStationRows(service,metadata,line);
    expect(availableStationStatuses(service,rows[0],metadata)).toEqual(['ORIGIN']);
    expect(availableStationStatuses(service,rows[1],metadata)).toEqual(['PASS','STOP','TERMINAL']);
    expect(availableStationStatuses(service,rows[2],metadata)).toEqual(['TERMINAL']);
    const withLaterMovement={...service,steps:[...service.steps,{...service.steps[1],tick:300,action:{...service.steps[1].action!,wayPointIds:['alpha@1']}}]};
    expect(availableStationStatuses(withLaterMovement,serviceStationRows(withLaterMovement,metadata,line)[1],metadata)).toEqual(['PASS','STOP']);
    const stopped=updateStationStatus(service,'beta','STOP',metadata);
    expect(availableStationStatuses(stopped,serviceStationRows(stopped,metadata,line)[1],metadata)).toEqual(['STOP','PASS']);
  });
});
