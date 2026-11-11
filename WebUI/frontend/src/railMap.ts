import type { RailMapFile, RailMapPoint, RailMapTrain, RailMapTrainCar } from './types';

export const MAX_RAIL_MAP_ZOOM=8;
export const MAP_PADDING=40;
export const RAIL_STROKE_WIDTH=3;
export const RAIL_CASING_WIDTH=9;
export const STATION_MARKER_RADIUS=5;
export const DESTINATION_MARKER_RADIUS=3;
export const TRAIN_CAR_WIDTH_PIXELS=7;
export const CONNECTED_ELEVATION_TOLERANCE_METERS=2;
export interface MapViewport { zoom:number;offsetX:number;offsetY:number }
export interface MapBounds { minX:number;maxX:number;minZ:number;maxZ:number }

export function railMapBounds(map:RailMapFile):MapBounds {
  const points=map.rails.flatMap((rail)=>rail.points);
  if(!points.length)return{minX:-1,maxX:1,minZ:-1,maxZ:1};
  return {minX:Math.min(...points.map((p)=>p.x)),maxX:Math.max(...points.map((p)=>p.x)),minZ:Math.min(...points.map((p)=>p.z)),maxZ:Math.max(...points.map((p)=>p.z))};
}

export function clampMapZoom(value:number):number{return Math.max(0.5,Math.min(MAX_RAIL_MAP_ZOOM,value));}
export function zoomMapViewport(viewport:MapViewport,zoom:number,anchor:{x:number;y:number},origin=MAP_PADDING):MapViewport{
  const next=clampMapZoom(zoom),ratio=next/viewport.zoom;
  return {zoom:next,offsetX:anchor.x-origin-(anchor.x-origin-viewport.offsetX)*ratio,offsetY:anchor.y-origin-(anchor.y-origin-viewport.offsetY)*ratio};
}

export interface MapScreenPoint { x:number;y:number }
export function trainCarRectangle(car:RailMapTrainCar,project:(x:number,z:number)=>MapScreenPoint,mapScale:number,widthPixels=TRAIN_CAR_WIDTH_PIXELS):MapScreenPoint[]{
  const center=project(car.x,car.z),magnitude=Math.hypot(car.directionX,car.directionZ);
  const direction=magnitude>1e-9?{x:car.directionX/magnitude,y:car.directionZ/magnitude}:{x:1,y:0};
  const side={x:-direction.y,y:direction.x},halfLength=car.lengthMeters*mapScale*magnitude/2,halfWidth=widthPixels/2;
  return [
    {x:center.x+direction.x*halfLength+side.x*halfWidth,y:center.y+direction.y*halfLength+side.y*halfWidth},
    {x:center.x+direction.x*halfLength-side.x*halfWidth,y:center.y+direction.y*halfLength-side.y*halfWidth},
    {x:center.x-direction.x*halfLength-side.x*halfWidth,y:center.y-direction.y*halfLength-side.y*halfWidth},
    {x:center.x-direction.x*halfLength+side.x*halfWidth,y:center.y-direction.y*halfLength+side.y*halfWidth},
  ];
}

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

export interface MapLabelInput { id:string;text:string;x:number;y:number;priority:number;color:string }
export interface PlacedMapLabel extends MapLabelInput { labelX:number;labelY:number;width:number;height:number }
export const MAX_MAP_LABELS=1_000;
const compareLabels=(a:MapLabelInput,b:MapLabelInput)=>a.priority-b.priority||(a.id<b.id?-1:a.id>b.id?1:0);
function preferredLabels(labels:MapLabelInput[]):MapLabelInput[]{
  const heap:MapLabelInput[]=[];
  const siftDown=(start:number)=>{let root=start;while(true){let child=root*2+1;if(child>=heap.length)return;if(child+1<heap.length&&compareLabels(heap[child],heap[child+1])<0)child++;if(compareLabels(heap[root],heap[child])>=0)return;[heap[root],heap[child]]=[heap[child],heap[root]];root=child;}};
  for(const label of labels){
    if(heap.length<MAX_MAP_LABELS){heap.push(label);let child=heap.length-1;while(child>0){const parent=(child-1)>>1;if(compareLabels(heap[parent],heap[child])>=0)break;[heap[parent],heap[child]]=[heap[child],heap[parent]];child=parent;}}
    else if(compareLabels(label,heap[0])<0){heap[0]=label;siftDown(0);}
  }
  return heap.sort(compareLabels);
}
function boundedLabelText(text:string,measureText:(text:string)=>number,maxWidth=210):string{
  if(measureText(text)<=maxWidth)return text;
  const characters=[...text];let low=0,high=characters.length;
  while(low<high){const middle=Math.ceil((low+high)/2);if(measureText(`${characters.slice(0,middle).join('')}…`)<=maxWidth)low=middle;else high=middle-1;}
  return `${characters.slice(0,low).join('')}…`;
}
export function layoutMapLabels(labels:MapLabelInput[],canvasWidth:number,canvasHeight:number,measureText:(text:string)=>number=(text)=>text.length*7):PlacedMapLabel[]{
  const placed:PlacedMapLabel[]=[],cellSize=32,occupancy=new Map<string,PlacedMapLabel[]>();
  const cells=(x:number,y:number,width:number,height:number)=>{
    const result:string[]=[];
    for(let cx=Math.floor((x-3)/cellSize);cx<=Math.floor((x+width+3)/cellSize);cx++)
      for(let cy=Math.floor((y-3)/cellSize);cy<=Math.floor((y+height+3)/cellSize);cy++)result.push(`${cx}:${cy}`);
    return result;
  };
  const collides=(x:number,y:number,width:number,height:number)=>{
    const nearby=new Set(cells(x,y,width,height).flatMap((key)=>occupancy.get(key)??[]));
    return [...nearby].some((item)=>x<item.labelX+item.width+3&&x+width+3>item.labelX&&y<item.labelY+item.height+3&&y+height+3>item.labelY);
  };
  for(const source of preferredLabels(labels)){
    const text=boundedLabelText(source.text,measureText),label={...source,text};
    const width=Math.max(28,Math.ceil(measureText(text))+6),height=14;
    let selected:{x:number;y:number}|undefined;
    for(let radius=10;radius<=130&&!selected;radius+=12){
      for(const [dx,dy] of [[radius,-radius*.45],[radius,radius*.45],[-radius,-radius*.45],[-radius,radius*.45],[0,-radius],[0,radius]] as const){
        const x=dx<0?label.x+dx-width:label.x+dx,y=label.y+dy-height/2;
        if(x<2||y<2||x+width>canvasWidth-2||y+height>canvasHeight-2||collides(x,y,width,height))continue;
        selected={x,y};break;
      }
    }
    if(selected){
      const item={...label,labelX:selected.x,labelY:selected.y,width,height};placed.push(item);
      for(const key of cells(item.labelX,item.labelY,item.width,item.height)){const bucket=occupancy.get(key)??[];bucket.push(item);occupancy.set(key,bucket);}
    }
  }
  return placed;
}

export interface RailMapSegment { railId:string; elevation:number; from:RailMapPoint; to:RailMapPoint }
export function railSegmentsByVisualHeight(map:RailMapFile):RailMapSegment[]{
  return map.rails.flatMap((rail)=>rail.points.slice(1).map((to,index)=>({railId:rail.id,from:rail.points[index],to,elevation:(rail.points[index].y+to.y)/2})))
    .sort((a,b)=>a.elevation-b.elevation);
}
export function railSegmentVisualLayers(map:RailMapFile,tolerance=CONNECTED_ELEVATION_TOLERANCE_METERS):RailMapSegment[][]{
  const layers:RailMapSegment[][]=[];
  for(const segment of railSegmentsByVisualHeight(map)){
    const layer=layers.at(-1);
    if(!layer||segment.elevation-layer[0].elevation>tolerance)layers.push([segment]);
    else layer.push(segment);
  }
  return layers;
}

export const TRAIN_FRAME_STALE_MILLIS=3_000;
export function trainFrameIsStale(lastReceivedAt:number|null,now:number):boolean {
  return lastReceivedAt===null||now-lastReceivedAt>=TRAIN_FRAME_STALE_MILLIS;
}
/** Validate the complete wire frame before replacing the last known positions. */
export function parseTrainFrame(payload:string):RailMapTrain[] {
  const value:unknown=JSON.parse(payload);
  const object=(item:unknown):item is Record<string,unknown>=>typeof item==='object'&&item!==null;
  const finite=(item:unknown)=>typeof item==='number'&&Number.isFinite(item);
  const valid=Array.isArray(value)&&value.every((train:unknown)=>object(train)&&
    typeof train.id==='string'&&typeof train.name==='string'&&finite(train.revision)&&
    finite(train.speedMetersPerSecond)&&typeof train.derailed==='boolean'&&typeof train.reversed==='boolean'&&
    typeof train.railId==='string'&&finite(train.arcLengthMeters)&&(train.facing==='AB'||train.facing==='BA')&&
    Array.isArray(train.cars)&&train.cars.every((car:unknown)=>object(car)&&typeof car.id==='string'&&
      finite(car.lengthMeters)&&(car.lengthMeters as number)>0&&['x','y','z','directionX','directionZ'].every((key)=>finite(car[key]))));
  if(!valid)throw new Error('Invalid train frame');
  return value as RailMapTrain[];
}
