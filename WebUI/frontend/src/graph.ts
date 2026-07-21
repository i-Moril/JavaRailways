import type { LineV1, SavedTimetableV1, TimetableActionV1, WorldMetadata } from './types';

export interface GraphViewport { scaleX:number; scaleY:number; offsetX:number; offsetY:number }

export function zoomGraphViewport(viewport:GraphViewport, axis:'x'|'y', factor:number, anchor:number, origin:number, minimum=0.5, maximum=8):GraphViewport {
  const scaleKey=axis==='x'?'scaleX':'scaleY';
  const offsetKey=axis==='x'?'offsetX':'offsetY';
  const previous=viewport[scaleKey];
  const next=Math.min(maximum,Math.max(minimum,previous*factor));
  return { ...viewport, [scaleKey]:next, [offsetKey]:anchor-origin-(anchor-origin-viewport[offsetKey])*next/previous };
}

export function graphScreenXToTick(layout:Pick<GraphLayout,'left'|'xToTick'>, viewport:GraphViewport, screenX:number):number {
  const logicalX=layout.left+(screenX-layout.left-viewport.offsetX)/viewport.scaleX;
  return layout.xToTick(logicalX);
}

export interface GraphPoint {
  serviceIndex: number;
  stepIndex: number;
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

export function snapTick(tick: number, snap = 20) {
  return Math.max(0, Math.round(tick / snap) * snap);
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
): GraphLayout {
  const left = 150;
  const right = 28;
  const usable = Math.max(1, width - left - right);
  const periodTicks = Math.max(1, value.periodDays * 24_000);
  const tickToX = (tick: number) => left + Math.min(periodTicks, Math.max(0, tick)) / periodTicks * usable;
  const xToTick = (x: number) => Math.min(periodTicks - 1, Math.max(0, (x - left) / usable * periodTicks));

  const destinationStations = new Map((metadata?.stations || []).map((berth) => [berth.destinationId, berth.stationId]));
  const normalizeLocation = (id:string) => destinationStations.get(id) || id;
  const serviceLocations = value.services.map((service) => {
    let current = service.steps.find((step) => step.type === 'CHECK')?.wayPointId || `${service.id}: origin`;
    return service.steps.map((step) => {
      if (step.type === 'CHECK' && step.wayPointId) current = step.wayPointId;
      const locationAtAction = current;
      const target = step.type === 'AT' ? destination(step.action) : undefined;
      if (target) current = target;
      return normalizeLocation(locationAtAction);
    });
  });
  const declaredTargets = value.services.flatMap((service) => service.steps.map((step) =>
    step.type === 'AT' ? destination(step.action) : undefined));
  const discovered = [...serviceLocations.flat(), ...declaredTargets.map((id) => id ? normalizeLocation(id) : id)]
    .filter((id): id is string => Boolean(id));
  const preferred = line?.stationIds || [];
  const locationIds = Array.from(new Set([...preferred, ...discovered]));
  if (locationIds.length === 0) locationIds.push('No locations');
  const lanes = locationIds.map((id, index) => ({ id, y: 54 + index * rowHeight }));
  const laneById = new Map(lanes.map((lane) => [lane.id, lane.y]));

  const paths = value.services.map((service, serviceIndex) => {
    const points = service.steps.flatMap((step, stepIndex): GraphPoint[] => {
      if (step.type !== 'AT') return [];
      const location = serviceLocations[serviceIndex][stepIndex] || `${service.id}: origin`;
      const tick = step.tick ?? 0;
      return [{
        serviceIndex,
        stepIndex,
        tick,
        x: tickToX(tick),
        y: laneById.get(location) ?? lanes[0].y,
        label: step.action?.type === 'DESTINATION' ? location : step.action?.type || 'Action',
        location,
      }];
    });
    return { serviceIndex, serviceId: service.id, points };
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
