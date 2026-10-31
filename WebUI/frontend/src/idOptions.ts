import type {IdOption} from './EditableIdSelect';
import type {WorldMetadata} from './types';

export const idOptions=(ids:readonly string[]):IdOption[]=>ids.map(value=>({value,label:value}));
/** Names annotate the exact known waypoint ID; local berth/depot point IDs are never substituted. */
export function waypointOptions(metadata:WorldMetadata):IdOption[]{
  const names=new Map((metadata.destinations??[]).map(point=>[point.id,point.displayName]));
  for(const point of metadata.programSimulationWayPoints??[])names.set(point.id,point.displayName);
  for(const berth of metadata.stations??[])if(!names.has(berth.destinationId))names.set(berth.destinationId,`${berth.stationDisplayName||berth.stationId} · ${berth.berthId}`);
  for(const depot of metadata.depotDetails??[])for(const point of depot.stablingPoints)if(!names.has(point.destinationId))names.set(point.destinationId,`${depot.displayName||depot.id} · ${point.id}`);
  return (metadata.wayPoints??[]).map(value=>({value,label:names.get(value)&&names.get(value)!==value?`${names.get(value)} · ${value}`:value}));
}
export function berthOptions(metadata:WorldMetadata,stationId:string):IdOption[]{
  return (metadata.stations??[]).filter(berth=>berth.stationId===stationId).map(berth=>({value:berth.berthId,label:`${berth.stationDisplayName||stationId} · ${berth.berthId}`}));
}
export function stablingPointOptions(metadata:WorldMetadata,depotId:string):IdOption[]{
  return (metadata.depotDetails??[]).find(depot=>depot.id===depotId)?.stablingPoints.map(point=>({value:point.id,label:`${point.id} · ${point.destinationId}`}))??[];
}
export function directedWaypointOptions(metadata:WorldMetadata):IdOption[]{
  return (metadata.programSimulationWayPoints??[]).map(point=>{
    const depot=(metadata.depotDetails??[]).find(depot=>depot.stablingPoints.some(candidate=>candidate.destinationId===point.id));
    const local=depot?.stablingPoints.find(candidate=>candidate.destinationId===point.id);
    const parts=[point.displayName!==point.id?point.displayName:'',depot?`${depot.displayName||depot.id} · ${local!.id}`:'',point.id].filter(Boolean);
    return {value:point.id,label:parts.join(' · ')};
  });
}
