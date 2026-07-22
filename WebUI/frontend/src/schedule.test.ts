import { describe, expect, it } from 'vitest';
import { emptyAction } from './editor';
import {
  addRowAction, assignLineToService, canInitializeServiceRows, compileServiceRows, createLineRows, deleteRowAction,
  deleteServiceRow, insertServiceRow, lineStations, moveServiceRow, updateGeneratedDeparture, updateServiceRow,
} from './schedule';
import type { LineV1, TimetableServiceV1, WorldMetadata } from './types';

const metadata:WorldMetadata={stations:[
  {stationId:'alpha',berthId:'1',destinationId:'alpha@1',stationDisplayName:'Alpha'},
  {stationId:'beta',berthId:'1',destinationId:'beta@1',stationDisplayName:'Beta'},
  {stationId:'beta',berthId:'2',destinationId:'beta@2',stationDisplayName:'Beta'},
  {stationId:'gamma',berthId:'1',destinationId:'gamma@1',stationDisplayName:'Gamma'},
]};
const line:LineV1={id:'line',displayName:'Line',upStationIds:['alpha','beta','gamma'],downStationIds:['gamma','alpha']};
const service:TimetableServiceV1={id:'s',selectorType:'TRAIN_ID',trainId:'train',activation:{type:'SCHEDULED'},steps:[]};

describe('row-backed service schedule',()=>{
  it('does not silently convert a non-empty legacy schedule',()=>{
    const legacy={...service,steps:[{type:'AT' as const,tick:20,action:emptyAction('REVERSE')}]};
    expect(canInitializeServiceRows(legacy)).toBe(false);
    expect(canInitializeServiceRows({...service,steps:[{type:'CHECK',wayPointId:''},{type:'AT',tick:0,action:{...emptyAction('DESTINATION'),wayPointIds:['']}}]})).toBe(true);
  });

  it('keeps independently ordered UP and DOWN patterns',()=>{
    expect(lineStations(line,'UP')).toEqual(['alpha','beta','gamma']);
    expect(lineStations(line,'DOWN')).toEqual(['gamma','alpha']);
    expect(createLineRows(line,'DOWN',metadata).map((row)=>row.stationId)).toEqual(['gamma','alpha']);
  });

  it('generates destination movement from row order without an editable destination action',()=>{
    const assigned=assignLineToService(service,line,'UP',metadata);
    expect(assigned.lineDirection).toBe('UP');
    expect(assigned.rows?.flatMap((row)=>row.actions)).toEqual([]);
    expect(assigned.steps.map((step)=>step.action?.type||step.type)).toEqual(['CHECK','STATION_DESTINATION_OPTIONS','DESTINATION']);
    expect(assigned.steps.at(-1)?.action?.wayPointIds).toEqual(['beta@1','gamma@1']);
  });

  it('omits disabled stations and uses the selected berth for stops',()=>{
    let assigned=assignLineToService(service,line,'UP',metadata);
    const beta=assigned.rows![1];
    assigned=updateServiceRow(assigned,beta.id,{status:'DISABLED'},metadata);
    expect(assigned.steps.at(-1)?.action?.wayPointIds).toEqual(['gamma@1']);
    assigned=updateServiceRow(assigned,beta.id,{status:'STOP',berthId:'2',departureTick:200},metadata);
    expect(assigned.steps.filter((step)=>step.action?.type==='DESTINATION').map((step)=>step.action?.wayPointIds))
      .toEqual([['beta@2'],['gamma@1']]);
  });

  it('inserts moves and deletes each supported custom row type',()=>{
    let assigned=assignLineToService(service,line,'UP',metadata);
    assigned=insertServiceRow(assigned,1,'SPECIAL_DESTINATION',metadata);
    assigned=insertServiceRow(assigned,2,'DEPOT_OPERATION',metadata);
    assigned=insertServiceRow(assigned,3,'ACTION_ONLY',metadata);
    expect(assigned.rows?.map((row)=>row.type)).toEqual(['DEPOT_OPERATION','STATION','SPECIAL_DESTINATION','ACTION_ONLY','STATION','STATION']);
    const custom=assigned.rows![2];
    assigned=moveServiceRow(assigned,custom.id,1,metadata);
    expect(assigned.rows?.[3].id).toBe(custom.id);
    assigned=deleteServiceRow(assigned,custom.id,metadata);
    expect(assigned.rows?.some((row)=>row.id===custom.id)).toBe(false);
  });

  it('enforces one take first at zero and one matching store last',()=>{
    let assigned=assignLineToService(service,line,'UP',metadata);
    assigned=updateServiceRow(assigned,assigned.rows![0].id,{departureTick:100},metadata);
    assigned=insertServiceRow(assigned,2,'DEPOT_OPERATION',metadata);
    const take=assigned.rows![0];
    expect(take).toMatchObject({depotOperation:'TAKE_FROM_DEPOT',tick:0});
    assigned=updateServiceRow(assigned,take.id,{depotId:'north',tick:90},metadata);
    assigned=insertServiceRow(assigned,2,'DEPOT_OPERATION',metadata);
    expect(assigned.rows?.at(-1)).toMatchObject({depotOperation:'STORE_AT_DEPOT',depotId:'north',tick:100});
    const beforeAction=assigned;
    assigned=addRowAction(assigned,assigned.rows!.at(-1)!.id,emptyAction('REVERSE'),metadata);
    expect(assigned).toBe(beforeAction);
    expect(assigned.steps.at(-1)).toMatchObject({tick:100,action:{type:'STORE_AT_DEPOT'}});
    const count=assigned.rows?.length;
    assigned=insertServiceRow(assigned,2,'DEPOT_OPERATION',metadata);
    expect(assigned.rows).toHaveLength(count!);
    expect(moveServiceRow(assigned,take.id,1,metadata)).toBe(assigned);
    assigned=deleteServiceRow(assigned,take.id,metadata);
    expect(assigned.rows?.some((row)=>row.type==='DEPOT_OPERATION')).toBe(false);
  });

  it('writes graph node time dragging back to the generating row',()=>{
    let assigned=assignLineToService(service,line,'UP',metadata);
    const movementIndex=assigned.steps.findIndex((step)=>step.action?.type==='DESTINATION');
    assigned=updateGeneratedDeparture(assigned,movementIndex,480,metadata);
    expect(assigned.rows?.[0].departureTick).toBe(480);
    expect(assigned.steps[movementIndex].tick).toBe(480);
  });

  it('adds edits and deletes row actions while regenerating runtime steps',()=>{
    let assigned=assignLineToService(service,line,'UP',metadata);
    const origin=assigned.rows![0];
    const wait=emptyAction('STOP');
    assigned=addRowAction(assigned,origin.id,wait,metadata);
    expect(assigned.steps.some((step)=>step.action?.type==='STOP')).toBe(true);
    assigned=deleteRowAction(assigned,origin.id,0,metadata);
    expect(assigned.rows![0].actions).toEqual([]);
    expect(assigned.steps.some((step)=>step.action?.type==='STOP')).toBe(false);
  });

  it('compiles depot and action-only custom rows in their explicit order',()=>{
    const rows=createLineRows(line,'UP',metadata);
    const depot={id:'depot',type:'DEPOT_OPERATION' as const,depotOperation:'TAKE_FROM_DEPOT' as const,depotId:'north',tick:10,actions:[]};
    const reverse={id:'action',type:'ACTION_ONLY' as const,tick:20,actions:[emptyAction('REVERSE')]};
    const compiled=compileServiceRows({...service,lineId:'line',lineDirection:'UP',rows:[depot,reverse,...rows]},metadata);
    expect(compiled.steps.slice(0,3).map((step)=>step.action?.type||step.type)).toEqual(['TAKE_FROM_DEPOT','REVERSE','STATION_DESTINATION_OPTIONS']);
  });
});
