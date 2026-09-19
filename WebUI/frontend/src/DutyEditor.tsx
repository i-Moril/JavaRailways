import { useEffect,useMemo,useRef,useState,type CSSProperties } from 'react';
import { Button,Flex,Input,InputNumber,Modal,Popover,Segmented,Select,Space,Tag,Tooltip,Typography } from 'antd';
import { DeleteOutlined,EditOutlined,LeftOutlined,PlusOutlined,RightOutlined,SettingOutlined } from '@ant-design/icons';
import { api } from './api';
import { frontendDemoEnabled } from './frontendDev';
import {
  authoringGraphTimetable,authoringSelectorKey,createRunFromLine,deleteRunFromDocument,emptyAuthoringDocument,generateTimetableSource,moveRunFromCall,moveRunToDuty,
  type AuthoringDutyElementV1,type AuthoringDutyV1,type AuthoringFunctionCallV1,type AuthoringRunV1,type RunMoveEvent,type RunMoveMode,
  type TimetableAuthoringDocumentV1,
} from './authoring';
import { clearAuthoringRecovery,loadAuthoringRecovery,saveAuthoringRecovery } from './authoringRecovery';
import { useDocumentHistory } from './documentHistory';
import { Graph } from './Graph';
import { RunRouteEditor } from './RunRouteEditor';
import { TimetableWorkspace } from './TimetableWorkspace';
import { useI18n } from './i18n';
import { formatTimetableTick } from './timetableTime';
import type { CompiledTimetableSource,EditorDocumentSummary,LineV1,TimetableSimulationResult,WorldMetadata } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const hash=(value:string)=>{let result=2166136261;for(let index=0;index<value.length;index++){result^=value.charCodeAt(index);result=Math.imul(result,16777619);}return (result>>>0).toString(16).padStart(8,'0');};
const defaultScriptName=(id:string)=>`${(id.replace(/[^A-Za-z0-9._-]/g,'-').replace(/^[^A-Za-z0-9]+/,'')||'timetable').slice(0,120)}.js`;
export const clampAuthoringSidebarWidth=(width:number,viewportWidth:number)=>Math.max(360,Math.min(width,960,Math.max(360,viewportWidth-320)));

export function DutyEditor({world,recoveryOwner,lines,metadata,onError}:{world:string;recoveryOwner:string;lines:LineV1[];metadata:WorldMetadata;onError:(message:string)=>void}) {
  const {locale,t}=useI18n();
  const [initialRecovery]=useState(()=>loadAuthoringRecovery(recoveryOwner,world));
  const initialDocument=initialRecovery?.document??emptyAuthoringDocument();
  const savedDocumentJson=useRef(initialRecovery?.savedDocumentJson??JSON.stringify(initialDocument));
  const [workspaceWorld,setWorkspaceWorld]=useState(world);
  const [documents,setDocuments]=useState<EditorDocumentSummary[]>([]);
  const [documentId,setDocumentId]=useState(initialRecovery?.documentId??'');
  const [formalScriptName,setFormalScriptName]=useState(initialRecovery?.formalScriptName??'new-timetable.js');
  const [persistedFormalScriptName,setPersistedFormalScriptName]=useState(initialRecovery?.persistedFormalScriptName??'');
  const documentHistory=useDocumentHistory<TimetableAuthoringDocumentV1>(()=>initialDocument);
  const {value,setValue}=documentHistory;
  const [selectedRun,setSelectedRun]=useState(initialRecovery?.selectedRun??'');
  const [previewRun,setPreviewRun]=useState<AuthoringRunV1>();
  const [moveOpen,setMoveOpen]=useState(false);
  const [moveCallId,setMoveCallId]=useState('');
  const [moveEvent,setMoveEvent]=useState<RunMoveEvent>('DEPARTURE');
  const [moveMode,setMoveMode]=useState<RunMoveMode>('OFFSET');
  const [moveValue,setMoveValue]=useState<number|''>(0);
  const [selectedDuty,setSelectedDuty]=useState(initialRecovery?.selectedDuty??'');
  const [sidebarTab,setSidebarTab]=useState<'runs'|'duties'>('runs');
  const [runLibraryOpen,setRunLibraryOpen]=useState(true);
  const [runSearch,setRunSearch]=useState('');
  const [runDirection,setRunDirection]=useState<'UP'|'DOWN'>('UP');
  const [sidebarOpen,setSidebarOpen]=useState(true);
  const [sidebarWidth,setSidebarWidth]=useState(()=>{
    if(typeof window==='undefined')return 520;
    const saved=Number(window.localStorage.getItem('javarailways.authoringSidebarWidthV2'));
    return clampAuthoringSidebarWidth(Number.isFinite(saved)&&saved>0?saved:520,window.innerWidth);
  });
  const sidebarResize=useRef<{startX:number;startWidth:number}|null>(null);
  const demoAutoloadAttempted=useRef(false);
  const [graphLineId,setGraphLineId]=useState(initialRecovery?.graphLineId??'');
  const [compiledState,setCompiledState]=useState<{result:CompiledTimetableSource;documentHashInput:string}>();
  const [busy,setBusy]=useState(false);
  const [profileAssignments,setProfileAssignments]=useState<Record<string,string>>(initialRecovery?.profileAssignments??{});
  const [delayThresholdTicks,setDelayThresholdTicks]=useState(initialRecovery?.delayThresholdTicks??1_200);
  const [simulationState,setSimulationState]=useState<{result:TimetableSimulationResult;inputKey:string}>();
  const generated=useMemo(()=>generateTimetableSource(value),[value]);
  const compiled=compiledState?.documentHashInput===generated.documentHashInput?compiledState.result:undefined;
  const simulationInputKey=JSON.stringify([generated.documentHashInput,delayThresholdTicks,Object.entries(profileAssignments).sort(([a],[b])=>a.localeCompare(b))]);
  const simulation=simulationState?.inputKey===simulationInputKey?simulationState.result:undefined;
  const simulationProfiles=metadata.simulationProfiles||[];
  const profilesBySelector=new Map<string,string>();
  let hasConflictingProfiles=false;
  value.duties.forEach((duty)=>{const selected=profileAssignments[duty.id],key=authoringSelectorKey(duty.selector),existing=profilesBySelector.get(key);if(selected&&existing&&selected!==existing)hasConflictingProfiles=true;else if(selected)profilesBySelector.set(key,selected);});
  const hasAllProfiles=value.duties.every((duty)=>profileAssignments[duty.id])&&!hasConflictingProfiles;
  const assigned=new Map(value.duties.flatMap((duty)=>duty.elements.flatMap((element)=>element.type==='RUN'?[[element.runId,duty.id] as const]:[])));
  const effectiveRuns=useMemo(()=>value.runs.map((run)=>({...run,calls:run.calls.map((call)=>{
    const override=value.arrivalOverrides.find((item)=>item.runId===run.id&&item.callId===call.id);
    return override?{...call,arrivalTick:override.tick}:call;
  })})),[value.runs,value.arrivalOverrides]);
  const selectedRunValue=effectiveRuns.find((run)=>run.id===selectedRun);
  const stationNames=useMemo(()=>new Map((metadata.stations??[]).map((station)=>[station.stationId,station.stationDisplayName||station.stationId])),[metadata.stations]);
  const moveCalculation=useMemo(()=>{
    if(!selectedRunValue||!moveCallId||moveValue==='')return {preview:undefined,error:''};
    try{return {preview:moveRunFromCall(selectedRunValue,moveCallId,moveEvent,moveMode,moveValue,value.periodDays*24_000),error:''};}
    catch(error){return {preview:undefined,error:error instanceof Error?error.message:String(error)};}
  },[selectedRunValue,moveCallId,moveEvent,moveMode,moveValue,value.periodDays]);
  const selectedDutyValue=value.duties.find((duty)=>duty.id===selectedDuty);
  const graphRuns=useMemo(()=>effectiveRuns.map((run)=>previewRun?.id===run.id?previewRun:run),[effectiveRuns,previewRun]);
  const fullGraphValue=useMemo(()=>authoringGraphTimetable({...value,runs:graphRuns}),[value,graphRuns]);
  const graphValue=useMemo(()=>({...fullGraphValue,services:fullGraphValue.services.filter((service)=>!graphLineId||service.diagramLineId===graphLineId)}),[fullGraphValue,graphLineId]);
  const lineRuns=useMemo(()=>value.runs.filter((run)=>run.diagramLineId===graphLineId&&run.lineDirection===runDirection&&run.id.toLowerCase().includes(runSearch.trim().toLowerCase())),[value.runs,graphLineId,runDirection,runSearch]);
  const highlightedRunIds=useMemo(()=>new Set(sidebarTab==='duties'
    ?selectedDutyValue?.elements.flatMap((element)=>element.type==='RUN'?[element.runId]:[])??[]
    :selectedRun?[selectedRun]:[]),[sidebarTab,selectedDutyValue,selectedRun]);
  const previewRunIds=useMemo(()=>new Set(previewRun?[previewRun.id]:[]),[previewRun]);
  const authoringWorkspaceStyle={'--authoring-sidebar-width':`${sidebarWidth}px`} as CSSProperties;
  const delayedOrigin=simulation?.delayedServiceId==null||simulation.delayedStepIndex==null?undefined:compiled?.timetable.compilationTrace?.find((trace)=>trace.serviceId===simulation.delayedServiceId&&trace.stepIndex===simulation.delayedStepIndex)?.origin;
  const persistRecovery=()=>{
    if(workspaceWorld!==world)return;
    if(JSON.stringify(value)===savedDocumentJson.current){clearAuthoringRecovery(recoveryOwner,world);return;}
    saveAuthoringRecovery({owner:recoveryOwner,world,documentId,formalScriptName,persistedFormalScriptName,document:value,savedDocumentJson:savedDocumentJson.current,selectedRun,selectedDuty,sidebarTab,graphLineId,profileAssignments,delayThresholdTicks});
  };

  const refresh=async()=>setDocuments(await api.editorDocuments(world));
  useEffect(()=>{if(typeof window!=='undefined')window.localStorage.setItem('javarailways.authoringSidebarWidthV2',String(sidebarWidth));},[sidebarWidth]);
  useEffect(()=>{setPreviewRun(undefined);},[selectedRun]);
  useEffect(()=>{setPreviewRun(moveOpen?moveCalculation.preview?.run:undefined);},[moveOpen,moveCalculation.preview]);
  useEffect(()=>{if(!graphLineId&&lines[0])setGraphLineId(lines[0].id);},[graphLineId,lines]);
  useEffect(()=>{if(initialRecovery)onError(t('duties.recoveredLocalDraft'));},[]);
  useEffect(()=>{const timer=window.setTimeout(persistRecovery,250);return()=>window.clearTimeout(timer);},[world,workspaceWorld,value,documentId,formalScriptName,persistedFormalScriptName,selectedRun,selectedDuty,sidebarTab,graphLineId,profileAssignments,delayThresholdTicks]);
  useEffect(()=>{const preserve=()=>persistRecovery();window.addEventListener('javarailways:unauthorized',preserve);window.addEventListener('beforeunload',preserve);return()=>{preserve();window.removeEventListener('javarailways:unauthorized',preserve);window.removeEventListener('beforeunload',preserve);};},[world,workspaceWorld,value,documentId,formalScriptName,persistedFormalScriptName,selectedRun,selectedDuty,sidebarTab,graphLineId,profileAssignments,delayThresholdTicks]);
  useEffect(()=>{
    if(workspaceWorld!==world){
      const recovered=loadAuthoringRecovery(recoveryOwner,world),document=recovered?.document??emptyAuthoringDocument();
      savedDocumentJson.current=recovered?.savedDocumentJson??JSON.stringify(document);documentHistory.reset(document);
      setDocumentId(recovered?.documentId??'');setFormalScriptName(recovered?.formalScriptName??'new-timetable.js');setPersistedFormalScriptName(recovered?.persistedFormalScriptName??'');
      setSelectedRun(recovered?.selectedRun??'');setSelectedDuty(recovered?.selectedDuty??'');setSidebarTab('runs');setGraphLineId(recovered?.graphLineId??'');
      setProfileAssignments(recovered?.profileAssignments??{});setDelayThresholdTicks(recovered?.delayThresholdTicks??1_200);setWorkspaceWorld(world);
      if(recovered)onError(t('duties.recoveredLocalDraft'));
    }
    setPreviewRun(undefined);setCompiledState(undefined);setSimulationState(undefined);
    if(world)void refresh().catch((error)=>onError(String(error)));
  },[world]);
  const execute=async(operation:()=>Promise<void>)=>{setBusy(true);onError('');try{await operation();}catch(error){onError(error instanceof Error?error.message:String(error));}finally{setBusy(false);}};
  const load=async(id:string)=>execute(async()=>{
    setDocumentId(id);setCompiledState(undefined);setSimulationState(undefined);
    if(!id){const empty=emptyAuthoringDocument();savedDocumentJson.current=JSON.stringify(empty);clearAuthoringRecovery(recoveryOwner,world);documentHistory.reset(empty);setFormalScriptName(defaultScriptName(empty.id));setPersistedFormalScriptName('');setSelectedRun('');setSelectedDuty('');return;}
    const loaded=await api.editorDocument(world,id);
    const loadedScriptName=loaded.formalScriptName||defaultScriptName(loaded.id);
    savedDocumentJson.current=JSON.stringify(loaded);clearAuthoringRecovery(recoveryOwner,world);documentHistory.reset(loaded);setFormalScriptName(loadedScriptName);setPersistedFormalScriptName(loaded.formalScriptName||'');setSelectedRun(loaded.runs[0]?.id||'');setSelectedDuty(loaded.duties[0]?.id||'');
  });
  useEffect(()=>{
    if(!frontendDemoEnabled||demoAutoloadAttempted.current||initialRecovery||documentId||!documents[0])return;
    demoAutoloadAttempted.current=true;void load(documents[0].id);
  },[documents,documentId,world]);
  const save=async()=>execute(async()=>{if(documentId)await api.saveEditorDocument(world,documentId,value);else{const created=await api.createEditorDocument(world,value);setDocumentId(created.id);}savedDocumentJson.current=JSON.stringify(value);clearAuthoringRecovery(recoveryOwner,world);await refresh();});
  const saveFormalScript=async()=>execute(async()=>{
    if(persistedFormalScriptName&&formalScriptName!==persistedFormalScriptName)throw new Error(t('duties.renameFirst'));
    const document={...value,formalScriptName};
    let targetDocumentId=documentId;
    if(!targetDocumentId){const created=await api.createEditorDocument(world,document);targetDocumentId=created.id;setDocumentId(created.id);}
    try{
      await api.saveFormalScript(world,targetDocumentId,formalScriptName,generated.source,document,false);
    }catch(error){
      const message=error instanceof Error?error.message:String(error);
      if(!message.includes('already exists')||!window.confirm(t('duties.confirmOverwrite')))throw error;
      await api.saveFormalScript(world,targetDocumentId,formalScriptName,generated.source,document,true);
    }
    savedDocumentJson.current=JSON.stringify(document);clearAuthoringRecovery(recoveryOwner,world);setValue(document);setPersistedFormalScriptName(formalScriptName);await refresh();
  });
  const renameFormalScript=async()=>execute(async()=>{
    if(!documentId||!persistedFormalScriptName||formalScriptName===persistedFormalScriptName)return;
    const document={...value,formalScriptName};
    await api.renameFormalScript(world,documentId,persistedFormalScriptName,formalScriptName,document);
    savedDocumentJson.current=JSON.stringify(document);clearAuthoringRecovery(recoveryOwner,world);setValue(document);setPersistedFormalScriptName(formalScriptName);await refresh();
  });
  const deleteFormalScript=async()=>{if(!documentId||!persistedFormalScriptName||!window.confirm(t('duties.confirmDeleteScript')))return;await execute(async()=>{
    await api.deleteFormalScript(world,documentId,persistedFormalScriptName);
    const empty=emptyAuthoringDocument();savedDocumentJson.current=JSON.stringify(empty);clearAuthoringRecovery(recoveryOwner,world);setDocumentId('');documentHistory.reset(empty);setFormalScriptName(defaultScriptName(empty.id));setPersistedFormalScriptName('');setSelectedRun('');setSelectedDuty('');setCompiledState(undefined);setSimulationState(undefined);await refresh();
  });};
  const remove=async()=>{if(!documentId)return;await execute(async()=>{await api.deleteEditorDocument(world,documentId);const empty=emptyAuthoringDocument();savedDocumentJson.current=JSON.stringify(empty);clearAuthoringRecovery(recoveryOwner,world);setDocumentId('');documentHistory.reset(empty);setSelectedRun('');setSelectedDuty('');setCompiledState(undefined);setSimulationState(undefined);await refresh();});};
  const compile=async()=>execute(async()=>{
    if(generated.issues.some((item)=>item.severity==='error')){setCompiledState(undefined);return;}
    const documentHashInput=generated.documentHashInput;
    const result=await api.compileSource(world,value.id,generated.source,hash(documentHashInput));
    setCompiledState({result,documentHashInput});
  });
  const publish=async()=>{if(!compiled?.validation.valid)return;await execute(async()=>{await api.publish(world,compiled.timetable);});};
  const simulate=async()=>{if(!compiled?.validation.valid||!hasAllProfiles)return;await execute(async()=>{
    const assignments=value.duties.map((duty)=>{
      const selected=profileAssignments[duty.id],separator=selected.indexOf(':');
      const profileType=selected.slice(0,separator) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',profileId=selected.slice(separator+1);
      return {serviceId:duty.id,profileType,profileId};
    });
    const result=await api.simulateTimetable(world,compiled.timetable,assignments,delayThresholdTicks);
    setSimulationState({result,inputKey:simulationInputKey});
  });};
  const replaceRun=(next:AuthoringRunV1,clearArrivalOverrides=false)=>{setPreviewRun(undefined);setValue((current)=>{
    const sourceLine=lines.find((line)=>line.id===next.diagramLineId);
    const diagramLines=sourceLine?[...current.diagramLines.filter((line)=>line.id!==sourceLine.id),{id:sourceLine.id,upStationIds:sourceLine.upStationIds,downStationIds:sourceLine.downStationIds}]:current.diagramLines;
    return {...current,diagramLines,runs:current.runs.map((run)=>run.id===next.id?next:run),arrivalOverrides:clearArrivalOverrides?current.arrivalOverrides.filter((override)=>override.runId!==next.id):current.arrivalOverrides};
  });};
  const renameRun=(previousId:string,nextId:string)=>{setValue((current)=>({...current,
    runs:current.runs.map((run)=>run.id===previousId?{...run,id:nextId}:run),
    duties:current.duties.map((duty)=>({...duty,elements:duty.elements.map((element)=>element.type==='RUN'&&element.runId===previousId?{...element,runId:nextId}:element)})),
    arrivalOverrides:current.arrivalOverrides.map((override)=>override.runId===previousId?{...override,runId:nextId}:override),
  }));setSelectedRun(nextId);};
  const addCopiedRuns=(copies:AuthoringRunV1[])=>{if(!copies.length)return;setValue((current)=>({...current,runs:[...current.runs,...copies]}));setSelectedRun(copies.at(-1)!.id);};
  const patchDuty=(id:string,patch:Partial<AuthoringDutyV1>)=>setValue((current)=>({...current,duties:current.duties.map((duty)=>duty.id===id?{...duty,...patch}:duty)}));
  const patchFunction=(elementId:string,patch:Partial<AuthoringFunctionCallV1>)=>{
    if(!selectedDutyValue)return;
    patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.map((element)=>
      element.id===elementId&&element.type==='FUNCTION'?{...element,call:{...element.call,...patch} as AuthoringFunctionCallV1}:element)});
  };
  const moveElement=(elementId:string,delta:number)=>{
    if(!selectedDutyValue)return;
    const elements=[...selectedDutyValue.elements],from=elements.findIndex((item)=>item.id===elementId),to=Math.max(0,Math.min(elements.length-1,from+delta));
    if(from<0||from===to)return;
    const [element]=elements.splice(from,1);elements.splice(to,0,element);patchDuty(selectedDutyValue.id,{elements});
  };
  const addRun=()=>{const id=`run-${value.runs.length+1}`;const selectedLine=lines.find((line)=>line.id===graphLineId)??lines[0];const run:AuthoringRunV1=selectedLine?createRunFromLine(id,selectedLine,'UP',metadata,()=>uid('call')):{id,type:'PASSENGER',diagramLineId:null,lineDirection:'UP',calls:[]};setValue((current)=>({...current,runs:[...current.runs,run],diagramLines:selectedLine?[...current.diagramLines.filter((line)=>line.id!==selectedLine.id),{id:selectedLine.id,upStationIds:selectedLine.upStationIds,downStationIds:selectedLine.downStationIds}]:current.diagramLines}));setSelectedRun(id);};
  const openMove=()=>{
    if(!selectedRunValue){onError(t('duties.selectRun'));return;}
    const anchor=selectedRunValue.calls.find((call)=>call.departureTick!=null||call.arrivalTick!=null);
    if(!anchor){onError(t('move.noTimedCall'));return;}
    setMoveCallId(anchor.id);setMoveEvent(anchor.departureTick!=null?'DEPARTURE':'ARRIVAL');setMoveMode('OFFSET');setMoveValue(0);setMoveOpen(true);
  };
  const closeMove=()=>{setMoveOpen(false);setPreviewRun(undefined);};
  const applyMove=()=>{
    if(!moveCalculation.preview)return;
    replaceRun(moveCalculation.preview.run,true);closeMove();
  };
  const deleteSelectedRun=()=>{if(!selectedRun||!window.confirm(t('duties.confirmDeleteRun')))return;const next=value.runs.find((run)=>run.id!==selectedRun)?.id||'';setValue((current)=>deleteRunFromDocument(current,selectedRun));setSelectedRun(next);};
  const addDuty=()=>{const id=`duty-${value.duties.length+1}`;setValue({...value,duties:[...value.duties,{id,selector:{type:'TRAIN_NAME',name:''},elements:[]}]});setSelectedDuty(id);};
  const assignRun=(runId:string)=>{if(!selectedDutyValue)return;setValue((current)=>moveRunToDuty(current,runId,selectedDutyValue.id,uid('element')));};
  const removeElement=(elementId:string)=>{if(selectedDutyValue)patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.filter((item)=>item.id!==elementId)});};
  const addFunction=(call:AuthoringFunctionCallV1)=>{if(!selectedDutyValue)return;const element:AuthoringDutyElementV1={id:uid('element'),type:'FUNCTION',call};const elements=call.type==='PLACE_INITIAL'?[element,...selectedDutyValue.elements]:[...selectedDutyValue.elements,element];patchDuty(selectedDutyValue.id,{elements});};
  const selectGraphRun=(serviceIndex:number)=>{const runId=graphValue.services[serviceIndex]?.id,run=value.runs.find((candidate)=>candidate.id===runId);if(runId)setSelectedRun(runId);if(run?.diagramLineId)setGraphLineId(run.diagramLineId);};
  const addRunToSelectedDuty=(runId:string)=>{
    if(!selectedDutyValue)return;
    setSelectedRun(runId);
    if(assigned.get(runId)!==selectedDutyValue.id)setValue((current)=>moveRunToDuty(current,runId,selectedDutyValue.id,uid('element')));
  };
  const patchDepotSelector=(patch:Partial<{depotId:string;tick:number;tags:string[]}>)=>{
    if(!selectedDutyValue||selectedDutyValue.selector.type!=='TAKE_FROM_DEPOT')return;
    patchDuty(selectedDutyValue.id,{selector:{...selectedDutyValue.selector,...patch}});
  };
  const patchWaypointSelector=(patch:Partial<{wayPointIds:string[];tick:number}>)=>{
    if(!selectedDutyValue||selectedDutyValue.selector.type!=='TAKE_FROM_WAYPOINT')return;
    patchDuty(selectedDutyValue.id,{selector:{...selectedDutyValue.selector,...patch}});
  };
  useEffect(()=>{
    const handle=(event:Event)=>{const command=(event as CustomEvent<string>).detail;if(command==='new-run')addRun();else if(command==='open-move')openMove();else if(command==='undo')documentHistory.undo();else if(command==='redo')documentHistory.redo();else if(command==='save')void save();else if(command==='save-formal')void saveFormalScript();else if(command==='compile')void compile();else if(command==='simulate')void simulate();else if(command==='publish')void publish();};
    window.addEventListener('javarailways:authoring-command',handle);return()=>window.removeEventListener('javarailways:authoring-command',handle);
  });

  const documentSettings=<div className="document-settings">
    <label>{t('duties.document')}<Select value={documentId||'__new__'} onChange={(id)=>void load(id==='__new__'?'':id)} options={[{value:'__new__',label:t('common.newDraft')},...documents.map((item)=>({value:item.id,label:item.id}))]}/></label>
    <label>{t('editor.timetableId')}<Input value={value.id} onChange={(event)=>{const id=event.target.value;if(!persistedFormalScriptName&&formalScriptName===defaultScriptName(value.id))setFormalScriptName(defaultScriptName(id));setValue({...value,id});}}/></label>
    <label>{t('editor.periodDays')}<InputNumber min={1} value={value.periodDays} onChange={(period)=>setValue({...value,periodDays:Math.max(1,period??1)})}/></label>
    <label>{t('duties.scriptFile')}<Input value={formalScriptName} onChange={(event)=>setFormalScriptName(event.target.value)}/></label>
    <label>{t('duties.delayThreshold')}<InputNumber min={1} value={delayThresholdTicks} onChange={(ticks)=>setDelayThresholdTicks(Math.max(1,ticks??1))}/></label>
    <Flex gap={6} wrap><Button icon={<EditOutlined/>} onClick={()=>void renameFormalScript()} disabled={busy||!persistedFormalScriptName||formalScriptName===persistedFormalScriptName}>{t('duties.renameScript')}</Button><Button danger icon={<DeleteOutlined/>} onClick={()=>void deleteFormalScript()} disabled={busy||!persistedFormalScriptName}>{t('duties.deleteScript')}</Button><Button danger onClick={()=>void remove()} disabled={!documentId||busy||!!persistedFormalScriptName}>{t('common.deleteDocument')}</Button></Flex>
  </div>;
  const moveCall=selectedRunValue?.calls.find((call)=>call.id===moveCallId);
  const moveAnchorTick=moveEvent==='ARRIVAL'?moveCall?.arrivalTick:moveCall?.departureTick;
  return <section className="duty-page studio-timetable-page">
    <TimetableWorkspace
      lines={lines} activeLineId={graphLineId} runs={lineRuns} selectedRunId={selectedRun} search={runSearch} direction={runDirection}
      onLineChange={(lineId)=>{setGraphLineId(lineId);const first=value.runs.find((run)=>run.diagramLineId===lineId&&run.lineDirection===runDirection);setSelectedRun(first?.id??'');}}
      onDirectionChange={(direction)=>{setRunDirection(direction);const first=value.runs.find((run)=>run.diagramLineId===graphLineId&&run.lineDirection===direction);setSelectedRun(first?.id??'');}}
      onSearchChange={setRunSearch} onSelectRun={setSelectedRun} onAddRun={addRun} assignmentFor={(runId)=>assigned.get(runId)}
      graph={<Graph value={graphValue} metadata={metadata} line={lines.find((line)=>line.id===graphLineId)} graphLineId={graphLineId||undefined}
        highlightedServiceIds={highlightedRunIds} previewServiceIds={previewRunIds} onSelect={(service)=>selectGraphRun(service)}/>}
      inspector={!selectedRunValue?<p className="empty inspector-empty">{t('duties.selectRun')}</p>:<RunRouteEditor run={selectedRunValue} lines={lines} metadata={metadata} busy={busy} onError={onError} onChange={replaceRun} onDelete={deleteSelectedRun}/>}
      graphActions={<Popover trigger="click" placement="bottomRight" title={t('common.documentSettings')} content={documentSettings}><Button type="text" icon={<SettingOutlined/>}/></Popover>}
      graphPreviewActive={Boolean(previewRun)} saved={JSON.stringify(value)===savedDocumentJson.current}/>

    <Modal title={t('move.title')} open={moveOpen} onCancel={closeMove} onOk={applyMove} okText={t('move.apply')} okButtonProps={{disabled:!moveCalculation.preview}} destroyOnHidden>
      <div className="move-dialog">
        <label>{t('move.station')}<Select value={moveCallId||undefined} onChange={(callId)=>{const call=selectedRunValue?.calls.find((candidate)=>candidate.id===callId);const nextEvent=call?.departureTick!=null?'DEPARTURE':'ARRIVAL';setMoveCallId(callId);setMoveEvent(nextEvent);setMoveValue(moveMode==='OFFSET'?0:(nextEvent==='DEPARTURE'?call?.departureTick:call?.arrivalTick)??'');}} options={(selectedRunValue?.calls??[]).filter((call)=>call.arrivalTick!=null||call.departureTick!=null).map((call)=>({value:call.id,label:stationNames.get(call.stationId)||call.stationId}))}/></label>
        <label>{t('move.event')}<Segmented block value={moveEvent} onChange={(next)=>{const event=next as RunMoveEvent;setMoveEvent(event);if(moveMode==='ABSOLUTE')setMoveValue(event==='ARRIVAL'?moveCall?.arrivalTick??'':moveCall?.departureTick??'');}} options={[{value:'ARRIVAL',label:t('move.arrival'),disabled:moveCall?.arrivalTick==null},{value:'DEPARTURE',label:t('move.departure'),disabled:moveCall?.departureTick==null}]}/></label>
        <label>{t('move.mode')}<Segmented block value={moveMode} onChange={(next)=>{const mode=next as RunMoveMode;setMoveMode(mode);setMoveValue(mode==='OFFSET'?0:moveAnchorTick??'');}} options={[{value:'OFFSET',label:t('move.offset')},{value:'ABSOLUTE',label:t('move.toTime')}]}/></label>
        <label>{moveMode==='OFFSET'?t('move.offsetTicks'):t('move.targetTick')}<InputNumber value={moveValue} min={moveMode==='ABSOLUTE'?0:undefined} precision={0} onChange={(next)=>setMoveValue(typeof next==='number'?Math.trunc(next):'')} addonAfter="tick"/></label>
        {moveMode==='ABSOLUTE'&&moveValue!==''&&<Typography.Text type="secondary">{formatTimetableTick(moveValue,locale)}</Typography.Text>}
        {moveCalculation.preview&&<div className="move-preview"><Typography.Text strong>{t('move.preview')}</Typography.Text><Typography.Text>{t('move.delta')}: {moveCalculation.preview.deltaTicks>0?'+':''}{moveCalculation.preview.deltaTicks} ticks · {t('move.affected')}: {moveCalculation.preview.affectedTimeCount}</Typography.Text></div>}
        {moveCalculation.error&&<Typography.Text type="danger">{moveCalculation.error}</Typography.Text>}
      </div>
    </Modal>
  </section>;
}
