import {useEffect,useRef,useState} from 'react';
import {Alert,Button,Modal,Radio,Select,Space,Typography} from 'antd';
import {api} from './api';
import {CommittedNumberInput} from './CommittedNumberInput';
import {ProgramLiteralInput} from './ProgramFields';
import {defaultDutyProgramArguments} from './dutyTimeline';
import {buildProgramSimulationRequest,completedProgramDuration,ProgramSimulationRequestOwner} from './programSimulation';
import {useI18n} from './i18n';
import type {ProgramSimulationRequest,ProgramSimulationResult,StationProgramDefinition,StationProgramLiteral,TimedOperation,WorldMetadata} from './types';
import './ProgramSimulationDialog.css';

export interface ProgramSimulationContext {
 world:string;owner:string;metadata:WorldMetadata;periodDays:number;events?:TimedOperation[];
 profile?:ProgramSimulationRequest['profile'];plannedContext?:Record<string,string>;
}
interface Props extends ProgramSimulationContext {
 programId:string;definition:StationProgramDefinition;arguments?:Record<string,StationProgramLiteral>;startTick?:number;
 valid?:boolean;editableArguments?:boolean;onClose:()=>void;onAdoptDuration?:(ticks:number)=>void;
}
export function ProgramSimulationDialog(p:Props){
 // Replacing the source call/world/owner creates a new scenario, with no late callbacks to it.
 const scope=JSON.stringify([p.owner,p.world,p.programId,p.definition,p.arguments,p.startTick,p.periodDays,p.events,p.plannedContext,p.profile]);
 return <SimulationDialog key={scope} {...p}/>;
}
function SimulationDialog(p:Props){
 const {t}=useI18n();
 const [args,setArgs]=useState(()=>structuredClone(p.arguments??defaultDutyProgramArguments(p.definition.parameters)));
 const [startTick,setStartTick]=useState(p.startTick??0),[periodDays,setPeriodDays]=useState(p.periodDays);
 const [profile,setProfile]=useState(p.profile),[wayPointId,setWayPointId]=useState<string>(),[reverseTrain,setReverseTrain]=useState<boolean>();
 const [maximumDurationTicks,setMaximumDurationTicks]=useState(Math.min(24000,p.periodDays*24000-(p.startTick??0)));
 const [invalid,setInvalid]=useState<Record<string,boolean>>({});
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 const [result,setResult]=useState<{token:number;value:ProgramSimulationResult}>();
 const owner=useRef<ProgramSimulationRequestOwner>(undefined);if(!owner.current)owner.current=new ProgramSimulationRequestOwner();
 const key=JSON.stringify([args,startTick,periodDays,profile,wayPointId,reverseTrain,maximumDurationTicks,p.metadata,p.valid]);owner.current.update(key);
 useEffect(()=>{owner.current!.activate();return()=>owner.current?.dispose();},[]);
 useEffect(()=>{setBusy(false);setError('');},[key]);
 const invalidate=()=>{owner.current!.invalidate();setResult(undefined);setError('');setBusy(false);};
 const validity=(field:string,valid:boolean)=>{invalidate();setInvalid(current=>current[field]===!valid?current:{...current,[field]:!valid});};
 let input:ProgramSimulationRequest|undefined,problem='';
 if(profile&&wayPointId&&typeof reverseTrain==='boolean'){
  try{input=buildProgramSimulationRequest({programId:p.programId,definition:p.definition,arguments:args,startTick,periodDays,profile,initialPosition:{wayPointId,reverseTrain},plannedContext:p.plannedContext,maximumDurationTicks},p.metadata,p.events);}
  catch(failure){problem=failure instanceof Error?failure.message:String(failure);}
 }
 const disabled=p.valid===false||Object.values(invalid).some(Boolean)||!input;
 const currentResult=result&&owner.current.current(result.token)?result.value:undefined;
 const duration=currentResult?completedProgramDuration(currentResult):undefined;
 const run=async()=>{
  if(disabled||!input)return;
  const token=owner.current!.begin();setBusy(true);setError('');setResult(undefined);
  try{const value=await api.simulateProgram(p.world,input);if(owner.current!.current(token))setResult({token,value});}
  catch(failure){if(owner.current!.current(token))setError(failure instanceof Error?failure.message:String(failure));}
  finally{if(owner.current!.current(token))setBusy(false);}
 };
 const close=()=>{owner.current!.dispose();p.onClose();};
 const profiles=p.metadata.simulationProfiles??[],points=p.metadata.programSimulationWayPoints??[],point=points.find(point=>point.id===wayPointId);
 return <Modal open title={t('programSimulation.title')} onCancel={close} width={760} footer={<Space wrap><Button onClick={close}>{t('programSimulation.cancel')}</Button>{p.onAdoptDuration&&<Button disabled={disabled||duration===undefined||busy} onClick={()=>{if(!disabled&&duration!==undefined&&result&&owner.current!.current(result.token)){p.onAdoptDuration?.(duration);close();}}}>{t('programSimulation.adopt')}</Button>}<Button type="primary" loading={busy} disabled={disabled||busy} onClick={()=>void run()}>{t('programSimulation.run')}</Button></Space>}>
  <div className="program-simulation">
   <Alert type="info" showIcon title={t('programSimulation.scenarioHint')}/>
   <div className="program-simulation-grid">
    <label>{t('duties.reference')}<Select aria-label={t('duties.reference')} placeholder={t('duties.reference')} value={profile?`${profile.type}:${profile.id}`:undefined} options={profiles.map(value=>({value:`${value.type}:${value.id}`,label:value.displayName}))} onChange={value=>{invalidate();const selected=profiles.find(profile=>`${profile.type}:${profile.id}`===value);setProfile(selected?{type:selected.type,id:selected.id}:undefined);}}/></label>
    <label>{t('programSimulation.waypoint')}<Select aria-label={t('programSimulation.waypoint')} showSearch optionFilterProp="label" placeholder={t('programSimulation.waypoint')} value={wayPointId} options={points.map(point=>({value:point.id,label:point.displayName||point.id}))} onChange={value=>{invalidate();setWayPointId(value);setReverseTrain(undefined);}}/></label>
    <label>{t('programSimulation.start')}<CommittedNumberInput aria-label={t('programSimulation.start')} fieldKey={`${p.owner}/simulation/start`} disabled={p.startTick!==undefined} value={startTick} allowEmpty={false} min={0} max={periodDays*24000-1} precision={0} onValidityChange={value=>validity('start',value)} onCommit={value=>{invalidate();setStartTick(value??0);}}/></label>
    <label>{t('programSimulation.period')}<CommittedNumberInput aria-label={t('programSimulation.period')} fieldKey={`${p.owner}/simulation/period`} disabled={!p.editableArguments} value={periodDays} allowEmpty={false} min={1} max={Math.floor(Number.MAX_SAFE_INTEGER/24000)} precision={0} onValidityChange={value=>validity('period',value)} onCommit={value=>{invalidate();setPeriodDays(value??1);}}/></label>
    <label>{t('programSimulation.limit')}<CommittedNumberInput aria-label={t('programSimulation.limit')} fieldKey={`${p.owner}/simulation/limit`} value={maximumDurationTicks} allowEmpty={false} min={1} max={periodDays*24000-startTick} precision={0} onValidityChange={value=>validity('limit',value)} onCommit={value=>{invalidate();setMaximumDurationTicks(value??1);}}/></label>
   </div>
   <Typography.Text type="secondary">{t('programSimulation.limitHint')}</Typography.Text>
   {!profiles.length&&<Alert type="warning" title={t('programSimulation.noProfiles')}/>}
   {!points.length&&<Alert type="warning" title={t('programSimulation.noPoints')}/>}
   <div className="program-simulation-reversal"><Typography.Text strong>{t('programSimulation.reverse')}</Typography.Text><Radio.Group aria-label={t('programSimulation.reverse')} disabled={!point} value={reverseTrain} onChange={event=>{invalidate();setReverseTrain(event.target.value);}}><Radio value={false}>{t('programSimulation.keepDirection')}</Radio><Radio value={true}>{t('programSimulation.reverseInPlace')}</Radio></Radio.Group>{point&&<Typography.Text type="secondary">{t(point.directionSource==='WAYPOINT'?'programSimulation.waypointDirection':'programSimulation.railDirection')} · {point.railId}</Typography.Text>}<Typography.Text type="secondary">{t('programSimulation.placementHint')}</Typography.Text></div>
   {!!p.definition.parameters.length&&<div className="program-simulation-arguments"><Typography.Text strong>{t('programSimulation.arguments')}</Typography.Text><div className="program-simulation-grid">{p.definition.parameters.map(parameter=><label key={parameter.id}>{parameter.id}{p.editableArguments?<ProgramLiteralInput type={parameter.type} fieldKey={`${p.owner}/simulation/argument/${parameter.id}`} value={args[parameter.id]} onValidityChange={valid=>validity(`argument/${parameter.id}`,valid)} onChange={(value:StationProgramLiteral)=>{invalidate();setArgs(current=>({...current,[parameter.id]:value}));}}/>:<Typography.Text code>{JSON.stringify(args[parameter.id])}</Typography.Text>}</label>)}</div></div>}
   {problem&&<Alert type="warning" showIcon title={problem}/>}
   {p.valid===false&&<Alert type="warning" title={t('programSimulation.invalidDraft')}/>}
   {error&&<Alert type="error" showIcon title={error}/>}
   {currentResult&&<Alert type={duration===undefined?'warning':'success'} showIcon title={duration===undefined?t(currentResult.status==='LIMIT_REACHED'?'programSimulation.limited':'programSimulation.failed'):`${t('programSimulation.duration')}: ${duration} tick`} description={<div className="program-simulation-result">{duration!==undefined&&<Typography.Text>{currentResult.startTick} → {currentResult.endTick}</Typography.Text>}{currentResult.reason&&<Typography.Text>{currentResult.reason}</Typography.Text>}{currentResult.failedProgramStepId&&<Typography.Text>{t('programSimulation.failedStep')}: {currentResult.failedProgramStepId}</Typography.Text>}<Typography.Text type="secondary">{t('programSimulation.elapsed')}: {currentResult.simulatedTicks} tick</Typography.Text></div>}/>}
  </div>
 </Modal>;
}
