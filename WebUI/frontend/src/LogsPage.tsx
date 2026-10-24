import { useEffect, useState } from 'react';
import { Alert, Button, Card, Empty, Flex, Input, Select, Spin, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { api } from './api';
import { frontendDemoEnabled } from './frontendDev';
import { useI18n } from './i18n';
import type { ServerLogEntry, ServerLogPage, ServerLogQuery, ServerLogSource } from './types';

type ReadRequest=ServerLogQuery & {history:(string|undefined)[]};
type ReadResult={request:ReadRequest;page?:ServerLogPage;error?:string};
const failureText=(failure:unknown)=>failure instanceof Error?failure.message:String(failure);

export function LogRecord({entry}:{entry:ServerLogEntry}) {
  const {t}=useI18n();
  const summary=entry.text.split('\n',1)[0];
  return <article className="server-log-record">
    <Flex gap={8} wrap align="center">
      {entry.level&&<Tag color={entry.level==='SEVERE'?'red':entry.level==='WARNING'?'gold':undefined}>{entry.level}</Tag>}
      {entry.module&&<Typography.Text strong>{entry.module}</Typography.Text>}
      {entry.time&&<Typography.Text type="secondary"><time>{entry.time}</time></Typography.Text>}
    </Flex>
    {entry.partial&&<Alert type="warning" showIcon title={t('logs.partial')}/>}
    <details><summary><span>{t('logs.details')}</span><code>{summary}</code></summary><pre>{entry.text}</pre></details>
  </article>;
}

export function LogsPage() {
  const {t}=useI18n();
  const [sources,setSources]=useState<ServerLogSource[]>();
  const [sourceError,setSourceError]=useState('');
  const [sourceWarning,setSourceWarning]=useState('');
  const [refresh,setRefresh]=useState(0);
  const [search,setSearch]=useState('');
  const [request,setRequest]=useState<ReadRequest>();
  const [result,setResult]=useState<ReadResult>();
  useEffect(()=>{
    if(frontendDemoEnabled)return;
    let cancelled=false;
    setSourceError('');
    setSourceWarning('');
    void api.logSources().then(({sources:values,warning})=>{
      if(cancelled)return;
      setSources(values);
      setSourceWarning(warning??'');
      setRequest((current)=>current&&values.some((value)=>value.id===current.source)?current:(values[0]?{source:values[0].id,history:[]}:undefined));
    }).catch((failure)=>{if(!cancelled)setSourceError(failureText(failure));});
    return()=>{cancelled=true;};
  },[refresh]);
  useEffect(()=>{
    if(!request||frontendDemoEnabled)return;
    let cancelled=false;
    void api.logs(request).then((page)=>{if(!cancelled)setResult({request,page});})
      .catch((failure)=>{if(!cancelled)setResult({request,error:failureText(failure)});});
    return()=>{cancelled=true;};
  },[request]);
  // Hide the previous query immediately, including the render before effect cleanup.
  const current=result?.request===request?result:undefined;
  const page=current?.page;
  const loading=!!request&&!current;
  const source=sources?.find((value)=>value.id===request?.source);
  const reload=()=>{setRefresh((value)=>value+1);setRequest((value)=>value?{source:value.source,query:value.query,history:[]}:undefined);};
  return <section className="page-scroll server-logs-page"><Card title={t('logs.title')} extra={<Button icon={<ReloadOutlined/>} onClick={reload} disabled={frontendDemoEnabled||loading}>{t('logs.refresh')}</Button>}>
    <Flex vertical gap={16}>
      <Typography.Paragraph type="secondary">{t('logs.retention')}</Typography.Paragraph>
      {frontendDemoEnabled?<Alert type="info" showIcon title={t('logs.demoUnavailable')}/>:<>
        <Flex wrap gap={12} className="server-logs-filters">
          <Select aria-label={t('logs.source')} placeholder={t('logs.source')} value={request?.source} loading={!sources&&!sourceError}
            options={sources?.map((value)=>({value:value.id,label:value.label}))}
            onChange={(value)=>{setSearch('');setRequest({source:value,history:[]});}}/>
          <Input.Search aria-label={t('logs.search')} placeholder={t('logs.search')} value={search} maxLength={200} allowClear enterButton
            disabled={!request} onChange={(event)=>setSearch(event.target.value)} onSearch={(query)=>setRequest((value)=>value?{source:value.source,query:query.trim(),history:[]}:undefined)}/>
        </Flex>
        {sourceWarning&&<Alert type="warning" showIcon title={t('logs.sourceWarning')} description={sourceWarning}/>}
        {sourceError&&<Alert type="error" showIcon title={t('logs.unavailable')} description={sourceError}/>}
        {current?.error&&<Alert type="error" showIcon title={t('logs.unavailable')} description={current.error}/>}
        {(!sources&&!sourceError||loading)&&<div role="status"><Spin size="small"/> {t('logs.loading')}</div>}
        {sources?.length===0&&<Empty description={t('logs.noSources')}/>}
        {page&&<>
          {page.reset?<Alert type="warning" showIcon title={t('logs.reset')}/>:<>
            {page.entries.length===0&&<Empty description={source?.available===false?t('logs.missing'):t('logs.empty')}/>}
            {page.entries.map((entry,index)=><LogRecord key={index} entry={entry}/>)}
          </>}
          <Flex gap={12} wrap align="center" justify="space-between">
            <Typography.Text type="secondary">{t('logs.page')} {(request?.history.length??0)+1} · {t('logs.scanned')} {page.scannedBytes.toLocaleString()} B</Typography.Text>
            <Flex gap={8}>
              <Button disabled={!request?.history.length} onClick={()=>setRequest((value)=>value&&value.history.length?{...value,cursor:value.history[value.history.length-1],history:value.history.slice(0,-1)}:value)}>{t('logs.previous')}</Button>
              <Button disabled={!page.nextCursor||page.reset} onClick={()=>setRequest((value)=>value&&page.nextCursor?{...value,cursor:page.nextCursor,history:[...value.history,value.cursor]}:value)}>{t('logs.next')}</Button>
            </Flex>
          </Flex>
        </>}
      </>}
    </Flex>
  </Card></section>;
}
