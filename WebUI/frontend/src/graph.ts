import type { LineV1, SavedTimetableV1, TimetableActionV1, WorldMetadata } from './types';

const DARK_SERVICE_COLORS=['#56c5ff','#ffb454','#8bd17c','#d28cff','#ff718b','#54d6c5'];
const LIGHT_SERVICE_COLORS=['#0076a8','#a94f00','#247a39','#7138ad','#b72b47','#00776c'];
export const graphServiceColors=(theme:'dark'|'light')=>theme==='light'?LIGHT_SERVICE_COLORS:DARK_SERVICE_COLORS;

export interface GraphPoint {
  serviceIndex: number;
  stepIndex: number;
  kind: 'DEPARTURE'|'ARRIVAL';
  tick: number;
  x: number;
  y: number;
  label: string;
  location: string;
}

export interface GraphLane { id: string; y: number }
export interface GraphPath { serviceIndex: number; serviceId: string; points: GraphPoint[] }
export interface GraphLayout {
  width: number;
  height: number;
  left: number;
  right: number;
  lanes: GraphLane[];
  paths: GraphPath[];
  points: GraphPoint[];
  tickToX: (tick: number) => number;
  xToTick: (x: number) => number;
}

export function filterTimetableForDiagramLine(value:SavedTimetableV1,lineId:string):SavedTimetableV1 {
  return {...value,services:value.services.filter((service)=>service.diagramLineId===lineId)};
}

function destination(action?: TimetableActionV1 | null): string | undefined {
  if (!action) return undefined;
  if (action.type === 'DESTINATION') return action.wayPointIds.at(-1) || undefined;
  if (action.type === 'STORE_AT_DEPOT') return action.depotId || undefined;
  return undefined;
}

/** Builds a conventional running graph: time on X, operational locations on Y. */
export function layoutGraph(
  value: SavedTimetableV1,
  width: number,
  rowHeight = 58,
  minimumHeight = 170,
  metadata?: WorldMetadata,
  line?: LineV1,
  graphLineId?: string,
): GraphLayout {
  const left = 150;
  const right = 28;
  const usable = Math.max(1, width - left - right);
  const periodTicks = Math.max(1, value.periodDays * 24_000);
  const tickToX = (tick: number) => left + Math.min(periodTicks, Math.max(0, tick)) / periodTicks * usable;
  const xToTick = (x: number) => Math.min(periodTicks - 1, Math.max(0, (x - left) / usable * periodTicks));

  const destinationStations = new Map((metadata?.stations || []).map((berth) => [berth.destinationId, berth.stationId]));
  const stationIds=new Set((metadata?.stations||[]).map((berth)=>berth.stationId));
  const normalizeLocation = (id:string) => destinationStations.get(id) || id;
  const diagramLine=value.diagramLines?.find((item)=>item.id===(graphLineId||line?.id));
  const preferred = diagramLine
    ? Array.from(new Set([...diagramLine.upStationIds,...diagramLine.downStationIds]))
    : line ? Array.from(new Set([...line.upStationIds,...line.downStationIds])) : [];
  const discovered=value.services.flatMap((service)=>service.steps.flatMap((step)=>{
    const id=step.type==='CHECK'&&step.wayPointId
      ? normalizeLocation(step.wayPointId)
      : step.type==='AT'&&destination(step.action) ? normalizeLocation(destination(step.action)!) : undefined;
    return id&&stationIds.has(id)?[id]:[];
  }));
  const locationIds=diagramLine ? preferred : Array.from(new Set([...preferred,...discovered]));
  if (locationIds.length === 0) locationIds.push('No locations');
  const lanes = locationIds.map((id, index) => ({ id, y: 54 + index * rowHeight }));
  const laneById = new Map(lanes.map((lane) => [lane.id, lane.y]));

  const paths = value.services.flatMap((service, serviceIndex) => {
    let current=normalizeLocation(service.steps.find((step)=>step.type==='CHECK')?.wayPointId||`${service.id}: origin`);
    let pendingArrival:{tick:number;stepIndex:number}|null=null;
    const segments:GraphPath[]=[];
    service.steps.forEach((step,stepIndex)=>{
      if(step.type==='CHECK'&&step.wayPointId){current=normalizeLocation(step.wayPointId);return;}
      if(step.type!=='AT')return;
      const action=step.action;
      if(action?.type==='DESTINATION_OPTIONS'||action?.type==='STATION_DESTINATION_OPTIONS'){
        pendingArrival=action.arrivalTick==null?null:{tick:action.arrivalTick,stepIndex};
        return;
      }
      const target=destination(action);
      if(!target)return;
      const next=normalizeLocation(target);
      const departureY=laneById.get(current),arrivalY=laneById.get(next);
      if(pendingArrival!=null&&departureY!=null&&arrivalY!=null){
        const departureTick=step.tick??0;
        segments.push({serviceIndex,serviceId:service.id,points:[
          {serviceIndex,stepIndex,kind:'DEPARTURE',tick:departureTick,x:tickToX(departureTick),y:departureY,label:current,location:current},
          {serviceIndex,stepIndex:pendingArrival.stepIndex,kind:'ARRIVAL',tick:pendingArrival.tick,x:tickToX(pendingArrival.tick),y:arrivalY,label:next,location:next},
        ]});
      }
      current=next;
      pendingArrival=null;
    });
    return segments;
  });
  const points = paths.flatMap((path) => path.points);
  return {
    width,
    height: Math.max(minimumHeight, 95 + lanes.length * rowHeight),
    left,
    right,
    lanes,
    paths,
    points,
    tickToX,
    xToTick,
  };
}
