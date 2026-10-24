import {useEffect,useRef,useState} from 'react';
import {Alert,Button,Flex,Input,Select,Space,Typography} from 'antd';
import * as Blockly from 'blockly/core';
import * as zh from 'blockly/msg/zh-hans';
import * as en from 'blockly/msg/en';
import {useI18n} from './i18n';
import {validateStationProgram} from './stationPrograms';
import {arrivalStepTypes,blockStepId,blocksToDefinition,defaultProgramStep,definitionToBlocks,expressionFields,programStepTypes,type ProgramWorkspaceState} from './programBlocks';
import type {StationProgramDefinition,StationProgramParameterType,StationProgramStep,TimetableExtensionDescriptor} from './types';
import {readProgramBlockDraft,storeProgramBlockDraft,clearProgramBlockDraft,type ProgramBlockSnapshot as Snapshot} from './programBlockDrafts';
export {clearProgramBlockDraft,getProgramBlockDraftValidity} from './programBlockDrafts';
import './ProgramBlockEditor.css';

const workspaceDraftInvalidators=new WeakMap<Blockly.Workspace,()=>void>();
const parameterTypes:StationProgramParameterType[]=['TICK','DURATION','DESTINATION','STATION','DEPOT','FORMATION_EVENT','STRING','ID_LIST','JSON_OBJECT','UNIT_SLOTS'];
export interface ProgramBlockEditorProps {value:StationProgramDefinition;onChange:(value:StationProgramDefinition)=>void;fieldScope:string;draftScope?:string;extensions?:TimetableExtensionDescriptor[];conditions?:TimetableExtensionDescriptor[];onValidityChange?:(valid:boolean)=>void;focusedStep?:string;context?:'library'|'arrival';onUndo?:()=>void;onRedo?:()=>void}
const copy=<T,>(value:T):T=>JSON.parse(JSON.stringify(value)) as T;
const fingerprint=(snapshot:Snapshot)=>JSON.stringify(snapshot,(key,value)=>key==='x'||key==='y'?undefined:value);
function defineBlocks(t:ReturnType<typeof useI18n>['t']){
 const rawField=(value:string)=>new Blockly.FieldTextInput(value,function(this:Blockly.FieldTextInput,next:string){const block=this.getSourceBlock();if(block)workspaceDraftInvalidators.get(block.workspace)?.();return next;});
 Blockly.Blocks.rail_entry={init(this:Blockly.Block){this.appendDummyInput().appendField(t('programs.blockEntry'));this.setNextStatement(true,'RailStep');this.setColour(45);this.setDeletable(false);this.setMovable(false);}};
 for(const type of programStepTypes)Blockly.Blocks[`rail_${type}`]={init(this:Blockly.Block){
  this.appendDummyInput().appendField(t(`programs.step.${type}`));this.setPreviousStatement(true,'RailStep');this.setNextStatement(true,'RailStep');this.setColour(type==='BRANCH'?270:type.startsWith('WAIT')?40:type.includes('DISPLAY')?160:210);
  if(type==='BRANCH'||type==='EXTENSION')this.appendDummyInput().appendField(rawField(''),'TYPE_ID').appendField('@').appendField(rawField('1'),'VERSION');
  if(type==='SELECT_STOP')this.appendDummyInput().appendField(new Blockly.FieldDropdown([[t('programs.policy.FIXED_PREFERENCE'),'FIXED_PREFERENCE'],[t('programs.policy.AVAILABLE_PRIORITY'),'AVAILABLE_PRIORITY']],function(this:Blockly.FieldDropdown,next:string){const block=this.getSourceBlock();if(block)workspaceDraftInvalidators.get(block.workspace)?.();return next;}),'POLICY');
  for(const [name,valueType] of Object.entries(expressionFields[type]??{}))this.appendValueInput(name).setCheck(valueType).appendField(t(`programs.${name}` as Parameters<typeof t>[0]));
  if(type==='BRANCH')for(const arm of ['thenSteps','elseSteps'] as const)this.appendStatementInput(arm).setCheck('RailStep').appendField(t(`programs.${arm}`));
  this.setInputsInline(false);
 }};
 for(const type of parameterTypes){
  Blockly.Blocks[`rail_literal_${type}`]={init(this:Blockly.Block){this.appendDummyInput().appendField(t(`programs.param.${type}`)).appendField(rawField(type==='JSON_OBJECT'?'{}':type==='ID_LIST'?'[]':type==='UNIT_SLOTS'?'[1]':type==='DURATION'?'1':type==='TICK'?'0':''),'VALUE');this.setOutput(true,type);this.setColour(160);}};
  Blockly.Blocks[`rail_parameter_${type}`]={init(this:Blockly.Block){this.appendDummyInput().appendField(t('programs.parameter')).appendField(t(`programs.param.${type}`)).appendField(rawField(''),'VALUE');this.setOutput(true,type);this.setColour(315);}};
 }
}
export function ProgramBlockEditor(props:ProgramBlockEditorProps){
 const {value,fieldScope,focusedStep,context='library',extensions=[],conditions=[]}=props;
 const draftScope=props.draftScope??fieldScope;
 const {t,locale}=useI18n();
 const host=useRef<HTMLDivElement>(null);const workspace=useRef<Blockly.WorkspaceSvg|undefined>(undefined);
 const latest=useRef(props);latest.current=props;
 const parameters=useRef(copy(value.parameters));const accepted=useRef(JSON.stringify(value));
 const baseline=useRef(JSON.stringify(value));
 const [selectedId,setSelectedId]=useState<string>();const [revision,setRevision]=useState(0);const [error,setError]=useState('');const [target,setTarget]=useState('$entry');
 const past=useRef<Snapshot[]>([]);const future=useRef<Snapshot[]>([]);const lastSnapshot=useRef<Snapshot|undefined>(undefined);const lastFingerprint=useRef('');
 const validity=useRef(true);const historyAction=useRef<(redo:boolean)=>void>(()=>{});
 const sync=useRef<()=>void>(()=>{});const restoring=useRef(false);const recoveryBlocked=useRef(false);
 const capture=():Snapshot=>({workspace:Blockly.serialization.workspaces.save(workspace.current!) as ProgramWorkspaceState,parameters:copy(parameters.current)});
 const load=(snapshot:Snapshot)=>{const ws=workspace.current;if(!ws)return;if(!snapshot||!Array.isArray(snapshot.parameters)||!snapshot.workspace?.blocks)throw new Error('Malformed workspace draft');restoring.current=true;Blockly.Events.disable();try{Blockly.serialization.workspaces.load(snapshot.workspace,ws);parameters.current=copy(snapshot.parameters);}finally{Blockly.Events.enable();restoring.current=false;}setSelectedId(undefined);setRevision((n)=>n+1);};
 sync.current=()=>{
  if(!workspace.current||restoring.current||recoveryBlocked.current)return;
  const snapshot=capture();const signature=fingerprint(snapshot);
  if(lastSnapshot.current&&signature!==lastFingerprint.current){past.current=[...past.current.slice(-99),lastSnapshot.current];future.current=[];}
  lastSnapshot.current=snapshot;lastFingerprint.current=signature;
  let definition:StationProgramDefinition|undefined;let failure='';
  try{definition=blocksToDefinition(snapshot.workspace,snapshot.parameters,latest.current.context);failure=validateStationProgram(definition).join('; ');}catch(problem){failure=String(problem instanceof Error?problem.message:problem);}
  validity.current=!failure;setError(failure);latest.current.onValidityChange?.(!failure);
  // Raw workspace preserves loose blocks and incomplete field text.
  if(!failure&&definition){const json=JSON.stringify(definition);if(json!==accepted.current){accepted.current=json;latest.current.onChange(definition);}}
  storeProgramBlockDraft(draftScope,baseline.current,accepted.current,snapshot);
  setRevision((n)=>n+1);
 };
 useEffect(()=>{
  if(!host.current)return;
  Blockly.setLocale(Object.fromEntries(Object.entries(locale==='zh-CN'?zh:en).filter((entry):entry is [string,string]=>typeof entry[1]==='string')));defineBlocks(t);
  const toolbox={kind:'categoryToolbox',contents:[{kind:'category',name:t('programs.steps'),colour:'210',contents:(context==='arrival'?arrivalStepTypes:programStepTypes).map((type)=>({kind:'block',...definitionToBlocks({parameters:[],steps:[defaultProgramStep(type,type==='BRANCH'?conditions[0]:extensions[0])]}).blocks!.blocks[0].next!.block,id:undefined}))},...parameterTypes.filter((type)=>context==='library'||['TICK','DURATION','ID_LIST','JSON_OBJECT','UNIT_SLOTS'].includes(type)).map((type)=>({kind:'category',name:t(`programs.param.${type}`),colour:'160',contents:[{kind:'block',type:`rail_literal_${type}`},...(context==='arrival'?[]:[{kind:'block',type:`rail_parameter_${type}`}])]}))]};
  const dark=document.documentElement.dataset.theme!=='light';
  const theme=Blockly.Theme.defineTheme(`rail-${dark?'dark':'light'}`,{name:`rail-${dark?'dark':'light'}`,base:Blockly.Themes.Classic,componentStyles:{workspaceBackgroundColour:dark?'#141c28':'#ffffff',toolboxBackgroundColour:dark?'#1e293b':'#f1f5f9',toolboxForegroundColour:dark?'#e5e7eb':'#172033',flyoutBackgroundColour:dark?'#283448':'#e2e8f0',flyoutForegroundColour:dark?'#e5e7eb':'#172033',flyoutOpacity:1,scrollbarColour:dark?'#64748b':'#94a3b8'},fontStyle:{family:'system-ui, sans-serif',weight:'normal',size:12}});
  const ws=Blockly.inject(host.current,{renderer:'zelos',toolbox,theme,trashcan:true,scrollbars:true,move:{scrollbars:true,drag:true,wheel:true},zoom:{controls:true,wheel:true,startScale:0.8,minScale:0.3,maxScale:1.5},media:'/blockly-media/',sounds:false});workspace.current=ws;workspaceDraftInvalidators.set(ws,()=>{if(!restoring.current)latest.current.onValidityChange?.(false);});ws.undo=(redo)=>historyAction.current(!!redo);
  recoveryBlocked.current=false;parameters.current=copy(latest.current.value.parameters);baseline.current=JSON.stringify(latest.current.value);accepted.current=baseline.current;
  const saved=readProgramBlockDraft(draftScope,baseline.current);if(saved)baseline.current=saved.baseline;let recoveryError='';
  try{load(saved?.snapshot??{workspace:definitionToBlocks(latest.current.value),parameters:parameters.current});}catch(problem){recoveryError=String(problem);recoveryBlocked.current=true;validity.current=false;load({workspace:definitionToBlocks(latest.current.value),parameters:parameters.current});}
  past.current=[];future.current=[];lastSnapshot.current=capture();lastFingerprint.current=fingerprint(lastSnapshot.current);
  let timer:ReturnType<typeof setTimeout>|undefined;
  const listener=(event:Blockly.Events.Abstract)=>{
   if(restoring.current)return;
   if(event.type===Blockly.Events.SELECTED){setSelectedId((event as Blockly.Events.Selected).newElementId??undefined);return;}
   if(event.isUiEvent)return;
   latest.current.onValidityChange?.(false);
   if(timer)clearTimeout(timer);
   timer=setTimeout(()=>{if(!ws.isDragging())sync.current();},0);
  };
  ws.addChangeListener(listener);
  const observer=new ResizeObserver(()=>Blockly.svgResize(ws));observer.observe(host.current);
  const themeObserver=new MutationObserver(()=>{const isDark=document.documentElement.dataset.theme!=='light';ws.setTheme(Blockly.Theme.defineTheme(`rail-live-${isDark?'dark':'light'}`,{name:`rail-live-${isDark?'dark':'light'}`,base:theme,componentStyles:{workspaceBackgroundColour:isDark?'#141c28':'#fff',toolboxBackgroundColour:isDark?'#1e293b':'#f1f5f9',toolboxForegroundColour:isDark?'#e5e7eb':'#172033',flyoutBackgroundColour:isDark?'#283448':'#e2e8f0',flyoutForegroundColour:isDark?'#e5e7eb':'#172033'}}));});themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
  sync.current();if(recoveryError){setError(`${t('programs.blockRecoveryFailed')} ${recoveryError}`);latest.current.onValidityChange?.(false);}
  return()=>{if(timer)clearTimeout(timer);if(!recoveryBlocked.current)storeProgramBlockDraft(draftScope,baseline.current,accepted.current,capture());observer.disconnect();themeObserver.disconnect();ws.removeChangeListener(listener);workspaceDraftInvalidators.delete(ws);ws.dispose();workspace.current=undefined;};
 },[fieldScope,locale,context]);
 useEffect(()=>{
  const json=JSON.stringify(value);if(!workspace.current||json===accepted.current)return;
  // Only authoritative external loads/Undo rebuild; accepted edits preserve loose blocks.
  accepted.current=json;baseline.current=json;past.current=[];future.current=[];load({workspace:definitionToBlocks(value),parameters:value.parameters});lastSnapshot.current=capture();lastFingerprint.current=fingerprint(lastSnapshot.current);sync.current();
 },[value]);
 useEffect(()=>{if(!focusedStep||!workspace.current)return;const block=workspace.current.getAllBlocks(false).find((block)=>block.type.startsWith('rail_')&&blockStepId(block.id)===focusedStep);if(block){block.select();workspace.current.centerOnBlock(block.id);}},[focusedStep]);
 const history=(redo=false)=>{
  if((redo?latest.current.onRedo:latest.current.onUndo)&&validity.current&&(!redo||!future.current.length)){past.current=[];future.current=[];(redo?latest.current.onRedo:latest.current.onUndo)?.();return;}
  const source=redo?future:past;const dest=redo?past:future;const next=source.current.at(-1);if(!next||!workspace.current)return;
  source.current=source.current.slice(0,-1);dest.current=[...dest.current.slice(-99),capture()];load(next);lastSnapshot.current=next;lastFingerprint.current=fingerprint(next);sync.current();
 };
 historyAction.current=history;
 const transaction=(action:()=>void)=>{Blockly.Events.setGroup(true);try{action();}finally{Blockly.Events.setGroup(false);}sync.current();};
 const ws=workspace.current;const selected=selectedId?ws?.getBlockById(selectedId):null;
 const selectedType=selected?.type.slice(5) as StationProgramStep['type']|undefined;
 const changeField=(block:Blockly.Block,name:string,text:string)=>transaction(()=>block.setFieldValue(text,name));
 const add=(type:StationProgramStep['type'])=>{if(!ws)return;transaction(()=>{const state=definitionToBlocks({parameters:[],steps:[defaultProgramStep(type,type==='BRANCH'?conditions[0]:extensions[0])]}).blocks!.blocks[0].next!.block;let connection:Blockly.Connection|null|undefined;if(target==='$entry')connection=ws.getBlockById('$rail-entry')?.nextConnection;else{const [id,arm]=JSON.parse(target) as [string,string];connection=ws.getBlockById(id)?.getInput(arm)?.connection;}if(!connection){setTarget('$entry');return;}const block=Blockly.serialization.blocks.append(state,ws,{recordUndo:true});while(connection.targetBlock())connection=connection.targetBlock()!.nextConnection!;connection.connect(block.previousConnection!);(block as Blockly.BlockSvg).select();});};
 const move=(delta:number)=>{if(!selected||!selected.previousConnection||!ws)return;const before=selected.getPreviousBlock();const after=selected.getNextBlock();const first=delta<0?before:selected;const second=delta<0?selected:after;if(!first||!second||first.type==='rail_entry')return;transaction(()=>{const parent=first.previousConnection!.targetConnection;const tail=second.getNextBlock();tail?.unplug(false);second.unplug(false);first.unplug(false);parent?.connect(second.previousConnection!);second.nextConnection!.connect(first.previousConnection!);if(tail)first.nextConnection!.connect(tail.previousConnection!);});};
 const expressionMode=(parent:Blockly.Block,name:string,type:StationProgramParameterType)=>{
  const child=parent.getInputTargetBlock(name);const mode=child?(child.type.startsWith('rail_parameter_')?'parameter':'literal'):undefined;
  return <Select aria-label={`${t('programs.valueMode')} · ${name}`} value={mode} placeholder={t('programs.blockMissingInput')} options={[{value:'literal',label:t('programs.literal')},...(context==='arrival'?[]:[{value:'parameter',label:t('programs.parameter')}])]} onChange={(nextMode)=>{if(!ws)return;transaction(()=>{child?.dispose(false);const block=ws.newBlock(`rail_${nextMode}_${type}`) as Blockly.BlockSvg;block.initSvg();block.render();if(nextMode==='parameter')block.setFieldValue(parameters.current.find((p)=>p.type===type)?.id??'','VALUE');parent.getInput(name)!.connection!.connect(block.outputConnection!);});}}/>;
 };
 const expressionInput=(block:Blockly.Block)=>{
  const type=parameterTypes.find((type)=>block.type===`rail_literal_${type}`||block.type===`rail_parameter_${type}`);if(!type)return null;
  const raw=String(block.getFieldValue('VALUE')??'');const reference=block.type.startsWith('rail_parameter_');
  return <label key={block.id}>{t(`programs.param.${type}`)}{reference?<Select aria-label={t('programs.parameter')} value={raw||undefined} options={parameters.current.filter((p)=>p.type===type).map((p)=>({value:p.id,label:p.id}))} onChange={(id)=>changeField(block,'VALUE',id)} style={{minWidth:180}}/>:type==='JSON_OBJECT'?<Input.TextArea aria-label={t('programs.jsonParameters')} value={raw} rows={5} onChange={(event)=>changeField(block,'VALUE',event.target.value)}/>:type==='ID_LIST'||type==='UNIT_SLOTS'?<Input aria-label={t(`programs.param.${type}`)} value={raw} onChange={(event)=>changeField(block,'VALUE',event.target.value)}/>:type==='TICK'||type==='DURATION'?<Input aria-label={t(`programs.param.${type}`)} inputMode="numeric" value={raw} onChange={(event)=>changeField(block,'VALUE',event.target.value)}/>:<Input aria-label={t(`programs.param.${type}`)} value={raw} onChange={(event)=>changeField(block,'VALUE',event.target.value)}/>}</label>;
 };
 return <div className="program-block-editor" data-revision={revision} onKeyDownCapture={(event)=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='z'&&!(event.target instanceof HTMLInputElement)&&!(event.target instanceof HTMLTextAreaElement)){event.preventDefault();event.stopPropagation();history(event.shiftKey);}}}>
  {context==='arrival'&&<Alert type="info" title={t('programs.arrivalHint')}/>}
  <Typography.Paragraph type="secondary">{t('programs.blocksHint')}</Typography.Paragraph>
  {context==='library'&&<details className="program-parameters"><summary>{t('programs.parameters')}</summary>{parameters.current.map((parameter,index)=><Flex gap={8} key={index}><Input aria-label={t('programs.parameterId')} value={parameter.id} onChange={(event)=>{parameters.current=parameters.current.map((p,i)=>i===index?{...p,id:event.target.value}:p);sync.current();}}/><Select aria-label={t('programs.parameterType')} value={parameter.type} options={parameterTypes.map((type)=>({value:type,label:t(`programs.param.${type}`)}))} onChange={(type)=>{parameters.current=parameters.current.map((p,i)=>i===index?{...p,type}:p);sync.current();}}/><Button danger aria-label={t('common.delete')} onClick={()=>{parameters.current=parameters.current.filter((_,i)=>i!==index);sync.current();}}>{t('common.delete')}</Button></Flex>)}<Button onClick={()=>{parameters.current=[...parameters.current,{id:`parameter-${parameters.current.length+1}`,type:'DESTINATION'}];sync.current();}}>{t('programs.addParameter')}</Button></details>}
  <Space wrap className="program-block-toolbar"><Button onClick={()=>history()} disabled={!props.onUndo&&!past.current.length}>{t('programs.blockUndo')}</Button><Button onClick={()=>history(true)} disabled={!props.onRedo&&!future.current.length}>{t('programs.blockRedo')}</Button><Select aria-label={t('programs.blockInsertTarget')} value={target} onChange={setTarget} style={{minWidth:160}} options={[{value:'$entry',label:t('programs.blockEntry')},...(ws?.getAllBlocks(false).filter((block)=>block.type==='rail_BRANCH').flatMap((block)=>(['thenSteps','elseSteps'] as const).map((arm)=>({value:JSON.stringify([block.id,arm]),label:`${blockStepId(block.id)} · ${t(`programs.${arm}`)}`})))??[])]}/><Select aria-label={t('programs.addStep')} placeholder={t('programs.addStep')} value={null} style={{minWidth:180}} options={(context==='arrival'?arrivalStepTypes:programStepTypes).map((type)=>({value:type,label:t(`programs.step.${type}`),disabled:target!=='$entry'&&['COUPLE','SPLIT','STORE_AT_DEPOT'].includes(type)}))} onChange={add}/><Button disabled={!selected?.getPreviousBlock()||selected.getPreviousBlock()?.type==='rail_entry'} onClick={()=>move(-1)}>{t('common.moveUp')}</Button><Button disabled={!selected?.nextConnection?.targetBlock()} onClick={()=>move(1)}>{t('common.moveDown')}</Button><Button danger disabled={!selected||selected.type==='rail_entry'} onClick={()=>selected&&transaction(()=>selected.dispose(true))}>{t('common.delete')}</Button></Space>
  {error&&<Alert type="warning" showIcon title={t('programs.invalidDraft')} description={error}/>}
  {recoveryBlocked.current&&<Button danger onClick={()=>{clearProgramBlockDraft(draftScope);recoveryBlocked.current=false;sync.current();}}>{t('programs.discardBlockDraft')}</Button>}
  <div ref={host} className="program-block-canvas" aria-label={t('programs.blockCanvas')}/>
  {selected&&selected.type!=='rail_entry'&&<section className="program-block-inspector"><Typography.Title level={5}>{t('programs.blockInspector')}</Typography.Title>{selectedType&&programStepTypes.includes(selectedType)&&<><Typography.Text code>{blockStepId(selected.id)}</Typography.Text>{(selectedType==='EXTENSION'||selectedType==='BRANCH')&&<><Select aria-label={t(selectedType==='BRANCH'?'programs.condition':'programs.extension')} value={`${selected.getFieldValue('TYPE_ID')}@${selected.getFieldValue('VERSION')}`} options={(selectedType==='BRANCH'?conditions:extensions).map((provider)=>({value:`${provider.typeId}@${provider.version}`,label:`${provider.title} · ${provider.typeId}@${provider.version}`}))} onChange={(value)=>{const provider=(selectedType==='BRANCH'?conditions:extensions).find((p)=>`${p.typeId}@${p.version}`===value);if(provider)transaction(()=>{selected.setFieldValue(provider.typeId,'TYPE_ID');selected.setFieldValue(String(provider.version),'VERSION');});}}/><Typography.Paragraph type="secondary">{(selectedType==='BRANCH'?conditions:extensions).find((p)=>p.typeId===selected.getFieldValue('TYPE_ID'))?.description??t('programs.providerMissing')}</Typography.Paragraph><details><summary>{t('programs.parameterSchema')}</summary><pre>{JSON.stringify((selectedType==='BRANCH'?conditions:extensions).find((p)=>p.typeId===selected.getFieldValue('TYPE_ID')&&String(p.version)===String(selected.getFieldValue('VERSION')))?.parameterSchema??{},null,2)}</pre></details></>}{Object.entries(expressionFields[selectedType]??{}).map(([name,type])=>{const child=selected.getInputTargetBlock(name);return <div key={name}><Typography.Text>{t(`programs.${name}` as Parameters<typeof t>[0])}</Typography.Text>{expressionMode(selected,name,type)}{child?expressionInput(child):<Typography.Text type="warning">{t('programs.blockMissingInput')}</Typography.Text>}</div>;})}</>}{expressionInput(selected)}</section>}
 </div>;
}
