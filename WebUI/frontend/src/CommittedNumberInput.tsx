import { useEffect,useRef,useState } from 'react';
import { InputNumber,type InputNumberProps } from 'antd';
import { useI18n } from './i18n';

type Props=Omit<InputNumberProps<number|string>,'value'|'defaultValue'|'onChange'|'onBlur'|'onPressEnter'|'stringMode'|'changeOnBlur'|'min'|'max'> & {
  min?:number; max?:number; fieldKey:string; value?:number|null; onCommit:(value:number|null)=>void; allowEmpty?:boolean; onValidityChange?:(valid:boolean)=>void;
};
export function parseNumberDraft(raw:string,min?:number,max?:number,precision?:number):number|null {
  if(!raw.trim())return null;
  const value=Number(raw);
  if(!Number.isFinite(value)||(min!=null&&value<min)||(max!=null&&value>max)||(precision===0&&!Number.isSafeInteger(value)))throw new Error('Invalid number');
  return value;
}
// A new field identity unmounts the previous draft, including pending blur events.
export function CommittedNumberInput(props:Props) { return <NumberDraft key={props.fieldKey} {...props}/>; }
function NumberDraft({fieldKey:_,value,onCommit,allowEmpty=true,onValidityChange,...props}:Props) {
  const {t}=useI18n();
  const [draft,setDraft]=useState<string>(()=>value==null?'':String(value));
  const [invalid,setInvalid]=useState(false);
  const raw=useRef(draft),lastCommit=useRef(draft),active=useRef(true);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  useEffect(()=>{const next=value==null?'':String(value);setDraft(next);raw.current=next;lastCommit.current=next;setInvalid(false);},[value]);
  const commit=()=>{
    if(!active.current||raw.current===lastCommit.current)return;
    try {
      const next=parseNumberDraft(raw.current,props.min,props.max,props.precision);
      if(next==null&&!allowEmpty)throw new Error('Required number');
      if(next!==(value??null))onCommit(next);
      lastCommit.current=raw.current;setInvalid(false);onValidityChange?.(true);
    }catch{setInvalid(true);onValidityChange?.(false);}
  };
  const update=(text:string)=>{raw.current=text;setDraft(text);setInvalid(false);if(onValidityChange){try{const parsed=parseNumberDraft(text,props.min,props.max,props.precision);onValidityChange(parsed!=null||allowEmpty);}catch{onValidityChange(false);}}};
  return <InputNumber<number|string> {...props} stringMode value={draft===''?null:draft} changeOnBlur={false} status={invalid?'error':props.status} aria-invalid={invalid||undefined} title={invalid?t('ui.invalidNumber'):props.title}
    onInput={update}
    onChange={(next)=>update(next==null?'':String(next))}
    onKeyDown={(event)=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();const initial=value==null?'':String(value);raw.current=initial;lastCommit.current=initial;setDraft(initial);setInvalid(false);onValidityChange?.(true);}props.onKeyDown?.(event);}}
    onKeyDownCapture={(event)=>{
      props.onKeyDownCapture?.(event);
      // InputNumber otherwise flushes/clamps the draft before onPressEnter runs.
      if(event.key==='Enter'&&!event.nativeEvent.isComposing&&!event.defaultPrevented){event.preventDefault();event.stopPropagation();commit();}
    }} onBlur={commit}/>;
}
