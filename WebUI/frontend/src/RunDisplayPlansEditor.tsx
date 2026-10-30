import {useEffect,useRef,useState} from 'react';
import {Alert,Button,Flex,Select,Typography} from 'antd';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {OperationalTrip,OperationalTripSegment} from './types';
import {displayPlanRuns,displayPlansForRun,createDisplayPlan,attachDisplayPlanRun,updateDisplayPlanDefaults,updateDisplayPlanLeg,moveDisplayPlanLeg,removeDisplayPlanLeg,removeDisplayPlan} from './displayPlans';
import {UnitSlotsInput} from './UnitSlotsInput';
import {ProgramJsonInput} from './ProgramJsonInput';
import {useI18n} from './i18n';
import './RunDisplayPlansEditor.css';

export function RunDisplayPlansEditor(p:{document:TimetableAuthoringDocumentV1;serviceId:string;runId:string;fieldScope:string;onChange:(document:TimetableAuthoringDocumentV1)=>void;onViewRun:(id:string)=>void;onValidityChange?:(valid:boolean)=>void}) {
 const {t}=useI18n();const latest=useRef(p);latest.current=p;
 const [slots,setSlots]=useState([1]),[attachId,setAttachId]=useState<string>(),[error,setError]=useState('');
 const [invalid,setInvalid]=useState<Record<string,boolean>>({});
 const plans=displayPlansForRun(p.document,p.serviceId,p.runId);
 const fieldKeys=new Set(plans.flatMap(plan=>[`${plan.id}/defaults`,...plan.segments.map((leg,index)=>`${plan.id}/${index}/${JSON.stringify([leg.serviceId,leg.runId])}`)]));
 const valid=!Object.entries(invalid).some(([key,value])=>value&&fieldKeys.has(key));
 useEffect(()=>{latest.current.onValidityChange?.(valid);},[valid]);
 useEffect(()=>()=>{latest.current.onValidityChange?.(true);},[]);
 const validity=(key:string,value:boolean)=>setInvalid(current=>current[key]===!value?current:{...current,[key]:!value});
 const change=(operation:(document:TimetableAuthoringDocumentV1)=>TimetableAuthoringDocumentV1)=>{if(latest.current.document!==p.document)return;try{p.onChange(operation(p.document));setError('');}catch(problem){setError(problem instanceof Error?problem.message:String(problem));}};
 const leg={serviceId:p.serviceId,runId:p.runId,unitSlots:slots};
 const choices=(p.document.operationalTrips??[]).filter(plan=>!plans.some(item=>item.id===plan.id));
 return <section className="run-display-plans" aria-label={t('displayPlan.title')}><Typography.Title level={5}>{t('displayPlan.title')}</Typography.Title><Typography.Paragraph type="secondary">{t('displayPlan.help')}</Typography.Paragraph>
 {error&&<Alert type="error" showIcon title={error}/>}
 {plans.map(plan=><DisplayPlan key={`${p.fieldScope}/${plan.id}`} plan={plan} document={p.document} fieldScope={p.fieldScope} change={change} validity={validity} onViewRun={p.onViewRun}/>)}
 <section className="display-plan-attach"><Typography.Text strong>{t('displayPlan.unitsForRun')}</Typography.Text><UnitSlotsInput fieldKey={`${p.fieldScope}/create`} value={slots} onChange={setSlots}/><Flex wrap gap={8}><Button onClick={()=>change(document=>createDisplayPlan(document,leg))}>{t('displayPlan.create')}</Button><Select aria-label={t('displayPlan.existing')} placeholder={t('displayPlan.existing')} value={choices.some(plan=>plan.id===attachId)?attachId:undefined} options={choices.map(plan=>({value:plan.id,label:`${t('displayPlan.plan')} ${plan.id}`}))} onChange={setAttachId}/><Button disabled={!attachId||!choices.some(plan=>plan.id===attachId)} onClick={()=>{if(attachId)change(document=>attachDisplayPlanRun(document,attachId,leg));}}>{t('displayPlan.attach')}</Button></Flex></section>
 </section>;
}
function DisplayPlan(p:{plan:OperationalTrip;document:TimetableAuthoringDocumentV1;fieldScope:string;change:(operation:(document:TimetableAuthoringDocumentV1)=>TimetableAuthoringDocumentV1)=>void;validity:(key:string,valid:boolean)=>void;onViewRun:(id:string)=>void}) {
 const {t}=useI18n(),plan=p.plan,runs=displayPlanRuns(p.document);
 const [run,setRun]=useState<string>(),[slots,setSlots]=useState([1]);
 const options=runs.map(item=>({value:JSON.stringify([item.serviceId,item.runId]),label:item.label}));
 const patch=(index:number,leg:OperationalTripSegment)=>p.change(document=>updateDisplayPlanLeg(document,plan.id,index,leg));
 const available=options.filter(option=>!plan.segments.some(leg=>JSON.stringify([leg.serviceId,leg.runId])===option.value));
 return <section className="display-plan"><Flex wrap gap={8} justify="space-between" align="center"><Typography.Text strong>{t('displayPlan.plan')} {plan.id}</Typography.Text><Button danger onClick={()=>p.change(document=>removeDisplayPlan(document,plan.id))}>{t('displayPlan.delete')}</Button></Flex>
 <Typography.Text>{t('displayPlan.defaults')}</Typography.Text><ProgramJsonInput label={t('displayPlan.defaults')} fieldKey={`${p.fieldScope}/${plan.id}/defaults`} value={plan.defaults} onValidityChange={valid=>p.validity(`${plan.id}/defaults`,valid)} onChange={defaults=>p.change(document=>updateDisplayPlanDefaults(document,plan.id,defaults))}/>
 <ol className="display-plan-legs">{plan.segments.map((leg,index)=>{const value=JSON.stringify([leg.serviceId,leg.runId]),exists=runs.some(item=>item.serviceId===leg.serviceId&&item.runId===leg.runId),identity=`${plan.id}/${index}/${value}`;return <li key={identity} className="display-plan-leg"><div className="display-plan-leg-heading"><Select aria-label={`${t('displayPlan.run')} ${index+1}`} value={value} options={exists?options:[{value,label:`${leg.serviceId} / ${leg.runId} · ${t('displayPlan.missing')}`},...options]} onChange={value=>{const [serviceId,runId]=JSON.parse(value) as string[];patch(index,{...leg,serviceId,runId});}}/><Button disabled={!exists} onClick={()=>p.onViewRun(leg.runId)}>{t('displayPlan.viewRun')}</Button><Button aria-label={`${t('common.moveUp')} ${index+1}`} disabled={index===0} onClick={()=>p.change(document=>moveDisplayPlanLeg(document,plan.id,index,-1))}>↑</Button><Button aria-label={`${t('common.moveDown')} ${index+1}`} disabled={index===plan.segments.length-1} onClick={()=>p.change(document=>moveDisplayPlanLeg(document,plan.id,index,1))}>↓</Button><Button danger aria-label={`${t('displayPlan.removeRun')} ${index+1}`} onClick={()=>p.change(document=>removeDisplayPlanLeg(document,plan.id,index))}>×</Button></div>{!exists&&<Typography.Text type="danger">{t('displayPlan.missing')}: {leg.serviceId} / {leg.runId}</Typography.Text>}<UnitSlotsInput fieldKey={`${p.fieldScope}/${identity}/slots`} value={leg.unitSlots} onValidityChange={valid=>p.validity(identity,valid)} onChange={unitSlots=>patch(index,{...leg,unitSlots})}/></li>;})}</ol>
 <div className="display-plan-add-leg"><Typography.Text>{t('displayPlan.addRun')}</Typography.Text><Select aria-label={t('displayPlan.addRun')} value={available.some(option=>option.value===run)?run:undefined} options={available} onChange={setRun}/><UnitSlotsInput fieldKey={`${p.fieldScope}/${plan.id}/add-slots`} value={slots} onChange={setSlots}/><Button disabled={!run||!available.some(option=>option.value===run)} onClick={()=>{if(!run)return;const [serviceId,runId]=JSON.parse(run) as string[];p.change(document=>attachDisplayPlanRun(document,plan.id,{serviceId,runId,unitSlots:slots}));}}>{t('common.add')}</Button></div>
 </section>;
}
