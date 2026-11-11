import {ProgramJsonInput} from './ProgramJsonInput';
import { Input,Select } from 'antd';
import { CommittedNumberInput } from './CommittedNumberInput';
import type { StationProgramLiteral,StationProgramParameterType,WorldMetadata } from './types';

export const programParameterTypes:StationProgramParameterType[]=['NUMBER','DURATION','STRING','ID_LIST','JSON_OBJECT','BOOLEAN'];
export function ProgramLiteralInput({type,value,onChange,fieldKey,onValidityChange}:{type:StationProgramParameterType;value:StationProgramLiteral|undefined;onChange:(value:StationProgramLiteral)=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void;metadata?:WorldMetadata}){
  if(type==='NUMBER')return <CommittedNumberInput aria-label={type} fieldKey={fieldKey} onValidityChange={onValidityChange} value={typeof value==='number'?value:null} allowEmpty={false} onCommit={(next)=>onChange(next??0)}/>;
  if(type==='JSON_OBJECT')return <ProgramJsonInput key={fieldKey} fieldKey={fieldKey} value={value&&typeof value==='object'&&!Array.isArray(value)?value:undefined} onChange={onChange} onValidityChange={onValidityChange}/>;
  if(type==='DURATION')return <CommittedNumberInput aria-label={type} fieldKey={fieldKey} onValidityChange={onValidityChange} value={typeof value==='number'?value:null} allowEmpty={false} min={0} precision={0} onCommit={(next)=>onChange(next??0)}/>;
  if(type==='BOOLEAN')return <Select aria-label={type} value={typeof value==='boolean'?String(value):undefined} options={[{value:'true',label:'true'},{value:'false',label:'false'}]} onChange={(next)=>onChange(next==='true')} style={{minWidth:120}}/>;
  if(type==='ID_LIST')return <Select aria-label={type} mode="tags" value={Array.isArray(value)?value:[]} onChange={onChange} style={{minWidth:'var(--program-literal-select-min-width,170px)'}}/>;
  return <Input aria-label={type} value={typeof value==='string'?value:''} onChange={(event)=>onChange(event.target.value)}/>;
}
export {ProgramBlockEditor as ProgramDefinitionEditor} from './ProgramBlockEditor';
