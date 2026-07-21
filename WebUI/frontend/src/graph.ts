import type { SavedTimetableV1, TimetableActionV1 } from './types';

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
export function layoutGraph(value: SavedTimetableV1, width: number, rowHeight = 58): GraphLayout {
  const left = 150;
  const right = 28;
  const usable = Math.max(1, width - left - right);
  const periodTicks = Math.max(1, value.periodDays * 24_000);
  const tickToX = (tick: number) => left + Math.min(periodTicks, Math.max(0, tick)) / periodTicks * usable;
  const xToTick = (x: number) => Math.min(periodTicks - 1, Math.max(0, (x - left) / usable * periodTicks));

  const serviceLocations = value.services.map((service) => {
    let current = service.steps.find((step) => step.type === 'CHECK')?.wayPointId || `${service.id}: origin`;
    return service.steps.map((step) => {
      if (step.type === 'CHECK' && step.wayPointId) current = step.wayPointId;
      const locationAtAction = current;
      const target = step.type === 'AT' ? destination(step.action) : undefined;
      if (target) current = target;
      return locationAtAction;
    });
  });
  const declaredTargets = value.services.flatMap((service) => service.steps.map((step) =>
    step.type === 'AT' ? destination(step.action) : undefined));
  const locationIds = Array.from(new Set([...serviceLocations.flat(), ...declaredTargets].filter((id): id is string => Boolean(id))));
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
    height: Math.max(170, 95 + lanes.length * rowHeight),
    left,
    right,
    lanes,
    paths,
    points,
    tickToX,
    xToTick,
  };
}
