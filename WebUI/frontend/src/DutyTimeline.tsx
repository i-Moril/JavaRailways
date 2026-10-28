import {RunDisplayPlansEditor} from './RunDisplayPlansEditor';
import {useEffect,useRef,useState,type PointerEvent} from 'react';
import {Alert,Button,Dropdown,Flex,Select,Space,Typography} from 'antd';
import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import type {AuthoringDutyV1,TimetableAuthoringDocumentV1} from './authoring';
import {projectDutyTimeline} from './dutyTimeline';
import type {ProgramSourceTarget} from './programNavigation';
import type {TimedOperation,StationProgramRecord,TimetableSimulationResult} from './types';
import {TimedOperationFields} from './TimedOperationFields';
import {FormationTaskFields} from './FormationEventsEditor';
import type {AuthoringFormationEvent} from './formationAuthoring';
import {resolveDutyTimedOperation} from './timedOperations';
import {useI18n} from './i18n';
import './DutyTimeline.css';

export interface DutyTimelineProps {
  document:TimetableAuthoringDocumentV1;onDocumentChange?:(document:TimetableAuthoringDocumentV1)=>void;duty:AuthoringDutyV1;fieldScope:string;library:StationProgramRecord[];
  simulation?:TimetableSimulationResult;simulationReason?:string;busy?:boolean;programLoading?:boolean;focusedElementId?:string;focusedProgram?:ProgramSourceTarget;
  onAddFormation?:(kind:AuthoringFormationEvent['kind'])=>void;onPatchFormation?:(event:AuthoringFormationEvent)=>void;onSelectDuty?:(id:string)=>void;onOpenFormation?:(dutyId:string,eventId:string)=>void;destinations?:string[];
  onSimulate?:()=>void;onRefreshPrograms?:()=>void;onAssign:(id:string)=>void;onAddProgram?:(id:string)=>void;
  onPatch:(patch:Partial<AuthoringDutyV1>)=>void;onPatchProgram?:(id:string,patch:Partial<TimedOperation>)=>void;
  onReferenceEvent?:(id:string)=>void;onOpenGraphEvent?:(id:string)=>void;onOperationValidityChange?:(elementId:string,valid:boolean)=>void;stationNames:ReadonlyMap<string,string>;stationIds:string[];
  onRemove:(id:string)=>void;onViewRun:(id:string)=>void;onOpenProgramLibrary?:(id:string,stepId?:string)=>void;
}
export function DutyTimeline(p:DutyTimelineProps) {
  const {t}=useI18n();
  const [selected,setSelected]=useState(p.duty.elements[0]?.id??''),[zoom,setZoom]=useState(1);
  const [runId,setRunId]=useState<string>(),[programId,setProgramId]=useState<string>(),[eventId,setEventId]=useState<string>();
  const [preview,setPreview]=useState<{id:string;tick:number;ticks?:number}>();
  const drag=useRef<{id:string;x:number;tick:number;ticks?:number;resize:boolean;signature:string;scale:number}|undefined>(undefined);
  const latest=useRef(p);latest.current=p;
  useEffect(()=>{const target=p.focusedProgram;if(target?.dutyId===p.duty.id)setSelected(target.elementId);},[p.focusedProgram,p.duty.id]);
  useEffect(()=>{if(p.focusedElementId)setSelected(p.focusedElementId);},[p.focusedElementId,p.duty.id]);
  useEffect(()=>{setPreview(undefined);drag.current=undefined;},[p.duty.elements]);
  const tasks=projectDutyTimeline(p.document,p.duty,p.simulation).map(task=>{if(task.type!=='FORMATION')return task;const element=p.duty.elements.find(e=>e.id===task.id),event=element?.type==='FORMATION'?p.document.formationEvents?.find(e=>e.id===element.eventId):undefined;return {...task,label:event?t(`programs.step.${event.kind}`):t('formation.missingTask')};});
  const selectedElement=p.duty.elements.find((item)=>item.id===selected)??p.duty.elements[0];
  const task=tasks.find((item)=>item.id===selectedElement?.id);
  const availableRuns=p.document.runs.filter((run)=>!p.document.duties.some((d)=>d.elements.some((e)=>e.type==='RUN'&&e.runId===run.id)));
  const programOptions=p.library.map((record)=>({value:record.id,label:record.name}));
  const min=Math.max(0,Math.floor((tasks.length?Math.min(...tasks.map((item)=>item.startTick)):0)/100)*100);
  const max=Math.max(min+1200,...tasks.map((item)=>item.endTick??item.startTick));
  const span=Math.max(1200,max-min)*1.08,width=900*zoom,scale=width/span;
  const patchTiming=(id:string,tick:number,ticks?:number)=>{
    const current=latest.current;
    current.onPatch({elements:current.duty.elements.map((e)=>e.id===id&&e.type==='PROGRAM'?{...e,tick,...(ticks===undefined?{}:{duration:{type:'MANUAL' as const,ticks}})}:e)});
  };
  const begin=(event:PointerEvent<HTMLButtonElement>,id:string,resize:boolean)=>{
    if(event.button!==0)return;
    const e=p.duty.elements.find((e)=>e.id===id),item=tasks.find((item)=>item.id===id);
    if(e?.type!=='PROGRAM'||!item)return;
    event.stopPropagation();event.currentTarget.setPointerCapture(event.pointerId);setSelected(id);
    drag.current={id,x:event.clientX,tick:e.tick,ticks:e.duration?.type==='MANUAL'?e.duration.ticks:undefined,resize,signature:JSON.stringify(p.duty.elements),scale};
  };
  const move=(event:PointerEvent<HTMLButtonElement>)=>{
    const d=drag.current;if(!d)return;
    const delta=Math.round((event.clientX-d.x)/d.scale);
    setPreview({id:d.id,tick:d.resize?d.tick:Math.max(0,d.tick+delta),ticks:d.resize?Math.max(0,(d.ticks??0)+delta):d.ticks});
  };
  const finish=(event:PointerEvent<HTMLButtonElement>)=>{
    const d=drag.current;if(!d)return;drag.current=undefined;setPreview(undefined);
    if(d.signature!==JSON.stringify(latest.current.duty.elements))return;
    const delta=Math.round((event.clientX-d.x)/d.scale);if(!delta)return;
    patchTiming(d.id,d.resize?d.tick:Math.max(0,d.tick+delta),d.resize?Math.max(0,(d.ticks??0)+delta):undefined);
  };
  const pointerHandlers={onPointerMove:move,onPointerUp:finish,onPointerCancel:()=>{drag.current=undefined;setPreview(undefined);}};
  const openTask=(id:string)=>{
    const element=p.duty.elements.find((item)=>item.id===id);
    if(element?.type==='RUN')p.onViewRun(element.runId);
    else if(element?.type==='PROGRAM'&&p.library.some((record)=>record.id===element.program.programId))p.onOpenProgramLibrary?.(element.program.programId);
    else if(element?.type==='EVENT')p.onOpenGraphEvent?.(element.eventId);
  };
  const canEdit=(id:string)=>{const e=p.duty.elements.find(e=>e.id===id);if(e?.type!=='FORMATION')return true;const event=p.document.formationEvents?.find(event=>event.id===e.eventId);return !event||event.coordinatorDutyId===p.duty.id;};
  const taskMenu=(id:string)=>({items:[{key:'delete',danger:true,label:t('timeline.remove'),disabled:!canEdit(id)}],onClick:()=>p.onRemove(id)});
  // Hit targets and labels do not represent occupancy. Only the inner interval uses elapsed time.
  const layout=tasks.map((item)=>{
    const draft=preview?.id===item.id?preview:undefined;
    const start=draft?.tick??item.startTick;
    const end=draft?draft.ticks!==undefined?start+draft.ticks:item.endTick===undefined?undefined:start+item.endTick-item.startTick:item.endTick;
    const marker=end===undefined?'unknown':end===start?'point':undefined;
    const intervalWidth=end===undefined?0:Math.max(0,(end-start)*scale);
    const hitWidth=Math.max(24,intervalWidth),inset=(hitWidth-intervalWidth)/2;
    return {...item,start,end,marker,intervalWidth,hitWidth,inset,left:20+(start-min)*scale,
      description:`${item.label} · ${start}${end===undefined?' ?':`–${end}`}`};
  });
  const conflicts=layout.flatMap((a,index)=>layout.slice(index+1).flatMap((b)=>{
    if(a.end===undefined||b.end===undefined||a.end<=a.start||b.end<=b.start)return [];
    const start=Math.max(a.start,b.start),end=Math.min(a.end,b.end);
    return start<end?[{id:JSON.stringify([a.id,b.id]),taskIds:[a.id,b.id],left:20+(start-min)*scale,width:(end-start)*scale,label:`${a.description}; ${b.description}`}]:[];
  }));
  return <section className="duty-timeline" aria-label={t('timeline.title')}>
    <Flex wrap gap={12} align="center" className="duty-timeline-toolbar">
      <Space.Compact><Select aria-label={t('timeline.run')} placeholder={t('timeline.run')} value={availableRuns.some((r)=>r.id===runId)?runId:undefined} options={availableRuns.map((run)=>({value:run.id,label:run.id}))} onChange={setRunId}/><Button icon={<PlusOutlined/>} disabled={!runId||!availableRuns.some((r)=>r.id===runId)} onClick={()=>{if(runId)p.onAssign(runId);setRunId(undefined);}}>{t('common.add')}</Button></Space.Compact>
      <Space.Compact><Select aria-label={t('timeline.program')} placeholder={t('timeline.program')} value={programOptions.some((o)=>o.value===programId)?programId:undefined} options={programOptions} onChange={setProgramId}/><Button icon={<PlusOutlined/>} disabled={!programId||!p.onAddProgram||!programOptions.some((o)=>o.value===programId)} onClick={()=>{if(programId)p.onAddProgram?.(programId);setProgramId(undefined);}}>{t('common.add')}</Button></Space.Compact>
      <Space.Compact><Select aria-label={t('timed.reference')} placeholder={t('timed.reference')} value={(p.document.events??[]).some((event)=>event.id===eventId&&event.program)?eventId:undefined} options={(p.document.events??[]).filter((event)=>event.program).map((event)=>({value:event.id,label:event.label||event.id}))} onChange={setEventId}/><Button icon={<PlusOutlined/>} disabled={!eventId||!p.onReferenceEvent} onClick={()=>{if(eventId)p.onReferenceEvent?.(eventId);setEventId(undefined);}}>{t('common.add')}</Button></Space.Compact>
      {p.onAddFormation&&(['SPLIT','COUPLE'] as const).map(kind=><Button key={kind} icon={<PlusOutlined/>} onClick={()=>p.onAddFormation?.(kind)}>{t(`programs.step.${kind}`)}</Button>)}
      <Button loading={p.programLoading} onClick={p.onRefreshPrograms}>{t('programs.refresh')}</Button>
      <label>{t('timeline.zoom')}<Select aria-label={t('timeline.zoom')} value={zoom} options={[1,2,4,8].map((value)=>({value,label:`${value}×`}))} onChange={setZoom}/></label>
    </Flex>
    <Typography.Paragraph type="secondary">{t('timeline.hint')}</Typography.Paragraph>
    {tasks.some((item)=>item.overlaps.length>0)&&<Alert type="warning" showIcon title={t('timeline.overlap')}/>}
    {!tasks.length?<Typography.Paragraph type="secondary">{t('timeline.empty')}</Typography.Paragraph>:<div className="duty-timeline-scroll"><div className="duty-timeline-track" style={{width:width+200}}>
      <div className="duty-timeline-axis">{Array.from({length:9},(_,i)=><span key={i} style={{left:20+i*width/8}}>{Math.round(min+i*span/8)}</span>)}<small>{t('timeline.tickUnit')}</small></div>
      <div className="duty-timeline-lane" role="group" aria-label={p.duty.id}>
        {layout.map((item)=>{
          const e=p.duty.elements.find((e)=>e.id===item.id),selected=selectedElement?.id===item.id;
          const conflict=conflicts.some((entry)=>entry.taskIds.includes(item.id));
          return <div key={item.id} className="duty-timeline-item" data-task-id={item.id}>
            <Dropdown trigger={['contextMenu']} menu={taskMenu(item.id)}>
              <button type="button" aria-label={item.description} title={item.description} aria-pressed={selected} className={`duty-timeline-task ${item.type.toLowerCase()} ${selected?'selected':''} ${conflict?'conflict':''}`} style={{left:item.left-item.inset,width:item.hitWidth}} onClick={()=>setSelected(item.id)} onDoubleClick={()=>openTask(item.id)} onPointerDown={(event)=>begin(event,item.id,false)} {...pointerHandlers}>
                <span aria-hidden="true" className={`duty-timeline-interval ${item.marker??''}`} data-start-tick={item.start} data-end-tick={item.end} style={{left:item.marker?item.hitWidth/2:item.inset,width:item.marker?undefined:item.intervalWidth}}/>
                {item.intervalWidth>=110&&<span aria-hidden="true" className="duty-timeline-task-text"><span>{item.label}</span><small>{item.start}–{item.end}</small></span>}
              </button>
            </Dropdown>
            {e?.type==='PROGRAM'&&e.duration?.type==='MANUAL'&&<button type="button" className="duty-timeline-resize" style={{left:item.left+item.intervalWidth-8}} aria-label={`${t('timeline.resize')} ${item.label}`} onPointerDown={(event)=>begin(event,item.id,true)} {...pointerHandlers}/>}
          </div>;
        })}
        {conflicts.map((conflict)=><span key={conflict.id} className="duty-timeline-conflict" role="img" aria-label={`${t('timeline.overlap')} ${conflict.label}`} title={`${t('timeline.overlap')} ${conflict.label}`} style={{left:conflict.left,width:conflict.width}}/>)}
      </div>
    </div></div>}
    {tasks.length>0&&<div className="duty-timeline-labels">{layout.map((item)=><Dropdown key={item.id} trigger={['contextMenu']} menu={taskMenu(item.id)}><button type="button" className={`duty-timeline-label ${selectedElement?.id===item.id?'selected':''} ${item.overlaps.length?'conflict':''}`} aria-pressed={selectedElement?.id===item.id} onClick={()=>setSelected(item.id)} onDoubleClick={()=>openTask(item.id)}><span>{item.label}</span><small>{item.start}{item.end===undefined?` · ${t('timeline.unknown')}`:`–${item.end}`}</small></button></Dropdown>)}</div>}
    {selectedElement&&<section className="duty-timeline-inspector" aria-label={t('timeline.selected')}>
      <Flex justify="space-between" gap={12} align="center"><Typography.Title level={5}>{task?.label??selectedElement.id}</Typography.Title><Button danger disabled={!canEdit(selectedElement.id)} icon={<DeleteOutlined/>} onClick={()=>p.onRemove(selectedElement.id)}>{t('timeline.remove')}</Button></Flex>
      {selectedElement.type==='FORMATION'?(()=>{const event=p.document.formationEvents?.find(e=>e.id===selectedElement.eventId);return event?<FormationTaskFields key={`${p.fieldScope}/${selectedElement.id}`} event={event} dutyId={p.duty.id} document={p.document} fieldScope={`${p.fieldScope}/${selectedElement.id}`} destinations={p.destinations??[]} stationNames={p.stationNames} stationIds={p.stationIds} onChange={e=>p.onPatchFormation?.(e)} onSelectDuty={id=>p.onOpenFormation?p.onOpenFormation(id,event.id):p.onSelectDuty?.(id)} onValidityChange={valid=>p.onOperationValidityChange?.(selectedElement.id,valid)}/>:<Alert type="error" title={t('formation.missingSource')}/>;})():selectedElement.type==='RUN'?<><Typography.Paragraph type="secondary">{t('timeline.runHint')}</Typography.Paragraph><Button onClick={()=>p.onViewRun(selectedElement.runId)}>{t('timeline.editRun')}</Button>{p.onDocumentChange&&<RunDisplayPlansEditor key={`${p.fieldScope}/${selectedElement.id}`} document={p.document} serviceId={p.duty.id} runId={selectedElement.runId} fieldScope={`${p.fieldScope}/${selectedElement.id}/displayPlans`} onChange={p.onDocumentChange} onViewRun={p.onViewRun} onValidityChange={valid=>p.onOperationValidityChange?.(selectedElement.id,valid)}/>}</>:selectedElement.type==='EVENT'?<>
        <Typography.Paragraph type="secondary">{t('timed.graphOwned')}</Typography.Paragraph>
        {(()=>{const operation=resolveDutyTimedOperation(p.document,selectedElement);return operation?.program?<TimedOperationFields key={`${p.fieldScope}/${selectedElement.id}/reference`} fieldScope={`${p.fieldScope}/${selectedElement.id}/reference`} operation={operation} onChange={()=>{}} stationNames={p.stationNames} stationIds={p.stationIds} bindings={p.document.boundPrograms??[]} library={p.library} readOnly measuredTicks={task?.measured&&task.endTick!==undefined?task.endTick-task.startTick:undefined}/>:<Alert type="error" title={t('timed.missing')}/>;})()}
        <Button onClick={()=>p.onOpenGraphEvent?.(selectedElement.eventId)} disabled={!p.onOpenGraphEvent}>{t('timed.openGraph')}</Button>
      </>:<TimedOperationFields key={`${p.fieldScope}/${selectedElement.id}`} fieldScope={`${p.fieldScope}/${selectedElement.id}`} operation={selectedElement} onChange={(operation)=>p.onPatchProgram?.(selectedElement.id,operation)} stationNames={p.stationNames} stationIds={p.stationIds} bindings={p.document.boundPrograms??[]} library={p.library} onValidityChange={(valid)=>p.onOperationValidityChange?.(selectedElement.id,valid)} measuredTicks={task?.measured&&task.endTick!==undefined?task.endTick-task.startTick:undefined} onSimulate={p.onSimulate} simulationReason={p.simulationReason} busy={p.busy} onOpenProgramLibrary={p.onOpenProgramLibrary}/>}

    </section>}
  </section>;
}
