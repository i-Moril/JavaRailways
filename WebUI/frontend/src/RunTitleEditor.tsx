import { useEffect,useRef,useState } from 'react';
import { Button,Input,Typography } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useI18n } from './i18n';
interface Props {fieldKey:string;value:string;disabled?:boolean;onCommit:(id:string)=>void}
export function RunTitleEditor(props:Props) {return <RunTitleDraft key={props.fieldKey} {...props}/>;}
function RunTitleDraft({value,disabled,onCommit}:Props) {
  const {t}=useI18n();
  const [editing,setEditing]=useState(false),[draft,setDraft]=useState(value),[error,setError]=useState('');
  const raw=useRef(value),active=useRef(true),open=useRef(false),lastAttempt=useRef<string|undefined>(undefined);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  const cancel=()=>{open.current=false;raw.current=value;setDraft(value);setError('');setEditing(false);};
  const commit=()=>{
    if(!active.current||!open.current||lastAttempt.current===raw.current)return;
    lastAttempt.current=raw.current;
    try{if(raw.current!==value)onCommit(raw.current);open.current=false;setEditing(false);setError('');}
    catch(failure){setError(failure instanceof Error?failure.message:String(failure));}
  };
  if(!editing)return <Button type="text" className="run-title-edit" aria-label={t('ui.renameRun')} disabled={disabled} icon={<EditOutlined/>} onClick={()=>{raw.current=value;lastAttempt.current=undefined;setDraft(value);setError('');open.current=true;setEditing(true);}}>{value}</Button>;
  return <div className="run-title-draft"><Input autoFocus aria-label={t('ui.runId')} value={draft} status={error?'error':undefined}
    onChange={(event)=>{raw.current=event.target.value;lastAttempt.current=undefined;setDraft(event.target.value);setError('');}}
    onBlur={commit} onPressEnter={(event)=>{event.preventDefault();event.stopPropagation();commit();}}
    onKeyDown={(event)=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();cancel();}}}/>
    {error&&<Typography.Text type="danger" role="alert">{error}</Typography.Text>}
  </div>;
}
