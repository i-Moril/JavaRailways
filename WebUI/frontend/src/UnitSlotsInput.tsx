import {useEffect,useId,useRef,useState} from 'react';
import {Button,Input,Tag,Typography} from 'antd';
import {useI18n} from './i18n';
import {isUnitSlots,parseUnitSlots,unitSlotCandidate} from './unitSlots';
import './UnitSlotsInput.css';

interface Props {
  value?:unknown;onChange:(value:number[])=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void;
  rawValue?:string;onRawChange?:(raw:string)=>void;
}
// The key isolates uncommitted text when a different block, document or world is selected.
export function UnitSlotsInput(props:Props){return <UnitSlotsField key={props.fieldKey} {...props}/>;}
function UnitSlotsField({value,onChange,onValidityChange,rawValue,onRawChange}:Props){
  const {t}=useI18n();const helpId=useId();
  const validityCallback=useRef(onValidityChange);validityCallback.current=onValidityChange;
  const [candidate,setCandidate]=useState('');const [repair,setRepair]=useState<string>();
  const encoded=JSON.stringify(value);
  useEffect(()=>{setRepair(undefined);},[encoded]);
  const raw=rawValue??repair??encoded??'';
  const slots=rawValue===undefined&&repair===undefined&&isUnitSlots(value)?value:parseUnitSlots(raw);
  const valid=!!slots;
  useEffect(()=>{validityCallback.current?.(valid);},[valid]);
  const next=unitSlotCandidate(candidate,slots??[]);
  const add=()=>{if(next===undefined||!slots)return;onChange([...slots,next]);setCandidate('');};
  return <div className="unit-slots-input" role="group" aria-label={t('programs.unitSlots')} aria-describedby={helpId}>
    <Typography.Text type="secondary" id={helpId}>{t('programs.unitSlotsHelp')}</Typography.Text>
    {slots?<><div className="unit-slots-chips">{slots.map((slot)=><Tag key={slot}><span>{t('programs.unitNumber').replace('{number}',String(slot))}</span><Button type="text" size="small" aria-label={t('programs.removeUnit').replace('{number}',String(slot))} disabled={slots.length===1} onClick={()=>onChange(slots.filter((item)=>item!==slot))}>×</Button></Tag>)}</div>
      <div className="unit-slots-add"><Input aria-label={t('programs.unitToAdd')} aria-describedby={helpId} inputMode="numeric" value={candidate} onChange={(event)=>setCandidate(event.target.value)} onKeyDown={(event)=>{if(event.key==='Enter'){event.preventDefault();event.stopPropagation();add();}else if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setCandidate('');}}}/><Button disabled={next===undefined} onClick={add}>{t('programs.addUnit')}</Button></div>
      {candidate&&next===undefined&&<Typography.Text type="secondary">{t('programs.unitCandidateHint')}</Typography.Text>}
    </>:<><Typography.Text type="danger">{t('programs.unitSlotsInvalid')}</Typography.Text><Input aria-label={t('programs.unitSlotsRaw')} status="error" value={raw} onChange={(event)=>{const text=event.target.value;if(onRawChange)onRawChange(text);else{setRepair(text);const parsed=parseUnitSlots(text);onValidityChange?.(!!parsed);if(parsed)onChange(parsed);}}}/></>}
  </div>;
}
