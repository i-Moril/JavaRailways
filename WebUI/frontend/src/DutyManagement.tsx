import {EditableIdSelect,EditableIdList} from './EditableIdSelect';
import {waypointOptions} from './idOptions';
import {useEffect,useRef,useState} from 'react';
import {Alert,Button,Flex,Input,Select,Typography} from 'antd';
import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import type {AuthoringDutyV1,AuthoringRunV1,TimetableAuthoringDocumentV1} from './authoring';
import {DutyTimeline} from './DutyTimeline';
import type {ProgramSimulationContext} from './ProgramSimulationDialog';
import {orphanDisplayPlans,removeDisplayPlan} from './displayPlans';
import {FormationSourceFields} from './FormationEventsEditor';
import {type AuthoringFormationEvent} from './formationAuthoring';
import type {ProgramSourceTarget} from './programNavigation';
import type {TimedOperation,StationProgramRecord,TimetableSimulationResult,WorldMetadata} from './types';
import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';

export interface DutyManagementProps {
  programSimulation?:ProgramSimulationContext;graph:import('react').ReactNode;
  onOperationValidityChange?:(dutyId:string,elementId:string,valid:boolean)=>void;
  document?:TimetableAuthoringDocumentV1;onDocumentChange?:(document:TimetableAuthoringDocumentV1)=>void;
  onPatchFormation?:(event:AuthoringFormationEvent)=>void;onFormationSource?:(eventId:string,outputId:string)=>void;onOpenFormation?:(dutyId:string,eventId:string)=>void;
  focusedDutyElement?:{dutyId:string;elementId:string};fieldScope?:string;focusedProgram?:ProgramSourceTarget;onOpenProgramLibrary?:(programId:string,stepId?:string)=>void;
  programLibrary?:StationProgramRecord[];programError?:string;programLoading?:boolean;
  onOpenGraphEvent?:(id:string)=>void;
  onRefreshPrograms?:()=>void;onPatchProgram?:(id:string,patch:Partial<TimedOperation>)=>void;
  duties:AuthoringDutyV1[];runs:AuthoringRunV1[];selectedId:string;metadata:WorldMetadata;
  onSelect:(id:string)=>void;onAdd:()=>void;onDelete:()=>void;
  onPatch:(patch:Partial<AuthoringDutyV1>)=>void;
  onRemove:(id:string)=>void;onViewRun:(id:string)=>void;
  simulation?:TimetableSimulationResult;onSimulate?:()=>void;simulationReason?:string;busy?:boolean;
}
export function DutyManagement(p:DutyManagementProps) {
  const {t}=useI18n();
  const currentDocument=useRef(p.document);currentDocument.current=p.document;
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
      <div className="detail-heading"><div><Typography.Title level={4}>{duty.id}</Typography.Title><Typography.Text type="secondary">{duty.elements.length} {t('timeline.tasks')}</Typography.Text></div></div>
      <Typography.Paragraph className="duty-graph-hint" type="secondary">{t('duties.graphSelectHint')}</Typography.Paragraph>
      {p.graph}
      {p.programError&&<Typography.Paragraph type="danger" role="alert">{p.programError}</Typography.Paragraph>}
      {p.document&&<DutyTimeline programSimulation={p.programSimulation?{...p.programSimulation,profile:duty.referenceVehicle,plannedContext:{dutyId:duty.id}}:undefined} onDocumentChange={p.onDocumentChange} onOpenFormation={p.onOpenFormation} onPatchFormation={p.onPatchFormation} onSelectDuty={p.onSelect} destinations={p.metadata.wayPoints??[]} key={duty.id} document={p.document} duty={duty} focusedElementId={p.focusedDutyElement?.dutyId===duty.id?p.focusedDutyElement.elementId:undefined} fieldScope={p.fieldScope??''} library={p.programLibrary??[]} stationNames={new Map((p.metadata.stations??[]).map((station)=>[station.stationId,station.stationDisplayName||station.stationId]))} stationIds={[...new Set((p.metadata.stations??[]).map((station)=>station.stationId))]} onOpenGraphEvent={p.onOpenGraphEvent} onOperationValidityChange={p.onOperationValidityChange?((elementId,valid)=>p.onOperationValidityChange?.(duty.id,elementId,valid)):undefined} simulation={p.simulation} simulationReason={p.simulationReason} busy={p.busy} onSimulate={p.onSimulate} onPatch={p.onPatch} onPatchProgram={p.onPatchProgram} onRemove={p.onRemove} onViewRun={p.onViewRun} onOpenProgramLibrary={p.onOpenProgramLibrary} onRefreshPrograms={p.onRefreshPrograms} programLoading={p.programLoading} focusedProgram={p.focusedProgram}/>}
    </>}</section>
    <aside className="panel-surface duty-sidebar">{!duty||!selector?<p className="empty">{t('ui.selectDuty')}</p>:<>
      <section className="duty-side-section"><Typography.Title level={5}>{t('duties.dutySection')}</Typography.Title>
        <label>{t('ui.rename')}<Input value={name} onChange={(event)=>setName(event.target.value)} onBlur={commitName} onPressEnter={commitName}/></label>
        <label>{t('ui.selector')}<Select value={selector.type} onChange={(type)=>p.onPatch({initialPosition:type==='TRAIN_NAME'?duty.initialPosition:undefined,selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:type==='TAKE_FROM_FORMATION_OUTPUT'?{type,eventId:'',outputId:''}:{type:'TAKE_FROM_WAYPOINT',wayPointIds:[],tick:0}})} options={selectorOptions}/></label>
        {selector.type!=='TAKE_FROM_FORMATION_OUTPUT'&&<label>{t('duties.reference')}<Select aria-label={t('duties.reference')} value={duty.referenceVehicle?`${duty.referenceVehicle.type}:${duty.referenceVehicle.id}`:undefined} options={(p.metadata.simulationProfiles??[]).map((profile)=>({value:`${profile.type}:${profile.id}`,label:profile.displayName}))} onChange={(value)=>{const split=value.indexOf(':');p.onPatch({referenceVehicle:{type:value.slice(0,split) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',id:value.slice(split+1)}});}}/></label>}
        <Button danger block onClick={p.onDelete}>{t('common.delete')}</Button>
      </section>
      <section className="duty-side-section"><Typography.Title level={5}>{t('duties.initialSection')}</Typography.Title>
        {selector.type==='TAKE_FROM_FORMATION_OUTPUT'?p.document&&<FormationSourceFields document={p.document} dutyId={duty.id} eventId={selector.eventId} outputId={selector.outputId} onSelectDuty={id=>p.onOpenFormation?.(id,selector.eventId)} onChange={(eventId,outputId)=>p.onFormationSource?.(eventId,outputId)}/>:selector.type==='TRAIN_NAME'?<>
          <label>{t('ui.trainName')}<Input value={selector.name} onChange={(event)=>p.onPatch({selector:{...selector,name:event.target.value}})}/></label>
          <label>{t('duties.initialWaypoint')}<EditableIdSelect fieldKey={`${p.fieldScope}/${duty.id}/initialPosition`} allowClear aria-label={t('duties.initialWaypoint')} value={duty.initialPosition} options={waypointOptions(p.metadata)} onCommit={(initialPosition)=>p.onPatch({initialPosition:initialPosition||undefined})}/></label>
          <Typography.Text type="secondary">{t('timeline.initialHint')}</Typography.Text>
        </>:<>
          <label>{t('duties.acquireTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/selector/${selector.type}/tick`} allowEmpty={false} min={0} precision={0} value={selector.tick} onCommit={(tick)=>p.onPatch({selector:{...selector,tick:tick??0}})}/></label>
          {selector.type==='TAKE_FROM_DEPOT'?<><label>{t('duties.depot')}<Select value={selector.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatch({selector:{...selector,depotId}})}/></label><label>{t('duties.requiredTags')}<Select mode="tags" value={selector.tags} onChange={(tags)=>p.onPatch({selector:{...selector,tags}})}/></label></>:<label>{t('duties.waypoints')}<EditableIdList fieldKey={`${p.fieldScope}/${duty.id}/waypoints`} aria-label={t('duties.waypoints')} value={selector.wayPointIds} options={waypointOptions(p.metadata)} onChange={(wayPointIds)=>p.onPatch({selector:{...selector,wayPointIds}})}/></label>}
        </>}
      </section>
    </>}</aside></div>
    {p.document&&p.onDocumentChange&&orphanDisplayPlans(p.document).length>0&&<Alert className="display-plan-orphans" type="warning" showIcon title={t('displayPlan.orphans')} description={<>{orphanDisplayPlans(p.document).map(plan=><details key={plan.id}><summary>{t('displayPlan.plan')} {plan.id}</summary><pre>{JSON.stringify({defaults:plan.defaults,segments:plan.segments},null,2)}</pre><Button danger onClick={()=>{if(p.document&&currentDocument.current===p.document)p.onDocumentChange?.(removeDisplayPlan(p.document,plan.id));}}>{t('displayPlan.delete')}</Button></details>)}</>}/>}

  </div>;
}
