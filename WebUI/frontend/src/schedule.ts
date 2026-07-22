import { emptyAction } from './editor';
import type {
  LineDirection, LineV1, ServiceEditorRowV1, ServiceStopStatus, TimetableActionV1,
  TimetableServiceV1, TimetableStepV1, WorldMetadata,
} from './types';

export const lineStations = (line:LineV1,direction:LineDirection):string[] =>
  direction==='UP' ? line.upStationIds : line.downStationIds;

const rowId = (prefix:string,index:number) => `${prefix}-${index}-${Math.random().toString(36).slice(2,8)}`;
const berths = (metadata:WorldMetadata,stationId:string) => (metadata.stations || []).filter((item) => item.stationId===stationId);
const berthDestination = (metadata:WorldMetadata,stationId?:string|null,berthId?:string|null) =>
  berths(metadata,stationId || '').find((item) => item.berthId===berthId)?.destinationId || berths(metadata,stationId || '')[0]?.destinationId;

export function createLineRows(line:LineV1,direction:LineDirection,metadata:WorldMetadata):ServiceEditorRowV1[] {
  const stations=lineStations(line,direction);
  return stations.map((stationId,index) => ({
    id:rowId('station',index), type:'STATION', stationId,
    berthId:berths(metadata,stationId)[0]?.berthId || null,
    status:index===0?'ORIGIN':index===stations.length-1?'TERMINAL':'PASS',
    arrivalTick:null, departureTick:index===0?0:null, actions:[],
  }));
}

export function canInitializeServiceRows(service:TimetableServiceV1):boolean {
  return service.rows!=null || service.steps.every((step) =>
    step.type==='CHECK' ? !step.wayPointId : step.action?.type==='DESTINATION' && step.action.wayPointIds.every((id)=>!id));
}

export function assignLineToService(service:TimetableServiceV1,line:LineV1,direction:LineDirection,metadata:WorldMetadata):TimetableServiceV1 {
  const rows=createLineRows(line,direction,metadata);
  return compileServiceRows({ ...service,lineId:line.id,lineDirection:direction,rows },metadata);
}

export function updateServiceRow(
  service:TimetableServiceV1,rowIdValue:string,patch:Partial<ServiceEditorRowV1>,metadata:WorldMetadata,
):TimetableServiceV1 {
  const current=service.rows || [];
  const selectedIndex=current.findIndex((row)=>row.id===rowIdValue);
  let rows=current.map((row) => row.id===rowIdValue ? {...row,...patch} : row);
  const selected=rows[selectedIndex];
  if(selected?.type==='DEPOT_OPERATION') {
    const operation=current[selectedIndex]?.depotOperation;
    rows[selectedIndex]={...selected,depotOperation:operation,tick:operation==='TAKE_FROM_DEPOT'?0:selected.tick};
    if(operation==='TAKE_FROM_DEPOT'&&patch.depotId!==undefined) rows=rows.map((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='STORE_AT_DEPOT'?{...row,depotId:patch.depotId}:row);
  }
  const isTarget=(row:ServiceEditorRowV1)=>row.type==='STATION'||row.type==='SPECIAL_DESTINATION';
  if(patch.status==='ORIGIN') rows=rows.map((row,index)=>index<selectedIndex&&isTarget(row)?{...row,status:'DISABLED'}:row);
  if(patch.status==='TERMINAL') rows=rows.map((row,index)=>index>selectedIndex&&isTarget(row)?{...row,status:'DISABLED'}:row);
  return compileServiceRows({...service,rows},metadata);
}

export function insertServiceRow(
  service:TimetableServiceV1,index:number,type:ServiceEditorRowV1['type'],metadata:WorldMetadata,
):TimetableServiceV1 {
  const current=service.rows || [];
  if(type==='DEPOT_OPERATION') {
    const take=current.find((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='TAKE_FROM_DEPOT');
    const store=current.find((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='STORE_AT_DEPOT');
    if(store) return service;
    const row:ServiceEditorRowV1=take
      ? {id:rowId('depot-store',index),type,depotOperation:'STORE_AT_DEPOT',depotId:take.depotId||'',tick:0,actions:[]}
      : {id:rowId('depot-take',index),type,depotOperation:'TAKE_FROM_DEPOT',depotId:'',tick:0,actions:[]};
    return compileServiceRows({...service,rows:take?[...current,row]:[row,...current]},metadata);
  }
  const row:ServiceEditorRowV1 = type==='SPECIAL_DESTINATION'
    ? {id:rowId('destination',index),type,status:'STOP',destinationId:'',arrivalTick:null,departureTick:null,actions:[]}
    : {id:rowId('action',index),type,tick:0,actions:[]};
  const storeIndex=current.findIndex((item)=>item.type==='DEPOT_OPERATION'&&item.depotOperation==='STORE_AT_DEPOT');
  const safeIndex=storeIndex>=0?Math.min(index,storeIndex):index;
  return compileServiceRows({...service,rows:[...current.slice(0,safeIndex),row,...current.slice(safeIndex)]},metadata);
}

export function deleteServiceRow(service:TimetableServiceV1,rowIdValue:string,metadata:WorldMetadata):TimetableServiceV1 {
  const selected=(service.rows||[]).find((row)=>row.id===rowIdValue);
  return compileServiceRows({...service,rows:(service.rows || []).filter((row) => row.id!==rowIdValue&&!(selected?.depotOperation==='TAKE_FROM_DEPOT'&&row.depotOperation==='STORE_AT_DEPOT'))},metadata);
}

export function moveServiceRow(service:TimetableServiceV1,rowIdValue:string,delta:number,metadata:WorldMetadata):TimetableServiceV1 {
  const rows=[...(service.rows || [])];
  const from=rows.findIndex((row)=>row.id===rowIdValue);
  if(from<0||rows[from].type==='DEPOT_OPERATION') return service;
  const takeOffset=rows[0]?.depotOperation==='TAKE_FROM_DEPOT'?1:0;
  const storeOffset=rows.at(-1)?.depotOperation==='STORE_AT_DEPOT'?1:0;
  const to=Math.max(takeOffset,Math.min(rows.length-1-storeOffset,from+delta));
  if(from===to) return service;
  const [row]=rows.splice(from,1); rows.splice(to,0,row);
  return compileServiceRows({...service,rows},metadata);
}

export function addRowAction(service:TimetableServiceV1,rowIdValue:string,action:TimetableActionV1,metadata:WorldMetadata):TimetableServiceV1 {
  const row=(service.rows || []).find((item)=>item.id===rowIdValue);
  if(!row||row.type==='DEPOT_OPERATION') return service;
  return updateServiceRow(service,rowIdValue,{actions:[...row.actions,action]},metadata);
}

export function updateRowAction(service:TimetableServiceV1,rowIdValue:string,actionIndex:number,action:TimetableActionV1,metadata:WorldMetadata):TimetableServiceV1 {
  const row=(service.rows || []).find((item)=>item.id===rowIdValue);
  if(!row) return service;
  return updateServiceRow(service,rowIdValue,{actions:row.actions.map((item,index)=>index===actionIndex?action:item)},metadata);
}

export function deleteRowAction(service:TimetableServiceV1,rowIdValue:string,actionIndex:number,metadata:WorldMetadata):TimetableServiceV1 {
  const row=(service.rows || []).find((item)=>item.id===rowIdValue);
  if(!row) return service;
  return updateServiceRow(service,rowIdValue,{actions:row.actions.filter((_,index)=>index!==actionIndex)},metadata);
}

function target(row:ServiceEditorRowV1,metadata:WorldMetadata):string|undefined {
  if(row.type==='STATION') return berthDestination(metadata,row.stationId,row.berthId);
  if(row.type==='SPECIAL_DESTINATION') return row.destinationId || undefined;
  return undefined;
}

function scheduledAction(tick:number,action:TimetableActionV1):TimetableStepV1 {
  return {type:'AT',tick:Math.max(0,tick),action};
}

/** Compiles editor rows into the authoritative ordered runtime steps. DESTINATION is always generated. */
export function compileServiceRows(service:TimetableServiceV1,metadata:WorldMetadata):TimetableServiceV1 {
  if(!service.rows) return service;
  let normalizedRows=service.rows;
  const enabledTargets=()=>normalizedRows.filter((row)=>(row.type==='STATION'||row.type==='SPECIAL_DESTINATION')&&row.status!=='DISABLED');
  if(!enabledTargets().some((row)=>row.status==='ORIGIN')&&enabledTargets()[0]) {
    const id=enabledTargets()[0].id; normalizedRows=normalizedRows.map((row)=>row.id===id?{...row,status:'ORIGIN'}:row);
  }
  if(!enabledTargets().some((row)=>row.status==='TERMINAL')&&enabledTargets().at(-1)) {
    const id=enabledTargets().at(-1)!.id; normalizedRows=normalizedRows.map((row)=>row.id===id?{...row,status:'TERMINAL'}:row);
  }
  const take=normalizedRows.find((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='TAKE_FROM_DEPOT');
  const store=normalizedRows.find((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='STORE_AT_DEPOT');
  if(take) {
    const middle=normalizedRows.filter((row)=>row.id!==take.id&&row.id!==store?.id);
    const minimumStoreTick=middle.reduce((maximum,row)=>Math.max(maximum,row.type==='ACTION_ONLY'||row.type==='DEPOT_OPERATION'?row.tick??0:row.departureTick??0),0);
    normalizedRows=[{...take,tick:0},...middle,...(store?[{...store,depotId:take.depotId||'',tick:Math.max(store.tick??0,minimumStoreTick)}]:[])];
  }
  service={...service,rows:normalizedRows};
  const steps:TimetableStepV1[]=[];
  let currentAnchor:ServiceEditorRowV1|undefined;
  let route:string[]=[];
  let depotDepartureTick:number|undefined;
  const appendActions=(row:ServiceEditorRowV1) => {
    const tick=row.type==='ACTION_ONLY'||row.type==='DEPOT_OPERATION' ? row.tick ?? 0 : row.departureTick ?? 0;
    row.actions.forEach((action)=>steps.push(scheduledAction(tick,action)));
  };
  for(const row of normalizedRows) {
    if(row.type==='ACTION_ONLY') { appendActions(row); continue; }
    if(row.type==='DEPOT_OPERATION') {
      const action=emptyAction(row.depotOperation || 'TAKE_FROM_DEPOT');
      action.depotId=row.depotId || '';
      if(row.depotOperation==='STORE_AT_DEPOT') appendActions(row);
      steps.push(scheduledAction(row.tick ?? 0,action));
      if(row.depotOperation!=='STORE_AT_DEPOT') appendActions(row);
      route=[];
      currentAnchor=undefined;
      depotDepartureTick=row.depotOperation==='TAKE_FROM_DEPOT' ? row.tick ?? 0 : undefined;
      continue;
    }
    if(row.status==='DISABLED') continue;
    const destination=target(row,metadata);
    if(!destination) continue;
    if(!currentAnchor && depotDepartureTick==null) {
      steps.push({type:'CHECK',wayPointId:destination});
      currentAnchor=row;
      appendActions(row);
      continue;
    }
    if(row.status==='PASS') { route.push(destination); continue; }
    const departureTick=currentAnchor?.departureTick ?? depotDepartureTick ?? 0;
    if(row.type==='STATION') {
      const options=emptyAction('STATION_DESTINATION_OPTIONS');
      options.stationId=row.stationId || '';
      options.berthId=row.berthId || '';
      steps.push(scheduledAction(departureTick,options));
    }
    const movement=emptyAction('DESTINATION');
    movement.wayPointIds=[...route,destination];
    steps.push(scheduledAction(departureTick,movement));
    route=[];
    depotDepartureTick=undefined;
    currentAnchor=row;
    appendActions(row);
  }
  const hasTake=normalizedRows.some((row)=>row.type==='DEPOT_OPERATION'&&row.depotOperation==='TAKE_FROM_DEPOT');
  return {...service,selectorType:hasTake?'ANY':service.selectorType,trainId:hasTake?null:service.trainId,steps};
}

export function updateGeneratedDeparture(service:TimetableServiceV1,stepIndex:number,tick:number,metadata:WorldMetadata):TimetableServiceV1 {
  if(!service.rows || service.steps[stepIndex]?.action?.type!=='DESTINATION') return service;
  const movementOrdinal=service.steps.slice(0,stepIndex+1).filter((step)=>step.action?.type==='DESTINATION').length-1;
  const anchors:ServiceEditorRowV1[]=[];
  let anchor:ServiceEditorRowV1|undefined;
  for(const row of service.rows) {
    if(row.type==='DEPOT_OPERATION'&&row.depotOperation==='TAKE_FROM_DEPOT') { anchor=row; continue; }
    if(row.type!=='STATION'&&row.type!=='SPECIAL_DESTINATION'||row.status==='DISABLED') continue;
    if(!target(row,metadata)) continue;
    if(!anchor) { anchor=row; continue; }
    if(row.status==='PASS') continue;
    anchors.push(anchor); anchor=row;
  }
  const selected=anchors[movementOrdinal];
  if(!selected) return service;
  return updateServiceRow(service,selected.id,selected.type==='DEPOT_OPERATION'?{tick}:{departureTick:tick},metadata);
}

export function actionLabel(action:TimetableActionV1):string {
  if(action.type==='STOP') return 'WAIT';
  if(action.type==='EXTENSION') return action.extensionCall?.typeId || 'EXTENSION';
  return action.type;
}

export const editableStatuses:ServiceStopStatus[]=['ORIGIN','STOP','PASS','TERMINAL','DISABLED'];
