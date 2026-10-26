import { useMemo,useRef,useState } from 'react';
import { Button,Tooltip } from 'antd';
import { graphServiceColors,layoutGraph,type GraphPoint } from './graph';
import { useI18n } from './i18n';
import { formatTimetableTick } from './timetableTime';
import {graphEventAtPointer,type GraphEventContext} from './timetableEvents';
export type {GraphEventContext} from './timetableEvents';
import { useTheme } from './theme';
import type { LineV1,SavedTimetableV1,WorldMetadata } from './types';

type Pan={x:number;y:number};
type DragState={pointerId:number;x:number;y:number;originX:number;originY:number};
type ServicePath={serviceIndex:number;serviceId:string;points:GraphPoint[]};

const pointKey=(point:GraphPoint)=>`${point.serviceIndex}:${point.stepIndex}:${point.kind}:${point.location}`;
const clamp=(value:number)=>Math.min(3.2,Math.max(.55,value));

export function Graph({
  events=[],value,onSelect,metadata,line,graphLineId,highlightedServiceIds,previewServiceIds,onSelectPoint,onDismissPoint,onEventContext,
}:{
  events?:import('./types').TimetableEventPoint[];
  value:SavedTimetableV1;
  metadata?:WorldMetadata;
  line?:LineV1;
  graphLineId?:string;
  highlightedServiceIds?:ReadonlySet<string>;
  previewServiceIds?:ReadonlySet<string>;
  onSelect?:(service:number,step:number)=>void;
  onSelectPoint?:(point:GraphPoint,anchor:{x:number;y:number})=>void;
  onDismissPoint?:()=>void;
  onEventContext?:(context:GraphEventContext)=>void;
}) {
  const {locale,t}=useI18n();
  const {theme}=useTheme();
  const [pan,setPan]=useState<Pan>({x:28,y:24});
  const [scaleX,setScaleX]=useState(1);
  const [scaleY,setScaleY]=useState(1);
  const [hoveredPoint,setHoveredPoint]=useState<string|null>(null);
  const [panning,setPanning]=useState(false);
  const drag=useRef<DragState|null>(null);
  const sheetWidth=Math.round(1_000*scaleX);
  const minimumSheetHeight=Math.round(620*scaleY);
  const layout=useMemo(()=>layoutGraph(value,sheetWidth,88*scaleY,minimumSheetHeight,metadata,line,graphLineId),[
    value,sheetWidth,minimumSheetHeight,scaleY,metadata,line,graphLineId,
  ]);
  const sheetHeight=Math.ceil(layout.height);
  const stationNames=useMemo(()=>new Map((metadata?.stations??[]).map((station)=>[
    station.stationId,station.stationDisplayName||station.stationId,
  ])),[metadata?.stations]);
  const services=useMemo(()=>{
    const grouped=new Map<number,ServicePath>();
    layout.paths.forEach((path)=>{
      const target=grouped.get(path.serviceIndex)??{serviceIndex:path.serviceIndex,serviceId:path.serviceId,points:[]};
      path.points.forEach((point)=>{
        if(!target.points.some((candidate)=>pointKey(candidate)===pointKey(point)))target.points.push(point);
      });
      grouped.set(path.serviceIndex,target);
    });
    return [...grouped.values()].map((path)=>({...path,points:path.points.sort((a,b)=>a.tick-b.tick)}));
  },[layout.paths]);
  const palette=graphServiceColors(theme);
  const period=Math.max(1,value.periodDays*24_000);
  const tickInterval=scaleX>=2?1_000:scaleX>=1.25?2_000:4_000;
  const ticks:Array<{tick:number;x:number}>=[];
  for(let tick=0;tick<=period;tick+=tickInterval)ticks.push({tick,x:layout.tickToX(tick)});
  const graphColor=(serviceIndex:number)=>line?.color||palette[serviceIndex%palette.length];
  const reset=()=>{onDismissPoint?.();setPan({x:28,y:24});setScaleX(1);setScaleY(1);};
  const zoom=(axis:'x'|'y',factor:number)=>{onDismissPoint?.();(axis==='x'?setScaleX:setScaleY)((current)=>clamp(current*factor));};

  return <div className={`graph-wrap ${panning?'is-panning':''}`}
    onWheel={(event)=>{event.preventDefault();onDismissPoint?.();setPan((current)=>({x:current.x-event.deltaX,y:current.y-event.deltaY}));}}
    onPointerDown={(event)=>{
      if(event.button!==0||(event.target as HTMLElement).closest('.canvas-controls,[data-graph-point],[data-event-id]'))return;
      onDismissPoint?.();event.currentTarget.setPointerCapture(event.pointerId);
      drag.current={pointerId:event.pointerId,x:event.clientX,y:event.clientY,originX:pan.x,originY:pan.y};setPanning(true);
    }}
    onPointerMove={(event)=>{if(!drag.current||drag.current.pointerId!==event.pointerId)return;setPan({x:drag.current.originX+event.clientX-drag.current.x,y:drag.current.originY+event.clientY-drag.current.y});}}
    onPointerUp={(event)=>{if(drag.current?.pointerId===event.pointerId)event.currentTarget.releasePointerCapture(event.pointerId);drag.current=null;setPanning(false);}}
    onPointerCancel={()=>{drag.current=null;setPanning(false);}}>
    <div className="canvas-controls" aria-label={t('editor.graphViewport')} onPointerDown={(event)=>event.stopPropagation()}>
      <Button.Group>
        <Tooltip title={t('editor.zoomXIn')}><Button onClick={()=>zoom('x',1.25)}>+X</Button></Tooltip>
        <Tooltip title={t('editor.zoomXOut')}><Button onClick={()=>zoom('x',.8)}>−X</Button></Tooltip>
        <Tooltip title={t('editor.zoomYIn')}><Button onClick={()=>zoom('y',1.25)}>+Y</Button></Tooltip>
        <Tooltip title={t('editor.zoomYOut')}><Button onClick={()=>zoom('y',.8)}>−Y</Button></Tooltip>
        <Button className="axis-readout" disabled>{Math.round(scaleX*100)}% · {Math.round(scaleY*100)}%</Button>
        <Tooltip title={t('editor.graphReset')}><Button onClick={reset}>1:1</Button></Tooltip>
      </Button.Group>
    </div>
    {onEventContext&&<div className="graph-event-hint">{t('events.graphHint')}</div>}
    <div className="canvas-sheet" style={{width:sheetWidth,height:sheetHeight,transform:`translate(${pan.x}px,${pan.y}px)`}}>
      <svg className="diagram-svg" width={sheetWidth} height={sheetHeight} viewBox={`0 0 ${sheetWidth} ${sheetHeight}`} role={onSelectPoint||onSelect||onEventContext?'group':'img'} aria-label={t('editor.graph')}
        onContextMenu={(event)=>{
          if(!onEventContext)return;
          event.preventDefault();
          const selected=graphEventAtPointer(layout,event.currentTarget.getBoundingClientRect(),{x:event.clientX,y:event.clientY},new Set(layout.lanes.filter((lane)=>lane.id!=='No locations').map((lane)=>lane.id)),period);
          if(selected){onDismissPoint?.();onEventContext({...selected,anchor:{x:event.clientX,y:event.clientY}});}
        }}>
        <defs><pattern id="timetable-minor-grid" width="40" height="22" patternUnits="userSpaceOnUse"><path d="M 40 0 L 0 0 0 22" className="minor-grid-line"/></pattern></defs>
        <rect x={layout.left} width={Math.max(0,sheetWidth-layout.left)} height={sheetHeight} fill="url(#timetable-minor-grid)"/>
        {ticks.map(({tick,x})=><g key={tick}><line className="time-grid-line" x1={x} y1={42} x2={x} y2={sheetHeight}/><text className="time-axis tick-label" x={x+5} y={16}>{tick}</text><text className="time-axis translated-time" x={x+5} y={31}>{formatTimetableTick(tick,locale)}</text></g>)}
        {layout.lanes.map((lane)=><g key={lane.id}><line className="station-grid-line" x1={layout.left} y1={lane.y} x2={sheetWidth} y2={lane.y}/><text className="station-label" x={layout.left-12} y={lane.y+4} textAnchor="end">{stationNames.get(lane.id)||lane.id}</text></g>)}
        {services.map((service)=>{
          const color=graphColor(service.serviceIndex);
          const hasSelection=(highlightedServiceIds?.size??0)>0;
          const selected=!hasSelection||highlightedServiceIds!.has(service.serviceId);
          const preview=previewServiceIds?.has(service.serviceId)??false;
          const first=service.points[0];
          const labelWidth=Math.max(58,service.serviceId.length*7+18);
          return <g key={`${service.serviceIndex}:${service.serviceId}`} opacity={selected?1:.28}>
            {service.points.length>1&&<polyline points={service.points.map((point)=>`${point.x},${point.y}`).join(' ')} fill="none" stroke={color} strokeWidth={selected?5:3} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={preview?'10 7':undefined} className={selected?'selected-path':undefined}/>}
            {first&&selected&&<g transform={`translate(${first.x+12} ${Math.max(43,first.y-34)})`}><rect width={labelWidth} height="25" rx="5" fill={color}/><text x={labelWidth/2} y="17" textAnchor="middle" fill="#fff" fontWeight="700" fontSize="12">{service.serviceId}</text></g>}
            {service.points.map((point)=>{
              const label=`${service.serviceId} · ${stationNames.get(point.location)||point.location} · ${t(point.kind==='ARRIVAL'?'editor.arrivalTime':'editor.departureTime')} · ${point.tick} · ${formatTimetableTick(point.tick,locale)}`;
              const select=(element:SVGCircleElement)=>{
                const bounds=element.getBoundingClientRect();
                onSelect?.(point.serviceIndex,point.stepIndex);
                onSelectPoint?.(point,{x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2});
              };
              return <circle key={pointKey(point)} data-graph-point cx={point.x} cy={point.y} r={selected?6:4.5} fill="var(--ant-color-bg-container)" stroke={color} strokeWidth={selected?4:3}
                role={onSelectPoint||onSelect?'button':undefined} tabIndex={onSelectPoint||onSelect?0:undefined} aria-label={label}
                style={{cursor:onSelectPoint||onSelect?'pointer':undefined}}
                onPointerDown={(event)=>event.stopPropagation()}
                onClick={(event)=>select(event.currentTarget)}
                onKeyDown={(event)=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select(event.currentTarget);}}}
                onMouseEnter={()=>setHoveredPoint(pointKey(point))} onMouseLeave={()=>setHoveredPoint(null)}><title>{label}</title></circle>;
            })}
          </g>;
        })}
        {events.map((event)=>{
          const lane=layout.lanes.find((lane)=>lane.id===event.stationId);if(!lane)return null;
          const label=`${event.label||event.id} · ${stationNames.get(event.stationId)||event.stationId} · ${formatTimetableTick(event.tick,locale)}`;
          const select=(element:SVGGElement,anchor?:{x:number;y:number})=>{
            if(!onEventContext)return;
            const bounds=element.getBoundingClientRect();
            onDismissPoint?.();onEventContext({eventId:event.id,stationId:event.stationId,tick:event.tick,anchor:anchor??{x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2}});
          };
          return <g key={`event-${event.id}`} data-event-id={event.id} transform={`translate(${layout.tickToX(event.tick)} ${lane.y})`}
            role={onEventContext?'button':undefined} tabIndex={onEventContext?0:undefined} aria-label={label}
            style={{cursor:onEventContext?'pointer':undefined}}
            onPointerDown={(mouse)=>mouse.stopPropagation()}
            onClick={(mouse)=>{mouse.stopPropagation();select(mouse.currentTarget);}}
            onContextMenu={(mouse)=>{if(!onEventContext)return;mouse.preventDefault();mouse.stopPropagation();select(mouse.currentTarget,{x:mouse.clientX,y:mouse.clientY});}}
            onKeyDown={(key)=>{if(key.key==='Enter'||key.key===' '||key.key==='ContextMenu'||(key.key==='F10'&&key.shiftKey)){key.preventDefault();select(key.currentTarget);}}}>
            <title>{label}</title><path d="M 0 -7 L 7 0 L 0 7 L -7 0 Z" fill="var(--ant-color-primary)"/><text x={10} y={-9} fill="currentColor">{event.label||event.id}</text>
          </g>;
        })}
        {hoveredPoint&&layout.points.filter((point)=>pointKey(point)===hoveredPoint).map((point)=><g className="graph-point-tooltip" key={`tooltip-${pointKey(point)}`} transform={`translate(${point.x+10} ${point.y-30})`}><rect width="158" height="24" rx="5"/><text x="8" y="16">{stationNames.get(point.location)||point.location} · {point.tick}</text></g>)}
      </svg>
    </div>
  </div>;
}
