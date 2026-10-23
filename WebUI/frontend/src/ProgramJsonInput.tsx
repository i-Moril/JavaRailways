import {useEffect,useRef,useState} from 'react';
import {Input,Typography} from 'antd';
import {programJsonObjectError} from './stationPrograms';
import {useI18n} from './i18n';
import type {ProgramJsonObject} from './types';

export function parseProgramJsonDraft(text:string):{value?:ProgramJsonObject;error?:string}{
  try{const value:unknown=JSON.parse(text);const error=programJsonObjectError(value);return error?{error}:{value:value as ProgramJsonObject};}
  catch{return {error:'Invalid JSON object'};}
}
export function ProgramJsonInput({value,onChange,fieldKey,onValidityChange,label}:{label?:string;value:ProgramJsonObject|undefined;onChange:(value:ProgramJsonObject)=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void}){
  const {t}=useI18n();
  const currentField=useRef(fieldKey);currentField.current=fieldKey;const mounted=useRef(true);
  const raw=useRef(JSON.stringify(value??{},null,2));const lastCommitted=useRef<string|undefined>(JSON.stringify(value));
  const [draft,setDraft]=useState(()=>JSON.stringify(value??{},null,2));const [error,setError]=useState('');
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;onValidityChange?.(true);};},[fieldKey]);
  useEffect(()=>{raw.current=JSON.stringify(value??{},null,2);lastCommitted.current=JSON.stringify(value);setDraft(raw.current);setError('');onValidityChange?.(true);},[value,fieldKey]);
  const commit=()=>{if(!mounted.current||currentField.current!==fieldKey)return;const result=parseProgramJsonDraft(raw.current);setError(result.error??'');onValidityChange?.(!result.error);if(result.value&&JSON.stringify(result.value)!==lastCommitted.current){onChange(result.value);lastCommitted.current=JSON.stringify(result.value);}};
  return <div className="program-json-input"><Input.TextArea aria-label={label??t('programs.jsonParameters')} rows={5} value={draft} status={error?'error':undefined} onChange={(event)=>{const text=event.target.value;raw.current=text;setDraft(text);const result=parseProgramJsonDraft(text);setError(result.error??'');onValidityChange?.(!result.error);}} onBlur={commit} onKeyDown={(event)=>{if(event.key==='Escape'){event.preventDefault();raw.current=JSON.stringify(value??{},null,2);lastCommitted.current=JSON.stringify(value);setDraft(raw.current);setError('');onValidityChange?.(true);}else if(event.key==='Enter'&&(event.ctrlKey||event.metaKey)){event.preventDefault();commit();}}}/>{error&&<Typography.Text type="danger">{t('programs.invalidJson')} · {error}</Typography.Text>}<Typography.Paragraph type="secondary">{t('programs.jsonHint')}</Typography.Paragraph></div>;
}
