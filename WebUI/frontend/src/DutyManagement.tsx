import { useEffect,useState } from 'react';
import { Button,Flex,Input,Select,Space,Tag,Typography } from 'antd';
import { ArrowDownOutlined,ArrowUpOutlined,DeleteOutlined,PlusOutlined } from '@ant-design/icons';
import type { AuthoringDutyV1,AuthoringFunctionCallV1,AuthoringRunV1 } from './authoring';
import type { WorldMetadata } from './types';
import { CommittedNumberInput } from './CommittedNumberInput';
import { useI18n } from './i18n';

export interface DutyManagementProps {
  fieldScope?:string;
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
  const selectorOptions=[{value:'TRAIN_NAME',label:t('ui.namedTrain')},{value:'TAKE_FROM_DEPOT',label:t('actions.TAKE_FROM_DEPOT')},{value:'TAKE_FROM_WAYPOINT',label:t('actions.TAKE_FROM_WAYPOINT')}];
  const functionLabel=(type:AuthoringFunctionCallV1['type'])=>type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${type}`);
  return <div className="module-page duties-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('duties.duties')}</Typography.Title><Typography.Text type="secondary">{t('ui.dutyDescription')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} onClick={p.onAdd}>{t('ui.newDuty')}</Button></div>
    <div className="duty-admin-grid"><aside className="entity-list panel-surface">{p.duties.map((item)=><Button type="text" className={`entity-card ${item.id===duty?.id?'active':''}`} key={item.id} onClick={()=>p.onSelect(item.id)}><i style={{background:'var(--ant-color-primary)'}}/><span><b>{item.id}</b><small>{item.elements.filter((element)=>element.type==='RUN').length} {t('duties.runs')}</small></span></Button>)}</aside>
    <section className="panel-surface duty-detail">{!duty||!selector?<p className="empty">{t('ui.selectDuty')}</p>:<>
      <div className="detail-heading"><Typography.Title level={4}>{duty.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={p.onDelete}>{t('common.delete')}</Button></div>
      <div className="duty-properties">
        <label>{t('ui.rename')}<Input value={name} onChange={(event)=>setName(event.target.value)} onBlur={commitName} onPressEnter={commitName}/></label>
        <label>{t('ui.selector')}<Select value={selector.type} onChange={(type)=>p.onPatch({selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:{type:'TAKE_FROM_WAYPOINT',wayPointIds:[],tick:0}})} options={selectorOptions}/></label>
        {selector.type==='TRAIN_NAME'?<label>{t('ui.trainName')}<Input value={selector.name} onChange={(event)=>p.onPatch({selector:{...selector,name:event.target.value}})}/></label>:<>
          <label>{t('duties.acquireTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/selector/${selector.type}/tick`} allowEmpty={false} min={0} precision={0} value={selector.tick} onCommit={(tick)=>p.onPatch({selector:{...selector,tick:tick??0}})}/></label>
          {selector.type==='TAKE_FROM_DEPOT'?<><label>{t('duties.depot')}<Select value={selector.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatch({selector:{...selector,depotId}})}/></label><label>{t('duties.requiredTags')}<Select mode="tags" value={selector.tags} onChange={(tags)=>p.onPatch({selector:{...selector,tags}})}/></label></>:<label>{t('duties.waypoints')}<Select mode="multiple" value={selector.wayPointIds} options={options(p.metadata.wayPoints??[])} onChange={(wayPointIds)=>p.onPatch({selector:{...selector,wayPointIds}})}/></label>}
        </>}
      </div>
      <Flex className="duty-toolbar" wrap gap={8}><Select aria-label={t('duties.assign')} placeholder={t('duties.assign')} value={null} style={{minWidth:220}} onChange={p.onAssign} options={p.runs.map((run)=>({value:run.id,label:`${run.id} · ${p.duties.find((item)=>item.elements.some((element)=>element.type==='RUN'&&element.runId===run.id))?.id??t('duties.unassigned')}`}))}/><Select aria-label={t('ui.function')} placeholder={t('ui.function')} value={null} style={{minWidth:180}} onChange={p.onAddFunction} options={(['PLACE_INITIAL','REVERSE','WAIT','STORE_AT_DEPOT'] as const).map((value)=>({value,label:functionLabel(value)}))}/></Flex>
      <div className="duty-chain">{duty.elements.map((element,index)=><section key={element.id} className="duty-element"><Flex justify="space-between" align="center" gap={8}><Space><Tag>{index+1}</Tag>{element.type==='RUN'?<Button type="link" onClick={()=>p.onViewRun(element.runId)}>{element.runId}</Button>:<Typography.Text strong>{functionLabel(element.call.type)}</Typography.Text>}</Space><Space><Button aria-label={t('common.moveUp')} icon={<ArrowUpOutlined/>} disabled={index===0} onClick={()=>p.onMove(element.id,-1)}/><Button aria-label={t('common.moveDown')} icon={<ArrowDownOutlined/>} disabled={index===duty.elements.length-1} onClick={()=>p.onMove(element.id,1)}/><Button danger aria-label={t('common.delete')} icon={<DeleteOutlined/>} onClick={()=>p.onRemove(element.id)}/></Space></Flex>
        {element.type==='FUNCTION'&&<div className="function-fields">
          {'tick' in element.call&&<label>{t('duties.executeTick')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/tick`} allowEmpty={false} min={0} precision={0} value={element.call.tick} onCommit={(tick)=>p.onPatchFunction(element.id,{tick:tick??0})}/></label>}
          {element.call.type==='PLACE_INITIAL'&&<><label>{t('ui.trainName')}<Input value={element.call.trainName} onChange={(event)=>p.onPatchFunction(element.id,{trainName:event.target.value})}/></label><label>{t('duties.initialWaypoint')}<Select value={element.call.wayPointId||undefined} options={options(p.metadata.wayPoints??[])} onChange={(wayPointId)=>p.onPatchFunction(element.id,{wayPointId})}/></label></>}
          {element.call.type==='WAIT'&&<label>{t('duties.waitDuration')}<CommittedNumberInput fieldKey={`${p.fieldScope}/${duty.id}/${element.id}/duration`} allowEmpty={false} min={0} precision={0} value={element.call.durationTicks} onCommit={(durationTicks)=>p.onPatchFunction(element.id,{durationTicks:durationTicks??0})}/></label>}
          {element.call.type==='STORE_AT_DEPOT'&&<><label>{t('duties.depot')}<Select value={element.call.depotId||undefined} options={options(p.metadata.depots??[])} onChange={(depotId)=>p.onPatchFunction(element.id,{depotId})}/></label><label>{t('ui.stabling')}<Select mode="tags" value={element.call.stablingPointIds} options={options((p.metadata.depotDetails??[]).find((depot)=>element.call.type==='STORE_AT_DEPOT'&&depot.id===element.call.depotId)?.stablingPoints.map((point)=>point.id)??[])} onChange={(stablingPointIds)=>p.onPatchFunction(element.id,{stablingPointIds})}/></label></>}
        </div>}
      </section>)}</div>
    </>}</section></div>
  </div>;
}
