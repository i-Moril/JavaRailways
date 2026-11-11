import {useEffect,useState} from 'react';
import {Button,Flex,Typography} from 'antd';
import {CommittedNumberInput} from './CommittedNumberInput';
import {editRunConnection,type AuthoringRunV1,type TimetableAuthoringDocumentV1} from './authoring';
import {useI18n} from './i18n';

export function RunConnectionEditor({document,from,to,onChange,fieldScope}:{document:TimetableAuthoringDocumentV1;from:AuthoringRunV1;to:AuthoringRunV1;onChange:(value:TimetableAuthoringDocumentV1)=>void;fieldScope:string}){
  const {t}=useI18n();
  const call=from.calls.at(-1),arrival=document.arrivalOverrides.find((o)=>o.runId===from.id&&o.callId===call?.id)?.tick??call?.arrivalTick??null,departure=to.calls[0]?.departureTick??null;
  const [draft,setDraft]=useState({arrival,departure}),[error,setError]=useState('');
  useEffect(()=>{setDraft({arrival,departure});setError('');},[fieldScope,from.id,to.id,arrival,departure]);
  return <section className="trip-boundary"><Typography.Text strong>{t('trip.connection')} · {from.id} → {to.id} · {call?.stationId}</Typography.Text><Typography.Paragraph type="secondary">{t('trip.connectionHelp')}</Typography.Paragraph><Flex wrap gap={8}>
    <label>{t('editor.arrivalTime')}<CommittedNumberInput aria-label={`${from.id} ${t('editor.arrivalTime')}`} fieldKey={`${fieldScope}/${from.id}/${to.id}/arrival`} value={draft.arrival} min={0} precision={0} onCommit={(arrival)=>setDraft((v)=>({...v,arrival}))}/></label>
    <label>{t('editor.departureTime')}<CommittedNumberInput aria-label={`${to.id} ${t('editor.departureTime')}`} fieldKey={`${fieldScope}/${from.id}/${to.id}/departure`} value={draft.departure} min={0} precision={0} onCommit={(departure)=>setDraft((v)=>({...v,departure}))}/></label>
    <Button disabled={draft.arrival===arrival&&draft.departure===departure} onClick={()=>{try{onChange(editRunConnection(document,from.id,to.id,draft.arrival,draft.departure));setError('');}catch(e){setError(e instanceof Error?e.message:String(e));}}}>{t('common.apply')}</Button>
  </Flex>{error&&<Typography.Text type="danger" role="alert">{error}</Typography.Text>}</section>;
}
