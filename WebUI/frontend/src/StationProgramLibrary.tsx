import {flushSync} from 'react-dom';
import {clearProgramBlockDraft} from './programBlockDrafts';
import { useEffect,useRef,useState } from 'react';
import { Alert,Button,Flex,Input,Modal,Space,Typography } from 'antd';
import { api } from './api';
import { ProgramDefinitionEditor } from './ProgramFields';
import {CommittedNumberInput} from './CommittedNumberInput';
import {ProgramSimulationDialog} from './ProgramSimulationDialog';
import { findProgramStep,validateStationProgram } from './stationPrograms';
import { useI18n } from './i18n';
import type { WorldMetadata,TimetableExtensionDescriptor,StationProgramDefinition,StationProgramRecord } from './types';

type Props={metadata?:WorldMetadata;onLibraryChange?:()=>void;world:string;recoveryOwner:string;extensions?:TimetableExtensionDescriptor[];conditions?:TimetableExtensionDescriptor[];focus?:{programId:string;stepId?:string}};
export function newLibraryProgram(records:StationProgramRecord[],entropy=()=>crypto.getRandomValues(new Uint32Array(4))){
  let id:string;
  do{id=String(entropy().reduce((value,word)=>(value<<32n)|BigInt(word),0n));}while(records.some((record)=>record.id===id));
  let name=1;while(records.some((record)=>record.name===String(name)))name++;
  return {id,name:String(name),definition:{parameters:[],steps:[]} as StationProgramDefinition};
}
const sortLibraryRecords=(records:StationProgramRecord[])=>[...records].sort((a,b)=>a.name.localeCompare(b.name,undefined,{numeric:true})||a.id.localeCompare(b.id));
export function libraryHasChanges(record:StationProgramRecord|undefined,definition:StationProgramDefinition,valid:boolean,estimatedDurationTicks?:number){
  return !!record&&(!valid||record.estimatedDurationTicks!==estimatedDurationTicks||JSON.stringify(record.definition)!==JSON.stringify(definition));
}
// A different owner/world owns a completely separate editor, request generation and recovery scope.
export function StationProgramLibrary(props:Props){return <Library key={JSON.stringify([props.recoveryOwner,props.world])} {...props}/>;}
function Library({world,recoveryOwner,metadata={},extensions=[],conditions=[],focus,onLibraryChange}:Props){
  const {t}=useI18n();
  const [records,setRecords]=useState<StationProgramRecord[]>([]);
  const [selected,setSelected]=useState<StationProgramRecord>();
  const [definition,setDefinition]=useState<StationProgramDefinition>({parameters:[],steps:[]});
  const [estimatedDurationTicks,setEstimatedDurationTicks]=useState<number>();const [durationValid,setDurationValid]=useState(true);const [estimateEpoch,setEstimateEpoch]=useState(0);
  const [valid,setValid]=useState(true);const [editorEpoch,setEditorEpoch]=useState(0);
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const [dialog,setDialog]=useState<{kind:'leave';action:()=>void}|{kind:'rename'|'delete';record:StationProgramRecord}>();
  const [rename,setRename]=useState('');const [simulationOpen,setSimulationOpen]=useState(false);
  const alive=useRef(true);const request=useRef(0);const locked=useRef(false);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;request.current++;locked.current=false;};},[]);
  const perform=async(action:(current:()=>boolean)=>Promise<void>)=>{
    if(locked.current||!world)return;
    locked.current=true;const token=++request.current;setBusy(true);setError('');
    try{await action(()=>alive.current&&token===request.current);}catch(failure){if(alive.current&&token===request.current)setError(String(failure));}
    finally{if(alive.current&&token===request.current){locked.current=false;setBusy(false);}}
  };
  const load=(reloadId?:string)=>perform(async(current)=>{const values=await api.stationPrograms(world);if(current()){setRecords(sortLibraryRecords(values));if(reloadId)select(values.find((record)=>record.id===reloadId));}});
  useEffect(()=>{if(world)void load();},[world]);
  const select=(record?:StationProgramRecord)=>{
    setSimulationOpen(false);setEstimatedDurationTicks(record?.estimatedDurationTicks);setDurationValid(true);setSelected(record);setDefinition(record?structuredClone(record.definition):{parameters:[],steps:[]});setValid(true);setEditorEpoch((value)=>value+1);setError('');
  };
  const discardSelection=()=>{const scope=selected?`${recoveryOwner}/${world}/${selected.id}`:undefined;flushSync(()=>select());if(scope)clearProgramBlockDraft(scope);};
  const dirty=libraryHasChanges(selected,definition,valid&&durationValid,estimatedDurationTicks);
  const leave=(action:()=>void)=>{if(locked.current)return;if(dirty)setDialog({kind:'leave',action});else action();};
  const handledFocus=useRef('');
  useEffect(()=>{
    const identity=focus?`${focus.programId}/${focus.stepId??''}`:'';
    if(!focus||handledFocus.current===identity||busy)return;
    const record=records.find((item)=>item.id===focus.programId);
    if(record){handledFocus.current=identity;if(record.id!==selected?.id)leave(()=>select(record));}
  },[focus,records,busy]);
  useEffect(()=>{
    if(!focus?.stepId||selected?.id!==focus.programId)return;
    const node=[...document.querySelectorAll<HTMLElement>('[data-program-step]')].find((item)=>item.dataset.programStep===focus.stepId);
    node?.focus();node?.scrollIntoView({block:'center'});
  },[selected?.id,focus]);
  const updateList=(record:StationProgramRecord)=>setRecords((values)=>sortLibraryRecords([...values.filter((item)=>item.id!==record.id),record]));
  const create=()=>leave(()=>void perform(async(current)=>{
    const next=newLibraryProgram(records);const record=await api.createStationProgram(world,next.id,next.name,next.definition);
    onLibraryChange?.();if(current()){updateList(record);select(record);}
  }));
  const save=()=>{
    if(!selected||!valid||!durationValid||validateStationProgram(definition).length)return;
    const source=selected;const snapshot=structuredClone(definition);
    void perform(async(current)=>{const record=await api.saveStationProgram(world,source.id,source.name,snapshot,source.revision,estimatedDurationTicks);onLibraryChange?.();if(current()){updateList(record);select(record);}});
  };
  const confirm=()=>{
    if(!dialog||locked.current)return;
    if(dialog.kind==='leave'){setDialog(undefined);discardSelection();dialog.action();return;}
    const {record,kind}=dialog;
    if(kind==='rename'&&!rename.trim())return;
    void perform(async(current)=>{
      if(kind==='delete'){
        const result=await api.deleteStationProgram(world,record.id,record.revision);
        if(!result.removed)throw new Error(t('programs.deleteMissing'));onLibraryChange?.();
        if(current()){setRecords((values)=>values.filter((item)=>item.id!==record.id));if(selected?.id===record.id)discardSelection();setDialog(undefined);}
      }else{
        // Rename the server baseline only. Unsaved definition/invalid canvas stays in this editor.
        const renamed=await api.saveStationProgram(world,record.id,rename.trim(),record.definition,record.revision,record.estimatedDurationTicks);
        onLibraryChange?.();if(current()){updateList(renamed);if(selected?.id===record.id)setSelected(renamed);setDialog(undefined);}
      }
    });
  };
  const errors=validateStationProgram(definition);
  return <div className="module-page program-library"><div className="page-heading"><div><Typography.Title level={3}>{t('programs.title')}</Typography.Title><Typography.Text type="secondary">{t('programs.description')}</Typography.Text></div><Button disabled={busy||!world} onClick={()=>leave(()=>void load(selected?.id))}>{t('common.refresh')}</Button></div>
    {error&&<Alert type="error" showIcon title={error}/>}
    <div className="program-library-grid"><aside className="panel-surface program-library-files" aria-label={t('programs.title')}><Button type="primary" disabled={busy||!world} onClick={create}>{t('programs.new')}</Button>
      <div role="list" className="program-library-file-list">{records.map((record)=><div role="listitem" key={record.id} className="program-library-file"><Button type={selected?.id===record.id?'primary':'text'} disabled={busy} onClick={()=>{if(record.id!==selected?.id)leave(()=>select(record));}} title={record.name}><span className="program-library-file-name">{record.name}</span></Button><Space size={0}><Button type="text" disabled={busy} aria-label={`${t('programs.rename')} ${record.name}`} title={t('programs.rename')} onClick={()=>{setError('');setRename(record.name);setDialog({kind:'rename',record});}}>✎</Button><Button danger type="text" disabled={busy} aria-label={`${t('common.delete')} ${record.name}`} title={t('common.delete')} onClick={()=>{setError('');setDialog({kind:'delete',record});}}>×</Button></Space></div>)}</div>
    </aside><section className="panel-surface program-library-editor">{selected?<><Flex align="center" justify="space-between" gap={12}><Typography.Title level={4}>{selected.name}{dirty?' *':''}</Typography.Title><Space><Button disabled={busy||!!errors.length||!valid} onClick={()=>setSimulationOpen(true)}>{t('programSimulation.open')}</Button><Button type="primary" loading={busy} disabled={busy||!!errors.length||!valid||!durationValid||!dirty} onClick={save}>{t('common.save')}</Button></Space></Flex>
      <Alert type="info" showIcon title={t('programs.executionHint')}/>
      <div className="timed-operation-grid"><label>{t('programs.estimatedDuration')}<CommittedNumberInput aria-label={t('programs.estimatedDuration')} fieldKey={`${recoveryOwner}/${world}/${selected.id}/${editorEpoch}/estimate/${estimateEpoch}`} value={estimatedDurationTicks} min={0} max={Number.MAX_SAFE_INTEGER} precision={0} disabled={busy} onValidityChange={setDurationValid} onCommit={value=>setEstimatedDurationTicks(value??undefined)}/></label></div><Typography.Text type="secondary">{t('programs.estimatedDurationHint')}</Typography.Text>
      {focus?.programId===selected.id&&focus?.stepId&&!findProgramStep(selected.definition.steps,focus.stepId)&&<Alert type="info" title={t('programs.stepUnavailable')}/>}
      <fieldset inert={busy} disabled={busy} className="program-editor-fields"><ProgramDefinitionEditor metadata={metadata} conditions={conditions} extensions={extensions} onValidityChange={setValid} focusedStep={focus?.programId===selected.id?focus.stepId:undefined} key={`${selected.id}/${editorEpoch}`} fieldScope={`${recoveryOwner}/${world}/${selected.id}`} value={definition} onChange={setDefinition}/></fieldset>
      {!!errors.length&&<Alert type="warning" title={errors.join('; ')}/>}
      <Typography.Paragraph type="secondary">{t('programs.conflictHint')}</Typography.Paragraph>
    </>:<Typography.Paragraph type="secondary">{t('programs.selectFile')}</Typography.Paragraph>}</section></div>
    {simulationOpen&&selected&&<ProgramSimulationDialog world={world} owner={`${recoveryOwner}/${selected.id}`} metadata={metadata} programId={selected.id} definition={definition} valid={valid&&!errors.length} editableArguments onAdoptDuration={ticks=>{setEstimatedDurationTicks(ticks);setDurationValid(true);setEstimateEpoch(epoch=>epoch+1);}} onClose={()=>setSimulationOpen(false)}/>}
    <Modal open={!!dialog} title={dialog?.kind==='rename'?t('programs.rename'):dialog?.kind==='delete'?t('common.delete'):t('programs.unsavedTitle')} onCancel={()=>{if(!busy)setDialog(undefined);}} onOk={confirm} confirmLoading={busy} closable={!busy} maskClosable={!busy} cancelButtonProps={{disabled:busy}} okButtonProps={{danger:dialog?.kind==='delete',disabled:busy||dialog?.kind==='rename'&&!rename.trim()}}>
      {error&&<Alert type="error" showIcon title={error}/>}
      {dialog?.kind==='rename'?<Input autoFocus maxLength={256} aria-label={t('programs.name')} value={rename} disabled={busy} onChange={(event)=>setRename(event.target.value)} onPressEnter={confirm}/>:<Typography.Paragraph>{dialog?.kind==='delete'?t('programs.deleteHint'):t('programs.unsavedHint')}</Typography.Paragraph>}
      {dialog?.kind==='delete'&&selected?.id===dialog.record.id&&dirty&&<Typography.Paragraph>{t('programs.unsavedHint')}</Typography.Paragraph>}
    </Modal>
  </div>;
}
