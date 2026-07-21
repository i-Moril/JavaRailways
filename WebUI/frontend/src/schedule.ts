import type { LineV1, TimetableServiceV1, WorldMetadata } from './types';

export type StationStopStatus = 'ORIGIN'|'STOP'|'PASS'|'TERMINAL';
export interface ServiceStationRow {
  stationId:string;
  displayName:string;
  destinationId?:string;
  status:StationStopStatus;
  arrivalTick?:number;
  departureTick?:number;
  departureStepIndex?:number;
  actionStepIndices:number[];
}

function stationLookups(metadata:WorldMetadata) {
  const destinationToStation = new Map<string,string>();
  const stationToDestination = new Map<string,string>();
  const displayNames = new Map<string,string>();
  for (const berth of metadata.stations || []) {
    destinationToStation.set(berth.destinationId, berth.stationId);
    if (!stationToDestination.has(berth.stationId)) stationToDestination.set(berth.stationId, berth.destinationId);
    displayNames.set(berth.stationId, berth.stationDisplayName || berth.stationId);
  }
  const normalize = (id?:string|null) => id ? destinationToStation.get(id) || id : undefined;
  return { destinationToStation, stationToDestination, displayNames, normalize };
}

export function assignLineToService(service:TimetableServiceV1, line:LineV1, metadata:WorldMetadata):TimetableServiceV1 {
  const hasRoute=service.steps.some((step) => step.action?.type==='DESTINATION' && step.action.wayPointIds.some(Boolean));
  if (hasRoute || line.stationIds.length<2) return { ...service, lineId:line.id };
  const lookup=stationLookups(metadata);
  const ids=line.stationIds.map((stationId) => lookup.stationToDestination.get(stationId) || stationId);
  const checkIndex=service.steps.findIndex((step) => step.type==='CHECK');
  const movementIndex=service.steps.findIndex((step) => step.action?.type==='DESTINATION');
  const steps=[...service.steps];
  const check={type:'CHECK' as const,wayPointId:ids[0]};
  const movement={type:'AT' as const,tick:movementIndex>=0 ? service.steps[movementIndex].tick ?? 0 : 0,action:{
    ...(movementIndex>=0 ? service.steps[movementIndex].action! : {
      type:'DESTINATION' as const,requiredTags:[],requiredCapabilities:[],stablingPointIds:[],thenActions:[],elseActions:[],
    }),wayPointIds:ids.slice(1),
  }};
  if(checkIndex>=0) steps[checkIndex]=check; else steps.unshift(check);
  const adjustedMovementIndex=movementIndex>=0 ? movementIndex+(checkIndex<0?1:0) : steps.length;
  if(movementIndex>=0) steps[adjustedMovementIndex]=movement; else steps.push(movement);
  return { ...service,lineId:line.id,steps };
}

/** Lossless projection: rows retain exact source step indexes; arrival is presentation-only. */
export function serviceStationRows(service:TimetableServiceV1, metadata:WorldMetadata, line?:LineV1):ServiceStationRow[] {
  const lookup = stationLookups(metadata);
  const discovered:string[] = [];
  const add = (id?:string) => { if (id && !discovered.includes(id)) discovered.push(id); };
  for (const step of service.steps) {
    if (step.type === 'CHECK') add(lookup.normalize(step.wayPointId));
    if (step.type === 'AT' && step.action?.type === 'DESTINATION') {
      step.action.wayPointIds.forEach((id) => add(lookup.normalize(id)));
    }
    if (step.type === 'AT' && step.action?.type === 'STATION_DESTINATION_OPTIONS') add(step.action.stationId || undefined);
  }
  const stationIds = [...(line?.stationIds || discovered)];
  for (const id of discovered) if (!stationIds.includes(id)) stationIds.push(id);
  const rows = stationIds.map((stationId):ServiceStationRow => ({
    stationId,
    displayName:lookup.displayNames.get(stationId) || stationId,
    destinationId:lookup.stationToDestination.get(stationId),
    status:'PASS',
    actionStepIndices:[],
  }));
  const byId = new Map(rows.map((row) => [row.stationId,row]));
  let current = lookup.normalize(service.steps.find((step) => step.type === 'CHECK')?.wayPointId) || stationIds[0];
  if (current && byId.has(current)) byId.get(current)!.status = 'ORIGIN';
  let finalStation:string|undefined;
  service.steps.forEach((step,stepIndex) => {
    if (step.type === 'CHECK') {
      current = lookup.normalize(step.wayPointId) || current;
      return;
    }
    const action = step.action;
    if (!action) return;
    if (action.type === 'STATION_DESTINATION_OPTIONS') {
      const station = action.stationId ? byId.get(action.stationId) : undefined;
      if (station) station.actionStepIndices.push(stepIndex);
      return;
    }
    if (action.type === 'DESTINATION') {
      const currentRow = current ? byId.get(current) : undefined;
      if (currentRow) {
        currentRow.departureTick = step.tick ?? 0;
        currentRow.departureStepIndex = stepIndex;
      }
      const normalized = action.wayPointIds.map((id) => lookup.normalize(id)).filter((id):id is string => Boolean(id));
      normalized.slice(0,-1).forEach((id) => { const row=byId.get(id); if (row && row.status !== 'ORIGIN') row.status='PASS'; });
      const target = normalized.at(-1);
      if (target) {
        const targetRow = byId.get(target);
        if (targetRow) {
          targetRow.status='STOP';
        }
        current=target;
        finalStation=target;
      }
      return;
    }
    if (current && byId.has(current)) byId.get(current)!.actionStepIndices.push(stepIndex);
  });
  if (finalStation && byId.has(finalStation)) byId.get(finalStation)!.status='TERMINAL';
  return rows;
}

export function availableStationStatuses(service:TimetableServiceV1, row:ServiceStationRow, metadata:WorldMetadata):StationStopStatus[] {
  const lookup=stationLookups(metadata);
  if(row.status==='ORIGIN' || row.status==='TERMINAL') return [row.status];
  if(row.status==='PASS') {
    const movementIndex=service.steps.findIndex((step) => step.action?.type==='DESTINATION' &&
      step.action.wayPointIds.slice(0,-1).some((id) => lookup.normalize(id)===row.stationId));
    if(movementIndex<0) return ['PASS'];
    const hasLaterMovement=service.steps.some((step,index) => index>movementIndex && step.action?.type==='DESTINATION');
    return hasLaterMovement ? ['PASS','STOP'] : ['PASS','STOP','TERMINAL'];
  }
  const stopIndex=service.steps.findIndex((step) => step.action?.type==='DESTINATION' &&
    lookup.normalize(step.action.wayPointIds.at(-1))===row.stationId);
  const nextIndex=service.steps.findIndex((step,index) => index>stopIndex && step.action?.type==='DESTINATION');
  const canMerge=stopIndex>=0 && nextIndex>stopIndex && service.steps.slice(stopIndex+1,nextIndex)
    .every((step) => ['DESTINATION_OPTIONS','STATION_DESTINATION_OPTIONS'].includes(step.action?.type || ''));
  return canMerge ? ['STOP','PASS'] : ['STOP'];
}

/** Changes only the route steps needed for the requested status and preserves unrelated actions verbatim. */
export function updateStationStatus(
  service:TimetableServiceV1,
  stationId:string,
  status:StationStopStatus,
  metadata:WorldMetadata,
):TimetableServiceV1 {
  const lookup = stationLookups(metadata);
  const targetId = lookup.stationToDestination.get(stationId) || stationId;
  if (status === 'ORIGIN') {
    const check = service.steps.findIndex((step) => step.type === 'CHECK');
    const steps = [...service.steps];
    if (check >= 0) steps[check] = { ...steps[check], wayPointId:targetId };
    else steps.unshift({ type:'CHECK', wayPointId:targetId });
    return { ...service, steps };
  }
  const movementIndex = service.steps.findIndex((step) => step.type === 'AT' && step.action?.type === 'DESTINATION' &&
    step.action.wayPointIds.slice(0,-1).some((id) => lookup.normalize(id) === stationId));
  if (status === 'STOP' || status === 'TERMINAL') {
    if (movementIndex < 0) return service;
    const movement = service.steps[movementIndex];
    const ids = movement.action!.wayPointIds;
    const splitAt = ids.findIndex((id) => lookup.normalize(id) === stationId);
    const first = { ...movement, action:{ ...movement.action!, wayPointIds:[...ids.slice(0,splitAt),targetId] } };
    const second = { ...movement, action:{ ...movement.action!, wayPointIds:ids.slice(splitAt+1) } };
    const optionIndex = movementIndex > 0 && ['DESTINATION_OPTIONS','STATION_DESTINATION_OPTIONS'].includes(service.steps[movementIndex-1].action?.type || '')
      ? movementIndex-1 : -1;
    const steps = [...service.steps.slice(0,optionIndex >= 0 ? optionIndex : movementIndex), first];
    if (status !== 'TERMINAL' && second.action.wayPointIds.length) {
      if (optionIndex >= 0) steps.push(service.steps[optionIndex]);
      steps.push(second);
    }
    steps.push(...service.steps.slice(movementIndex+1));
    return { ...service, steps };
  }
  const stopIndex = service.steps.findIndex((step) => step.type === 'AT' && step.action?.type === 'DESTINATION' &&
    lookup.normalize(step.action.wayPointIds.at(-1)) === stationId);
  if (status === 'PASS' && stopIndex >= 0) {
    const nextIndex = service.steps.findIndex((step,index) => index > stopIndex && step.type === 'AT' && step.action?.type === 'DESTINATION');
    if (nextIndex < 0) return service;
    const between = service.steps.slice(stopIndex+1,nextIndex);
    if (between.some((step) => step.action?.type !== 'DESTINATION_OPTIONS' && step.action?.type !== 'STATION_DESTINATION_OPTIONS')) return service;
    const first = service.steps[stopIndex];
    const next = service.steps[nextIndex];
    const merged = { ...first, action:{ ...first.action!, wayPointIds:[...first.action!.wayPointIds,...next.action!.wayPointIds] } };
    const pairedOptions = stopIndex > 0 && ['DESTINATION_OPTIONS','STATION_DESTINATION_OPTIONS'].includes(service.steps[stopIndex-1].action?.type || '');
    const preservedOptions=between.map((step) => ({...step,tick:first.tick}));
    return { ...service, steps:[...service.steps.slice(0,pairedOptions ? stopIndex-1 : stopIndex),...preservedOptions,merged,...service.steps.slice(nextIndex+1)] };
  }
  return service;
}
