import { useEffect, useState } from 'react';
import { api } from './api';
import { useI18n } from './i18n';
import type { WorldIssue, WorldIssueKind } from './types';

export const issueKinds:WorldIssueKind[]=['WORLD','TRAIN','PROTECTION_REGION','BLOCK','SIGNAL'];
export function filterWorldIssues(issues:WorldIssue[],kind:WorldIssueKind|''):WorldIssue[] {
  return kind?issues.filter((issue)=>issue.kind===kind):issues;
}
export function IssuesPage({world}:{world:string}) {
  const {t,locale}=useI18n();
  const [issues,setIssues]=useState<WorldIssue[]>([]);
  const [kind,setKind]=useState<WorldIssueKind|''>('');
  const [refresh,setRefresh]=useState(0);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  useEffect(()=>{
    if(!world)return;
    let cancelled=false,inFlight=false;
    const load=async()=>{
      if(inFlight)return;
      inFlight=true;
      try{const value=await api.issues(world);if(!cancelled){setIssues(value);setError('');}}
      catch(failure){if(!cancelled)setError(failure instanceof Error?failure.message:String(failure));}
      finally{inFlight=false;if(!cancelled)setLoading(false);}
    };
    void load();const timer=window.setInterval(()=>void load(),5_000);
    return()=>{cancelled=true;window.clearInterval(timer);};
  },[world,refresh]);
  const visible=filterWorldIssues(issues,kind);
  return <section className="page-scroll issues-page"><article className="card">
    <div className="issues-toolbar"><h2>{t('issues.title')}</h2><label>{t('issues.filter')}<select value={kind} onChange={(event)=>setKind(event.target.value as WorldIssueKind|'')}>
      <option value="">{t('issues.all')}</option>{issueKinds.map((value)=><option key={value} value={value}>{t(`issues.${value}`)}</option>)}
    </select></label><button onClick={()=>{setLoading(true);setRefresh((value)=>value+1);}} disabled={loading}>{t('issues.refresh')}</button></div>
    {error&&<p className="error" role="alert">{error}</p>}
    {loading?<p role="status">{t('issues.loading')}</p>:visible.length===0?<p className="empty">{t('issues.empty')}</p>:<table><thead><tr>
      <th>{t('issues.object')}</th><th>{t('issues.reason')}</th><th>{t('issues.firstSeen')}</th><th>{t('issues.lastSeen')}</th>
    </tr></thead><tbody>{visible.map((issue)=><tr key={`${issue.source}:${issue.kind}:${issue.objectId}:${issue.code}`}>
      <td>{t(`issues.${issue.kind}`)}<br/><code>{issue.objectId}</code></td><td>{issue.message}<br/><small>{issue.source} · {issue.code}</small>{issue.details&&<details><summary>{issue.code}</summary><pre>{issue.details}</pre></details>}</td>
      <td><time dateTime={issue.firstSeen}>{new Date(issue.firstSeen).toLocaleString(locale)}</time></td><td><time dateTime={issue.lastSeen}>{new Date(issue.lastSeen).toLocaleString(locale)}</time></td>
    </tr>)}</tbody></table>}
  </article></section>;
}
