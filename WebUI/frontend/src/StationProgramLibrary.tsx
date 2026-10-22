import { useEffect,useRef,useState } from 'react';
import { Alert,Button,Flex,Input,Space,Tag,Typography } from 'antd';
import { api } from './api';
import { ProgramDefinitionEditor } from './ProgramFields';
import { findProgramStep,validateStationProgram } from './stationPrograms';
import { useI18n } from './i18n';
import type { TimetableExtensionDescriptor,StationProgramDefinition,StationProgramRecord } from './types';

export function StationProgramLibrary({world,extensions=[],conditions=[],focus}:{world:string;extensions?:TimetableExtensionDescriptor[];conditions?:TimetableExtensionDescriptor[];focus?:{programId:string;stepId?:string}}){
  const {t}=useI18n();
  const [records,setRecords]=useState<StationProgramRecord[]>([]);
  const [selected,setSelected]=useState<StationProgramRecord>();
  const [id,setId]=useState('');const [name,setName]=useState('');
  const [definition,setDefinition]=useState<StationProgramDefinition>({parameters:[],steps:[]});
  const [jsonValid,setJsonValid]=useState(true);
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const alive=useRef(true);const request=useRef(0);
  const load=async()=>{const token=++request.current;setBusy(true);setError('');try{const values=await api.stationPrograms(world);if(alive.current&&token===request.current)setRecords(values);}catch(failure){if(alive.current&&token===request.current)setError(String(failure));}finally{if(alive.current&&token===request.current)setBusy(false);}};
  useEffect(()=>{alive.current=true;if(world)void load();return()=>{alive.current=false;request.current++;};},[world]);
  const select=(record?:StationProgramRecord)=>{setSelected(record);setId(record?.id??'');setName(record?.name??'');setDefinition(record?JSON.parse(JSON.stringify(record.definition)) as StationProgramDefinition:{parameters:[],steps:[]});setError('');};
  const handledFocus=useRef('');
  useEffect(()=>{
    if(!focus||handledFocus.current===`${focus.programId}/${focus.stepId??''}`)return;
    const record=records.find((item)=>item.id===focus.programId);
    if(record){select(record);handledFocus.current=`${focus.programId}/${focus.stepId??''}`;}
  },[focus,records]);
  useEffect(()=>{
    if(!focus?.stepId||selected?.id!==focus.programId)return;
    const node=document.querySelector<HTMLElement>(`[data-program-step="${focus.stepId}"]`);
    node?.focus();node?.scrollIntoView({block:'center'});
  },[selected?.id,selected?.revision,focus]);
  const save=async(archive=false)=>{
    if(busy||!world||!jsonValid&&!archive)return;
    const token=++request.current;setBusy(true);setError('');
    try{
      const record=archive&&selected?await api.archiveStationProgram(world,selected.id,selected.revision):selected?await api.saveStationProgram(world,selected.id,name,definition,selected.revision):await api.createStationProgram(world,id,name,definition);
      if(!alive.current||token!==request.current)return;
      setRecords((values)=>[...values.filter((item)=>item.id!==record.id),record].sort((a,b)=>a.id.localeCompare(b.id)));select(record);
    }catch(failure){if(alive.current&&token===request.current)setError(String(failure));}
    finally{if(alive.current&&token===request.current)setBusy(false);}
  };
  const errors=validateStationProgram(definition);
  return <div className="module-page"><div className="page-heading"><div><Typography.Title level={3}>{t('programs.title')}</Typography.Title><Typography.Text type="secondary">{t('programs.description')}</Typography.Text></div><Space><Button disabled={busy} onClick={()=>void load()}>{t('common.refresh')}</Button><Button disabled={busy} onClick={()=>select()}>{t('programs.new')}</Button></Space></div>
    {error&&<Alert type="error" showIcon title={error}/>}
    <Alert type="info" showIcon title={t('programs.executionHint')}/>
    <div className="duty-admin-grid"><aside className="entity-list panel-surface">{records.map((record)=><Button key={record.id} type="text" disabled={busy} onClick={()=>select(record)}><Space>{record.name}<Tag>r{record.revision}</Tag>{record.archived&&<Tag>{t('programs.archived')}</Tag>}</Space></Button>)}</aside>
    <section className="panel-surface duty-detail"><Flex wrap gap={12}><label>{t('programs.id')}<Input aria-label={t('programs.id')} disabled={!!selected||busy} value={id} onChange={(event)=>setId(event.target.value)}/></label><label>{t('programs.name')}<Input aria-label={t('programs.name')} disabled={busy||selected?.archived} value={name} onChange={(event)=>setName(event.target.value)}/></label></Flex>
      {focus?.programId===selected?.id&&focus?.stepId&&!findProgramStep(selected?.definition.steps??[],focus.stepId)&&<Alert type="info" title={t('programs.stepUnavailable')}/>}
      <fieldset inert={busy||selected?.archived} disabled={busy||selected?.archived} className="program-editor-fields"><ProgramDefinitionEditor conditions={conditions} extensions={extensions} onValidityChange={setJsonValid} focusedStep={focus?.programId===selected?.id?focus?.stepId:undefined} key={`${world}/${selected?.id??'new'}/${selected?.revision??0}`} fieldScope={`${world}/${selected?.id??'new'}/${selected?.revision??0}`} value={definition} onChange={setDefinition}/></fieldset>
      {!!errors.length&&<Alert type="warning" title={errors.join('; ')}/>}
      <Flex wrap gap={8}><Button type="primary" loading={busy} disabled={!world||!id.trim()||!name.trim()||!!errors.length||!jsonValid||selected?.archived} onClick={()=>void save()}>{t('common.save')}</Button>{selected&&<Button danger disabled={busy||selected.archived} onClick={()=>void save(true)}>{t('programs.archive')}</Button>}</Flex>
      <Typography.Paragraph type="secondary">{t('programs.conflictHint')}</Typography.Paragraph>
    </section></div>
  </div>;
}
