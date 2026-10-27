import {useEffect,useRef,useState,type PointerEvent} from 'react';
import {Alert,Button,Dropdown,Flex,Select,Space,Tag,Typography} from 'antd';
import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import type {AuthoringDutyV1,TimetableAuthoringDocumentV1} from './authoring';
import {projectDutyTimeline} from './dutyTimeline';
import type {ProgramSourceTarget} from './programNavigation';
import type {StationProgramCall,StationProgramRecord,TimetableSimulationResult} from './types';
import {CommittedNumberInput} from './CommittedNumberInput';
import {ProgramLiteralInput} from './ProgramFields';
import {useI18n} from './i18n';
import './DutyTimeline.css';

export interface DutyTimelineProps {
  document:TimetableAuthoringDocumentV1;duty:AuthoringDutyV1;fieldScope:string;library:StationProgramRecord[];
  simulation?:TimetableSimulationResult;simulationReason?:string;busy?:boolean;programLoading?:boolean;focusedProgram?:ProgramSourceTarget;
  onSimulate?:()=>void;onRefreshPrograms?:()=>void;onAssign:(id:string)=>void;onAddProgram?:(id:string)=>void;
  onPatch:(patch:Partial<AuthoringDutyV1>)=>void;onPatchProgram?:(id:string,patch:Partial<StationProgramCall>)=>void;
  onRemove:(id:string)=>void;onViewRun:(id:string)=>void;onOpenProgramLibrary?:(id:string,stepId?:string)=>void;
}
export function DutyTimeline(p:DutyTimelineProps) {
  const {t}=useI18n();
  const [selected,setSelected]=useState(p.duty.elements[0]?.id??''),[zoom,setZoom]=useState(1);
  const [runId,setRunId]=useState<string>(),[programId,setProgramId]=useState<string>();
  const [preview,setPreview]=useState<{id:string;tick:number;ticks?:number}>();
  const drag=useRef<{id:string;x:number;tick:number;ticks?:number;resize:boolean;signature:string;scale:number}|undefined>(undefined);
  const latest=useRef(p);latest.current=p;
  useEffect(()=>{const target=p.focusedProgram;if(target?.dutyId===p.duty.id)setSelected(target.elementId);},[p.focusedProgram,p.duty.id]);
  useEffect(()=>{setPreview(undefined);drag.current=undefined;},[p.duty.elements]);
  const tasks=projectDutyTimeline(p.document,p.duty,p.simulation);
  const selectedElement=p.duty.elements.find((item)=>item.id===selected)??p.duty.elements[0];
  const task=tasks.find((item)=>item.id===selectedElement?.id);
  const availableRuns=p.document.runs.filter((run)=>!p.document.duties.some((d)=>d.elements.some((e)=>e.type==='RUN'&&e.runId===run.id)));
  const programOptions=[...(p.document.boundPrograms??[]).map((binding)=>({value:binding.programId,label:p.library.find((record)=>record.id===binding.programId)?.name??binding.programId})),...p.library.filter((record)=>!p.document.boundPrograms?.some((binding)=>binding.programId===record.id)).map((record)=>({value:record.id,label:record.name}))];
  const min=Math.max(0,Math.floor((tasks.length?Math.min(...tasks.map((item)=>item.startTick)):0)/100)*100);
  const max=Math.max(min+1200,...tasks.map((item)=>item.endTick??item.startTick));
  const span=Math.max(1200,max-min)*1.08,width=900*zoom,scale=width/span;
  const patchTiming=(id:string,tick:number,ticks?:number)=>{
    const current=latest.current;
    current.onPatch({elements:current.duty.elements.map((e)=>e.id===id&&e.type==='PROGRAM'?{...e,call:{...e.call,tick},...(ticks===undefined?{}:{duration:{type:'MANUAL' as const,ticks}})}:e)});
  };
  const begin=(event:PointerEvent<HTMLButtonElement>,id:string,resize:boolean)=>{
    if(event.button!==0)return;
    const e=p.duty.elements.find((e)=>e.id===id),item=tasks.find((item)=>item.id===id);
    if(e?.type!=='PROGRAM'||!item)return;
    event.stopPropagation();event.currentTarget.setPointerCapture(event.pointerId);setSelected(id);
    drag.current={id,x:event.clientX,tick:e.call.tick,ticks:e.duration?.type==='MANUAL'?e.duration.ticks:undefined,resize,signature:JSON.stringify(p.duty.elements),scale};
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
  const setDuration=(duration?:{type:'MANUAL';ticks:number}|{type:'SIMULATED'})=>{if(selectedElement?.type==='PROGRAM')p.onPatch({elements:p.duty.elements.map((e)=>e.id===selectedElement.id?{...e,duration}:e)});};
  return <section className="duty-timeline" aria-label={t('timeline.title')}>
    <Flex wrap gap={12} align="center" className="duty-timeline-toolbar">
      <Space.Compact><Select aria-label={t('timeline.run')} placeholder={t('timeline.run')} value={availableRuns.some((r)=>r.id===runId)?runId:undefined} options={availableRuns.map((run)=>({value:run.id,label:run.id}))} onChange={setRunId}/><Button icon={<PlusOutlined/>} disabled={!runId||!availableRuns.some((r)=>r.id===runId)} onClick={()=>{if(runId)p.onAssign(runId);setRunId(undefined);}}>{t('common.add')}</Button></Space.Compact>
      <Space.Compact><Select aria-label={t('timeline.program')} placeholder={t('timeline.program')} value={programOptions.some((o)=>o.value===programId)?programId:undefined} options={programOptions} onChange={setProgramId}/><Button icon={<PlusOutlined/>} disabled={!programId||!p.onAddProgram||!programOptions.some((o)=>o.value===programId)} onClick={()=>{if(programId)p.onAddProgram?.(programId);setProgramId(undefined);}}>{t('common.add')}</Button></Space.Compact>
      <Button loading={p.programLoading} onClick={p.onRefreshPrograms}>{t('programs.refresh')}</Button>
      <label>{t('timeline.zoom')}<Select aria-label={t('timeline.zoom')} value={zoom} options={[1,2,4,8].map((value)=>({value,label:`${value}×`}))} onChange={setZoom}/></label>
    </Flex>
    <Typography.Paragraph type="secondary">{t('timeline.hint')}</Typography.Paragraph>
    {tasks.some((item)=>item.overlaps.length>0)&&<Alert type="warning" showIcon title={t('timeline.overlap')}/>}
    {!tasks.length?<Typography.Paragraph type="secondary">{t('timeline.empty')}</Typography.Paragraph>:<div className="duty-timeline-scroll"><div className="duty-timeline-track" style={{width:width+200}}>
      <div className="duty-timeline-axis">{Array.from({length:9},(_,i)=><span key={i} style={{left:20+i*width/8}}>{Math.round(min+i*span/8)}</span>)}<small>{t('timeline.tickUnit')}</small></div>
      {tasks.map((item)=>{
        const e=p.duty.elements.find((e)=>e.id===item.id),draft=preview?.id===item.id?preview:undefined;
        const start=draft?.tick??item.startTick,end=draft?draft.tick+(draft.ticks??(item.endTick===undefined?0:item.endTick-item.startTick)):item.endTick;
        const point=end===undefined||end===start,left=20+(start-min)*scale,barWidth=point?14:Math.max(20,(end-start)*scale);
        return <div key={item.id} className="duty-timeline-row" data-task-id={item.id}>
          <Dropdown trigger={['contextMenu']} menu={{items:[{key:'delete',danger:true,label:t('timeline.remove')}],onClick:()=>p.onRemove(item.id)}}>
            <button type="button" aria-label={`${item.label} · ${start}${end===undefined?' ?':`–${end}`}`} aria-pressed={selectedElement?.id===item.id} className={`duty-timeline-task ${item.type.toLowerCase()} ${point?'point':''} ${item.endTick===undefined?'unknown':''} ${selectedElement?.id===item.id?'selected':''}`} style={{left,width:barWidth}} onClick={()=>setSelected(item.id)} onDoubleClick={()=>{if(e?.type==='RUN')p.onViewRun(e.runId);else if(e?.type==='PROGRAM')p.onOpenProgramLibrary?.(e.call.programId);}} onPointerDown={(event)=>begin(event,item.id,false)} {...pointerHandlers}><span>{item.label}</span><small>{start} · {item.durationMode==='SIMULATED'&&item.endTick===undefined?t('timeline.unknown'):end===undefined?'?':end-start} {t('timeline.tickUnit')}</small></button>
          </Dropdown>
          {e?.type==='PROGRAM'&&e.duration?.type==='MANUAL'&&<button type="button" className="duty-timeline-resize" style={{left:left+barWidth-4}} aria-label={`${t('timeline.resize')} ${item.label}`} onPointerDown={(event)=>begin(event,item.id,true)} {...pointerHandlers}/>}
        </div>;
      })}
    </div></div>}
    {selectedElement&&<section className="duty-timeline-inspector" aria-label={t('timeline.selected')}>
      <Flex justify="space-between" gap={12} align="center"><Typography.Title level={5}>{task?.label??selectedElement.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={()=>p.onRemove(selectedElement.id)}>{t('timeline.remove')}</Button></Flex>
      {selectedElement.type==='RUN'?<><Typography.Paragraph type="secondary">{t('timeline.runHint')}</Typography.Paragraph><Button onClick={()=>p.onViewRun(selectedElement.runId)}>{t('timeline.editRun')}</Button></>:<>
        <div className="function-fields">
          <label>{t('programs.callTick')}<CommittedNumberInput aria-label={t('programs.callTick')} fieldKey={`${p.fieldScope}/${selectedElement.id}/tick`} value={selectedElement.call.tick} min={0} precision={0} allowEmpty={false} onCommit={(tick)=>p.onPatchProgram?.(selectedElement.id,{tick:tick??0})}/></label>
          <label>{t('timeline.durationMode')}<Select aria-label={t('timeline.durationMode')} value={selectedElement.duration?.type??'POINT'} options={[{value:'POINT',label:t('timeline.point')},{value:'MANUAL',label:t('timeline.manual')},{value:'SIMULATED',label:t('timeline.simulated')}]} onChange={(value)=>setDuration(value==='POINT'?undefined:value==='MANUAL'?{type:'MANUAL',ticks:0}:{type:'SIMULATED'})}/></label>
          {selectedElement.duration?.type==='MANUAL'&&<label>{t('timeline.duration')}<CommittedNumberInput aria-label={t('timeline.duration')} fieldKey={`${p.fieldScope}/${selectedElement.id}/duration`} value={selectedElement.duration.ticks} min={0} precision={0} allowEmpty={false} onCommit={(ticks)=>setDuration({type:'MANUAL',ticks:ticks??0})}/></label>}
          {(p.document.boundPrograms?.find((binding)=>binding.programId===selectedElement.call.programId)?.definition.parameters??[]).map((parameter)=><div className="duty-program-argument" key={parameter.id}><span>{parameter.id}</span><ProgramLiteralInput type={parameter.type} value={selectedElement.call.arguments[parameter.id]} fieldKey={`${p.fieldScope}/${selectedElement.id}/${parameter.id}`} onChange={(value)=>p.onPatchProgram?.(selectedElement.id,{arguments:{...selectedElement.call.arguments,[parameter.id]:value}})}/></div>)}
        </div>
        <Typography.Paragraph type="secondary">{t('timeline.durationHint')}</Typography.Paragraph>
        {selectedElement.duration?.type==='SIMULATED'&&<Space wrap><Tag>{task?.endTick===undefined?t('timeline.unknown'):`${task.endTick-task.startTick} ${t('timeline.tickUnit')}`}</Tag><Button loading={p.busy} disabled={!p.onSimulate||!!p.simulationReason||p.busy} onClick={p.onSimulate}>{t('timeline.simulate')}</Button>{p.simulationReason&&<Typography.Text type="secondary">{p.simulationReason}</Typography.Text>}</Space>}
        <div className="duty-timeline-source"><Button disabled={!p.onOpenProgramLibrary} onClick={()=>p.onOpenProgramLibrary?.(selectedElement.call.programId,p.focusedProgram?.elementId===selectedElement.id?p.focusedProgram.stepId:undefined)}>{t('timeline.editProgram')}</Button></div>
      </>}
    </section>}
  </section>;
}
