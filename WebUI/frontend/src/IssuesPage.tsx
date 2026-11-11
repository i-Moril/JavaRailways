import { useEffect,useRef,useState } from 'react';
import { Alert,Button,Flex,Select,Space,Spin,Table,Tag,Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { api } from './api';
import { useI18n } from './i18n';
import type { WorldIssue,WorldIssueKind } from './types';

export const issueKinds:WorldIssueKind[]=['WORLD','TRAIN','PROTECTION_REGION','BLOCK','SIGNAL'];
export function filterWorldIssues(issues:WorldIssue[],kind:WorldIssueKind|''):WorldIssue[] {
  return kind?issues.filter((issue)=>issue.kind===kind):issues;
}
export function pollWorldIssues(world:string,callbacks:{onIssues:(issues:WorldIssue[])=>void;onError:(error:string)=>void;onLoading:(loading:boolean)=>void},load=api.issues) {
  let cancelled=false,inFlight=false;
  const refresh=async()=>{
    if(cancelled||inFlight)return;
    inFlight=true;callbacks.onLoading(true);
    try{const value=await load(world);if(!cancelled){callbacks.onIssues(value);callbacks.onError('');}}
    catch(failure){if(!cancelled)callbacks.onError(failure instanceof Error?failure.message:String(failure));}
    finally{inFlight=false;if(!cancelled)callbacks.onLoading(false);}
  };
  void refresh();const timer=setInterval(()=>void refresh(),5_000);
  return {refresh,stop:()=>{cancelled=true;clearInterval(timer);}};
}
export function IssuesPage({world}:{world:string}) {
  const {t,locale}=useI18n();
  const [snapshot,setSnapshot]=useState<{world:string;issues:WorldIssue[]}>();
  const [kind,setKind]=useState<WorldIssueKind|''>('');
  const [loading,setLoading]=useState(Boolean(world));
  const [error,setError]=useState('');
  const refresh=useRef<(()=>Promise<void>)|undefined>(undefined);
  useEffect(()=>{
    if(!world)return;
    setError('');
    const poller=pollWorldIssues(world,{onIssues:(issues)=>setSnapshot({world,issues}),onError:setError,onLoading:setLoading});
    refresh.current=poller.refresh;
    return()=>{poller.stop();refresh.current=undefined;};
  },[world]);
  const issues=snapshot?.world===world?snapshot.issues:[];
  const visible=filterWorldIssues(issues,kind);
  const formatTime=(value:string)=><time dateTime={value}>{new Date(value).toLocaleString(locale)}</time>;
  const columns:TableColumnsType<WorldIssue>=[
    {title:t('issues.object'),key:'object',width:220,render:(_,issue)=><Space orientation="vertical" size={4}><Tag>{t(`issues.${issue.kind}`)}</Tag><Typography.Text code>{issue.objectId}</Typography.Text></Space>},
    {title:t('issues.reason'),key:'reason',render:(_,issue)=><Space orientation="vertical" size={4}><Typography.Text>{issue.message}</Typography.Text><Typography.Text type="secondary">{issue.source} · {issue.code}</Typography.Text></Space>},
    {title:t('issues.firstSeen'),dataIndex:'firstSeen',width:190,render:formatTime},
    {title:t('issues.lastSeen'),dataIndex:'lastSeen',width:190,render:formatTime},
  ];
  return <section className="module-page issues-module">
    <div className="page-heading"><div><Typography.Title level={3}>{t('issues.title')}</Typography.Title><Typography.Text type="secondary">{t('issues.description')}</Typography.Text></div><Flex gap={8} align="center" wrap><Select aria-label={t('issues.filter')} value={kind} onChange={setKind} style={{minWidth:180}} options={[{value:'',label:t('issues.all')},...issueKinds.map((value)=>({value,label:t(`issues.${value}`)}))]}/><Button icon={<ReloadOutlined/>} loading={loading} disabled={!world||loading} onClick={()=>void refresh.current?.()}>{t('issues.refresh')}</Button></Flex></div>
    {error&&<Alert type="error" title={error} showIcon/>}
    <section className="panel-surface issues-panel">
      {loading&&<Flex className="issues-loading" gap={8} align="center" role="status"><Spin size="small"/>{t('issues.loading')}</Flex>}
      <Table<WorldIssue> size="small" pagination={false} columns={columns} dataSource={visible} scroll={{x:800}}
        rowKey={(issue)=>JSON.stringify([issue.source,issue.kind,issue.objectId,issue.code])}
        locale={{emptyText:error?t('issues.unavailable'):loading?t('issues.loading'):t('issues.empty')}}
        expandable={{rowExpandable:(issue)=>Boolean(issue.details),expandedRowRender:(issue)=><div className="issue-diagnostics"><Typography.Text strong>{t('issues.details')}</Typography.Text><pre>{issue.details}</pre></div>}}/>
    </section>
  </section>;
}
