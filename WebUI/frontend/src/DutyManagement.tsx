import {findProgramStep} from './stationPrograms';
import {FormationEventsEditor} from './FormationEventsEditor';
import {formationOutputs,type AuthoringFormationEvent} from './formationAuthoring';
import { useEffect,useState } from 'react';
import { Button,Flex,Input,Select,Space,Tag,Typography } from 'antd';
import { ArrowDownOutlined,ArrowUpOutlined,DeleteOutlined,PlusOutlined } from '@ant-design/icons';
import type { AuthoringDutyV1,AuthoringFunctionCallV1,AuthoringRunV1 } from './authoring';
import type {ProgramSourceTarget} from './programNavigation';
import { ProgramLiteralInput } from './ProgramFields';
import type { BoundStationProgram,StationProgramCall,StationProgramRecord,WorldMetadata } from './types';
import { CommittedNumberInput } from './CommittedNumberInput';
import { useI18n } from './i18n';

export interface DutyManagementProps {
  formationEvents?:AuthoringFormationEvent[];onFormationEventsChange?:(events:AuthoringFormationEvent[])=>void;
  fieldScope?:string;focusedProgram?:ProgramSourceTarget;onOpenProgramLibrary?:(programId:string,stepId?:string)=>void;
  programLibrary?:StationProgramRecord[];boundPrograms?:BoundStationProgram[];programError?:string;programLoading?:boolean;
  onRefreshPrograms?:()=>void;onAddProgram?:(id:string)=>void;onAdoptProgram?:(id:string)=>void;onPatchProgram?:(id:string,patch:Partial<StationProgramCall>)=>void;
  duties:AuthoringDutyV1[]; runs:AuthoringRunV1[]; selectedId:string; metadata:WorldMetadata;
  onSelect:(id:string)=>void; onAdd:()=>void; onDelete:()=>void;
  onPatch:(patch:Partial<AuthoringDutyV1>)=>void; onAssign:(runId:string)=>void;
  onAddFunction:(type:AuthoringFunctionCallV1['type'])=>void;
  onPatchFunction:(id:string,patch:Partial<AuthoringFunctionCallV1>)=>void;
  onMove:(id:string,delta:number)=>void; onRemove:(id:string)=>void; onViewRun:(id:string)=>void;
}
export function DutyManagement(p:DutyManagementProps) {
  const {t}=useI18n();
  const duty=p.duties.find((item)=>item.id===p.selectedId);
  const options=(values:string[])=>values.map((value)=>({value,label:value}));
  const selector=duty?.selector;
  const [name,setName]=useState(duty?.id??'');
  useEffect(()=>setName(duty?.id??''),[duty?.id]);
  const commitName=()=>{if(duty&&name!==duty.id){p.onPatch({id:name});setName(duty.id);}};
  const selectorOptions=[{value:'TRAIN_NAME',label:t('ui.namedTrain')},{value:'TAKE_FROM_DEPOT',label:t('actions.TAKE_FROM_DEPOT')},{value:'TAKE_FROM_WAYPOINT',label:t('actions.TAKE_FROM_WAYPOINT')},{value:'TAKE_FROM_FORMATION_OUTPUT',label:t('formation.take')}];
  const functionLabel=(type:AuthoringFunctionCallV1['type'])=>type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${type}`);
  return <div className="module-page duties-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('duties.duties')}</Typography.Title><Typography.Text type="secondary">{t('ui.dutyDescription')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} onClick={p.onAdd}>{t('ui.newDuty')}</Button></div>
    <div className="duty-admin-grid"><aside className="entity-list panel-surface">{p.duties.map((item)=><Button type="text" className={`entity-card ${item.id===duty?.id?'active':''}`} key={item.id} onClick={()=>p.onSelect(item.id)}><i style={{background:'var(--ant-color-primary)'}}/><span><b>{item.id}</b><small>{item.elements.filter((element)=>element.type==='RUN').length} {t('duties.runs')}</small></span></Button>)}</aside>
    <section className="panel-surface duty-detail">{!duty||!selector?<p className="empty">{t('ui.selectDuty')}</p>:<>
      <div className="detail-heading"><Typography.Title level={4}>{duty.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={p.onDelete}>{t('common.delete')}</Button></div>
      <div className="duty-properties">
        <label>{t('ui.rename')}<Input value={name} onChange={(event)=>setName(event.target.value)} onBlur={commitName} onPressEnter={commitName}/></label>
        <label>{t('ui.selector')}<Select value={selector.type} onChange={(type)=>p.onPatch({selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:type==='TAKE_FROM_FORMATION_OUTPUT'?{type,eventId:'',outputId:'',tick:0}:{type:'TAKE_FROM_WAYPOINT',wayPointIds:[],tick:0}})} options={selectorOptions}/></label>
        {selector.type==='TRAIN_NAME'?<label>{t('ui.trainName')}<Input value={selector.name} onChange={(event)=>p.onPatch({selector:{...selector,name:event.target.value}})}/></label>:<>
          <label>{t('duties.acquireTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/selector/${selector.type}/tick`} allowEmpty={false} min={0} precision={0} value={selector.tick} onCommit={(tick)=>p.onPatch({selector:{...selector,tick:tick??0}})}/></label>
          {selector.type==='TAKE_FROM_DEPOT'?<><label>{t('duties.depot')}<Select value={selector.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatch({selector:{...selector,depotId}})}/></label><label>{t('duties.requiredTags')}<Select mode="tags" value={selector.tags} onChange={(tags)=>p.onPatch({selector:{...selector,tags}})}/></label></>:selector.type==='TAKE_FROM_FORMATION_OUTPUT'?<><label>{t('programs.eventId')}<Select value={selector.eventId||undefined} options={options((p.formationEvents??[]).map((e)=>e.id))} onChange={(eventId)=>p.onPatch({selector:{...selector,eventId,outputId:''}})}/></label><label>{t('formation.outputId')}<Select value={selector.outputId||undefined} options={options((p.formationEvents??[]).filter((e)=>e.id===selector.eventId).flatMap(formationOutputs).map((o)=>o.id))} onChange={(outputId)=>p.onPatch({selector:{...selector,outputId}})}/></label></>:<label>{t('duties.waypoints')}<Select mode="multiple" value={selector.wayPointIds} options={options(p.metadata.wayPoints??[])} onChange={(wayPointIds)=>p.onPatch({selector:{...selector,wayPointIds}})}/></label>}
        </>}
      </div>
      <Flex className="duty-toolbar" wrap gap={8}><Select aria-label={t('duties.assign')} placeholder={t('duties.assign')} value={null} style={{minWidth:220}} onChange={p.onAssign} options={p.runs.map((run)=>({value:run.id,label:`${run.id} · ${p.duties.find((item)=>item.elements.some((element)=>element.type==='RUN'&&element.runId===run.id))?.id??t('duties.unassigned')}`}))}/><Select aria-label={t('ui.function')} placeholder={t('ui.function')} value={null} style={{minWidth:180}} onChange={p.onAddFunction} options={(['PLACE_INITIAL','REVERSE','WAIT','STORE_AT_DEPOT'] as const).map((value)=>({value,label:functionLabel(value)}))}/></Flex>
      <Flex wrap gap={8}><Select aria-label={t('programs.bind')} placeholder={t('programs.bind')} value={null} loading={p.programLoading} disabled={!p.onAddProgram||p.programLoading} options={(p.programLibrary??[]).filter((record)=>!record.archived&&record.definition.steps.length>0).map((record)=>({value:record.id,label:`${record.name} · r${p.boundPrograms?.find((item)=>item.programId===record.id)?.revision??record.revision}`}))} onChange={p.onAddProgram}/><Button disabled={p.programLoading} onClick={p.onRefreshPrograms}>{t('programs.refresh')}</Button></Flex>
      {p.programError&&<Typography.Paragraph type="danger" role="alert">{p.programError}</Typography.Paragraph>}
      {!!p.boundPrograms?.length&&<section><Typography.Title level={5}>{t('programs.bound')}</Typography.Title><Typography.Paragraph type="secondary">{t('programs.snapshotHint')}</Typography.Paragraph>{p.boundPrograms.map((snapshot)=>{const latest=p.programLibrary?.find((record)=>record.id===snapshot.programId);return <Flex wrap gap={8} key={snapshot.programId}><Typography.Text>{snapshot.programId} · r{snapshot.revision}</Typography.Text>{latest?.archived?<Tag>{t('programs.archived')}</Tag>:latest&&latest.revision!==snapshot.revision?<Button onClick={()=>p.onAdoptProgram?.(snapshot.programId)}>{t('programs.adopt')} r{latest.revision}</Button>:null}</Flex>;})}</section>}
      <div className="duty-chain">{duty.elements.map((element,index)=><section key={element.id} className={`duty-element ${p.focusedProgram?.dutyId===duty.id&&p.focusedProgram.elementId===element.id?'program-step-focused':''}`}><Flex justify="space-between" align="center" gap={8}><Space><Tag>{index+1}</Tag>{element.type==='RUN'?<Button type="link" onClick={()=>p.onViewRun(element.runId)}>{element.runId}</Button>:<Typography.Text strong>{element.type==='PROGRAM'?`${t('programs.call')} · ${element.call.programId}`:functionLabel(element.call.type)}</Typography.Text>}</Space><Space><Button aria-label={t('common.moveUp')} icon={<ArrowUpOutlined/>} disabled={index===0} onClick={()=>p.onMove(element.id,-1)}/><Button aria-label={t('common.moveDown')} icon={<ArrowDownOutlined/>} disabled={index===duty.elements.length-1} onClick={()=>p.onMove(element.id,1)}/><Button danger aria-label={t('common.delete')} icon={<DeleteOutlined/>} onClick={()=>p.onRemove(element.id)}/></Space></Flex>
        {element.type==='PROGRAM'&&p.focusedProgram?.dutyId===duty.id&&p.focusedProgram.elementId===element.id&&<section className="program-bound-step"><Typography.Text strong>{t('programs.boundStep')} · {p.focusedProgram.stepId??element.call.id} · r{p.focusedProgram.revision}</Typography.Text><Typography.Paragraph type="secondary">{t('programs.boundStepHint')}</Typography.Paragraph><pre>{JSON.stringify(findProgramStep(p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition.steps??[],p.focusedProgram?.stepId??'')??p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition,null,2)}</pre><Button onClick={()=>p.onOpenProgramLibrary?.(element.call.programId,p.focusedProgram?.stepId)}>{t('programs.library')}</Button></section>}
        {element.type==='PROGRAM'&&<div className="function-fields"><label>{t('programs.callTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} value={element.call.tick} allowEmpty={false} min={0} precision={0} onCommit={(tick)=>p.onPatchProgram?.(element.id,{tick:tick??0})}/></label>{p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition.parameters.map((parameter)=><label key={parameter.id}>{parameter.id}<ProgramLiteralInput type={parameter.type} value={element.call.arguments[parameter.id]} fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/${p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.revision}/${parameter.id}`} onChange={(next)=>p.onPatchProgram?.(element.id,{arguments:{...element.call.arguments,[parameter.id]:next}})}/></label>)}</div>}
        {element.type==='FUNCTION'&&<div className="function-fields">
          {'tick' in element.call&&<label>{t('duties.executeTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} allowEmpty={false} min={0} precision={0} value={element.call.tick} onCommit={(tick)=>p.onPatchFunction(element.id,{tick:tick??0})}/></label>}
          {element.call.type==='PLACE_INITIAL'&&<><label>{t('ui.trainName')}<Input value={element.call.trainName} onChange={(event)=>p.onPatchFunction(element.id,{trainName:event.target.value})}/></label><label>{t('duties.initialWaypoint')}<Select value={element.call.wayPointId||undefined} options={options(p.metadata.wayPoints??[])} onChange={(wayPointId)=>p.onPatchFunction(element.id,{wayPointId})}/></label></>}
          {element.call.type==='WAIT'&&<label>{t('duties.waitDuration')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/duration`} allowEmpty={false} min={0} precision={0} value={element.call.durationTicks} onCommit={(durationTicks)=>p.onPatchFunction(element.id,{durationTicks:durationTicks??0})}/></label>}
          {element.call.type==='STORE_AT_DEPOT'&&<><label>{t('duties.depot')}<Select value={element.call.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatchFunction(element.id,{depotId})}/></label><label>{t('ui.stabling')}<Select mode="tags" value={element.call.stablingPointIds} options={options((p.metadata.depotDetails??[]).find((depot)=>element.call.type==='STORE_AT_DEPOT'&&depot.id===element.call.depotId)?.stablingPoints.map((point)=>point.id)??[])} onChange={(stablingPointIds)=>p.onPatchFunction(element.id,{stablingPointIds})}/></label></>}
        </div>}
      </section>)}</div>
    </>}</section></div>
    {p.onFormationEventsChange&&<FormationEventsEditor events={p.formationEvents??[]} dutyIds={p.duties.map((d)=>d.id)} destinations={p.metadata.wayPoints??[]} onChange={p.onFormationEventsChange} fieldScope={`${p.fieldScope}/formationEvents`}/>}
  </div>;
}
