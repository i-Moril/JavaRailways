import type { RailMapFile, RailMapPoint, RailMapTrain } from './types';

export const MAX_RAIL_MAP_ZOOM=8;
export interface MapViewport { zoom:number;offsetX:number;offsetY:number }
export interface MapBounds { minX:number;maxX:number;minZ:number;maxZ:number }

export function railMapBounds(map:RailMapFile):MapBounds {
  const points=map.rails.flatMap((rail)=>rail.points);
  if(!points.length)return{minX:-1,maxX:1,minZ:-1,maxZ:1};
  return {minX:Math.min(...points.map((p)=>p.x)),maxX:Math.max(...points.map((p)=>p.x)),minZ:Math.min(...points.map((p)=>p.z)),maxZ:Math.max(...points.map((p)=>p.z))};
}

export function clampMapZoom(value:number):number{return Math.max(0.5,Math.min(MAX_RAIL_MAP_ZOOM,value));}

export function interpolateTrainPosition(map:RailMapFile,train:RailMapTrain):RailMapPoint|undefined {
  const points=map.rails.find((rail)=>rail.id===train.railId)?.points;
  if(!points?.length)return undefined;
  if(train.arcLengthMeters<=points[0].arc)return points[0];
  for(let index=1;index<points.length;index++){
    const next=points[index];const previous=points[index-1];
    if(train.arcLengthMeters<=next.arc){
      const ratio=(train.arcLengthMeters-previous.arc)/Math.max(1e-9,next.arc-previous.arc);
      return {arc:train.arcLengthMeters,x:previous.x+(next.x-previous.x)*ratio,y:previous.y+(next.y-previous.y)*ratio,z:previous.z+(next.z-previous.z)*ratio};
    }
  }
  return points.at(-1);
}

export interface RailMapSegment { railId:string; elevation:number; from:RailMapPoint; to:RailMapPoint }
export function railSegmentsByVisualHeight(map:RailMapFile):RailMapSegment[]{
  return map.rails.flatMap((rail)=>rail.points.slice(1).map((to,index)=>({railId:rail.id,from:rail.points[index],to,elevation:(rail.points[index].y+to.y)/2})))
    .sort((a,b)=>a.elevation-b.elevation);
}
