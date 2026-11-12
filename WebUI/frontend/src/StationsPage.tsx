import { localizedNameLabel } from './metadata';
import { useEffect, useReducer, useRef, useState } from 'react';
import { Alert, Button, Empty, Flex, Modal, Spin, Typography } from 'antd';
import { MetadataJsonEditor } from './MetadataJsonEditor';
import { api } from './api';
import { ApiError } from './apiErrors';
import { frontendDemoEnabled } from './frontendDev';
import { useI18n } from './i18n';
import { initialMetadataEditor, metadataEditorReducer, parseStationMetadata } from './stationMetadata';
import type { LineV1, StationDescriptor } from './types';

export function StationMetadataEditor({world,station,lines,onSaved}:{world:string;station:StationDescriptor;lines?:LineV1[];onSaved?:(station:StationDescriptor)=>void}) {
  const {t,locale}=useI18n();
  const [state,dispatch]=useReducer(metadataEditorReducer,station.metadata,initialMetadataEditor);
  const {baseline,draft,saving,error,saved,incoming}=state;
  const mounted=useRef(true);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;};},[]);
  // A refresh only offers the incoming value. Advancing the baseline needs explicit review.
  useEffect(()=>{dispatch({type:'refresh',metadata:station.metadata});},[station]);
  const parsed=parseStationMetadata(draft);
  const save=async()=>{
    if(parsed.error||saving)return;
    const submitted=draft;
    dispatch({type:'saving'});
    try {
      const updated=await api.saveStationMetadata(world,station.id,parsed.value,baseline);
      if(!mounted.current)return;
      dispatch({type:'saved',metadata:updated.metadata,submitted});
      onSaved?.(updated);
    } catch(failure) {if(mounted.current)dispatch({type:'failed',error:failure});}
  };
  return <Flex vertical gap={12}>
    <Typography.Paragraph type="secondary">{t('stations.help')}</Typography.Paragraph>
    <MetadataJsonEditor scope="station" lines={lines} fieldKey={JSON.stringify([world,station.id])} label={t('stations.json')} draft={draft}
      onChange={(value)=>dispatch({type:'edit',draft:value})}/>
    {!!error&&<Alert type="error" showIcon title={error instanceof ApiError?error.localized(locale):error instanceof Error?error.message:String(error)}/>}
    {incoming&&<Alert type="warning" showIcon title={t('stations.incoming')} description={<>
      <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{JSON.stringify(incoming,null,2)}</pre>
      <Button disabled={saving} onClick={()=>dispatch({type:'rebase'})}>{t('stations.rebase')}</Button>
    </>}/>}
    {saved&&<Alert type="success" showIcon title={t('stations.saved')}/>}
    <Flex gap={8}>
      <Button type="primary" loading={saving} disabled={!!parsed.error} onClick={()=>void save()}>{t('stations.save')}</Button>
    </Flex>
  </Flex>;
}

type StationMetadataModalProps={
  world:string;stationId?:string;lines:LineV1[];onClose:()=>void;onSaved?:(station:StationDescriptor)=>void;
};

export function StationMetadataModal(props:StationMetadataModalProps) {
  return <WorldStationMetadataModal key={props.world} {...props}/>;
}

function WorldStationMetadataModal({world,stationId,lines,onClose,onSaved}:StationMetadataModalProps) {
  const {t,locale}=useI18n();
  // Keep each visited editor mounted so closing or opening another station retains its draft and baseline.
  const [stations,setStations]=useState<StationDescriptor[]>([]);
  const [load,setLoad]=useState<{stationId:string;loading:boolean;missing?:boolean;error?:unknown}>();
  const [refresh,setRefresh]=useState(0);
  useEffect(()=>{
    if(!world||!stationId||frontendDemoEnabled)return;
    let cancelled=false;
    setLoad({stationId,loading:true});
    void api.stations(world).then(({stations:values})=>{
      if(cancelled)return;
      const station=values.find((value)=>value.id===stationId);
      if(station)setStations((current)=>[...current.filter((value)=>value.id!==stationId),station]);
      setLoad({stationId,loading:false,missing:!station});
    }).catch((failure)=>{if(!cancelled)setLoad({stationId,loading:false,error:failure});});
    return()=>{cancelled=true;};
  },[world,stationId,refresh]);
  const ids=stations.map((station)=>station.id);
  if(stationId&&!ids.includes(stationId))ids.push(stationId);
  return <>{ids.map((id)=>{
    const station=stations.find((value)=>value.id===id);
    const loading=id===stationId&&(load?.stationId!==id||load.loading);
    const error=load?.stationId===id?load.error:undefined;
    return <Modal key={id} open={stationId===id} forceRender width={920} onCancel={onClose}
      title={<>{t('stations.title')} · {localizedNameLabel(id,station?.metadata.name,locale)} <Typography.Text type="secondary">({id})</Typography.Text></>}
      footer={<Flex gap={8} justify="flex-end">
        <Button disabled={loading||frontendDemoEnabled||!world} onClick={()=>setRefresh((value)=>value+1)}>{t('stations.refresh')}</Button>
        <Button onClick={onClose}>{t('metadata.close')}</Button>
      </Flex>}>
      <Flex vertical gap={16}>
        {frontendDemoEnabled?<Alert type="info" title={t('stations.demoUnavailable')}/>:<>
          {!!error&&<Alert type="error" showIcon title={error instanceof ApiError?error.localized(locale):error instanceof Error?error.message:String(error)}/>}
          {loading&&<div role="status"><Spin size="small"/> {t('stations.loading')}</div>}
          {load?.stationId===id&&load.missing&&<Empty description={t('stations.missing')}/>}
          {station&&<StationMetadataEditor world={world} station={station} lines={lines} onSaved={(updated)=>{
            setStations((current)=>current.map((item)=>item.id===updated.id?updated:item));
            onSaved?.(updated);
          }}/>}
        </>}
      </Flex>
    </Modal>;
  })}</>;
}
