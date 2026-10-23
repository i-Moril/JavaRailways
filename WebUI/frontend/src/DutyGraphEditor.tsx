import {useState} from 'react';
import {Button,Flex,Input,Select,Space,Tag,Typography} from 'antd';
import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {OperationalTrip,OperationalTripSegment} from './types';
import {buildDutyGraph} from './dutyGraph';
import {ProgramJsonInput} from './ProgramJsonInput';
import {useI18n} from './i18n';
import {ProgramLiteralInput} from './ProgramFields';

export function DutyGraphEditor({document,onChange,onViewRun,onSelectDuty,fieldScope,onValidityChange}:{onValidityChange?:(valid:boolean)=>void;document:TimetableAuthoringDocumentV1;onChange:(document:TimetableAuthoringDocumentV1)=>void;onViewRun:(id:string)=>void;onSelectDuty:(id:string)=>void;fieldScope:string}){
  const {t}=useI18n();
  const graph=buildDutyGraph(document),trips=document.operationalTrips??[];
  const [selectedNode,setSelectedNode]=useState(''),[selectedTrip,setSelectedTrip]=useState(trips[0]?.id??'');
  const [newId,setNewId]=useState('');
  const selected=graph.nodes.find((n)=>n.id===selectedNode),trip=trips.find((v)=>v.id===selectedTrip);
  const patchTrips=(next:OperationalTrip[])=>onChange({...document,operationalTrips:next});
  const patchTrip=(patch:Partial<OperationalTrip>)=>{if(trip)patchTrips(trips.map((v)=>v.id===trip.id?{...v,...patch}:v));};
  const runOptions=graph.edges.map((e)=>({value:e.runId,label:e.label}));
  const addLeg=(runId:string)=>{const edge=graph.edges.find((e)=>e.runId===runId);if(edge&&trip)patchTrip({segments:[...trip.segments,{serviceId:edge.dutyId,runId,unitSlots:[1]}]});};
  const patchLeg=(index:number,patch:Partial<OperationalTripSegment>)=>{if(trip)patchTrip({segments:trip.segments.map((leg,i)=>i===index?{...leg,...patch}:leg)});};
  const ranks=new Map(graph.nodes.map((n)=>[n.id,0]));
  for(let i=0;i<graph.nodes.length;i++){let changed=false;for(const e of graph.edges){const next=Math.min(graph.nodes.length,(ranks.get(e.from)??0)+1);if(next>(ranks.get(e.to)??0)){ranks.set(e.to,next);changed=true;}}if(!changed)break;}
  const positions=new Map(graph.nodes.map((n)=>{const rows=n.dutyIds.map((id)=>Math.max(0,document.duties.findIndex((d)=>d.id===id)));return [n.id,{x:100+(ranks.get(n.id)??0)*260,y:65+(rows.reduce((a,b)=>a+b,0)/Math.max(1,rows.length))*100}];}));
  return <section className="panel-surface duty-network"><Typography.Title level={4}>{t('trip.graph')}</Typography.Title><Typography.Paragraph type="secondary">{t('trip.graphHelp')}</Typography.Paragraph>
    <div className="duty-network-canvas"><svg role="group" aria-label={t('trip.graph')} width={Math.max(600,...[...positions.values()].map((p)=>p.x+140))} height={Math.max(180,document.duties.length*100+70)}>
      {graph.edges.map((edge)=>{const from=positions.get(edge.from)!,to=positions.get(edge.to)!;return <g key={edge.id}><path d={`M ${from.x+36} ${from.y} C ${(from.x+to.x)/2} ${from.y}, ${(from.x+to.x)/2} ${to.y}, ${to.x-36} ${to.y}`} fill="none" stroke="var(--ant-color-primary)" strokeWidth={trip?.segments.some((s)=>s.runId===edge.runId&&s.serviceId===edge.dutyId)?4:2}/><g role="button" tabIndex={0} aria-label={edge.label} onClick={()=>onViewRun(edge.runId)} onKeyDown={(event)=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();onViewRun(edge.runId);}}}><rect x={(from.x+to.x)/2-72} y={(from.y+to.y)/2-18} width="144" height="26" rx="4" fill="var(--ant-color-bg-container)"/><text x={(from.x+to.x)/2} y={(from.y+to.y)/2} textAnchor="middle" fill="var(--ant-color-text)">{edge.runId}</text></g></g>;})}
      {graph.nodes.map((n)=>{const p=positions.get(n.id)!;return <g key={n.id} role="button" tabIndex={0} aria-label={`${t('trip.boundary')} ${n.label} ${n.kind}`} aria-pressed={selectedNode===n.id} onClick={()=>setSelectedNode(n.id)} onKeyDown={(event)=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();setSelectedNode(n.id);}}}><rect x={p.x-36} y={p.y-19} width="72" height="38" rx={n.kind==='BOUNDARY'?18:4} fill="var(--ant-color-bg-container)" stroke={selectedNode===n.id?'var(--ant-color-primary)':'var(--ant-color-border)'} strokeWidth="2"/><text x={p.x} y={p.y+4} textAnchor="middle" fill="var(--ant-color-text)">{n.label.length>9?n.label.slice(0,8)+'…':n.label}</text><title>{n.label} · {n.kind}</title></g>;})}
    </svg></div>
    <div className="duty-network-edges">{graph.edges.map((edge)=><div className="duty-network-edge" key={edge.id}>
      <Button className={selectedNode===edge.from?'active':''} onClick={()=>setSelectedNode(edge.from)} aria-label={`${t('trip.boundary')} ${edge.runId} ${t('status.origin')}`}>{graph.nodes.find((n)=>n.id===edge.from)?.label}</Button>
      <div><Button type="link" onClick={()=>{onSelectDuty(edge.dutyId);onViewRun(edge.runId);}}>{edge.label}</Button><div>{trips.flatMap((v)=>v.segments.filter((s)=>s.runId===edge.runId&&s.serviceId===edge.dutyId).map((s)=><Tag key={`${v.id}/${s.unitSlots.join(',')}`} onClick={()=>setSelectedTrip(v.id)}>{v.id} · U{s.unitSlots.join(', U')}</Tag>))}</div></div>
      <Button className={selectedNode===edge.to?'active':''} onClick={()=>setSelectedNode(edge.to)} aria-label={`${t('trip.boundary')} ${edge.runId} ${t('status.terminal')}`}>{graph.nodes.find((n)=>n.id===edge.to)?.label}</Button>
    </div>)}</div>
    {selected&&<section className="trip-boundary"><Typography.Text strong>{t('trip.boundary')} · {selected.label} · {selected.kind}</Typography.Text><Typography.Paragraph>{t('trip.boundaryHelp')}</Typography.Paragraph><Space wrap>{graph.edges.filter((e)=>selected.outgoing.includes(e.id)||selected.incoming.includes(e.id)).map((e)=><Button key={e.id} disabled={!trip} onClick={()=>addLeg(e.runId)}>{t('trip.addRun')} · {e.runId}</Button>)}</Space></section>}
    <Typography.Title level={4}>{t('trip.title')}</Typography.Title><Typography.Paragraph type="secondary">{t('trip.help')}</Typography.Paragraph>
    <Flex gap={8} wrap><Select aria-label={t('trip.title')} placeholder={t('trip.select')} value={trip?.id} options={trips.map((v)=>({value:v.id,label:v.id}))} onChange={setSelectedTrip} style={{minWidth:200}}/><Input aria-label={t('trip.newId')} placeholder={t('trip.newId')} value={newId} onChange={(e)=>setNewId(e.target.value)} style={{maxWidth:230}}/><Button icon={<PlusOutlined/>} disabled={!newId.trim()||trips.some((v)=>v.id===newId.trim())} onClick={()=>{const id=newId.trim();patchTrips([...trips,{id,defaults:{},segments:[]}]);setSelectedTrip(id);setNewId('');}}>{t('trip.create')}</Button></Flex>
    {trip&&<section className="trip-editor" key={`${fieldScope}/${trip.id}`}><Flex justify="space-between"><Typography.Title level={5}>{trip.id}</Typography.Title><Button danger icon={<DeleteOutlined/>} onClick={()=>{patchTrips(trips.filter((v)=>v.id!==trip.id));setSelectedTrip('');}}>{t('common.delete')}</Button></Flex>
      <Typography.Paragraph strong>{t('trip.defaults')}</Typography.Paragraph><ProgramJsonInput label={t('trip.defaults')} fieldKey={`${fieldScope}/${trip.id}/defaults`} value={trip.defaults} onValidityChange={onValidityChange} onChange={(defaults)=>patchTrip({defaults})}/>
      {trip.segments.map((leg,index)=><Flex gap={8} wrap align="center" className="trip-leg" key={index}><Typography.Text>{index+1}. {leg.serviceId}</Typography.Text><Select aria-label={t('trip.run')} value={leg.runId} options={runOptions} style={{minWidth:230}} onChange={(runId)=>{const edge=graph.edges.find((e)=>e.runId===runId);if(edge)patchLeg(index,{runId,serviceId:edge.dutyId});}}/><ProgramLiteralInput type="UNIT_SLOTS" fieldKey={`${fieldScope}/${trip.id}/${index}/slots`} value={leg.unitSlots} onChange={(slots)=>patchLeg(index,{unitSlots:slots as number[]})}/><Button aria-label={t('common.moveUp')} disabled={!index} onClick={()=>{const segments=[...trip.segments];[segments[index-1],segments[index]]=[segments[index],segments[index-1]];patchTrip({segments});}}>↑</Button><Button aria-label={t('common.moveDown')} disabled={index===trip.segments.length-1} onClick={()=>{const segments=[...trip.segments];[segments[index+1],segments[index]]=[segments[index],segments[index+1]];patchTrip({segments});}}>↓</Button><Button danger aria-label={t('common.delete')} icon={<DeleteOutlined/>} onClick={()=>patchTrip({segments:trip.segments.filter((_,i)=>i!==index)})}/></Flex>)}
      <Select aria-label={t('trip.addRun')} value={null} placeholder={t('trip.addRun')} options={runOptions} onChange={addLeg} style={{minWidth:280}}/>
    </section>}
  </section>;
}
