import { useEffect,useMemo,useState } from 'react';
import { Button,Flex,Input,Select,Space,Typography } from 'antd';
import { DeleteOutlined,DownOutlined,PlusOutlined,RightOutlined,SaveOutlined,UpOutlined } from '@ant-design/icons';
import { api } from './api';
import { useI18n } from './i18n';
import type { LineCollection,LineDirection,LineV1,WorldMetadata } from './types';

const blankLine=(index:number):LineV1=>({id:`line-${index}`,displayName:`Line ${index}`,upStationIds:[],downStationIds:[],color:'#1677ff'});
const cloneLine=(line:LineV1):LineV1=>({...line,upStationIds:[...line.upStationIds],downStationIds:[...line.downStationIds]});
const move=<T,>(items:T[],from:number,to:number):T[]=>{const copy=[...items];const [item]=copy.splice(from,1);copy.splice(to,0,item);return copy;};

export function LinesPage({world,metadata,collection,onCollection,run}:{
  world:string;metadata:WorldMetadata;collection:LineCollection;onCollection:(value:LineCollection)=>void;
  run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const {t}=useI18n();
  const [selectedId,setSelectedId]=useState(collection.lines[0]?.id??'');
  const [creating,setCreating]=useState(false);
  const [editing,setEditing]=useState<LineV1>(()=>collection.lines[0]?cloneLine(collection.lines[0]):blankLine(1));
  const stations=useMemo(()=>Array.from(new Map((metadata.stations??[]).map((station)=>[station.stationId,station])).values()),[metadata.stations]);
  const stationName=(stationId:string)=>stations.find((station)=>station.stationId===stationId)?.stationDisplayName||stationId;
  useEffect(()=>{
    if(creating||selectedId||!collection.lines[0])return;
    setSelectedId(collection.lines[0].id);setEditing(cloneLine(collection.lines[0]));
  },[collection.lines,creating,selectedId]);
  const select=(line:LineV1)=>{setCreating(false);setSelectedId(line.id);setEditing(cloneLine(line));};
  const create=()=>{setCreating(true);setSelectedId('');setEditing(blankLine(collection.lines.length+1));};
  const updateStations=(direction:LineDirection,stationIds:string[])=>setEditing((current)=>({...current,[direction==='UP'?'upStationIds':'downStationIds']:stationIds}));
  const save=async()=>{
    const result=await run(()=>api.saveLine(world,editing,collection.revision));
    if(!result)return;
    const lines=[...collection.lines.filter((line)=>line.id!==result.line.id),result.line].sort((a,b)=>a.id.localeCompare(b.id));
    onCollection({revision:result.revision,lines});setSelectedId(result.line.id);setEditing(cloneLine(result.line));setCreating(false);
  };
  const remove=async()=>{
    if(!selectedId)return;
    const result=await run(()=>api.deleteLine(world,selectedId,collection.revision));
    if(!result)return;
    const lines=collection.lines.filter((line)=>line.id!==selectedId);
    onCollection({revision:result.revision,lines});
    if(lines[0]){setSelectedId(lines[0].id);setEditing(cloneLine(lines[0]));}else create();
  };
  const valid=editing.id.trim()&&editing.displayName.trim()&&editing.upStationIds.length>=2&&editing.downStationIds.length>=2;
  return <section className="module-page lines-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('lines.title')}</Typography.Title><Typography.Text type="secondary">{t('lines.description')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} onClick={create}>{t('lines.new')}</Button></div>
    <div className="line-admin-grid">
      <aside className="entity-list panel-surface">{collection.lines.map((line)=><button className={`entity-card ${selectedId===line.id?'active':''}`} key={line.id} onClick={()=>select(line)}><i style={{background:line.color??'#1677ff'}}/><span><b>{line.displayName}</b><small>{line.upStationIds.length} {t('lines.upStations')} · {line.downStationIds.length} {t('lines.downStations')}</small></span><RightOutlined/></button>)}{!collection.lines.length&&!creating&&<p className="empty">{t('lines.empty')}</p>}</aside>
      <article className="line-detail panel-surface">
        <div className="detail-heading"><Space><span className="large-line-dot" style={{background:editing.color??'#1677ff'}}/><div><Typography.Title level={4}>{editing.displayName||t('lines.new')}</Typography.Title><Typography.Text type="secondary">{editing.id||'—'}</Typography.Text></div></Space><Space><Button danger icon={<DeleteOutlined/>} disabled={!selectedId} onClick={()=>void remove()}>{t('common.delete')}</Button><Button type="primary" icon={<SaveOutlined/>} disabled={!valid} onClick={()=>void save()}>{t('common.save')}</Button></Space></div>
        <div className="line-properties"><label>{t('lines.name')}<Input value={editing.displayName} onChange={(event)=>setEditing({...editing,displayName:event.target.value})}/></label><label>{t('lines.id')}<Input value={editing.id} disabled={Boolean(selectedId)} onChange={(event)=>setEditing({...editing,id:event.target.value})}/></label><label>{t('lines.color')}<Input type="color" value={editing.color??'#1677ff'} onChange={(event)=>setEditing({...editing,color:event.target.value})}/></label></div>
        <div className="direction-sequences">{(['UP','DOWN'] as const).map((direction)=>{
          const values=direction==='UP'?editing.upStationIds:editing.downStationIds;
          return <section className="direction-sequence" key={direction}><Flex className="sequence-heading" align="center" justify="space-between"><div><Typography.Title level={5}>{t(`direction.${direction}`)}</Typography.Title><Typography.Text type="secondary">{values.length} {t('lines.stations')}</Typography.Text></div><Select value={undefined} placeholder={`+ ${t('lines.addStation')}`} onChange={(stationId)=>{if(stationId)updateStations(direction,[...values,stationId]);}} options={stations.filter((station)=>!values.includes(station.stationId)).map((station)=>({value:station.stationId,label:station.stationDisplayName||station.stationId}))}/></Flex>
            <div className="station-sequence">{values.map((stationId,index)=><div className="sequence-row" key={stationId}><span className="sequence-index">{index+1}</span><span className="sequence-track" style={{background:editing.color??'#1677ff'}}/><div><b>{stationName(stationId)}</b><small>{stationId}</small></div><Space size={0}><Button type="text" size="small" icon={<UpOutlined/>} disabled={index===0} onClick={()=>updateStations(direction,move(values,index,index-1))}/><Button type="text" size="small" icon={<DownOutlined/>} disabled={index===values.length-1} onClick={()=>updateStations(direction,move(values,index,index+1))}/><Button type="text" danger size="small" icon={<DeleteOutlined/>} onClick={()=>updateStations(direction,values.filter((id)=>id!==stationId))}/></Space></div>)}</div>
          </section>;
        })}</div>
      </article>
    </div>
  </section>;
}
