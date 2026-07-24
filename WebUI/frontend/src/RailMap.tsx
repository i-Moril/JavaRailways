import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import { DESTINATION_MARKER_RADIUS, MAP_PADDING, RAIL_CASING_WIDTH, RAIL_STROKE_WIDTH, STATION_MARKER_RADIUS, layoutMapLabels, railMapBounds, railSegmentVisualLayers, trainCarRectangle, zoomMapViewport, type MapLabelInput, type MapViewport, type RailMapSegment } from './railMap';
import { useI18n } from './i18n';
import { useTheme } from './theme';
import type { RailMapFile, RailMapTrain } from './types';

export function RailMap({world,onError}:{world:string;onError:(message:string)=>void}) {
  const {t}=useI18n();
  const {theme}=useTheme();
  const canvas=useRef<HTMLCanvasElement>(null);
  const [map,setMap]=useState<RailMapFile>();
  const [trains,setTrains]=useState<RailMapTrain[]>([]);
  const [size,setSize]=useState({width:900,height:600});
  const [viewport,setViewport]=useState<MapViewport>({zoom:1,offsetX:0,offsetY:0});
  const drag=useRef<{x:number;y:number;offsetX:number;offsetY:number}|null>(null);

  useEffect(()=>{
    if(!world)return;
    setMap(undefined);setTrains([]);setViewport({zoom:1,offsetX:0,offsetY:0});
    let cancelled=false;
    void api.railMap(world).then((value)=>{if(!cancelled)setMap(value);}).catch((failure)=>onError(failure instanceof Error?failure.message:String(failure)));
    const source=new EventSource(`/api/v1/worlds/${encodeURIComponent(world)}/rail-map/trains`);
    source.addEventListener('trains',(event)=>{try{setTrains(JSON.parse((event as MessageEvent).data) as RailMapTrain[]);}catch{/* ignore malformed frame */}});
    source.onerror=()=>{/* EventSource reconnects with browser-managed backoff. */};
    return()=>{cancelled=true;source.close();};
  },[world]);

  useEffect(()=>{
    const element=canvas.current;if(!element)return;
    const resize=()=>setSize({width:element.parentElement?.clientWidth||900,height:element.parentElement?.clientHeight||600});
    resize();const observer=new ResizeObserver(resize);observer.observe(element.parentElement!);return()=>observer.disconnect();
  },[]);

  useEffect(()=>{
    const element=canvas.current;if(!element)return;
    const density=devicePixelRatio||1;element.width=size.width*density;element.height=size.height*density;
    const context=element.getContext('2d')!;context.scale(density,density);context.clearRect(0,0,size.width,size.height);
    const styles=getComputedStyle(document.documentElement),color=(name:string,fallback:string)=>styles.getPropertyValue(name).trim()||fallback;
    const background=color('--canvas-bg','#07101d'),labelColor=color('--canvas-text','#dbe8f7'),railColor=color('--canvas-rail','#7893ad');
    context.fillStyle=background;context.fillRect(0,0,size.width,size.height);
    if(!map){context.fillStyle=color('--canvas-label','#8195af');context.fillText(t('map.loading'),20,30);return;}
    const bounds=railMapBounds(map);const spanX=Math.max(1,bounds.maxX-bounds.minX);const spanZ=Math.max(1,bounds.maxZ-bounds.minZ);
    const fit=Math.min((size.width-MAP_PADDING*2)/spanX,(size.height-MAP_PADDING*2)/spanZ);const scale=fit*viewport.zoom;
    const project=(x:number,z:number)=>({x:MAP_PADDING+(x-bounds.minX)*scale+viewport.offsetX,y:MAP_PADDING+(z-bounds.minZ)*scale+viewport.offsetY});
    const strokeSegment=(segment:RailMapSegment,strokeStyle:string,lineWidth:number)=>{
      const from=project(segment.from.x,segment.from.z),to=project(segment.to.x,segment.to.z);
      context.beginPath();context.moveTo(from.x,from.y);context.lineTo(to.x,to.y);context.strokeStyle=strokeStyle;context.lineWidth=lineWidth;context.stroke();
    };
    context.lineCap='round';context.lineJoin='round';
    // Segments within one elevation layer share a casing pass, so near-level crossings remain connected.
    // A distinctly higher layer is painted later and its casing still erases the lower overpass.
    for(const layer of railSegmentVisualLayers(map)){
      for(const segment of layer)strokeSegment(segment,background,RAIL_CASING_WIDTH);
      for(const segment of layer)strokeSegment(segment,railColor,RAIL_STROKE_WIDTH);
    }
    const labels:MapLabelInput[]=[];
    for(const marker of map.markers){
      const point=project(marker.x,marker.z);context.beginPath();context.arc(point.x,point.y,marker.kind==='STATION'?STATION_MARKER_RADIUS:DESTINATION_MARKER_RADIUS,0,Math.PI*2);
      const markerColor=marker.kind==='STATION'?'#087caf':marker.kind==='DEPOT'?'#d68117':'#7b8fa4';context.fillStyle=markerColor;context.fill();
      if(viewport.zoom>=1.2||marker.kind==='STATION')labels.push({id:`marker:${marker.id}`,text:marker.label||marker.id,x:point.x,y:point.y,priority:marker.kind==='STATION'?0:marker.kind==='DEPOT'?1:2,color:labelColor});
    }
    for(const train of trains){
      const carColor=train.derailed?'#d9364f':'#43a65d';
      for(const car of train.cars){
        const polygon=trainCarRectangle(car,project,scale);context.beginPath();context.moveTo(polygon[0].x,polygon[0].y);
        for(const point of polygon.slice(1))context.lineTo(point.x,point.y);context.closePath();context.fillStyle=carColor;context.fill();
        context.strokeStyle=background;context.lineWidth=1;context.stroke();
      }
      const firstCar=train.cars[0];if(!firstCar)continue;
      const point=project(firstCar.x,firstCar.z);
      labels.push({id:`train:${train.id}`,text:train.name,x:point.x,y:point.y,priority:0,color:labelColor});
    }
    context.font='11px system-ui';context.lineWidth=1;
    for(const label of layoutMapLabels(labels,size.width,size.height,(text)=>context.measureText(text).width)){
      context.strokeStyle=color('--canvas-grid','#30445f');context.beginPath();context.moveTo(label.x,label.y);context.lineTo(label.labelX,label.labelY+label.height/2);context.stroke();
      context.fillStyle=background;context.fillRect(label.labelX-2,label.labelY-1,label.width+4,label.height+2);
      context.fillStyle=label.color;context.fillText(label.text,label.labelX+3,label.labelY+11);
    }
  },[map,trains,size,viewport,t,theme]);

  const position=(event:React.PointerEvent|React.WheelEvent)=>{const bounds=event.currentTarget.getBoundingClientRect();return{x:event.clientX-bounds.left,y:event.clientY-bounds.top};};
  return <section className="rail-map-page"><div className="map-overlay"><strong>{t('map.title')}</strong><span>{t('map.hint')}</span><button onClick={()=>setViewport({zoom:1,offsetX:0,offsetY:0})}>{t('map.reset')}</button></div>
    <canvas ref={canvas} className="rail-map-canvas" onWheel={(event)=>{event.preventDefault();const pointer=position(event);setViewport((current)=>zoomMapViewport(current,current.zoom*(event.deltaY<0?1.15:0.87),pointer));}}
      onPointerDown={(event)=>{const p=position(event);drag.current={...p,offsetX:viewport.offsetX,offsetY:viewport.offsetY};event.currentTarget.setPointerCapture(event.pointerId);}}
      onPointerMove={(event)=>{if(!drag.current)return;const p=position(event),start=drag.current;setViewport((current)=>({...current,offsetX:start.offsetX+p.x-start.x,offsetY:start.offsetY+p.y-start.y}));}}
      onPointerUp={()=>{drag.current=null;}} onPointerCancel={()=>{drag.current=null;}} />
  </section>;
}
