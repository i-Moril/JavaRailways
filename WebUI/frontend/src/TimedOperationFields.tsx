import {useEffect,useRef,useState} from 'react';
import {Button,ConfigProvider,Input,Select,Space,Tag,Typography} from 'antd';
import {CommittedNumberInput} from './CommittedNumberInput';
import {ProgramLiteralInput} from './ProgramFields';
import {ProgramSimulationDialog,type ProgramSimulationContext} from './ProgramSimulationDialog';
import {defaultDutyProgramArguments} from './dutyTimeline';
import {useI18n} from './i18n';
import type {BoundStationProgram,StationProgramRecord,TimedOperation} from './types';
import './TimedOperationFields.css';

/** One form for both owners. The parent adopts library snapshots and commits history. */
export function TimedOperationFields(p:{programSimulation?:ProgramSimulationContext;operation:TimedOperation;onChange:(operation:TimedOperation)=>void;fieldScope:string;stationNames:ReadonlyMap<string,string>;stationIds:string[];library:StationProgramRecord[];bindings:BoundStationProgram[];allowMarker?:boolean;readOnly?:boolean;onValidityChange?:(valid:boolean)=>void;measuredTicks?:number;onSimulate?:()=>void;simulationReason?:string;busy?:boolean;onOpenProgramLibrary?:(id:string)=>void}) {
 const {t}=useI18n();
 const [invalid,setInvalid]=useState<Record<string,boolean>>({});
 const [simulationOpen,setSimulationOpen]=useState(false),[draftRevision,setDraftRevision]=useState(0);
 const op=p.operation;
 const patch=(change:Partial<TimedOperation>)=>p.onChange({...op,...change});
 const programs=[...p.bindings.filter((binding)=>p.library.some((item)=>item.id===binding.programId)||binding.programId===op.program?.programId).map((binding)=>({id:binding.programId,name:p.library.find((item)=>item.id===binding.programId)?.name??`${binding.programId} · ${t('programs.snapshotOnly')}`,definition:binding.definition,disabled:!p.library.some((item)=>item.id===binding.programId)})),...p.library.filter((item)=>!p.bindings.some((binding)=>binding.programId===item.id)).map((item)=>({id:item.id,name:item.name,definition:item.definition,disabled:false}))];
 const boundDefinition=p.bindings.find(binding=>binding.programId===op.program?.programId)?.definition;
 const definition=programs.find((item)=>item.id===op.program?.programId)?.definition;
 const validity=(key:string,valid:boolean)=>{setDraftRevision(value=>value+1);setInvalid((current)=>current[key]===!valid?current:{...current,[key]:!valid});};
 const validityCallback=useRef(p.onValidityChange);validityCallback.current=p.onValidityChange;
 useEffect(()=>{validityCallback.current?.(!Object.values(invalid).some(Boolean));},[invalid]);
 useEffect(()=>()=>{validityCallback.current?.(true);},[]);
 return <ConfigProvider componentDisabled={p.readOnly}><div className="timed-operation-fields">
  <div className="timed-operation-grid">
   <label>{t('programs.callTick')}<CommittedNumberInput aria-label={t('programs.callTick')} fieldKey={`${p.fieldScope}/tick`} value={op.tick} min={0} precision={0} allowEmpty={false} onValidityChange={(valid)=>validity('tick',valid)} onCommit={(tick)=>patch({tick:tick??0})}/></label>
   <label>{t('timed.station')}<Select allowClear aria-label={t('timed.station')} value={op.stationId} options={p.stationIds.map((id)=>({value:id,label:p.stationNames.get(id)||id}))} onChange={(stationId)=>patch({stationId})}/></label>
   <label>{t('events.label')}<Input aria-label={t('events.label')} value={op.label??''} onChange={(event)=>patch({label:event.target.value||undefined})}/></label>
   <label>{t('timeline.program')}<Select allowClear={p.allowMarker} aria-label={t('timeline.program')} placeholder={p.allowMarker?t('timed.marker'):t('timeline.program')} value={op.program?.programId} options={programs.map((item)=>({value:item.id,label:item.name,disabled:item.disabled}))} onChange={(programId:string|undefined)=>{setInvalid((current)=>Object.fromEntries(Object.entries(current).filter(([key])=>!key.startsWith('argument/'))));const parameters=programs.find((item)=>item.id===programId)?.definition.parameters??[];patch({program:programId?{programId,arguments:defaultDutyProgramArguments(parameters)}:undefined});}}/></label>
   <label>{t('timeline.durationMode')}<Select aria-label={t('timeline.durationMode')} value={op.duration?.type??'POINT'} options={[{value:'POINT',label:t('timeline.point')},{value:'MANUAL',label:t('timeline.manual')},{value:'SIMULATED',label:t('timeline.simulated')}]} onChange={(mode)=>{validity('duration',true);patch({duration:mode==='POINT'?undefined:mode==='MANUAL'?{type:'MANUAL',ticks:0}:{type:'SIMULATED'}});}}/></label>
   {op.duration?.type==='MANUAL'&&<label>{t('timeline.duration')}<CommittedNumberInput aria-label={t('timeline.duration')} fieldKey={`${p.fieldScope}/duration`} value={op.duration.ticks} min={0} precision={0} allowEmpty={false} onValidityChange={(valid)=>validity('duration',valid)} onCommit={(ticks)=>patch({duration:{type:'MANUAL',ticks:ticks??0}})}/></label>}
  </div>
  {!!op.program&&<div className="timed-operation-grid">{definition?.parameters.map((parameter)=><div className="duty-program-argument" key={`${op.program!.programId}/${parameter.id}`}><span>{parameter.id}</span><ProgramLiteralInput type={parameter.type} value={op.program!.arguments[parameter.id]} fieldKey={`${p.fieldScope}/arguments/${op.program!.programId}/${parameter.id}`} onValidityChange={(valid)=>validity(`argument/${parameter.id}`,valid)} onChange={(value)=>patch({program:{...op.program!,arguments:{...op.program!.arguments,[parameter.id]:value}}})}/></div>)}</div>}
  {!op.program&&<Typography.Paragraph type="secondary">{t('timed.markerHint')}</Typography.Paragraph>}
  <Typography.Paragraph type="secondary">{t('timed.stationHint')} {t('timeline.durationHint')}</Typography.Paragraph>
  {op.duration?.type==='SIMULATED'&&<Space wrap><Tag>{p.measuredTicks===undefined?t('timeline.unknown'):`${p.measuredTicks} ${t('timeline.tickUnit')}`}</Tag>{p.onSimulate&&<Button loading={p.busy} disabled={!!p.simulationReason||p.busy} onClick={p.onSimulate}>{t('timeline.simulate')}</Button>}{p.simulationReason&&<Typography.Text type="secondary">{p.simulationReason}</Typography.Text>}</Space>}
  {op.program&&boundDefinition&&p.programSimulation&&<ConfigProvider componentDisabled={false}><div className="duty-timeline-source"><Button disabled={p.busy||Object.values(invalid).some(Boolean)} onClick={()=>setSimulationOpen(true)}>{t('programSimulation.open')}</Button></div></ConfigProvider>}
  {simulationOpen&&op.program&&boundDefinition&&p.programSimulation&&<ConfigProvider componentDisabled={false}><ProgramSimulationDialog key={draftRevision} {...p.programSimulation} owner={`${p.programSimulation.owner}/${p.fieldScope}`} programId={op.program.programId} definition={boundDefinition} arguments={op.program.arguments} startTick={op.tick} valid={!Object.values(invalid).some(Boolean)} onClose={()=>setSimulationOpen(false)} onAdoptDuration={p.readOnly?undefined:ticks=>patch({duration:{type:'MANUAL',ticks}})}/></ConfigProvider>}
  {op.program&&p.library.some((record)=>record.id===op.program!.programId)&&p.onOpenProgramLibrary&&<div className="duty-timeline-source"><Button onClick={()=>p.onOpenProgramLibrary?.(op.program!.programId)}>{t('timeline.editProgram')}</Button></div>}
 </div></ConfigProvider>;
}
