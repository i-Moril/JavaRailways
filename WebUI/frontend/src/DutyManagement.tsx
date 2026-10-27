import {applyDutyCanvasElements,defaultDutyProgramArguments,getDutyBlockDraftValidity} from './dutyBlocks';
import {ProgramBlockEditor,getProgramBlockDraftValidity} from './ProgramBlockEditor';
import {DutyBlockEditor,type DutyBlockTemplate} from './DutyBlockEditor';
import {RunConnectionEditor} from './RunConnectionEditor';
import {DutyGraphEditor} from './DutyGraphEditor';
import {findProgramStep} from './stationPrograms';
import {FormationEventsEditor} from './FormationEventsEditor';
import {formationOutputs,type AuthoringFormationEvent} from './formationAuthoring';
import { useCallback,useEffect,useRef,useState } from 'react';
import { Button,Flex,Input,Select,Space,Typography } from 'antd';
import { DeleteOutlined,PlusOutlined } from '@ant-design/icons';
import type { AuthoringDutyV1,AuthoringFunctionCallV1,AuthoringRunV1,TimetableAuthoringDocumentV1 } from './authoring';
import type {ProgramSourceTarget} from './programNavigation';
import { ProgramLiteralInput } from './ProgramFields';
import type { BoundStationProgram,StationProgramCall,StationProgramRecord,WorldMetadata } from './types';
import { CommittedNumberInput } from './CommittedNumberInput';
import { useI18n } from './i18n';

export interface DutyManagementProps {
  onValidityChange?:(valid:boolean)=>void;
  draftScope?:string;onUndo?:()=>void;onRedo?:()=>void;
  document?:TimetableAuthoringDocumentV1;onDocumentChange?:(document:TimetableAuthoringDocumentV1)=>void;
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
  const [selectedElement,setSelectedElement]=useState('');
  const latest=useRef(p);latest.current=p;
  const invalidSources=useRef(new Set<string>()),[,setValidityRevision]=useState(0);
  const sourceValid=useCallback((key:string,valid:boolean)=>{
    if(invalidSources.current.has(key)===!valid)return;
    if(valid)invalidSources.current.delete(key);else invalidSources.current.add(key);
    setValidityRevision((n)=>n+1);latest.current.onValidityChange?.(invalidSources.current.size===0);
  },[]);
  const stableDraftScope=p.draftScope??p.fieldScope??'';
  const cachedInvalid=p.duties.some((item)=>!getDutyBlockDraftValidity(`${stableDraftScope}/duty/${item.id}`,item.elements)||item.elements.some((element)=>element.type==='INLINE'&&!getProgramBlockDraftValidity(`${stableDraftScope}/duty/${item.id}/inline/${element.id}`,element.definition,'duty')));
  useEffect(()=>{sourceValid('cached',!cachedInvalid);},[cachedInvalid,sourceValid]);
  useEffect(()=>{for(const key of [...invalidSources.current])if(key.startsWith('canvas/')&&!p.duties.some((item)=>key===`canvas/${item.id}`)||key.startsWith('inline/')&&!p.duties.some((item)=>item.elements.some((element)=>element.type==='INLINE'&&key===`inline/${item.id}/${element.id}`)))sourceValid(key,true);},[p.duties,sourceValid]);

  const duty=p.duties.find((item)=>item.id===p.selectedId);
  useEffect(()=>{setSelectedElement('');},[duty?.id]);
  useEffect(()=>{const target=p.focusedProgram;if(target&&target.dutyId===duty?.id)setSelectedElement(target.elementId);},[p.focusedProgram,duty?.id]);
  const options=(values:string[])=>values.map((value)=>({value,label:value}));
  const selector=duty?.selector;
  const [name,setName]=useState(duty?.id??'');
  useEffect(()=>setName(duty?.id??''),[duty?.id]);
  const commitName=()=>{if(duty&&name!==duty.id){p.onPatch({id:name});setName(duty.id);}};
  const selectorOptions=[{value:'TRAIN_NAME',label:t('ui.namedTrain')},{value:'TAKE_FROM_DEPOT',label:t('actions.TAKE_FROM_DEPOT')},{value:'TAKE_FROM_WAYPOINT',label:t('actions.TAKE_FROM_WAYPOINT')},{value:'TAKE_FROM_FORMATION_OUTPUT',label:t('formation.take')}];
  const functionLabel=(type:AuthoringFunctionCallV1['type'])=>type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${type}`);
  const programSnapshots:BoundStationProgram[]=[...(p.boundPrograms??[])];
  for(const record of p.programLibrary??[])if(!programSnapshots.some((item)=>item.programId===record.id))programSnapshots.push({programId:record.id,revision:record.revision,contentIdentity:record.contentIdentity,definition:record.definition});
  const templates:DutyBlockTemplate[]=[
    ...p.runs.filter((run)=>!p.duties.some((item)=>item.elements.some((element)=>element.type==='RUN'&&element.runId===run.id))).map((run)=>({label:run.id,element:{id:'',type:'RUN' as const,runId:run.id}})),
    {label:t('duties.inlineProgram'),element:{id:'',type:'INLINE',definition:{parameters:[],steps:[]}}},
    ...programSnapshots.filter((snapshot)=>snapshot.definition.steps.length>0).map((snapshot)=>({label:p.programLibrary?.find((record)=>record.id===snapshot.programId)?.name??snapshot.programId,programSnapshot:snapshot,element:{id:'',type:'PROGRAM' as const,call:{id:'',programId:snapshot.programId,tick:0,arguments:defaultDutyProgramArguments(snapshot.definition.parameters)}}})),
    ...(['PLACE_INITIAL','REVERSE','WAIT','STORE_AT_DEPOT'] as const).map((type)=>({label:functionLabel(type),element:{id:'',type:'FUNCTION' as const,call:type==='PLACE_INITIAL'?{id:'',type,trainName:selector?.type==='TRAIN_NAME'?selector.name:'',wayPointId:''}:type==='WAIT'?{id:'',type,tick:0,durationTicks:200}:type==='STORE_AT_DEPOT'?{id:'',type,tick:0,depotId:'',stablingPointIds:[]}:{id:'',type,tick:0}}})),
  ];
  const changeCanvas=(elements:NonNullable<typeof duty>['elements'],snapshots:BoundStationProgram[])=>{
    if(!duty)return;
    if(p.document&&p.onDocumentChange)p.onDocumentChange(applyDutyCanvasElements(p.document,duty.id,elements,snapshots));
    else p.onPatch({elements});
  };
  return <div className="module-page duties-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('duties.duties')}</Typography.Title><Typography.Text type="secondary">{t('ui.dutyDescription')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} onClick={p.onAdd}>{t('ui.newDuty')}</Button></div>
    <div className="duty-admin-grid"><aside className="entity-list panel-surface">{p.duties.map((item)=><Button type="text" className={`entity-card ${item.id===duty?.id?'active':''}`} key={item.id} onClick={()=>p.onSelect(item.id)}><i style={{background:'var(--ant-color-primary)'}}/><span><b>{item.id}</b><small>{item.elements.filter((element)=>element.type==='RUN').length} {t('duties.runs')}</small></span></Button>)}</aside>
    <section className="panel-surface duty-detail">{!duty||!selector?<p className="empty">{t('ui.selectDuty')}</p>:<>
      <div className="detail-heading"><Typography.Title level={4}>{duty.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={p.onDelete}>{t('common.delete')}</Button></div>
      <div className="duty-properties">
        <label>{t('duties.reference')}<Select aria-label={t('duties.reference')} value={duty.referenceVehicle?`${duty.referenceVehicle.type}:${duty.referenceVehicle.id}`:undefined} options={(p.metadata.simulationProfiles??[]).map((profile)=>({value:`${profile.type}:${profile.id}`,label:profile.displayName}))} onChange={(value)=>{const split=value.indexOf(':');p.onPatch({referenceVehicle:{type:value.slice(0,split) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',id:value.slice(split+1)}});}}/></label>
        <label>{t('ui.rename')}<Input value={name} onChange={(event)=>setName(event.target.value)} onBlur={commitName} onPressEnter={commitName}/></label>
        <label>{t('ui.selector')}<Select value={selector.type} onChange={(type)=>p.onPatch({selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:type==='TAKE_FROM_FORMATION_OUTPUT'?{type,eventId:'',outputId:'',tick:0}:{type:'TAKE_FROM_WAYPOINT',wayPointIds:[],tick:0}})} options={selectorOptions}/></label>
        {selector.type==='TRAIN_NAME'?<label>{t('ui.trainName')}<Input value={selector.name} onChange={(event)=>p.onPatch({selector:{...selector,name:event.target.value}})}/></label>:<>
          <label>{t('duties.acquireTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/selector/${selector.type}/tick`} allowEmpty={false} min={0} precision={0} value={selector.tick} onCommit={(tick)=>p.onPatch({selector:{...selector,tick:tick??0}})}/></label>
          {selector.type==='TAKE_FROM_DEPOT'?<><label>{t('duties.depot')}<Select value={selector.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatch({selector:{...selector,depotId}})}/></label><label>{t('duties.requiredTags')}<Select mode="tags" value={selector.tags} onChange={(tags)=>p.onPatch({selector:{...selector,tags}})}/></label></>:selector.type==='TAKE_FROM_FORMATION_OUTPUT'?<><label>{t('programs.eventId')}<Select value={selector.eventId||undefined} options={options((p.formationEvents??[]).map((e)=>e.id))} onChange={(eventId)=>p.onPatch({selector:{...selector,eventId,outputId:''}})}/></label><label>{t('formation.outputId')}<Select value={selector.outputId||undefined} options={options((p.formationEvents??[]).filter((e)=>e.id===selector.eventId).flatMap(formationOutputs).map((o)=>o.id))} onChange={(outputId)=>p.onPatch({selector:{...selector,outputId}})}/></label></>:<label>{t('duties.waypoints')}<Select mode="multiple" value={selector.wayPointIds} options={options(p.metadata.wayPoints??[])} onChange={(wayPointIds)=>p.onPatch({selector:{...selector,wayPointIds}})}/></label>}
        </>}
      </div>
      <Space style={{margin:'12px 0'}}><Button disabled={p.programLoading} loading={p.programLoading} onClick={p.onRefreshPrograms}>{t('programs.refresh')}</Button></Space>
      {p.programError&&<Typography.Paragraph type="danger" role="alert">{p.programError}</Typography.Paragraph>}
      {!!p.boundPrograms?.length&&<section><Typography.Title level={5}>{t('programs.bound')}</Typography.Title><Typography.Paragraph type="secondary">{t('programs.snapshotHint')}</Typography.Paragraph>{p.boundPrograms.map((snapshot)=>{const latest=p.programLibrary?.find((record)=>record.id===snapshot.programId);return <Flex wrap gap={8} key={snapshot.programId}><Typography.Text>{snapshot.programId} · r{snapshot.revision}</Typography.Text>{latest&&latest.revision!==snapshot.revision?<Button onClick={()=>p.onAdoptProgram?.(snapshot.programId)}>{t('programs.adopt')} r{latest.revision}</Button>:null}</Flex>;})}</section>}
      {p.document&&p.onDocumentChange&&duty.elements.flatMap((element,i)=>{
        const next=duty.elements[i+1];if(element.type!=='RUN'||next?.type!=='RUN')return [];
        const from=p.runs.find((r)=>r.id===element.runId),to=p.runs.find((r)=>r.id===next.runId);
        return from&&to&&from.calls.at(-1)?.targetId===to.calls[0]?.targetId?[<RunConnectionEditor key={`${element.id}/${next.id}`} document={p.document!} from={from} to={to} onChange={p.onDocumentChange!} fieldScope={p.fieldScope??''}/>]:[];
      })}
      <DutyBlockEditor templates={templates} key={duty.id} scope={`${p.fieldScope}/${duty.id}`} draftScope={`${stableDraftScope}/duty/${duty.id}`} onValidityChange={(valid)=>sourceValid(`canvas/${duty.id}`,valid)} onUndo={p.onUndo} onRedo={p.onRedo} elements={duty.elements} onSelect={setSelectedElement} onChange={changeCanvas}/>
      <div className="duty-chain">{duty.elements.filter((element)=>element.id===(duty.elements.some((item)=>item.id===selectedElement)?selectedElement:duty.elements[0]?.id)).map((element)=>{return <section key={element.id} className={`duty-element ${p.focusedProgram?.dutyId===duty.id&&p.focusedProgram.elementId===element.id?'program-step-focused':''}`}><Flex justify="space-between" align="center" gap={8}><Space>{element.type==='RUN'?<Button type="link" onClick={()=>p.onViewRun(element.runId)}>{element.runId}</Button>:<Typography.Text strong>{element.type==='INLINE'?t('duties.inlineProgram'):element.type==='PROGRAM'?`${t('programs.call')} · ${element.call.programId}`:functionLabel(element.call.type)}</Typography.Text>}</Space></Flex>
        {element.type==='INLINE'&&<section><label>{t('duties.optionalTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} allowEmpty min={0} precision={0} value={element.tick} onCommit={(tick)=>p.onPatch({elements:duty.elements.map((item)=>item.id===element.id?{...element,tick:tick??undefined}:item)})}/></label><ProgramBlockEditor events={p.document?.events} value={element.definition} context="duty" fieldScope={`${p.fieldScope}/${duty.id}/${element.id}/inline`} draftScope={`${stableDraftScope}/duty/${duty.id}/inline/${element.id}`} extensions={p.metadata.extensions?.actions} conditions={p.metadata.extensions?.conditions} onUndo={p.onUndo} onRedo={p.onRedo} onValidityChange={(valid)=>sourceValid(`inline/${duty.id}/${element.id}`,valid)} onChange={(definition)=>p.onPatch({elements:duty.elements.map((item)=>item.id===element.id?{...element,definition}:item)})}/></section>}
        {element.type==='PROGRAM'&&p.focusedProgram?.dutyId===duty.id&&p.focusedProgram.elementId===element.id&&<section className="program-bound-step"><Typography.Text strong>{t('programs.boundStep')} · {p.focusedProgram.stepId??element.call.id} · r{p.focusedProgram.revision}</Typography.Text><Typography.Paragraph type="secondary">{t('programs.boundStepHint')}</Typography.Paragraph><pre>{JSON.stringify(findProgramStep(p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition.steps??[],p.focusedProgram?.stepId??'')??p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition,null,2)}</pre><Button onClick={()=>p.onOpenProgramLibrary?.(element.call.programId,p.focusedProgram?.stepId)}>{t('programs.library')}</Button></section>}
        {element.type==='PROGRAM'&&<div className="function-fields"><label>{t('programs.callTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} value={element.call.tick} allowEmpty={false} min={0} precision={0} onCommit={(tick)=>p.onPatchProgram?.(element.id,{tick:tick??0})}/></label>{p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.definition.parameters.map((parameter)=><label key={parameter.id}>{parameter.id}<ProgramLiteralInput type={parameter.type} value={element.call.arguments[parameter.id]} fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/${p.boundPrograms?.find((snapshot)=>snapshot.programId===element.call.programId)?.revision}/${parameter.id}`} onChange={(next)=>p.onPatchProgram?.(element.id,{arguments:{...element.call.arguments,[parameter.id]:next}})}/></label>)}</div>}
        {element.type==='FUNCTION'&&<div className="function-fields">
          {'tick' in element.call&&<label>{t('duties.executeTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} allowEmpty={false} min={0} precision={0} value={element.call.tick} onCommit={(tick)=>p.onPatchFunction(element.id,{tick:tick??0})}/></label>}
          {element.call.type==='PLACE_INITIAL'&&<><label>{t('ui.trainName')}<Input value={element.call.trainName} onChange={(event)=>p.onPatchFunction(element.id,{trainName:event.target.value})}/></label><label>{t('duties.initialWaypoint')}<Select value={element.call.wayPointId||undefined} options={options(p.metadata.wayPoints??[])} onChange={(wayPointId)=>p.onPatchFunction(element.id,{wayPointId})}/></label></>}
          {element.call.type==='WAIT'&&<label>{t('duties.waitDuration')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/duration`} allowEmpty={false} min={0} precision={0} value={element.call.durationTicks} onCommit={(durationTicks)=>p.onPatchFunction(element.id,{durationTicks:durationTicks??0})}/></label>}
          {element.call.type==='STORE_AT_DEPOT'&&<><label>{t('duties.depot')}<Select value={element.call.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatchFunction(element.id,{depotId})}/></label><label>{t('ui.stabling')}<Select mode="tags" value={element.call.stablingPointIds} options={options((p.metadata.depotDetails??[]).find((depot)=>element.call.type==='STORE_AT_DEPOT'&&depot.id===element.call.depotId)?.stablingPoints.map((point)=>point.id)??[])} onChange={(stablingPointIds)=>p.onPatchFunction(element.id,{stablingPointIds})}/></label></>}
        </div>}
      </section>;})}</div>
    </>}</section></div>
    {p.document&&p.onDocumentChange&&<DutyGraphEditor onValidityChange={(valid)=>sourceValid('graph',valid)} document={p.document} onChange={p.onDocumentChange} onViewRun={p.onViewRun} onSelectDuty={p.onSelect} fieldScope={p.fieldScope??''}/>}
    {p.onFormationEventsChange&&<FormationEventsEditor events={p.formationEvents??[]} dutyIds={p.duties.map((d)=>d.id)} destinations={p.metadata.wayPoints??[]} onChange={p.onFormationEventsChange} fieldScope={`${p.fieldScope}/formationEvents`}/>}
  </div>;
}
