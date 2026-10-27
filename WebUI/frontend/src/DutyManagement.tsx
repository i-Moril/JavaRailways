import {useEffect,useState} from 'react';
import {Button,Collapse,Flex,Input,Select,Typography} from 'antd';
import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import type {AuthoringDutyV1,AuthoringRunV1,TimetableAuthoringDocumentV1} from './authoring';
import {DutyTimeline} from './DutyTimeline';
import {DutyGraphEditor} from './DutyGraphEditor';
import {FormationEventsEditor} from './FormationEventsEditor';
import {formationOutputs,type AuthoringFormationEvent} from './formationAuthoring';
import type {ProgramSourceTarget} from './programNavigation';
import type {BoundStationProgram,StationProgramCall,StationProgramRecord,TimetableSimulationResult,WorldMetadata} from './types';
import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';

export interface DutyManagementProps {
  onValidityChange?:(valid:boolean)=>void;
  document?:TimetableAuthoringDocumentV1;onDocumentChange?:(document:TimetableAuthoringDocumentV1)=>void;
  formationEvents?:AuthoringFormationEvent[];onFormationEventsChange?:(events:AuthoringFormationEvent[])=>void;
  fieldScope?:string;focusedProgram?:ProgramSourceTarget;onOpenProgramLibrary?:(programId:string,stepId?:string)=>void;
  programLibrary?:StationProgramRecord[];boundPrograms?:BoundStationProgram[];programError?:string;programLoading?:boolean;
  onRefreshPrograms?:()=>void;onAddProgram?:(id:string)=>void;onAdoptProgram?:(id:string)=>void;onPatchProgram?:(id:string,patch:Partial<StationProgramCall>)=>void;
  duties:AuthoringDutyV1[];runs:AuthoringRunV1[];selectedId:string;metadata:WorldMetadata;
  onSelect:(id:string)=>void;onAdd:()=>void;onDelete:()=>void;
  onPatch:(patch:Partial<AuthoringDutyV1>)=>void;onAssign:(runId:string)=>void;
  onRemove:(id:string)=>void;onViewRun:(id:string)=>void;
  simulation?:TimetableSimulationResult;onSimulate?:()=>void;simulationReason?:string;busy?:boolean;
}
export function DutyManagement(p:DutyManagementProps) {
  const {t}=useI18n();
  const duty=p.duties.find((item)=>item.id===p.selectedId),selector=duty?.selector;
  const options=(values:string[])=>values.map((value)=>({value,label:value}));
  const [name,setName]=useState(duty?.id??'');
  useEffect(()=>setName(duty?.id??''),[duty?.id]);
  const commitName=()=>{if(duty&&name!==duty.id){p.onPatch({id:name});setName(duty.id);}};
  const selectorOptions=[{value:'TRAIN_NAME',label:t('ui.namedTrain')},{value:'TAKE_FROM_DEPOT',label:t('actions.TAKE_FROM_DEPOT')},{value:'TAKE_FROM_WAYPOINT',label:t('actions.TAKE_FROM_WAYPOINT')},{value:'TAKE_FROM_FORMATION_OUTPUT',label:t('formation.take')}];
  return <div className="module-page duties-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('duties.duties')}</Typography.Title><Typography.Text type="secondary">{t('timeline.description')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} onClick={p.onAdd}>{t('ui.newDuty')}</Button></div>
    <div className="duty-admin-grid"><aside className="entity-list panel-surface">{p.duties.map((item)=><Button type="text" className={`entity-card ${item.id===duty?.id?'active':''}`} key={item.id} onClick={()=>p.onSelect(item.id)}><i style={{background:'var(--ant-color-primary)'}}/><span><b>{item.id}</b><small>{item.elements.length} {t('timeline.tasks')}</small></span></Button>)}</aside>
    <section className="panel-surface duty-detail">{!duty||!selector?<p className="empty">{t('ui.selectDuty')}</p>:<>
      <div className="detail-heading"><Typography.Title level={4}>{duty.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={p.onDelete}>{t('common.delete')}</Button></div>
      <div className="duty-properties">
        <label>{t('duties.reference')}<Select aria-label={t('duties.reference')} value={duty.referenceVehicle?`${duty.referenceVehicle.type}:${duty.referenceVehicle.id}`:undefined} options={(p.metadata.simulationProfiles??[]).map((profile)=>({value:`${profile.type}:${profile.id}`,label:profile.displayName}))} onChange={(value)=>{const split=value.indexOf(':');p.onPatch({referenceVehicle:{type:value.slice(0,split) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',id:value.slice(split+1)}});}}/></label>
        <label>{t('ui.rename')}<Input value={name} onChange={(event)=>setName(event.target.value)} onBlur={commitName} onPressEnter={commitName}/></label>
        <label>{t('ui.selector')}<Select value={selector.type} onChange={(type)=>p.onPatch({initialPosition:type==='TRAIN_NAME'?duty.initialPosition:undefined,selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:type==='TAKE_FROM_FORMATION_OUTPUT'?{type,eventId:'',outputId:'',tick:0}:{type:'TAKE_FROM_WAYPOINT',wayPointIds:[],tick:0}})} options={selectorOptions}/></label>
        {selector.type==='TRAIN_NAME'?<label>{t('ui.trainName')}<Input value={selector.name} onChange={(event)=>p.onPatch({selector:{...selector,name:event.target.value}})}/></label>:<>
          <label>{t('duties.acquireTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/selector/${selector.type}/tick`} allowEmpty={false} min={0} precision={0} value={selector.tick} onCommit={(tick)=>p.onPatch({selector:{...selector,tick:tick??0}})}/></label>
          {selector.type==='TAKE_FROM_DEPOT'?<><label>{t('duties.depot')}<Select value={selector.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatch({selector:{...selector,depotId}})}/></label><label>{t('duties.requiredTags')}<Select mode="tags" value={selector.tags} onChange={(tags)=>p.onPatch({selector:{...selector,tags}})}/></label></>:selector.type==='TAKE_FROM_FORMATION_OUTPUT'?<><label>{t('programs.eventId')}<Select value={selector.eventId||undefined} options={options((p.formationEvents??[]).map((e)=>e.id))} onChange={(eventId)=>p.onPatch({selector:{...selector,eventId,outputId:''}})}/></label><label>{t('formation.outputId')}<Select value={selector.outputId||undefined} options={options((p.formationEvents??[]).filter((e)=>e.id===selector.eventId).flatMap(formationOutputs).map((o)=>o.id))} onChange={(outputId)=>p.onPatch({selector:{...selector,outputId}})}/></label></>:<label>{t('duties.waypoints')}<Select mode="multiple" value={selector.wayPointIds} options={options(p.metadata.wayPoints??[])} onChange={(wayPointIds)=>p.onPatch({selector:{...selector,wayPointIds}})}/></label>}
        </>}
      </div>

      {selector.type==='TRAIN_NAME'&&<label className="duty-initial-position">{t('duties.initialWaypoint')}<Select allowClear aria-label={t('duties.initialWaypoint')} value={duty.initialPosition} options={options(p.metadata.wayPoints??[])} onChange={(initialPosition)=>p.onPatch({initialPosition})}/><Typography.Text type="secondary">{t('timeline.initialHint')}</Typography.Text></label>}
      {p.programError&&<Typography.Paragraph type="danger" role="alert">{p.programError}</Typography.Paragraph>}
      {p.document&&<DutyTimeline key={duty.id} document={p.document} duty={duty} fieldScope={p.fieldScope??''} library={p.programLibrary??[]} simulation={p.simulation} simulationReason={p.simulationReason} busy={p.busy} onSimulate={p.onSimulate} onAssign={p.onAssign} onAddProgram={p.onAddProgram} onPatch={p.onPatch} onPatchProgram={p.onPatchProgram} onRemove={p.onRemove} onViewRun={p.onViewRun} onOpenProgramLibrary={p.onOpenProgramLibrary} onRefreshPrograms={p.onRefreshPrograms} programLoading={p.programLoading} focusedProgram={p.focusedProgram}/>}
      {!!p.boundPrograms?.length&&<Collapse className="duty-advanced" items={[{key:'bindings',label:t('programs.bound'),children:<><Typography.Paragraph type="secondary">{t('programs.snapshotHint')}</Typography.Paragraph>{p.boundPrograms.map((snapshot)=>{const latest=p.programLibrary?.find((record)=>record.id===snapshot.programId);return <Flex wrap gap={8} key={snapshot.programId}><Typography.Text>{latest?.name??snapshot.programId} · r{snapshot.revision}</Typography.Text>{latest&&latest.revision!==snapshot.revision?<Button onClick={()=>p.onAdoptProgram?.(snapshot.programId)}>{t('programs.adopt')} r{latest.revision}</Button>:null}</Flex>;})}</>}]} />}
    </>}</section></div>
    {p.document&&p.onDocumentChange&&<Collapse className="duty-advanced" items={[{key:'advanced',label:t('timeline.advanced'),children:<><DutyGraphEditor onValidityChange={p.onValidityChange} document={p.document} onChange={p.onDocumentChange} onViewRun={p.onViewRun} onSelectDuty={p.onSelect} fieldScope={p.fieldScope??''}/>{p.onFormationEventsChange&&<FormationEventsEditor events={p.formationEvents??[]} dutyIds={p.duties.map((d)=>d.id)} destinations={p.metadata.wayPoints??[]} onChange={p.onFormationEventsChange} fieldScope={`${p.fieldScope}/formationEvents`}/>}</>}]} />}
  </div>;
}
