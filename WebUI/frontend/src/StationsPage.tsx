import { localizedNameLabel } from './metadata';
import { useEffect, useReducer, useRef, useState } from 'react';
import { Alert, Button, Card, Empty, Flex, Input, Select, Spin, Typography } from 'antd';
import { api } from './api';
import { ApiError } from './apiErrors';
import { frontendDemoEnabled } from './frontendDev';
import { useI18n } from './i18n';
import { initialMetadataEditor, metadataEditorReducer, parseStationMetadata } from './stationMetadata';
import type { StationDescriptor } from './types';

export function StationMetadataEditor({world,station,onSaved}:{world:string;station:StationDescriptor;onSaved?:(station:StationDescriptor)=>void}) {
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
    <Input.TextArea aria-label={t('stations.json')} spellCheck={false} autoSize={{minRows:16,maxRows:32}} value={draft}
      onChange={(event)=>dispatch({type:'edit',draft:event.target.value})} style={{fontFamily:'monospace'}}/>
    {parsed.error&&<Alert type="error" showIcon title={t(`stations.${parsed.error}`)} description={parsed.detail}/>}
    {!!error&&<Alert type="error" showIcon title={error instanceof ApiError?error.localized(locale):error instanceof Error?error.message:String(error)}/>}
    {incoming&&<Alert type="warning" showIcon title={t('stations.incoming')} description={<>
      <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{JSON.stringify(incoming,null,2)}</pre>
      <Button disabled={saving} onClick={()=>dispatch({type:'rebase'})}>{t('stations.rebase')}</Button>
    </>}/>}
    {saved&&<Alert type="success" showIcon title={t('stations.saved')}/>}
    <Flex gap={8}>
      <Button type="primary" loading={saving} disabled={!!parsed.error} onClick={()=>void save()}>{t('stations.save')}</Button>
      <Button disabled={!!parsed.error} onClick={()=>{if(!parsed.error)dispatch({type:'edit',draft:JSON.stringify(parsed.value,null,2)});}}>{t('stations.format')}</Button>
    </Flex>
  </Flex>;
}

export function StationsPage({world,onSaved}:{world:string;onSaved?:(station:StationDescriptor)=>void}) {
  return <WorldStationsPage key={world} world={world} onSaved={onSaved}/>;
}

function WorldStationsPage({world,onSaved}:{world:string;onSaved?:(station:StationDescriptor)=>void}) {
  const {t,locale}=useI18n();
  const [stations,setStations]=useState<StationDescriptor[]>();
  const [selected,setSelected]=useState<string>();
  const [error,setError]=useState<unknown>();
  const [refresh,setRefresh]=useState(0);
  const [loading,setLoading]=useState(false);
  useEffect(()=>{
    if(!world||frontendDemoEnabled)return;
    let cancelled=false;
    setLoading(true);setError(undefined);
    void api.stations(world).then(({stations:values})=>{
      if(cancelled)return;
      setStations(values);
      setSelected((current)=>values.some((station)=>station.id===current)?current:values[0]?.id);
    }).catch((failure)=>{if(!cancelled)setError(failure);})
      .finally(()=>{if(!cancelled)setLoading(false);});
    return()=>{cancelled=true;};
  },[world,refresh]);
  const station=stations?.find((value)=>value.id===selected);
  return <section className="page-scroll"><Card title={t('stations.title')} extra={<Button disabled={loading||frontendDemoEnabled||!world} onClick={()=>setRefresh((value)=>value+1)}>{t('stations.refresh')}</Button>}>
    <Flex vertical gap={16}>
      {frontendDemoEnabled?<Alert type="info" title={t('stations.demoUnavailable')}/>:<>
        {!!error&&<Alert type="error" showIcon title={error instanceof ApiError?error.localized(locale):error instanceof Error?error.message:String(error)}/>}
        {loading&&<div role="status"><Spin size="small"/> {t('stations.loading')}</div>}
        {stations?.length===0&&<Empty description={t('stations.empty')}/>}
        <Select aria-label={t('stations.station')} placeholder={t('stations.station')} value={selected} showSearch optionFilterProp="label"
          options={stations?.map((value)=>{
            const label=localizedNameLabel(value.id,value.metadata.name,locale);
            return {value:value.id,label:label===value.id?value.id:`${label} (${value.id})`};
          })} onChange={setSelected}/>
        {station&&<StationMetadataEditor key={JSON.stringify([world,station.id])} world={world} station={station}
          onSaved={(updated)=>{
            setStations((current)=>current?.map((item)=>item.id===updated.id?updated:item));
            onSaved?.(updated);
          }}/>}
      </>}
    </Flex>
  </Card></section>;
}
