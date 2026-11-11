import { useEffect,useMemo,useRef,useState } from 'react';
import { Button,Tooltip } from 'antd';
import { graphServiceColor,layoutGraph,type GraphPoint } from './graph';
import { useI18n } from './i18n';
import { formatTimetableTick } from './timetableTime';
import type {TimedOperation} from './types';
import type {ProjectedTimedOperation} from './timedOperations';
import {type GraphEventContext} from './timetableEvents';
export type {GraphEventContext} from './timetableEvents';
import { useTheme } from './theme';
import type { LineV1,SavedTimetableV1,WorldMetadata } from './types';

export interface GraphDutyOperationContext {dutyId:string;elementId:string;operation:TimedOperation;anchor:{x:number;y:number}}

type Pan={x:number;y:number};
type DragState={pointerId:number;x:number;y:number;originX:number;originY:number;moved:boolean};
type ServicePath={serviceIndex:number;serviceId:string;points:GraphPoint[]};

const clamp=(value:number)=>Math.min(3.2,Math.max(.55,value));

export function Graph({
  operations=[],onOpenDutyOperation,onDutyOperationContext,value,onSelect,metadata,line,graphLineId,serviceColors,serviceColorIds,highlightedServiceIds,previewServiceIds,onSelectPoint,onDismissPoint,onClearSelection,onSelectEvent,onEditEvent,onEventContext,
}:{
  operations?:ProjectedTimedOperation[];onDutyOperationContext?:(context:GraphDutyOperationContext)=>void;onOpenDutyOperation?:(dutyId:string,elementId:string)=>void;
  value:SavedTimetableV1;
  metadata?:WorldMetadata;
  line?:LineV1;
  graphLineId?:string;
  serviceColors?:ReadonlyMap<string,string>;
  serviceColorIds?:readonly string[];
  highlightedServiceIds?:ReadonlySet<string>;
  previewServiceIds?:ReadonlySet<string>;
  onSelect?:(service:number,step:number)=>void;
  onSelectPoint?:(point:GraphPoint,anchor:{x:number;y:number})=>void;
  onDismissPoint?:()=>void;
  onClearSelection?:()=>void;
  onSelectEvent?:(event:import('./types').TimetableEventPoint)=>void;
  onEditEvent?:(event:import('./types').TimetableEventPoint)=>void;
  onEventContext?:(context:GraphEventContext)=>void;
}) {
  const {locale,t}=useI18n();
  const {theme}=useTheme();
  const [pan,setPan]=useState<Pan>({x:28,y:24});
  const [scaleX,setScaleX]=useState(1);
  const [scaleY,setScaleY]=useState(1);
  const [hoveredPoint,setHoveredPoint]=useState<string|null>(null);
  const [focusedPoint,setFocusedPoint]=useState<string|null>(null);
  const serviceIdentity=JSON.stringify(value.services.map((service)=>service.id));
  useEffect(()=>{setHoveredPoint(null);setFocusedPoint(null);},[serviceIdentity]);
  const pointKey=(point:GraphPoint)=>JSON.stringify([value.services[point.serviceIndex]?.id,point.runId,point.stepIndex,point.kind,point.location]);
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
  const colorIds=serviceColorIds??value.services.map((service)=>service.id);
  const period=Math.max(1,value.periodDays*24_000);
  const tickInterval=scaleX>=2?1_000:scaleX>=1.25?2_000:4_000;
  const ticks:Array<{tick:number;x:number}>=[];
  for(let tick=0;tick<=period;tick+=tickInterval)ticks.push({tick,x:layout.tickToX(tick)});
  const graphColor=(service:ServicePath)=>graphServiceColor(theme,service.serviceId,colorIds,line?.color,serviceColors?.get(service.serviceId));
  const reset=()=>{onDismissPoint?.();setPan({x:28,y:24});setScaleX(1);setScaleY(1);};
  const zoom=(axis:'x'|'y',factor:number)=>{onDismissPoint?.();(axis==='x'?setScaleX:setScaleY)((current)=>clamp(current*factor));};

  return <div className={`graph-wrap ${panning?'is-panning':''}`}
    onWheel={(event)=>{event.preventDefault();onDismissPoint?.();setPan((current)=>({x:current.x-event.deltaX,y:current.y-event.deltaY}));}}
    onPointerDown={(event)=>{
      if(event.button!==0||(event.target as HTMLElement).closest('.canvas-controls,[data-graph-point],[data-event-id],[data-duty-operation]'))return;
      onDismissPoint?.();event.currentTarget.setPointerCapture(event.pointerId);
      drag.current={pointerId:event.pointerId,x:event.clientX,y:event.clientY,originX:pan.x,originY:pan.y,moved:false};setPanning(true);
    }}
    onPointerMove={(event)=>{const state=drag.current;if(!state||state.pointerId!==event.pointerId)return;if(!state.moved&&(Math.abs(event.clientX-state.x)>3||Math.abs(event.clientY-state.y)>3))state.moved=true;setPan({x:state.originX+event.clientX-state.x,y:state.originY+event.clientY-state.y});}}
    onPointerUp={(event)=>{const state=drag.current;if(state?.pointerId===event.pointerId)event.currentTarget.releasePointerCapture(event.pointerId);drag.current=null;setPanning(false);if(state&&!state.moved)onClearSelection?.();}}
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
    <div className="canvas-sheet" style={{width:sheetWidth,height:sheetHeight,transform:`translate(${pan.x}px,${pan.y}px)`}}>
      <svg className="diagram-svg" width={sheetWidth} height={sheetHeight} viewBox={`0 0 ${sheetWidth} ${sheetHeight}`} role={onSelectPoint||onSelect||onSelectEvent||onEditEvent||onEventContext||onOpenDutyOperation?'group':'img'} aria-label={t('editor.graph')}>

        <defs><pattern id="timetable-minor-grid" width="40" height="22" patternUnits="userSpaceOnUse"><path d="M 40 0 L 0 0 0 22" className="minor-grid-line"/></pattern></defs>
        <rect x={layout.left} width={Math.max(0,sheetWidth-layout.left)} height={sheetHeight} fill="url(#timetable-minor-grid)"/>
        {ticks.map(({tick,x})=><g key={tick}><line className="time-grid-line" x1={x} y1={42} x2={x} y2={sheetHeight}/><text className="time-axis tick-label" x={x+5} y={16}>{tick}</text><text className="time-axis translated-time" x={x+5} y={31}>{formatTimetableTick(tick,locale)}</text></g>)}
        {layout.lanes.map((lane)=><g key={lane.id}><line className="station-grid-line" x1={layout.left} y1={lane.y} x2={sheetWidth} y2={lane.y}/><text className="station-label" x={layout.left-12} y={lane.y+4} textAnchor="end">{stationNames.get(lane.id)||lane.id}</text></g>)}
        {operations.map(({operation:event,owner,dutyId,elementId,endTick})=>{
          const lane=layout.lanes.find((lane)=>lane.id===event.stationId);if(!lane)return null;
          const name=event.label||event.program?.programId||event.id;
          const label=`${name} · ${stationNames.get(event.stationId!)||event.stationId} · ${formatTimetableTick(event.tick,locale)}${endTick!==undefined&&endTick!==event.tick?`–${formatTimetableTick(endTick,locale)}`:''}${owner==='DUTY'?` · ${t('timed.dutyOwned')} ${dutyId}`:''}`;
          const editable=owner==='DUTY'?!!onOpenDutyOperation:!!onSelectEvent||!!onEditEvent||!!onEventContext;
          const open=()=>{onDismissPoint?.();if(owner==='DUTY')onOpenDutyOperation?.(dutyId!,elementId!);else if(onSelectEvent)onSelectEvent(structuredClone(event));else onEditEvent?.(structuredClone(event));};
          const menu=(element:SVGGElement,anchor?:{x:number;y:number})=>{
            const bounds=element.getBoundingClientRect();
            if(owner==='DUTY'){onDismissPoint?.();onDutyOperationContext?.({dutyId:dutyId!,elementId:elementId!,operation:structuredClone(event),anchor:anchor??{x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2}});return;}if(!onEventContext)return;
            onDismissPoint?.();onEventContext({event:{...event},anchor:anchor??{x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2}});
          };
          return <g key={`event-${owner}-${dutyId??''}-${event.id}`} data-event-id={owner==='GRAPH'?event.id:undefined} data-operation-owner={owner} data-duty-operation={owner==='DUTY'?`${dutyId}/${elementId}`:undefined} transform={`translate(${layout.tickToX(event.tick)} ${lane.y})`}
            role={editable?'button':undefined} tabIndex={editable?0:undefined} aria-label={label} aria-haspopup={(owner==='GRAPH'?onEventContext:onDutyOperationContext)?'menu':undefined}
            style={{cursor:editable?'pointer':undefined}}
            onPointerDown={(mouse)=>mouse.stopPropagation()}
            onClick={(mouse)=>{mouse.stopPropagation();open();}}
            onContextMenu={(mouse)=>{if(!(owner==='DUTY'?onDutyOperationContext:onEventContext))return;mouse.preventDefault();mouse.stopPropagation();menu(mouse.currentTarget,{x:mouse.clientX,y:mouse.clientY});}}
            onKeyDown={(key)=>{
              if(key.key==='ContextMenu'||(key.key==='F10'&&key.shiftKey)){key.preventDefault();key.stopPropagation();menu(key.currentTarget);}
              else if(key.key==='Enter'||key.key===' '){key.preventDefault();key.stopPropagation();open();}
            }}>
            <title>{label}</title>{endTick!==undefined&&endTick>event.tick&&<line x1={0} y1={0} x2={Math.max(2,layout.tickToX(endTick)-layout.tickToX(event.tick))} y2={0} stroke="var(--ant-color-primary)" strokeWidth={1.25} strokeDasharray={owner==='DUTY'?'4 3':undefined}/>}{endTick===undefined&&<text x={10} y={12} fill="currentColor">{t('timeline.unknown')}</text>}<circle r={9} fill="transparent"/><path d="M 0 -4 L 4 0 L 0 4 L -4 0 Z" fill="var(--ant-color-primary)"/><text x={10} y={-7} fill="currentColor">{name}{owner==='DUTY'?` · ${dutyId}`:''}</text>
          </g>;
        })}
        {services.map((service)=>{
          const color=graphColor(service);
          const hasSelection=(highlightedServiceIds?.size??0)>0;
          const selected=highlightedServiceIds?.has(service.serviceId)??false;
          const visible=!hasSelection||selected;
          const preview=previewServiceIds?.has(service.serviceId)??false;
          const first=service.points[0];
          const labelWidth=Math.max(20,service.serviceId.length*6.5+8);
          return <g key={`${service.serviceIndex}:${service.serviceId}`} opacity={visible?1:.28}>
            {service.points.length>1&&<><polyline points={service.points.map((point)=>`${point.x},${point.y}`).join(' ')} fill="none" stroke={color} strokeWidth={selected?2:hasSelection?1:1.25} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={preview?'10 7':undefined} className={selected?'selected-path':undefined} pointerEvents="none"/>{onSelect&&<polyline points={service.points.map((point)=>`${point.x},${point.y}`).join(' ')} fill="none" stroke="transparent" strokeWidth={12} strokeLinecap="round" strokeLinejoin="round" pointerEvents="stroke" style={{cursor:'pointer'}} onPointerDown={(event)=>event.stopPropagation()} onClick={()=>onSelect(service.serviceIndex,0)}/>}</>}
            {first&&visible&&(()=>{const above=first.y>54;const labelX=Math.min(Math.max(first.x,labelWidth/2+4),sheetWidth-labelWidth/2-4);const labelY=above?first.y-24:first.y+24;const lineEnd=above?labelY+8:labelY-8;return <g><line x1={first.x} y1={first.y} x2={labelX} y2={lineEnd} stroke={color} strokeWidth={1}/><rect x={labelX-labelWidth/2} y={labelY-8} width={labelWidth} height="16" rx="3" fill={color}/><text x={labelX} y={labelY+3} textAnchor="middle" fill="#fff" fontWeight="700" fontSize="10">{service.serviceId}</text></g>;})()}
            {service.points.map((point)=>{
              const label=`${service.serviceId} · ${stationNames.get(point.location)||point.location} · ${t(point.kind==='ARRIVAL'?'editor.arrivalTime':'editor.departureTime')} · ${point.tick} · ${formatTimetableTick(point.tick,locale)}`;
              const select=(element:SVGCircleElement)=>{
                const bounds=element.getBoundingClientRect();
                onSelect?.(point.serviceIndex,point.stepIndex);
                onSelectPoint?.(point,{x:bounds.left+bounds.width/2,y:bounds.top+bounds.height/2});
              };
              return <g key={pointKey(point)}>
                <circle data-graph-point cx={point.x} cy={point.y} r={12} fill="transparent" stroke="none"
                role={onSelectPoint||onSelect?'button':undefined} tabIndex={onSelectPoint||onSelect?0:undefined} aria-label={label}
                style={{cursor:onSelectPoint||onSelect?'pointer':undefined}}
                onPointerDown={(event)=>event.stopPropagation()}
                onClick={(event)=>select(event.currentTarget)}
                onKeyDown={(event)=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();setFocusedPoint(pointKey(point));select(event.currentTarget);}}}
                onMouseEnter={()=>setHoveredPoint(pointKey(point))} onMouseLeave={()=>setHoveredPoint(null)}
                onFocus={(event)=>setFocusedPoint(event.currentTarget.matches(':focus-visible')?pointKey(point):null)} onBlur={()=>setFocusedPoint(null)}><title>{label}</title></circle>
                {(hoveredPoint===pointKey(point)||focusedPoint===pointKey(point))&&<circle data-graph-point-preview cx={point.x} cy={point.y} r={6} fill="var(--ant-color-bg-container)" stroke={color} strokeWidth={3} pointerEvents="none" aria-hidden="true"/>}
              </g>;
            })}
          </g>;
        })}
        {(hoveredPoint||focusedPoint)&&layout.points.filter((point)=>pointKey(point)===(hoveredPoint??focusedPoint)).map((point)=><g className="graph-point-tooltip" key={`tooltip-${pointKey(point)}`} transform={`translate(${point.x+10} ${point.y-30})`}><rect width="158" height="24" rx="5"/><text x="8" y="16">{stationNames.get(point.location)||point.location} · {point.tick}</text></g>)}
      </svg>
    </div>
  </div>;
}
