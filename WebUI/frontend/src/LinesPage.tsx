import { useEffect,useMemo,useRef,useState } from 'react';
import { Button,Flex,Input,Select,Space,Typography } from 'antd';
import { DeleteOutlined,DownOutlined,PlusOutlined,RightOutlined,SaveOutlined,UpOutlined } from '@ant-design/icons';
import { CommittedNumberInput } from './CommittedNumberInput';
import { api } from './api';
import { DocumentAutosave,type AutosaveState } from './documentAutosave';
import { useI18n } from './i18n';
import type { LineCollection,LineDirection,LineV1,WorldMetadata } from './types';

const blankLine=(index:number):LineV1=>({id:`line-${index}`,displayName:`Line ${index}`,upStationIds:[],downStationIds:[],color:'#1677ff'});
const cloneLine=(line:LineV1):LineV1=>({...line,upStationIds:[...line.upStationIds],downStationIds:[...line.downStationIds]});
const move=<T,>(items:T[],from:number,to:number):T[]=>{const copy=[...items];const [item]=copy.splice(from,1);copy.splice(to,0,item);return copy;};

type LinesPageProps={
  world:string;metadata:WorldMetadata;collection:LineCollection;onCollection:(value:LineCollection)=>void;
  run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
};
export function LinesPage(props:LinesPageProps){return <LineEditor key={props.world} {...props}/>;}
function LineEditor({world,metadata,collection,onCollection,run}:LinesPageProps) {
  const {t}=useI18n();
  const [selectedId,setSelectedId]=useState(collection.lines[0]?.id??'');
  const [creating,setCreating]=useState(false);
  const [lineCapValid,setLineCapValid]=useState(true);
  const [editingEpoch,setEditingEpoch]=useState(0);
  const [editing,setEditing]=useState<LineV1>(()=>collection.lines[0]?cloneLine(collection.lines[0]):blankLine(1));
  const [baseline,setBaseline]=useState(()=>JSON.stringify(editing));
  const [saveState,setSaveState]=useState<AutosaveState>({status:'saved'});
  const [switching,setSwitching]=useState(false);
  const collectionRef=useRef(collection);
  if(collection.revision>=collectionRef.current.revision)collectionRef.current=collection;
  const onCollectionRef=useRef(onCollection);onCollectionRef.current=onCollection;
  const editorRef=useRef({editing,baseline,selectedId,editingEpoch,valid:false});
  const valid=Boolean(lineCapValid&&editing.id.trim()&&editing.displayName.trim()&&editing.upStationIds.length>=2&&editing.downStationIds.length>=2);
  editorRef.current={editing,baseline,selectedId,editingEpoch,valid};
  const autosave=useRef<DocumentAutosave<LineV1>|undefined>(undefined);
  const commandPending=useRef(false);
  const active=useRef(true);
  const syncAutosave=()=>{
    const current=editorRef.current;
    autosave.current?.update({scope:`${world}/${current.editingEpoch}`,id:current.selectedId,value:current.editing,baseline:current.baseline,enabled:current.valid});
  };
  useEffect(()=>{
    active.current=true;
    const saver=new DocumentAutosave<LineV1>({
      write:async(_id,value)=>{
        const current=collectionRef.current;
        const result=await api.saveLine(world,value,current.revision);
        if(active.current){
          const lines=[...collectionRef.current.lines.filter((line)=>line.id!==result.line.id),result.line].sort((a,b)=>a.id.localeCompare(b.id));
          collectionRef.current={revision:result.revision,lines};
          onCollectionRef.current(collectionRef.current);
        }
        return {id:result.line.id};
      },
      onSaved:(id,value)=>{
        editorRef.current={...editorRef.current,baseline:JSON.stringify(value),selectedId:id};
        setBaseline(JSON.stringify(value));setSelectedId(id);setCreating(false);
      },
      onState:setSaveState,
    });
    autosave.current=saver;syncAutosave();
    return()=>{active.current=false;saver.dispose();};
  },[world]);
  useEffect(()=>{syncAutosave();},[editing,baseline,selectedId,editingEpoch,valid]);
  const stations=useMemo(()=>Array.from(new Map((metadata.stations??[]).map((station)=>[station.stationId,station])).values()),[metadata.stations]);
  const stationName=(stationId:string)=>stations.find((station)=>station.stationId===stationId)?.stationDisplayName||stationId;
  const replaceEditing=(line:LineV1,isNew=false)=>{
    const value=cloneLine(line),nextBaseline=JSON.stringify(value),epoch=editorRef.current.editingEpoch+1;
    editorRef.current={editing:value,baseline:nextBaseline,selectedId:isNew?'':line.id,editingEpoch:epoch,valid:false};
    setEditingEpoch(epoch);setLineCapValid(true);setCreating(isNew);setSelectedId(isNew?'':line.id);setEditing(value);setBaseline(nextBaseline);
    syncAutosave();
  };
  useEffect(()=>{
    if(creating||selectedId||!collection.lines[0])return;
    replaceEditing(collection.lines[0]);
  },[collection.lines,creating,selectedId]);
  const command=async(operation:(saver:DocumentAutosave<LineV1>)=>Promise<void>)=>{
    const saver=autosave.current;if(!saver||commandPending.current)return;
    commandPending.current=true;setSwitching(true);
    await saver.pause();
    try{if(active.current)await operation(saver);}
    finally{syncAutosave();saver.resume();commandPending.current=false;if(active.current)setSwitching(false);}
  };
  const flush=async(saver:DocumentAutosave<LineV1>)=>{
    syncAutosave();
    const current=editorRef.current;
    if(JSON.stringify(current.editing)!==current.baseline&&!current.valid)return false;
    return saver.flush();
  };
  const select=(line:LineV1)=>void command(async(saver)=>{if(await flush(saver)&&active.current)replaceEditing(collectionRef.current.lines.find((value)=>value.id===line.id)??line);});
  const create=()=>void command(async(saver)=>{if(await flush(saver)&&active.current)replaceEditing(blankLine(collectionRef.current.lines.length+1),true);});
  const updateStations=(direction:LineDirection,stationIds:string[])=>setEditing((current)=>({...current,[direction==='UP'?'upStationIds':'downStationIds']:stationIds}));
  const save=()=>command(async(saver)=>{await flush(saver);});
  const remove=()=>command(async()=>{
    const id=editorRef.current.selectedId;if(!id)return;
    const current=collectionRef.current;
    const result=await run(()=>api.deleteLine(world,id,current.revision));
    if(!result||!active.current)return;
    const lines=collectionRef.current.lines.filter((line)=>line.id!==id);
    collectionRef.current={revision:result.revision,lines};onCollectionRef.current(collectionRef.current);
    replaceEditing(lines[0]??blankLine(1),!lines[0]);
  });
  const saveLabel=saveState.status==='saving'?t('common.autosaving'):saveState.status==='failed'?t('common.autosaveFailed'):selectedId&&JSON.stringify(editing)===baseline?t('common.saved'):t('common.unsaved');
  return <section className="module-page lines-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('lines.title')}</Typography.Title><Typography.Text type="secondary">{t('lines.description')}</Typography.Text></div><Button type="primary" icon={<PlusOutlined/>} disabled={switching} onClick={create}>{t('lines.new')}</Button></div>
    <div className="line-admin-grid">
      <aside className="entity-list panel-surface">{collection.lines.map((line)=><button className={`entity-card ${selectedId===line.id?'active':''}`} key={line.id} disabled={switching} onClick={()=>select(line)}><i style={{background:line.color??'#1677ff'}}/><span><b>{line.displayName}</b><small>{line.upStationIds.length} {t('lines.upStations')} · {line.downStationIds.length} {t('lines.downStations')}</small></span><RightOutlined/></button>)}{!collection.lines.length&&!creating&&<p className="empty">{t('lines.empty')}</p>}</aside>
      <article className="line-detail panel-surface">
        <div className="detail-heading"><Space><span className="large-line-dot" style={{background:editing.color??'#1677ff'}}/><div><Typography.Title level={4}>{editing.displayName||t('lines.new')}</Typography.Title><Typography.Text type="secondary">{editing.id||'—'}</Typography.Text></div></Space><Space><Typography.Text role="status" type={saveState.status==='failed'?'danger':'secondary'} title={saveState.error}>{saveLabel}{saveState.error?`: ${saveState.error}`:''}</Typography.Text><Button danger icon={<DeleteOutlined/>} disabled={!selectedId||switching} onClick={()=>void remove()}>{t('common.delete')}</Button><Button type="primary" icon={<SaveOutlined/>} disabled={!valid||switching} onClick={()=>void save()}>{t(saveState.status==='failed'?'common.retrySave':'common.save')}</Button></Space></div>
        <div className="line-properties"><label>{t('ui.lineCap')}<CommittedNumberInput fieldKey={`${world}/${selectedId}/${editingEpoch}/line-cap`} aria-label={t('ui.lineCap')} onValidityChange={setLineCapValid} min={Number.MIN_VALUE} value={editing.maximumSpeedMetersPerSecond==null?null:editing.maximumSpeedMetersPerSecond*3.6} onCommit={(kmh)=>{const speed=kmh==null?null:kmh/3.6;if(speed!=null&&(!Number.isFinite(speed)||speed<=0))throw new Error(t('ui.lineCapInvalid'));setEditing((current)=>({...current,maximumSpeedMetersPerSecond:speed}));}} addonAfter="km/h"/></label><label>{t('lines.name')}<Input value={editing.displayName} onChange={(event)=>setEditing({...editing,displayName:event.target.value})}/></label><label>{t('lines.id')}<Input value={editing.id} disabled={Boolean(selectedId)||saveState.status==='saving'} onChange={(event)=>setEditing({...editing,id:event.target.value})}/></label><label>{t('lines.color')}<Input type="color" value={editing.color??'#1677ff'} onChange={(event)=>setEditing({...editing,color:event.target.value})}/></label></div>
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
