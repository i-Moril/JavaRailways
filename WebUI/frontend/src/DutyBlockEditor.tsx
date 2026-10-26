import {useEffect,useRef,useState} from 'react';
import {Alert,Button,Space} from 'antd';
import {UndoOutlined,RedoOutlined} from '@ant-design/icons';
import * as Blockly from 'blockly/core';
import * as zh from 'blockly/msg/zh-hans';
import * as en from 'blockly/msg/en';
import type {AuthoringDutyElementV1} from './authoring';
import type {BoundStationProgram} from './types';
import {clearDutyBlockDraft,dutyBlockElement,dutyBlockId,loadDutyBlockElement,dutyBlocksToElements,dutyElementId,dutyElementLabel,dutyElementsToBlocks,dutyWorkspacePrograms,readDutyBlockDraft,storeDutyBlockDraft,type DutyBlockState,type DutyWorkspaceState} from './dutyBlocks';
import {useI18n} from './i18n';
import './ProgramBlockEditor.css';

export interface DutyBlockTemplate {label:string;element:AuthoringDutyElementV1;programSnapshot?:BoundStationProgram}
interface DutyPayloadBlock extends Blockly.BlockSvg {dutyElement?:AuthoringDutyElementV1;programSnapshot?:BoundStationProgram}
interface Props {templates?:DutyBlockTemplate[];elements:AuthoringDutyElementV1[];onChange:(elements:AuthoringDutyElementV1[],snapshots:BoundStationProgram[])=>void;onSelect:(id:string)=>void;scope:string;draftScope?:string;onValidityChange?:(valid:boolean)=>void;onUndo?:()=>void;onRedo?:()=>void}
/** Typed toolbox blocks retain their complete payload in incomplete local drafts. */
export function DutyBlockEditor(props:Props){
 const {elements,scope,draftScope=scope}=props,{t,locale}=useI18n();
 const host=useRef<HTMLDivElement>(null),workspace=useRef<Blockly.WorkspaceSvg|undefined>(undefined),latest=useRef(props);latest.current=props;
 const accepted=useRef(JSON.stringify(elements)),baseline=useRef(accepted.current),lastDomain=useRef(elements);
 const past=useRef<DutyWorkspaceState[]>([]),future=useRef<DutyWorkspaceState[]>([]),last=useRef<DutyWorkspaceState|undefined>(undefined);
 const deletedIds=useRef(new Set<string>());
 const valid=useRef(true),recoverBlocked=useRef(false),restoring=useRef(false),sync=useRef<()=>void>(()=>{}),historyAction=useRef<(redo:boolean)=>void>(()=>{});
 const [error,setError]=useState(''),[revision,setRevision]=useState(0);
 const toolbox=(templates:DutyBlockTemplate[]):Blockly.utils.toolbox.ToolboxDefinition=>({kind:'categoryToolbox',contents:[
  {kind:'category',name:t('duties.runs'),colour:"210",contents:templates.filter((item)=>item.element.type==='RUN').map(templateBlock)},
  {kind:'category',name:t('programs.call'),colour:"270",contents:templates.filter((item)=>item.element.type==='INLINE'||item.element.type==='PROGRAM').map(templateBlock)},
  {kind:'category',name:t('ui.function'),colour:"160",contents:templates.filter((item)=>item.element.type==='FUNCTION').map(templateBlock)},
 ]});
 function templateBlock(template:DutyBlockTemplate){return {kind:'block',type:`rail_duty_${template.element.type}`,fields:{LABEL:template.label},extraState:{element:template.element,programSnapshot:template.programSnapshot}};}
 const capture=()=>Blockly.serialization.workspaces.save(workspace.current!) as DutyWorkspaceState;
 const signature=(state:DutyWorkspaceState)=>JSON.stringify(state,(key,value)=>key==='x'||key==='y'?undefined:value);
 const load=(state:DutyWorkspaceState)=>{if(!state?.blocks)throw new Error('Malformed duty workspace');restoring.current=true;Blockly.Events.disable();try{Blockly.serialization.workspaces.load(state,workspace.current!);for(const element of latest.current.elements)workspace.current!.getBlockById(dutyBlockId(element.id))?.setFieldValue(element.type==='FUNCTION'?element.call.type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${element.call.type}`):dutyElementLabel(element),'LABEL');}finally{Blockly.Events.enable();restoring.current=false;}};
 sync.current=()=>{
  if(!workspace.current||restoring.current||recoverBlocked.current)return;
  const snapshot=capture();if(last.current&&signature(last.current)!==signature(snapshot)){past.current=[...past.current.slice(-99),last.current];future.current=[];}last.current=snapshot;
  try{const ordered=dutyBlocksToElements(snapshot,latest.current.elements,deletedIds.current),json=JSON.stringify(ordered);valid.current=true;setError('');latest.current.onValidityChange?.(true);if(json!==accepted.current){latest.current.onChange(ordered,dutyWorkspacePrograms(snapshot));accepted.current=json;lastDomain.current=ordered;const selected=Blockly.common.getSelected();if(selected instanceof Blockly.BlockSvg&&selected.workspace===workspace.current){const id=(selected as DutyPayloadBlock).dutyElement?.id;if(id&&ordered.some((element)=>element.id===id))latest.current.onSelect(id);}}}
  catch(problem){valid.current=false;setError(problem instanceof Error?problem.message:String(problem));latest.current.onValidityChange?.(false);}
  storeDutyBlockDraft(draftScope,baseline.current,accepted.current,snapshot,deletedIds.current);setRevision((n)=>n+1);
 };
 useEffect(()=>{
  if(!host.current)return;
  Blockly.setLocale(Object.fromEntries(Object.entries(locale==='zh-CN'?zh:en).filter((entry):entry is [string,string]=>typeof entry[1]==='string')));
  Blockly.Blocks.rail_duty_entry={init(this:Blockly.Block){this.appendDummyInput().appendField(t('duties.duties'));this.setNextStatement(true,'DutyElement');this.setColour(45);this.setDeletable(false);this.setMovable(false);}};
  for(const type of ['RUN','FUNCTION','PROGRAM','INLINE'])Blockly.Blocks[`rail_duty_${type}`]={
   init(this:DutyPayloadBlock){
    this.appendDummyInput().appendField(type==='RUN'?t('duties.runs'):type==='INLINE'?t('duties.inlineProgram'):type==='PROGRAM'?t('programs.call'):t('ui.function')).appendField(new Blockly.FieldLabelSerializable(''),'LABEL');
    this.setPreviousStatement(true,'DutyElement');this.setNextStatement(true,'DutyElement');this.setColour(type==='RUN'?210:type==='PROGRAM'||type==='INLINE'?270:160);
    this.customContextMenu=(options)=>{options.length=0;options.push({text:t('common.delete'),enabled:true,callback:()=>{Blockly.Events.setGroup(true);try{this.dispose(true);}finally{Blockly.Events.setGroup(false);}}});};
   },
   saveExtraState(this:DutyPayloadBlock){const element=latest.current.elements.find((element)=>element.id===this.dutyElement?.id)??this.dutyElement;return element?{element,blockId:this.id,programSnapshot:this.programSnapshot}:undefined;},
   loadExtraState(this:DutyPayloadBlock,state:{element?:AuthoringDutyElementV1;blockId?:string;programSnapshot?:BoundStationProgram}){
    if(!state?.element)return;
    this.programSnapshot=state.programSnapshot;
    this.dutyElement=loadDutyBlockElement({...state,element:state.element},this.id,this.workspace.isFlyout,[...latest.current.elements.map((element)=>element.id),...this.workspace.getAllBlocks(false).map((block)=>(block as DutyPayloadBlock).dutyElement?.id??'')]);
    this.setFieldValue(this.dutyElement.type==='FUNCTION'?this.dutyElement.call.type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${this.dutyElement.call.type}`):dutyElementLabel(this.dutyElement),'LABEL');
   },
  };

  const dark=document.documentElement.dataset.theme!=='light';const theme=Blockly.Theme.defineTheme(`duty-${dark?'dark':'light'}`,{name:`duty-${dark?'dark':'light'}`,base:Blockly.Themes.Classic,componentStyles:{workspaceBackgroundColour:dark?'#141c28':'#fff',scrollbarColour:dark?'#64748b':'#94a3b8'}});
  const ws=Blockly.inject(host.current,{renderer:'zelos',theme,scrollbars:true,sounds:false,media:'/blockly-media/',move:{drag:true,wheel:true,scrollbars:true},zoom:{controls:false,wheel:true,startScale:0.85},toolbox:toolbox(latest.current.templates??[])});workspace.current=ws;ws.undo=(redo)=>historyAction.current(!!redo);
  baseline.current=JSON.stringify(latest.current.elements);accepted.current=baseline.current;lastDomain.current=latest.current.elements;recoverBlocked.current=false;
  const draft=readDutyBlockDraft(draftScope,latest.current.elements);deletedIds.current=new Set(draft?.deletedIds??[]);if(draft)baseline.current=draft.baseline;
  try{load(draft?.workspace??dutyElementsToBlocks(latest.current.elements));}catch{recoverBlocked.current=true;valid.current=false;load(dutyElementsToBlocks(latest.current.elements));setError(t('programs.blockRecoveryFailed'));latest.current.onValidityChange?.(false);}
  past.current=[];future.current=[];last.current=capture();let timer:ReturnType<typeof setTimeout>|undefined;
  const listener=(event:Blockly.Events.Abstract)=>{
   if(restoring.current)return;
   if(event.type===Blockly.Events.SELECTED){const id=(event as Blockly.Events.Selected).newElementId,elementId=id?((ws.getBlockById(id) as DutyPayloadBlock|null)?.dutyElement?.id??dutyElementId(id)):undefined;if(elementId&&latest.current.elements.some((element)=>element.id===elementId))latest.current.onSelect(elementId);return;}
   if(event.isUiEvent)return;
   if(event.type===Blockly.Events.BLOCK_DELETE){
    const remember=(block:DutyBlockState|undefined)=>{if(!block)return;const id=block.id&&dutyElementId(block.id)||dutyBlockElement(block)?.id;if(id)deletedIds.current.add(id);remember(block.next?.block);};
    remember((event as Blockly.Events.BlockDelete).oldJson as DutyBlockState|undefined);
   }
   latest.current.onValidityChange?.(false);
   if(timer)clearTimeout(timer);const settle=()=>{if(ws.isDragging()){timer=setTimeout(settle,25);return;}sync.current();};timer=setTimeout(settle,0);
  };
  ws.addChangeListener(listener);const resize=new ResizeObserver(()=>Blockly.svgResize(ws));resize.observe(host.current);
  const themeObserver=new MutationObserver(()=>{const darkNow=document.documentElement.dataset.theme!=='light';ws.setTheme(Blockly.Theme.defineTheme(`duty-live-${darkNow?'dark':'light'}`,{name:`duty-live-${darkNow?'dark':'light'}`,base:theme,componentStyles:{workspaceBackgroundColour:darkNow?'#141c28':'#fff',scrollbarColour:darkNow?'#64748b':'#94a3b8'}}));});themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
  sync.current();
  return()=>{if(timer)clearTimeout(timer);if(!recoverBlocked.current)storeDutyBlockDraft(draftScope,baseline.current,accepted.current,capture(),deletedIds.current);resize.disconnect();themeObserver.disconnect();ws.removeChangeListener(listener);ws.dispose();workspace.current=undefined;};
 },[scope,draftScope,locale]);
 useEffect(()=>{
  const ws=workspace.current,json=JSON.stringify(elements);if(!ws||json===accepted.current)return;
  const canvasIds=new Map(ws.getAllBlocks(false).map((block)=>[(block as DutyPayloadBlock).dutyElement?.id??dutyElementId(block.id),block]));
  const sameOrder=elements.length===lastDomain.current.length&&elements.every((element,index)=>element.id===lastDomain.current[index].id&&element.type===lastDomain.current[index].type);
  if(sameOrder){Blockly.Events.disable();try{for(const element of elements)canvasIds.get(element.id)?.setFieldValue(element.type==='FUNCTION'?element.call.type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${element.call.type}`):dutyElementLabel(element),'LABEL');}finally{Blockly.Events.enable();}}
  else if(!valid.current){
   // External additions/removals must not erase incomplete connections already on the canvas.
   Blockly.Events.disable();try{
    const previouslyOwned=new Set(lastDomain.current.map((element)=>element.id));
    for(const block of ws.getAllBlocks(false)){const id=(block as DutyPayloadBlock).dutyElement?.id??dutyElementId(block.id);if(id&&previouslyOwned.has(id)&&!elements.some((element)=>element.id===id))block.dispose(true);}
    const entry=ws.getBlockById('$duty-entry');let connection=entry?.nextConnection;
    while(connection?.targetBlock())connection=connection.targetBlock()!.nextConnection;
    for(const element of elements){let block=canvasIds.get(element.id) as Blockly.BlockSvg|undefined;
     if(!block){block=ws.newBlock(`rail_duty_${element.type}`,dutyBlockId(element.id)) as Blockly.BlockSvg;(block as DutyPayloadBlock).dutyElement=element;block.initSvg();block.render();connection?.connect(block.previousConnection!);connection=block.nextConnection;}
     block.setFieldValue(element.type==='FUNCTION'?element.call.type==='PLACE_INITIAL'?t('ui.placeInitial'):t(`actions.${element.call.type}`):dutyElementLabel(element),'LABEL');
    }
   }finally{Blockly.Events.enable();}
  }
  else{load(dutyElementsToBlocks(elements));past.current=[];future.current=[];last.current=capture();}
  accepted.current=json;lastDomain.current=elements;sync.current();
 },[elements]);
 useEffect(()=>{workspace.current?.updateToolbox(toolbox(props.templates??[]));},[JSON.stringify(props.templates),locale]);
 const history=(redo=false)=>{
  if(valid.current&&(redo?props.onRedo:props.onUndo)&&(!redo||!future.current.length)){past.current=[];future.current=[];(redo?props.onRedo:props.onUndo)?.();return;}
  const source=redo?future:past,destination=redo?past:future,next=source.current.at(-1);if(!next||!workspace.current)return;
  source.current=source.current.slice(0,-1);destination.current=[...destination.current.slice(-99),capture()];load(next);last.current=next;sync.current();
 };historyAction.current=history;
 return <section data-duty-workspace-revision={revision} onKeyDownCapture={(event)=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='z'&&!(event.target instanceof HTMLInputElement)&&!(event.target instanceof HTMLTextAreaElement)){event.preventDefault();event.stopPropagation();history(event.shiftKey);}}}>
  <Space className="program-block-toolbar"><Button aria-label={t('programs.blockUndo')} title={t('programs.blockUndo')} icon={<UndoOutlined/>} onClick={()=>history()} disabled={!props.onUndo&&!past.current.length}/><Button aria-label={t('programs.blockRedo')} title={t('programs.blockRedo')} icon={<RedoOutlined/>} onClick={()=>history(true)} disabled={!props.onRedo&&!future.current.length}/></Space>
  {error&&<Alert type="warning" title={t('programs.invalidDraft')} description={error}/>}
  {error&&<Button danger onClick={()=>{clearDutyBlockDraft(draftScope);recoverBlocked.current=false;deletedIds.current.clear();load(dutyElementsToBlocks(latest.current.elements));past.current=[];future.current=[];last.current=capture();sync.current();}}>{t('programs.discardBlockDraft')}</Button>}
  <div className="program-block-canvas" ref={host} aria-label={t('duties.duties')}/>
 </section>;
}
