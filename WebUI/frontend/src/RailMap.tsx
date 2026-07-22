import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import { clampMapZoom, interpolateTrainPosition, railMapBounds, railSegmentsByVisualHeight, type MapViewport } from './railMap';
import { useI18n } from './i18n';
import type { RailMapFile, RailMapTrain } from './types';

export function RailMap({world,onError}:{world:string;onError:(message:string)=>void}) {
  const {t}=useI18n();
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
    context.fillStyle='#07101d';context.fillRect(0,0,size.width,size.height);
    if(!map){context.fillStyle='#8195af';context.fillText(t('map.loading'),20,30);return;}
    const bounds=railMapBounds(map);const spanX=Math.max(1,bounds.maxX-bounds.minX);const spanZ=Math.max(1,bounds.maxZ-bounds.minZ);
    const fit=Math.min((size.width-80)/spanX,(size.height-80)/spanZ);const scale=fit*viewport.zoom;
    const project=(x:number,z:number)=>({x:40+(x-bounds.minX)*scale+viewport.offsetX,y:40+(z-bounds.minZ)*scale+viewport.offsetY});
    context.lineCap='round';context.lineJoin='round';
    for(const segment of railSegmentsByVisualHeight(map)){
      const from=project(segment.from.x,segment.from.z),to=project(segment.to.x,segment.to.z);
      context.beginPath();context.moveTo(from.x,from.y);context.lineTo(to.x,to.y);
      context.strokeStyle='#07101d';context.lineWidth=9;context.stroke();
      context.beginPath();context.moveTo(from.x,from.y);context.lineTo(to.x,to.y);
      context.strokeStyle='#7893ad';context.lineWidth=3;context.stroke();
    }
    for(const marker of map.markers){
      const point=project(marker.x,marker.z);context.beginPath();context.arc(point.x,point.y,marker.kind==='STATION'?5:3,0,Math.PI*2);
      context.fillStyle=marker.kind==='STATION'?'#56c5ff':marker.kind==='DEPOT'?'#ffb454':'#b8c6d8';context.fill();
      if(viewport.zoom>=1.2||marker.kind==='STATION'){context.fillStyle='#dbe8f7';context.font='11px system-ui';context.fillText(marker.label||marker.id,point.x+7,point.y-5);}
    }
    for(const train of trains){const location=interpolateTrainPosition(map,train);if(!location)continue;const point=project(location.x,location.z);
      context.beginPath();context.arc(point.x,point.y,6,0,Math.PI*2);context.fillStyle=train.derailed?'#ff718b':'#8bd17c';context.fill();
      context.fillStyle='#fff';context.font='bold 11px system-ui';context.fillText(train.id,point.x+8,point.y+4);
    }
  },[map,trains,size,viewport,t]);

  const position=(event:React.PointerEvent|React.WheelEvent)=>{const bounds=event.currentTarget.getBoundingClientRect();return{x:event.clientX-bounds.left,y:event.clientY-bounds.top};};
  return <section className="rail-map-page"><div className="map-overlay"><strong>{t('map.title')}</strong><span>{t('map.hint')}</span><button onClick={()=>setViewport({zoom:1,offsetX:0,offsetY:0})}>{t('map.reset')}</button></div>
    <canvas ref={canvas} className="rail-map-canvas" onWheel={(event)=>{event.preventDefault();const pointer=position(event);setViewport((current)=>{const zoom=clampMapZoom(current.zoom*(event.deltaY<0?1.15:0.87));const ratio=zoom/current.zoom;return{zoom,offsetX:pointer.x-(pointer.x-current.offsetX)*ratio,offsetY:pointer.y-(pointer.y-current.offsetY)*ratio};});}}
      onPointerDown={(event)=>{const p=position(event);drag.current={...p,offsetX:viewport.offsetX,offsetY:viewport.offsetY};event.currentTarget.setPointerCapture(event.pointerId);}}
      onPointerMove={(event)=>{if(!drag.current)return;const p=position(event),start=drag.current;setViewport((current)=>({...current,offsetX:start.offsetX+p.x-start.x,offsetY:start.offsetY+p.y-start.y}));}}
      onPointerUp={()=>{drag.current=null;}} onPointerCancel={()=>{drag.current=null;}} />
  </section>;
}
