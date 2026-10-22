import {useEffect,useState} from 'react';
import {ProgramJsonInput} from './ProgramJsonInput';
import { Button,Flex,Input,Select,Space,Typography } from 'antd';
import { ArrowDownOutlined,ArrowUpOutlined,DeleteOutlined } from '@ant-design/icons';
import { CommittedNumberInput } from './CommittedNumberInput';
import { useI18n } from './i18n';
import type { ProgramJsonObject,TimetableExtensionDescriptor,StationProgramDefinition,StationProgramLiteral,StationProgramParameterType,StationProgramStep,StationProgramValue } from './types';

export const programParameterTypes:StationProgramParameterType[]=['TICK','DURATION','DESTINATION','STATION','DEPOT','FORMATION_EVENT','STRING','ID_LIST','JSON_OBJECT'];
export function ProgramLiteralInput({type,value,onChange,fieldKey,onValidityChange}:{type:StationProgramParameterType;value:StationProgramLiteral|undefined;onChange:(value:StationProgramLiteral)=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void}){
  if(type==='JSON_OBJECT')return <ProgramJsonInput key={fieldKey} fieldKey={fieldKey} value={value&&typeof value==='object'&&!Array.isArray(value)?value:undefined} onChange={onChange} onValidityChange={onValidityChange}/>;
  if(type==='TICK'||type==='DURATION')return <CommittedNumberInput aria-label={type} fieldKey={fieldKey} value={typeof value==='number'?value:null} allowEmpty={false} min={0} precision={0} onCommit={(next)=>onChange(next??0)}/>;
  if(type==='ID_LIST')return <Select aria-label={type} mode="tags" value={Array.isArray(value)?value:[]} onChange={onChange} style={{minWidth:170}}/>;
  return <Input aria-label={type} value={typeof value==='string'?value:''} onChange={(event)=>onChange(event.target.value)}/>;
}
function ProgramValueInput({type,value,parameters,onChange,fieldKey,onValidityChange}:{type:StationProgramParameterType;value:StationProgramValue<StationProgramLiteral>;parameters:StationProgramDefinition['parameters'];onChange:(value:StationProgramValue<StationProgramLiteral>)=>void;fieldKey:string;onValidityChange?:(valid:boolean)=>void}){
  const {t}=useI18n();
  const reference='parameterId' in value;
  return <Space wrap><Select aria-label={t('programs.valueMode')} value={reference?'parameter':'literal'} options={[{value:'literal',label:t('programs.literal')},{value:'parameter',label:t('programs.parameter')}]} onChange={(mode)=>onChange(mode==='parameter'?{parameterId:''}:{value:type==='JSON_OBJECT'?{}:type==='ID_LIST'?[]:type==='TICK'||type==='DURATION'?0:''})}/>{reference?<Select aria-label={t('programs.parameter')} value={value.parameterId||undefined} options={parameters.filter((parameter)=>parameter.type===type).map((parameter)=>({value:parameter.id,label:parameter.id}))} style={{minWidth:160}} onChange={(parameterId)=>onChange({parameterId})}/>:<ProgramLiteralInput onValidityChange={onValidityChange} fieldKey={fieldKey} type={type} value={value.value} onChange={(next)=>onChange({value:next})}/>}</Space>;
}
export function ProgramDefinitionEditor({value,onChange,fieldScope,extensions=[],conditions=[],onValidityChange,focusedStep,branchDepth=0}:{value:StationProgramDefinition;onChange:(value:StationProgramDefinition)=>void;fieldScope:string;extensions?:TimetableExtensionDescriptor[];conditions?:TimetableExtensionDescriptor[];branchDepth?:number;onValidityChange?:(valid:boolean)=>void;focusedStep?:string}){
  const {t}=useI18n();
  const [invalidJson,setInvalidJson]=useState<Record<string,boolean>>({});
  useEffect(()=>()=>onValidityChange?.(true),[fieldScope]);
  useEffect(()=>onValidityChange?.(!Object.values(invalidJson).some(Boolean)),[invalidJson,onValidityChange]);
  const jsonValidity=(key:string,valid:boolean)=>setInvalidJson((current)=>{if(valid&&!current[key]||!valid&&current[key])return current;const next={...current};if(valid)delete next[key];else next[key]=true;return next;});
  const patchStep=(index:number,patch:Partial<StationProgramStep>)=>onChange({...value,steps:value.steps.map((step,i)=>i===index?{...step,...patch} as StationProgramStep:step)});
  const move=(index:number,delta:number)=>{const steps=[...value.steps];[steps[index],steps[index+delta]]=[steps[index+delta],steps[index]];onChange({...value,steps});};
  const field=(step:StationProgramStep,index:number,name:string,type:StationProgramParameterType,expression:StationProgramValue<StationProgramLiteral>)=><label>{t(`programs.${name}` as Parameters<typeof t>[0])}<ProgramValueInput fieldKey={`${fieldScope}/${index}/${step.id}/${name}`} type={type} value={expression} parameters={value.parameters} onValidityChange={(valid)=>jsonValidity(`${index}/${step.id}/${name}`,valid)} onChange={(next)=>patchStep(index,{[name]:next})}/></label>;
  return <>
    {branchDepth===0&&<><Typography.Title level={5}>{t('programs.parameters')}</Typography.Title>
    {value.parameters.map((parameter,index)=><Flex key={index} wrap gap={8} className="program-field-row"><Input aria-label={t('programs.parameterId')} value={parameter.id} onChange={(event)=>onChange({...value,parameters:value.parameters.map((item,i)=>i===index?{...item,id:event.target.value}:item)})}/><Select aria-label={t('programs.parameterType')} value={parameter.type} options={programParameterTypes.map((type)=>({value:type,label:t(`programs.param.${type}`)}))} onChange={(type)=>onChange({...value,parameters:value.parameters.map((item,i)=>i===index?{...item,type}:item)})}/><Button danger aria-label={t('common.delete')} icon={<DeleteOutlined/>} onClick={()=>onChange({...value,parameters:value.parameters.filter((_,i)=>i!==index)})}/></Flex>)}
    <Button onClick={()=>onChange({...value,parameters:[...value.parameters,{id:`parameter-${value.parameters.length+1}`,type:'DESTINATION'}]})}>{t('programs.addParameter')}</Button></>}
    <Typography.Title level={5}>{t('programs.steps')}</Typography.Title>
    {value.steps.map((step,index)=><section className={`program-step panel-surface ${focusedStep===step.id?'program-step-focused':''}`} tabIndex={-1} data-program-step={step.id} key={index}><Flex wrap gap={8} align="center"><Input aria-label={t('programs.stepId')} value={step.id} onChange={(event)=>patchStep(index,{id:event.target.value})}/><Typography.Text strong>{t(`programs.step.${step.type}`)}</Typography.Text><Button aria-label={t('common.moveUp')} disabled={!index} icon={<ArrowUpOutlined/>} onClick={()=>move(index,-1)}/><Button aria-label={t('common.moveDown')} disabled={index===value.steps.length-1} icon={<ArrowDownOutlined/>} onClick={()=>move(index,1)}/><Button danger aria-label={t('common.delete')} icon={<DeleteOutlined/>} onClick={()=>onChange({...value,steps:value.steps.filter((_,i)=>i!==index)})}/></Flex>
      {step.type==='EXTENSION'&&<>
        <Select aria-label={t('programs.extension')} value={`${step.typeId}@${step.version}`} options={extensions.map((extension)=>({value:`${extension.typeId}@${extension.version}`,label:`${extension.title} · ${extension.typeId}@${extension.version}`}))} onChange={(selected)=>{const extension=extensions.find((item)=>`${item.typeId}@${item.version}`===selected);if(extension)patchStep(index,{typeId:extension.typeId,version:extension.version});}}/>
        {!extensions.some((extension)=>extension.typeId===step.typeId&&extension.version===step.version)&&<Typography.Text type="warning">{t('programs.providerMissing')} · {step.typeId}@{step.version}</Typography.Text>}
        <Typography.Paragraph type="secondary">{extensions.find((extension)=>extension.typeId===step.typeId&&extension.version===step.version)?.description}</Typography.Paragraph>
        {field(step,index,'parameters','JSON_OBJECT',step.parameters)}
        <details><summary>{t('programs.parameterSchema')}</summary><pre>{JSON.stringify(extensions.find((extension)=>extension.typeId===step.typeId&&extension.version===step.version)?.parameterSchema??{},null,2)}</pre></details>
      </>}
      {step.type==='BRANCH'&&<>
        <Select aria-label={t('programs.condition')} value={`${step.typeId}@${step.version}`} options={conditions.map((item)=>({value:`${item.typeId}@${item.version}`,label:`${item.title} · ${item.typeId}@${item.version}`}))} onChange={(selected)=>{const item=conditions.find((item)=>`${item.typeId}@${item.version}`===selected);if(item)patchStep(index,{typeId:item.typeId,version:item.version});}}/>
        {!conditions.some((item)=>item.typeId===step.typeId&&item.version===step.version)&&<Typography.Text type="warning">{t('programs.providerMissing')} · {step.typeId}@{step.version}</Typography.Text>}
        <Typography.Paragraph type="secondary">{conditions.find((item)=>item.typeId===step.typeId&&item.version===step.version)?.description}</Typography.Paragraph>
        {field(step,index,'parameters','JSON_OBJECT',step.parameters)}
        <details><summary>{t('programs.parameterSchema')}</summary><pre>{JSON.stringify(conditions.find((item)=>item.typeId===step.typeId&&item.version===step.version)?.parameterSchema??{},null,2)}</pre></details>
        <Typography.Paragraph type="secondary">{t('programs.branchHelp')}</Typography.Paragraph>
        {(['thenSteps','elseSteps'] as const).map((arm)=><div className="program-branch" key={arm}><Typography.Title level={5}>{t(`programs.${arm}`)}</Typography.Title><ProgramDefinitionEditor value={{parameters:value.parameters,steps:step[arm]}} onChange={(next)=>patchStep(index,{[arm]:next.steps})} fieldScope={`${fieldScope}/${step.id}/${arm}`} extensions={extensions} conditions={conditions} branchDepth={branchDepth+1} focusedStep={focusedStep} onValidityChange={(valid)=>jsonValidity(`${step.id}/${arm}`,valid)}/></div>)}
      </>}
      {(step.type==='COUPLE'||step.type==='SPLIT')&&<>{field(step,index,'eventId','FORMATION_EVENT',step.eventId)}<Typography.Paragraph type="secondary">{t('programs.fixedEventHelp')}</Typography.Paragraph></>}
      {step.type==='WAIT_UNTIL' &&field(step,index,'tick','TICK',step.tick)}
      {step.type==='WAIT_DURATION'&&field(step,index,'durationTicks','DURATION',step.durationTicks)}
      {step.type==='MOVE'&&<>{field(step,index,'destinationId','DESTINATION',step.destinationId)}{field(step,index,'viaIds','ID_LIST',step.viaIds)}</>}
      {step.type==='SELECT_STOP'&&<>{field(step,index,'stationId','STATION',step.stationId)}{field(step,index,'berthIds','ID_LIST',step.berthIds)}<label>{t('programs.selectionPolicy')}<Select aria-label={t('programs.selectionPolicy')} value={step.selectionPolicy} options={(['FIXED_PREFERENCE','AVAILABLE_PRIORITY'] as const).map((policy)=>({value:policy,label:t(`programs.policy.${policy}`)}))} onChange={(selectionPolicy)=>patchStep(index,{selectionPolicy})}/></label><Typography.Paragraph type="secondary">{t('programs.selectionHelp')}</Typography.Paragraph></>}
      {step.type==='STORE_AT_DEPOT'&&<>{field(step,index,'depotId','DEPOT',step.depotId)}{field(step,index,'stablingPointIds','ID_LIST',step.stablingPointIds)}</>}
    </section>)}
    <Select aria-label={t('programs.addStep')} placeholder={t('programs.addStep')} value={null} options={(['WAIT_UNTIL','WAIT_DURATION','REVERSE','MOVE','SELECT_STOP','STORE_AT_DEPOT','COUPLE','SPLIT','EXTENSION','BRANCH'] as const).map((type)=>({value:type,label:t(`programs.step.${type}`),disabled:type==='EXTENSION'&&!extensions.length||type==='BRANCH'&&(!conditions.length||branchDepth>=16)||branchDepth>0&&['STORE_AT_DEPOT','COUPLE','SPLIT'].includes(type)}))} onChange={(type:StationProgramStep['type'])=>{
      const id=`step-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,9)}`;
      const step:StationProgramStep=type==='SELECT_STOP'?{id,type,stationId:{value:''},berthIds:{value:[]},selectionPolicy:'FIXED_PREFERENCE'}:type==='BRANCH'?{id,type,typeId:conditions[0]?.typeId??'',version:conditions[0]?.version??1,parameters:{value:{}},thenSteps:[],elseSteps:[]}:type==='COUPLE'||type==='SPLIT'?{id,type,eventId:{value:''}}:type==='WAIT_UNTIL'?{id,type,tick:{value:0}}:type==='WAIT_DURATION'?{id,type,durationTicks:{value:1}}:type==='MOVE'?{id,type,destinationId:{value:''},viaIds:{value:[]}}:type==='STORE_AT_DEPOT'?{id,type,depotId:{value:''},stablingPointIds:{value:[]}}:type==='EXTENSION'?{id,type,typeId:extensions[0]?.typeId??'',version:extensions[0]?.version??1,parameters:{value:{} as ProgramJsonObject}}:{id,type};
      onChange({...value,steps:[...value.steps,step]});
    }}/>
  </>;
}
