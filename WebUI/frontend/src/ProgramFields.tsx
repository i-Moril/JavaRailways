import {UnitSlotsInput} from './UnitSlotsInput';
import {ProgramJsonInput} from './ProgramJsonInput';
import { Input,Select } from 'antd';
import { CommittedNumberInput } from './CommittedNumberInput';
import type { StationProgramLiteral,StationProgramParameterType } from './types';

export const programParameterTypes:StationProgramParameterType[]=['TICK','DURATION','DESTINATION','STATION','DEPOT','STRING','ID_LIST','JSON_OBJECT','UNIT_SLOTS'];
export function ProgramLiteralInput({type,value,onChange,fieldKey,onValidityChange}:{type:StationProgramParameterType;value:StationProgramLiteral|undefined;onChange:(value:StationProgramLiteral)=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void}){
  if(type==='JSON_OBJECT')return <ProgramJsonInput key={fieldKey} fieldKey={fieldKey} value={value&&typeof value==='object'&&!Array.isArray(value)?value:undefined} onChange={onChange} onValidityChange={onValidityChange}/>;
  if(type==='TICK'||type==='DURATION')return <CommittedNumberInput aria-label={type} fieldKey={fieldKey} onValidityChange={onValidityChange} value={typeof value==='number'?value:null} allowEmpty={false} min={0} precision={0} onCommit={(next)=>onChange(next??0)}/>;
  if(type==='UNIT_SLOTS')return <UnitSlotsInput fieldKey={fieldKey} value={value} onChange={onChange} onValidityChange={onValidityChange}/>;
  if(type==='ID_LIST')return <Select aria-label={type} mode="tags" value={Array.isArray(value)?value:[]} onChange={onChange} style={{minWidth:'var(--program-literal-select-min-width,170px)'}}/>;
  return <Input aria-label={type} value={typeof value==='string'?value:''} onChange={(event)=>onChange(event.target.value)}/>;
}
export {ProgramBlockEditor as ProgramDefinitionEditor} from './ProgramBlockEditor';
