import {useEffect,useRef,useState,type CSSProperties} from 'react';
import {AutoComplete,Select} from 'antd';

export interface IdOption {value:string;label:string}
export const matchesIdOption=(text:string,option?:IdOption)=>!!option&&
  `${option.value}\n${option.label}`.toLowerCase().includes(text.toLowerCase());
interface Common {fieldKey:string;options:IdOption[];disabled?:boolean;placeholder?:string;'aria-label'?:string;style?:CSSProperties;className?:string}
interface SingleProps extends Common {
  value?:string;onCommit:(value:string)=>void;allowClear?:boolean;
  onEditingChange?:(editing:boolean)=>void;
}
// Uncommitted text belongs to this particular field, never to another world/document/block.
export function EditableIdSelect(props:SingleProps){return <SingleId key={props.fieldKey} {...props}/>;}
function SingleId({fieldKey:_,value,onCommit,onEditingChange,...props}:SingleProps){
  const [draft,setDraft]=useState(value??'');
  const raw=useRef(value??''),last=useRef(value??''),active=useRef(true),pending=useRef(false);
  const editing=useRef(onEditingChange);editing.current=onEditingChange;
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  useEffect(()=>{raw.current=value??'';last.current=raw.current;setDraft(raw.current);if(pending.current){pending.current=false;editing.current?.(false);}},[value]);
  const reportEditing=(next:boolean)=>{if(pending.current!==next){pending.current=next;editing.current?.(next);}};
  const update=(next:string)=>{raw.current=next;setDraft(next);reportEditing(next!==(value??''));};
  const commit=(next=raw.current)=>{
    if(!active.current)return;
    raw.current=next;setDraft(next);reportEditing(false);
    if(next===last.current)return;
    last.current=next;if(next!==(value??''))onCommit(next);
  };
  return <AutoComplete<string,IdOption> {...props} style={{width:'100%',...props.style}} value={draft}
    defaultActiveFirstOption={false} filterOption={matchesIdOption} onChange={update}
    onSelect={commit} onClear={()=>commit('')} onBlur={()=>commit()}
    onKeyDown={event=>{
      if(event.nativeEvent.isComposing)return;
      if(event.key==='Enter'){event.preventDefault();event.stopPropagation();commit();}
      else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();raw.current=value??'';last.current=raw.current;setDraft(raw.current);reportEditing(false);}
    }}/>;
}
interface MultipleProps extends Common {value:string[];onChange:(value:string[])=>void}
export function EditableIdList(props:MultipleProps){return <IdList key={props.fieldKey} {...props}/>;}
function IdList({fieldKey:_,onChange,...props}:MultipleProps){
  const active=useRef(true),[search,setSearch]=useState('');
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  return <Select<string[],IdOption> {...props} mode="tags" style={{width:'100%',...props.style}}
    showSearch={{filterOption:matchesIdOption,onSearch:setSearch,searchValue:search}} onChange={values=>{if(active.current){setSearch('');onChange(values);}}} onSelect={()=>setSearch('')}
    onKeyDown={event=>{if(event.key==='Escape'){event.preventDefault();setSearch('');}if(event.key==='Enter'||event.key==='Escape')event.stopPropagation();}}/>;
}
