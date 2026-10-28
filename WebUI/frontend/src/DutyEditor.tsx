import {defaultStationStopProgram} from './stationPlans';
import {defaultDutyProgramArguments,orderDutyTimeline,projectDutyTimeline,sortDutyTasks} from './dutyTimeline';
import {TimedOperationFields} from './TimedOperationFields';
import {projectTimetableOperations,validateTimedOperation} from './timedOperations';
import {TimetableEventsEditor} from './TimetableEventsEditor';
import {deleteTimetableEvent,saveTimetableEvent,timetableEventUnchanged,referencedTimetableEventIds,type GraphEventContext} from './timetableEvents';
import {renameFormationDuty,createDutyFormation,updateDutyFormation,removeDutyFormation,setDutyVehicleSource,formationOutputs,type AuthoringFormationEvent} from './formationAuthoring';
import {allocateAuthoringIds} from './authoringIds';
import { useCallback,useEffect,useMemo,useRef,useState,type CSSProperties } from 'react';
import { Button,Dropdown,Flex,Input,InputNumber,Modal,Popover,Segmented,Select,Space,Tag,Tooltip,Typography } from 'antd';
import { DeleteOutlined,EditOutlined,LeftOutlined,PlusOutlined,RightOutlined,SettingOutlined } from '@ant-design/icons';
import {resolveArrivalSourceTarget,resolveProgramSourceTarget,type ProgramSourceTarget} from './programNavigation';
import { adoptStationProgram } from './stationPrograms';
import { api } from './api';
import { frontendDemoEnabled } from './frontendDev';
import {
  authoringGraphTimetable,authoringSelectorKey,createRunFromLine,deleteRunFromDocument,emptyAuthoringDocument,generateTimetableSource,moveRunToDuty,copyRunShifted,lineSimulationRoute,resetRunTimings,renameAuthoringRun,resolveAuthoringRunSelection,authoringResetProfile,
  type AuthoringDutyV1,type AuthoringRunV1,type RunMoveMode,
  type TimetableAuthoringDocumentV1,
} from './authoring';
import {loadLastAuthoringDocument,rememberAuthoringDocument,resumeAuthoringWorkspace} from './authoringResume';
import { clearAuthoringRecovery,loadAuthoringRecovery,saveAuthoringRecovery } from './authoringRecovery';
import { useDocumentHistory } from './documentHistory';
import { Graph,type GraphDutyOperationContext } from './Graph';
import { getProgramBlockDraftValidity } from './ProgramBlockEditor';
import { RunRouteEditor } from './RunRouteEditor';
import { editAuthoringGraphTime,shiftAuthoringGraphSuffix } from './authoringGraphEditing';
import { CommittedNumberInput } from './CommittedNumberInput';
import { DutyManagement } from './DutyManagement';
import { AuthoringValidation } from './AuthoringValidation';
import type { GraphPoint } from './graph';
import type { AuthoringCommand,AuthoringCommandState } from './authoringCommands';
import { TimetableWorkspace } from './TimetableWorkspace';
import { useI18n } from './i18n';
import { formatTimetableTick } from './timetableTime';
import type { StationProgramRecord,TimedOperation,CompiledTimetableSource,EditorDocumentSummary,LineV1,TimetableEventPoint,TimetableSimulationResult,WorldMetadata } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const hash=(value:string)=>{let result=2166136261;for(let index=0;index<value.length;index++){result^=value.charCodeAt(index);result=Math.imul(result,16777619);}return (result>>>0).toString(16).padStart(8,'0');};
const defaultScriptName=(id:string)=>`${(id.replace(/[^A-Za-z0-9._-]/g,'-').replace(/^[^A-Za-z0-9]+/,'')||'timetable').slice(0,120)}.js`;
export const clampAuthoringSidebarWidth=(width:number,viewportWidth:number)=>Math.max(360,Math.min(width,960,Math.max(360,viewportWidth-320)));

export function DutyEditor({world,recoveryOwner,lines,metadata,onError,onOpenProgramLibrary,page='graph',onPageChange,onCommandState}:{onOpenProgramLibrary?:(programId:string,stepId?:string)=>void;page?:'graph'|'duties'|'validation';onPageChange?:(page:'graph'|'duties'|'validation')=>void;onCommandState?:(state:AuthoringCommandState)=>void;world:string;recoveryOwner:string;lines:LineV1[];metadata:WorldMetadata;onError:(message:string)=>void}) {
  const {locale,t}=useI18n();
  const [initialRecovery]=useState(()=>{const recovered=loadAuthoringRecovery(recoveryOwner,world);return recovered&&JSON.stringify(recovered.document)!==recovered.savedDocumentJson?recovered:undefined;});
  const initialDocument=initialRecovery?.document??emptyAuthoringDocument();
  const savedDocumentJson=useRef(initialRecovery?.savedDocumentJson??JSON.stringify(initialDocument));
  const ownerEpoch=useRef(0);
  const ownerIdentity=useRef(`${recoveryOwner}::${world}`);
  if(ownerIdentity.current!==`${recoveryOwner}::${world}`){ownerIdentity.current=`${recoveryOwner}::${world}`;ownerEpoch.current++;}
  const operationSerial=useRef(0);
  const [programResources,setProgramResources]=useState<{world:string;records:StationProgramRecord[]}>({world:'',records:[]});
  const [programError,setProgramError]=useState('');const [programLoading,setProgramLoading]=useState(false);const programRequest=useRef(0);
  const loadPrograms=async()=>{const token=++programRequest.current;setProgramLoading(true);setProgramError('');try{const records=await api.stationPrograms(world);if(token===programRequest.current)setProgramResources({world,records});}catch(failure){if(token===programRequest.current)setProgramError(String(failure));}finally{if(token===programRequest.current)setProgramLoading(false);}};
  useEffect(()=>{if(page!=='validation'&&world)void loadPrograms();return()=>{programRequest.current++;};},[world,page]);
  const programLibrary=programResources.world===world?programResources.records:[];
  const [documents,setDocuments]=useState<EditorDocumentSummary[]>([]);
  const blockDraftKey=useRef(initialRecovery?.blockDraftKey||(initialRecovery?.documentId||initialDocument.id));
  const [documentId,setDocumentId]=useState(initialRecovery?.documentId??'');
  const [formalScriptName,setFormalScriptName]=useState(initialRecovery?.formalScriptName??'new-timetable.js');
  const [persistedFormalScriptName,setPersistedFormalScriptName]=useState(initialRecovery?.persistedFormalScriptName??'');
  const documentHistory=useDocumentHistory<TimetableAuthoringDocumentV1>(()=>initialDocument);
  const {value}=documentHistory;
  const setValue=(next:TimetableAuthoringDocumentV1|((current:TimetableAuthoringDocumentV1)=>TimetableAuthoringDocumentV1))=>documentHistory.setValue((current)=>orderDutyTimeline(typeof next==='function'?next(current):next));
  const previousSelectedRun=useRef<AuthoringRunV1|undefined>(undefined);
  const [selectedRun,setSelectedRun]=useState(initialRecovery?.selectedRun??'');
  const [previewRun,setPreviewRun]=useState<AuthoringRunV1>();
  const [moveOpen,setMoveOpen]=useState(false);
  const [selectedPoint,setSelectedPoint]=useState<{runId:string;callId:string;kind:'ARRIVAL'|'DEPARTURE';stepIndex:number;routeSignature:string;anchor:{x:number;y:number}}>();
  const [eventDraft,setEventDraft]=useState<{scope:string;original?:TimetableEventPoint;operation:TimedOperation;valid:boolean}>();
  const [pendingGraphEvent,setPendingGraphEvent]=useState<{id:string;scope:string}>();
  const [focusedDutyElement,setFocusedDutyElement]=useState<{dutyId:string;elementId:string;scope:string}>();
  const [dutyOperationMenu,setDutyOperationMenu]=useState<GraphDutyOperationContext&{scope:string}>();
  const [eventMenu,setEventMenu]=useState<GraphEventContext&{scope:string}>();
  const [moveSource,setMoveSource]=useState('');
  const [copyOpen,setCopyOpen]=useState(false);
  const [moveInvalid,setMoveInvalid]=useState(false);
  const [copyOffsetInvalid,setCopyOffsetInvalid]=useState(false);
  const [copyCountInvalid,setCopyCountInvalid]=useState(false);
  const copyInvalid=copyOffsetInvalid||copyCountInvalid;
  const [moveMode,setMoveMode]=useState<RunMoveMode>('OFFSET');
  const [moveValue,setMoveValue]=useState<number|''>(0);
  const [focusedProgram,setFocusedProgram]=useState<{epoch:number;target:ProgramSourceTarget}>();
  const [selectedDuty,setSelectedDuty]=useState(initialRecovery?.selectedDuty??'');
  const [sidebarTab,setSidebarTab]=useState<'runs'|'duties'>('runs');
  const [runLibraryOpen,setRunLibraryOpen]=useState(true);
  const [runSearch,setRunSearch]=useState('');
  const [runDirection,setRunDirection]=useState<'UP'|'DOWN'>(()=>initialDocument.runs.find((run)=>run.id===initialRecovery?.selectedRun)?.lineDirection??'UP');
  const [sidebarOpen,setSidebarOpen]=useState(true);
  const [sidebarWidth,setSidebarWidth]=useState(()=>{
    if(typeof window==='undefined')return 520;
    const saved=Number(window.localStorage.getItem('javarailways.authoringSidebarWidthV2'));
    return clampAuthoringSidebarWidth(Number.isFinite(saved)&&saved>0?saved:520,window.innerWidth);
  });
  const sidebarResize=useRef<{startX:number;startWidth:number}|null>(null);
  const [graphLineId,setGraphLineId]=useState(initialRecovery?.graphLineId??'');
  const invalidDrafts=useRef(new Set<string>());
  const [focusedArrivalStep,setFocusedArrivalStep]=useState('');
  const [reportedEditorDraftInvalid,setEditorDraftInvalid]=useState(false);
  const editorDraftValidity=useRef({valid:true,revision:0});
  const [compiledState,setCompiledState]=useState<{result:CompiledTimetableSource;documentHashInput:string}>();
  const [compileFailure,setCompileFailure]=useState<{message:string;key:string}>();
  const [resetProfile,setResetProfile]=useState('');
  const [fixedDwell,setFixedDwell]=useState(200);
  const [copyOffset,setCopyOffset]=useState(1200);
  const [copyCount,setCopyCount]=useState(1);
  const [focusedCall,setFocusedCall]=useState('');
  const [busy,setBusy]=useState(false);
  const profileAssignments=Object.fromEntries(value.duties.filter((duty)=>duty.referenceVehicle).map((duty)=>[duty.id,`${duty.referenceVehicle!.type}:${duty.referenceVehicle!.id}`]));
  const [delayThresholdTicks,setDelayThresholdTicks]=useState(initialRecovery?.delayThresholdTicks??1_200);
  const [simulationState,setSimulationState]=useState<{result:TimetableSimulationResult;inputKey:string}>();
  const programDraftScope=JSON.stringify([recoveryOwner,world,documentId||blockDraftKey.current]);
  const editorDraftInvalid=reportedEditorDraftInvalid;
  const sharedStations=[...new Set(value.runs.flatMap((run)=>run.calls.filter((call)=>call.status!=='PASS').map((call)=>call.stationId)))].map((stationId)=>value.stationPrograms?.find((program)=>program.stationId===stationId)??defaultStationStopProgram(stationId));
  const arrivalDraftsInvalid=sharedStations.some((program)=>(['arrival','departurePreparation'] as const).some((phase)=>!getProgramBlockDraftValidity(`${programDraftScope}/station/${program.stationId}/${phase}`,program[phase],'arrival')));
  const generated=useMemo(()=>generateTimetableSource(value,lines),[value,lines]);
  const hasAuthoringErrors=generated.issues.some((issue)=>issue.severity==='error');
  const compiled=!hasAuthoringErrors&&!editorDraftInvalid&&!arrivalDraftsInvalid&&compiledState?.documentHashInput===generated.documentHashInput?compiledState.result:undefined;
  const simulationInputKey=JSON.stringify([world,generated.documentHashInput,delayThresholdTicks,Object.entries(profileAssignments).sort(([a],[b])=>a.localeCompare(b))]);
  const simulation=!hasAuthoringErrors&&!editorDraftInvalid&&!arrivalDraftsInvalid&&simulationState?.inputKey===simulationInputKey?simulationState.result:undefined;
  const onDraftValidityChange=useCallback((key:string,valid:boolean)=>{
    const previous=invalidDrafts.current.has(key);
    if(previous===!valid)return;
    if(valid)invalidDrafts.current.delete(key);else invalidDrafts.current.add(key);
    const allValid=invalidDrafts.current.size===0;
    editorDraftValidity.current={valid:allValid,revision:editorDraftValidity.current.revision+1};
    setEditorDraftInvalid(!allValid);
    if(!allValid){setCompiledState(undefined);setSimulationState(undefined);setCompileFailure(undefined);}
  },[]);
  const onArrivalValidityChange=useCallback((stationId:string,_callId:string,valid:boolean)=>onDraftValidityChange(`station/${stationId}`,valid),[onDraftValidityChange]);
  useEffect(()=>{for(const key of [...invalidDrafts.current])if(key.startsWith('station/')&&!value.runs.some((run)=>run.calls.some((call)=>call.status!=='PASS'&&key===`station/${call.stationId}`)))onDraftValidityChange(key,true);},[value.runs,onDraftValidityChange]);
  const simulationProfiles=metadata.simulationProfiles||[];
  const profileDuties=value.duties.filter((duty)=>duty.selector.type!=='TAKE_FROM_FORMATION_OUTPUT');
  const profilesBySelector=new Map<string,string>();
  let hasConflictingProfiles=false;
  profileDuties.forEach((duty)=>{const selected=profileAssignments[duty.id],key=authoringSelectorKey(duty.selector),existing=profilesBySelector.get(key);if(selected&&existing&&selected!==existing)hasConflictingProfiles=true;else if(selected)profilesBySelector.set(key,selected);});
  const hasAllProfiles=profileDuties.length>0&&profileDuties.every((duty)=>simulationProfiles.some((profile)=>`${profile.type}:${profile.id}`===profileAssignments[duty.id]))&&!hasConflictingProfiles;
  const documentRevision=useRef({value,revision:0});
  if(documentRevision.current.value!==value)documentRevision.current={value,revision:documentRevision.current.revision+1};
  const liveInputs=useRef({revision:documentRevision.current.revision,world,documentJson:JSON.stringify(value),simulationInputKey,documentHashInput:generated.documentHashInput});
  liveInputs.current={revision:documentRevision.current.revision,world,documentJson:JSON.stringify(value),simulationInputKey,documentHashInput:generated.documentHashInput};
  const assigned=new Map(value.duties.flatMap((duty)=>duty.elements.flatMap((element)=>element.type==='RUN'?[[element.runId,duty.id] as const]:[])));
  const effectiveRuns=useMemo(()=>value.runs.map((run)=>({...run,calls:run.calls.map((call)=>{
    const override=value.arrivalOverrides.find((item)=>item.runId===run.id&&item.callId===call.id);
    return override?{...call,arrivalTick:override.tick}:call;
  })})),[value.runs,value.arrivalOverrides]);
  const selectedRunValue=effectiveRuns.find((run)=>run.id===selectedRun&&run.diagramLineId===graphLineId&&run.lineDirection===runDirection);
  const graphTimeError=(error:unknown)=>{
    const message=error instanceof Error?error.message:String(error);
    if(message==='Departure violates arrival or minimum dwell')return t('ui.dwellViolation');
    if(message==='Minimum dwell must be a non-negative integer')return t('ui.invalidDwell');
    if(message.includes('inside the timetable period'))return t('duties.outsidePeriod');
    if(message==='Shifted events must remain in chronological order')return t('ui.timeOrder');
    if(message==='The selected event has no time')return t('move.noTimedCall');
    if(message.includes('must be a safe integer'))return t('ui.invalidNumber');
    if(message==='Unknown run'||message==='Unknown graph point'||message==='Unknown shift mode')return t('ui.shiftChanged');
    return message;
  };
  const stationNames=useMemo(()=>new Map((metadata.stations??[]).map((station)=>[station.stationId,station.stationDisplayName||station.stationId])),[metadata.stations]);
  const pointRun=effectiveRuns.find((run)=>run.id===selectedPoint?.runId);
  const pointRouteSignature=JSON.stringify(pointRun?.calls.map((call)=>[call.id,call.status]));
  const pointSelectionValid=Boolean(selectedPoint&&selectedPoint.routeSignature===pointRouteSignature);
  const pointCall=pointSelectionValid?pointRun?.calls.find((call)=>call.id===selectedPoint?.callId):undefined;
  const pointTick=selectedPoint?.kind==='ARRIVAL'?pointCall?.arrivalTick:pointCall?.departureTick;
  const nodeMinimumDwell=pointCall?.minimumDwellTicks??0;
  const nodeMinimumTick=selectedPoint?.kind==='DEPARTURE'&&pointCall?.arrivalTick!=null?Math.max(0,pointCall.arrivalTick+nodeMinimumDwell):0;
  const nodeMaximumTick=selectedPoint?.kind==='ARRIVAL'&&pointCall?.departureTick!=null?Math.min(value.periodDays*24000-1,pointCall.departureTick-nodeMinimumDwell):value.periodDays*24000-1;
  const moveCalculation=useMemo(()=>{
    if(!selectedPoint||moveValue==='')return {preview:undefined,error:''};
    if(!pointSelectionValid||moveSource!==generated.documentHashInput)return {preview:undefined,error:t('ui.shiftChanged')};
    try{return {preview:shiftAuthoringGraphSuffix(value,selectedPoint.runId,selectedPoint,moveMode,moveValue),error:''};}
    catch(error){return {preview:undefined,error:graphTimeError(error)};}
  },[value,selectedPoint,pointSelectionValid,moveMode,moveValue,moveSource,generated.documentHashInput,locale]);
  const selectedDutyValue=value.duties.find((duty)=>duty.id===selectedDuty);
  const graphRuns=useMemo(()=>effectiveRuns.map((run)=>previewRun?.id===run.id?previewRun:run),[effectiveRuns,previewRun]);
  const fullGraphValue=useMemo(()=>authoringGraphTimetable({...value,runs:graphRuns},lines),[value,graphRuns,lines]);
  const graphValue=useMemo(()=>({...fullGraphValue,services:fullGraphValue.services.filter((service)=>!graphLineId||service.diagramLineId===graphLineId)}),[fullGraphValue,graphLineId]);
  const lineRuns=useMemo(()=>value.runs.filter((run)=>run.diagramLineId===graphLineId&&run.lineDirection===runDirection&&run.id.toLowerCase().includes(runSearch.trim().toLowerCase())),[value.runs,graphLineId,runDirection,runSearch]);
  const highlightedRunIds=useMemo(()=>new Set(sidebarTab==='duties'
    ?selectedDutyValue?.elements.flatMap((element)=>element.type==='RUN'?[element.runId]:[])??[]
    :selectedRun?[selectedRun]:[]),[sidebarTab,selectedDutyValue,selectedRun]);
  const previewRunIds=useMemo(()=>new Set(previewRun?[previewRun.id]:[]),[previewRun]);
  const authoringWorkspaceStyle={'--authoring-sidebar-width':`${sidebarWidth}px`} as CSSProperties;
  const delayedOrigin=simulation?.delayedServiceId==null||simulation.delayedStepIndex==null?undefined:compiled?.timetable.compilationTrace?.find((trace)=>trace.serviceId===simulation.delayedServiceId&&trace.stepIndex===simulation.delayedStepIndex&&JSON.stringify(trace.branchPath??[])===JSON.stringify(simulation.delayedStepBranchPath??[]))?.origin;
  const recoverySnapshot={owner:recoveryOwner,world,documentId,blockDraftKey:blockDraftKey.current,formalScriptName,persistedFormalScriptName,document:value,savedDocumentJson:savedDocumentJson.current,selectedRun,selectedDuty,sidebarTab,graphLineId,profileAssignments,delayThresholdTicks};
  const latestRecovery=useRef(recoverySnapshot);
  latestRecovery.current=recoverySnapshot;
  const persistRecovery=()=>{
    const state=latestRecovery.current;
    if(JSON.stringify(state.document)===state.savedDocumentJson){clearAuthoringRecovery(state.owner,state.world);return;}
    saveAuthoringRecovery(state);
  };
  const acceptLoadedDocument=(id:string,loaded:TimetableAuthoringDocumentV1)=>{
    blockDraftKey.current=id||loaded.id;invalidDrafts.current.clear();editorDraftValidity.current={valid:true,revision:editorDraftValidity.current.revision+1};setEditorDraftInvalid(false);
    previousSelectedRun.current=undefined;setDocumentId(id);setCompiledState(undefined);setSimulationState(undefined);setCompileFailure(undefined);
    const loadedScriptName=loaded.formalScriptName||defaultScriptName(loaded.id);
    savedDocumentJson.current=JSON.stringify(loaded);clearAuthoringRecovery(recoveryOwner,world);documentHistory.reset(loaded);setFormalScriptName(loadedScriptName);setPersistedFormalScriptName(loaded.formalScriptName||'');setSelectedRun(loaded.runs[0]?.id||'');setSelectedDuty(loaded.duties[0]?.id||'');
    latestRecovery.current={...latestRecovery.current,documentId:id,blockDraftKey:blockDraftKey.current,document:loaded,savedDocumentJson:savedDocumentJson.current,formalScriptName:loadedScriptName,persistedFormalScriptName:loaded.formalScriptName||'',selectedRun:loaded.runs[0]?.id||'',selectedDuty:loaded.duties[0]?.id||''};
    rememberAuthoringDocument(recoveryOwner,world,id);
  };

  useEffect(()=>{if(typeof window!=='undefined')window.localStorage.setItem('javarailways.authoringSidebarWidthV2',String(sidebarWidth));},[sidebarWidth]);
  useEffect(()=>{setPreviewRun(undefined);},[selectedRun]);
  useEffect(()=>{const document=moveOpen?moveCalculation.preview?.document:undefined;const run=document?.runs.find((run)=>run.id===selectedPoint?.runId);setPreviewRun(run?{...run,calls:run.calls.map((call)=>{const override=document!.arrivalOverrides.find((item)=>item.runId===run.id&&item.callId===call.id);return override?{...call,arrivalTick:override.tick}:call;})}:undefined);},[moveOpen,moveCalculation.preview,selectedPoint?.runId]);
  useEffect(()=>{setSelectedPoint(undefined);setMoveOpen(false);setCopyOpen(false);},[world,documentId,graphLineId,page]);
  useEffect(()=>{setSelectedPoint((current)=>current?.runId===selectedRun?current:undefined);setMoveOpen(false);setCopyOpen(false);},[selectedRun]);
  useEffect(()=>{if(selectedPoint&&!pointSelectionValid){setSelectedPoint(undefined);setMoveOpen(false);setPreviewRun(undefined);}},[selectedPoint,pointSelectionValid]);
  useEffect(()=>{if(!lines.some((line)=>line.id===graphLineId))setGraphLineId(lines[0]?.id??'');},[graphLineId,lines]);
  useEffect(()=>{const scoped=value.runs.filter((run)=>run.diagramLineId===graphLineId&&run.lineDirection===runDirection);const resolved=resolveAuthoringRunSelection(scoped,selectedRun,previousSelectedRun.current)??scoped[0];previousSelectedRun.current=resolved;if((resolved?.id??'')!==selectedRun)setSelectedRun(resolved?.id??'');},[value.runs,selectedRun,graphLineId,runDirection]);
  useEffect(()=>{if(initialRecovery)onError(t('duties.recoveredLocalDraft'));},[]);
  useEffect(()=>{const timer=window.setTimeout(persistRecovery,250);return()=>window.clearTimeout(timer);},[value,documentId,formalScriptName,persistedFormalScriptName,selectedRun,selectedDuty,sidebarTab,graphLineId,delayThresholdTicks]);
  useEffect(()=>{
    const preserve=()=>persistRecovery();
    window.addEventListener('javarailways:unauthorized',preserve);window.addEventListener('beforeunload',preserve);
    return()=>{preserve();ownerEpoch.current++;operationSerial.current++;window.removeEventListener('javarailways:unauthorized',preserve);window.removeEventListener('beforeunload',preserve);};
  },[]);
  useEffect(()=>{
    const epoch=ownerEpoch.current,input=liveInputs.current,serial=operationSerial.current;
    let active=true;
    const isCurrent=()=>active&&ownerEpoch.current===epoch&&operationSerial.current===serial&&liveInputs.current.revision===input.revision;
    if(world)void resumeAuthoringWorkspace({
      documentId:loadLastAuthoringDocument(recoveryOwner,world),hasRecovery:Boolean(initialRecovery),demoAutoload:frontendDemoEnabled,isOwnerCurrent:()=>active,isCurrent,
      list:()=>api.editorDocuments(world),load:(id)=>api.editorDocument(world,id),onList:setDocuments,
      onMissing:()=>rememberAuthoringDocument(recoveryOwner,world,''),onResume:acceptLoadedDocument,
    }).catch((error)=>{if(isCurrent())onError(String(error));});
    return()=>{active=false;};
  },[]);
  const execute=async(operation:()=>Promise<void>)=>{const serial=++operationSerial.current,identity=ownerIdentity.current;setBusy(true);onError('');try{await operation();}catch(error){if(ownerIdentity.current===identity&&serial===operationSerial.current)onError(error instanceof Error?error.message:String(error));}finally{if(serial===operationSerial.current)setBusy(false);}};
  const load=async(id:string)=>execute(async()=>{
    const epoch=++ownerEpoch.current,input=liveInputs.current;
    const loaded=id?await api.editorDocument(world,id):emptyAuthoringDocument();
    if(ownerEpoch.current!==epoch||liveInputs.current.revision!==input.revision)return;
    acceptLoadedDocument(id,loaded);
  });
  const save=async()=>execute(async()=>{
    if(!editorDraftValidity.current.valid||arrivalDraftsInvalid)throw new Error(t('programs.invalidDraft'));
    const epoch=ownerEpoch.current,snapshot=JSON.stringify(value);
    const created=documentId?undefined:await api.createEditorDocument(world,value);
    if(documentId)await api.saveEditorDocument(world,documentId,value);
    if(ownerEpoch.current!==epoch)return;
    if(created)setDocumentId(created.id);
    savedDocumentJson.current=snapshot;
    const savedId=created?.id??documentId;
    latestRecovery.current={...latestRecovery.current,documentId:savedId,savedDocumentJson:snapshot};
    rememberAuthoringDocument(recoveryOwner,world,savedId);
    if(liveInputs.current.documentJson===snapshot)clearAuthoringRecovery(recoveryOwner,world);
    const documents=await api.editorDocuments(world);if(ownerEpoch.current===epoch)setDocuments(documents);
  });
  const saveFormalScript=async()=>execute(async()=>{
    if(!editorDraftValidity.current.valid||arrivalDraftsInvalid)throw new Error(t('programs.invalidDraft'));
    if(persistedFormalScriptName&&formalScriptName!==persistedFormalScriptName)throw new Error(t('duties.renameFirst'));
    const epoch=ownerEpoch.current,snapshot=JSON.stringify(value),document={...value,formalScriptName};
    let targetDocumentId=documentId;
    if(!targetDocumentId){const created=await api.createEditorDocument(world,document);if(ownerEpoch.current!==epoch)return;targetDocumentId=created.id;setDocumentId(created.id);}
    try{await api.saveFormalScript(world,targetDocumentId,formalScriptName,generated.source,document,false);}
    catch(error){
      if(ownerEpoch.current!==epoch)return;
      const message=error instanceof Error?error.message:String(error);
      if(!message.includes('already exists')||!window.confirm(t('duties.confirmOverwrite')))throw error;
      await api.saveFormalScript(world,targetDocumentId,formalScriptName,generated.source,document,true);
    }
    if(ownerEpoch.current!==epoch)return;
    savedDocumentJson.current=JSON.stringify(document);setPersistedFormalScriptName(formalScriptName);
    latestRecovery.current={...latestRecovery.current,documentId:targetDocumentId,savedDocumentJson:savedDocumentJson.current,persistedFormalScriptName:formalScriptName,...(liveInputs.current.documentJson===snapshot?{document}:{})};
    rememberAuthoringDocument(recoveryOwner,world,targetDocumentId);
    if(liveInputs.current.documentJson===snapshot){clearAuthoringRecovery(recoveryOwner,world);setValue(document);}
    const documents=await api.editorDocuments(world);if(ownerEpoch.current===epoch)setDocuments(documents);
  });
  const renameFormalScript=async()=>execute(async()=>{
    if(!editorDraftValidity.current.valid||arrivalDraftsInvalid)throw new Error(t('programs.invalidDraft'));
    if(!documentId||!persistedFormalScriptName||formalScriptName===persistedFormalScriptName)return;
    const epoch=ownerEpoch.current,snapshot=JSON.stringify(value),document={...value,formalScriptName};
    await api.renameFormalScript(world,documentId,persistedFormalScriptName,formalScriptName,document);
    if(ownerEpoch.current!==epoch)return;
    savedDocumentJson.current=JSON.stringify(document);setPersistedFormalScriptName(formalScriptName);
    latestRecovery.current={...latestRecovery.current,savedDocumentJson:savedDocumentJson.current,persistedFormalScriptName:formalScriptName,...(liveInputs.current.documentJson===snapshot?{document}:{})};
    rememberAuthoringDocument(recoveryOwner,world,documentId);
    if(liveInputs.current.documentJson===snapshot){clearAuthoringRecovery(recoveryOwner,world);setValue(document);}
    const documents=await api.editorDocuments(world);if(ownerEpoch.current===epoch)setDocuments(documents);
  });
  const resetDeletedDocument=()=>acceptLoadedDocument('',emptyAuthoringDocument());
  const deleteFormalScript=async()=>{if(!documentId||!persistedFormalScriptName||!window.confirm(t('duties.confirmDeleteScript')))return;await execute(async()=>{
    const epoch=ownerEpoch.current,input=liveInputs.current;
    await api.deleteFormalScript(world,documentId,persistedFormalScriptName);
    if(ownerEpoch.current!==epoch)return;
    ownerEpoch.current++;
    rememberAuthoringDocument(recoveryOwner,world,'');
    if(liveInputs.current.documentJson===input.documentJson)resetDeletedDocument();
    else {savedDocumentJson.current='';latestRecovery.current={...latestRecovery.current,documentId:'',savedDocumentJson:'',persistedFormalScriptName:''};setDocumentId('');setPersistedFormalScriptName('');setCompiledState(undefined);setSimulationState(undefined);}
    const nextEpoch=ownerEpoch.current;
    const documents=await api.editorDocuments(world);if(ownerEpoch.current===nextEpoch)setDocuments(documents);
  });};
  const remove=async()=>{if(!documentId)return;await execute(async()=>{
    const epoch=ownerEpoch.current,input=liveInputs.current;
    await api.deleteEditorDocument(world,documentId);
    if(ownerEpoch.current!==epoch)return;
    ownerEpoch.current++;
    rememberAuthoringDocument(recoveryOwner,world,'');
    if(liveInputs.current.documentJson===input.documentJson)resetDeletedDocument();
    else {savedDocumentJson.current='';latestRecovery.current={...latestRecovery.current,documentId:'',savedDocumentJson:'',persistedFormalScriptName:''};setDocumentId('');setPersistedFormalScriptName('');setCompiledState(undefined);setSimulationState(undefined);}
    const nextEpoch=ownerEpoch.current;
    const documents=await api.editorDocuments(world);if(ownerEpoch.current===nextEpoch)setDocuments(documents);
  });};
  const compile=async()=>execute(async()=>{
    if(!editorDraftValidity.current.valid||arrivalDraftsInvalid||generated.issues.some((item)=>item.severity==='error'))return;
    const documentHashInput=generated.documentHashInput,input=liveInputs.current,epoch=ownerEpoch.current,draftRevision=editorDraftValidity.current.revision;
    setCompileFailure(undefined);setCompiledState(undefined);setSimulationState(undefined);
    try {
      const result=await api.compileSource(world,value.id,generated.source,hash(documentHashInput));
      if(editorDraftValidity.current.valid&&editorDraftValidity.current.revision===draftRevision&&ownerEpoch.current===epoch&&liveInputs.current.world===input.world&&liveInputs.current.documentJson===input.documentJson)setCompiledState({result,documentHashInput});
    } catch(error) {
      if(editorDraftValidity.current.valid&&editorDraftValidity.current.revision===draftRevision&&ownerEpoch.current===epoch&&liveInputs.current.world===input.world&&liveInputs.current.documentJson===input.documentJson)setCompileFailure({message:error instanceof Error?error.message:String(error),key:documentHashInput});
      throw error;
    }
  });
  const publish=async()=>{if(!editorDraftValidity.current.valid||arrivalDraftsInvalid||!compiled?.validation.valid)return;await execute(async()=>{await api.publish(world,compiled.timetable);});};
  const simulate=async()=>{if(!editorDraftValidity.current.valid||arrivalDraftsInvalid||!hasAllProfiles||hasConflictingProfiles||generated.issues.some((issue)=>issue.severity==='error'))return;await execute(async()=>{
    const assignments=profileDuties.map((duty)=>{
      const selected=profileAssignments[duty.id],separator=selected.indexOf(':');
      return {serviceId:duty.id,profileType:selected.slice(0,separator) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',profileId:selected.slice(separator+1)};
    });
    const epoch=ownerEpoch.current,draftRevision=editorDraftValidity.current.revision;
    const current=()=>editorDraftValidity.current.valid&&editorDraftValidity.current.revision===draftRevision&&ownerEpoch.current===epoch&&liveInputs.current.world===world&&liveInputs.current.simulationInputKey===simulationInputKey;
    setSimulationState(undefined);setCompileFailure(undefined);
    let candidate=compiled;
    if(!candidate){
      candidate=await api.compileSource(world,value.id,generated.source,hash(generated.documentHashInput));
      if(!current())return;
      setCompiledState({result:candidate,documentHashInput:generated.documentHashInput});
    }
    if(!candidate.validation.valid)throw new Error(candidate.validation.errors.join('; '));
    const result=await api.simulateTimetable(world,candidate.timetable,assignments,delayThresholdTicks);
    if(current())setSimulationState({result,inputKey:simulationInputKey});
  });};
  const replaceRun=(next:AuthoringRunV1,clearArrivalOverrides=false)=>{setPreviewRun(undefined);setValue((current)=>{
    const sourceLine=lines.find((line)=>line.id===next.diagramLineId);
    const diagramLines=sourceLine?[...current.diagramLines.filter((line)=>line.id!==sourceLine.id),{id:sourceLine.id,upStationIds:sourceLine.upStationIds,downStationIds:sourceLine.downStationIds}]:current.diagramLines;
    return {...current,diagramLines,runs:current.runs.map((run)=>run.id===next.id?next:run),arrivalOverrides:clearArrivalOverrides?current.arrivalOverrides.filter((override)=>override.runId!==next.id):current.arrivalOverrides};
  });};
  const renameRun=(nextId:string)=>{
    try{if(editorDraftInvalid||arrivalDraftsInvalid)throw new Error(t('programs.invalidDraft'));const next=renameAuthoringRun(value,selectedRun,nextId);if(next===value)return;ownerEpoch.current++;setValue(next);setSelectedRun(nextId);setSelectedPoint(undefined);setMoveOpen(false);setCopyOpen(false);}
    catch(error){const message=error instanceof Error?error.message:String(error);const translated=message==='Run ID is required'?t('ui.runIdRequired'):message==='Run ID already exists'?t('ui.runIdDuplicate'):message;onError(translated);throw new Error(translated);}
  };
  const addCopiedRuns=(copies:AuthoringRunV1[])=>{if(!copies.length)return;setValue((current)=>({...current,runs:[...current.runs,...copies]}));setSelectedRun(copies.at(-1)!.id);};
  const patchDuty=(id:string,patch:Partial<AuthoringDutyV1>)=>setValue((current)=>({...current,operationalTrips:current.operationalTrips?.map((trip)=>({...trip,segments:trip.segments.filter((s)=>!patch.elements||s.serviceId!==id||patch.elements.some((e)=>e.type==='RUN'&&e.runId===s.runId)).map((s)=>patch.id&&s.serviceId===id?{...s,serviceId:patch.id}:s)})),formationEvents:patch.id&&patch.id!==id?renameFormationDuty(current.formationEvents,id,patch.id):current.formationEvents,duties:current.duties.map((duty)=>duty.id===id?{...duty,...patch}:duty)}));
  const addRun=()=>{const [id]=allocateAuthoringIds(value,'RUN');const selectedLine=lines.find((line)=>line.id===graphLineId);if(!selectedLine){onError(t('ui.noLine'));return;}const run:AuthoringRunV1=selectedLine?createRunFromLine(id,selectedLine,runDirection,metadata,()=>uid('call')):{id,type:'PASSENGER',diagramLineId:null,lineDirection:runDirection,calls:[]};setValue((current)=>({...current,runs:[...current.runs,run],diagramLines:selectedLine?[...current.diagramLines.filter((line)=>line.id!==selectedLine.id),{id:selectedLine.id,upStationIds:selectedLine.upStationIds,downStationIds:selectedLine.downStationIds}]:current.diagramLines}));setSelectedRun(id);};
  const openMove=()=>{
    if(!selectedPoint||pointTick==null){onError(t('move.noTimedCall'));return;}
    setMoveSource(generated.documentHashInput);setMoveMode('OFFSET');setMoveValue(0);setMoveInvalid(false);setMoveOpen(true);
  };
  const closeMove=()=>{setMoveOpen(false);setPreviewRun(undefined);};
  const applyMove=()=>{if(!moveCalculation.preview||moveInvalid)return;setValue(moveCalculation.preview.document);closeMove();};
  const deleteSelectedRun=()=>{if(!selectedRun||!window.confirm(t('duties.confirmDeleteRun')))return;const next=value.runs.find((run)=>run.id!==selectedRun&&run.diagramLineId===graphLineId&&run.lineDirection===runDirection)?.id||'';setValue((current)=>deleteRunFromDocument(current,selectedRun));setSelectedRun(next);};
  const addDuty=()=>{const [id]=allocateAuthoringIds(value,'DUTY');setValue({...value,duties:[...value.duties,{id,selector:{type:'TRAIN_NAME',name:''},elements:[]}]});setSelectedDuty(id);};
  const assignRun=(runId:string)=>{if(!selectedDutyValue)return;setValue((current)=>{const next=moveRunToDuty(current,runId,selectedDutyValue.id,uid('element'));return {...next,duties:next.duties.map((duty)=>duty.id===selectedDutyValue.id?{...duty,elements:sortDutyTasks(next,duty)}:duty)};});};
  const removeElement=(elementId:string)=>{if(!selectedDutyValue)return;const element=selectedDutyValue.elements.find(item=>item.id===elementId);if(element?.type==='FORMATION'){const event=value.formationEvents?.find(e=>e.id===element.eventId);if(event&&event.coordinatorDutyId!==selectedDutyValue.id)return;if(event)setValue(current=>removeDutyFormation(current,element.eventId));else patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.filter(item=>item.id!==elementId)});}else patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.filter(item=>item.id!==elementId)});};
  const addFormation=(kind:AuthoringFormationEvent['kind'])=>{if(!selectedDutyValue)return;try{const eventId=uid('formation'),created=createDutyFormation(value,selectedDutyValue.id,kind,eventId,()=>uid('element')),event=created.formationEvents!.find(e=>e.id===eventId)!,tick=Math.min(value.periodDays*24000-1,Math.max(0,...projectDutyTimeline(value,selectedDutyValue,simulation).map(task=>task.endTick??task.startTick))),next=updateDutyFormation(created,{...event,tick},()=>uid('element'));setValue(next);const element=next.duties.find(d=>d.id===selectedDutyValue.id)?.elements.find(e=>e.type==='FORMATION'&&e.eventId===eventId);if(element)setFocusedDutyElement({dutyId:selectedDutyValue.id,elementId:element.id,scope:fieldScope});}catch(error){onError(String(error));}};
  const patchFormation=(event:AuthoringFormationEvent)=>{try{setValue(current=>updateDutyFormation(current,event,()=>uid('element')));}catch(error){onError(String(error));}};
  const setFormationSource=(eventId:string,outputId:string)=>{if(!selectedDutyValue)return;try{setValue(current=>{const released=setDutyVehicleSource(current,selectedDutyValue.id,{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'',outputId:''}),event=released.formationEvents?.find(e=>e.id===eventId);if(!event)return current;const assign=(output:ReturnType<typeof formationOutputs>[number])=>output.id===outputId?{...output,disposition:{type:'DUTY' as const,dutyId:selectedDutyValue.id}}:output;return updateDutyFormation(released,event.kind==='SPLIT'?{...event,outputs:event.outputs.map(o=>({...assign(o),group:o.group}))}:{...event,output:assign(event.output)},()=>uid('element'));});}catch(error){onError(String(error));}};
  const addProgram=(programId:string)=>{
    if(!selectedDutyValue)return;
    const record=programLibrary.find((item)=>item.id===programId);
    try{
      const next=value.boundPrograms?.some((item)=>item.programId===programId)?value:record?adoptStationProgram(value,record):undefined;
      const snapshot=next?.boundPrograms?.find((item)=>item.programId===programId);if(!next||!snapshot)return;
      const tick=Math.min(value.periodDays*24000-1,Math.max(0,...projectDutyTimeline(value,selectedDutyValue,simulation).map((task)=>task.endTick??task.startTick)));
      const operation:TimedOperation={id:uid('element'),tick,program:{programId,arguments:defaultDutyProgramArguments(snapshot.definition.parameters)}};
      setValue({...next,duties:next.duties.map((duty)=>{if(duty.id!==selectedDutyValue.id)return duty;const updated:AuthoringDutyV1={...duty,elements:[...duty.elements,{...operation,type:'PROGRAM',program:operation.program!}]};return {...updated,elements:sortDutyTasks(next,updated)};})});
    }catch(failure){onError(String(failure));}
  };
  const adoptProgram=(programId:string)=>{const record=programLibrary.find((item)=>item.id===programId);if(!record)return;try{setValue(adoptStationProgram(value,record));}catch(failure){onError(String(failure));}};
  const patchProgram=(elementId:string,patch:Partial<TimedOperation>)=>{
    if(!selectedDutyValue)return;
    const programId=patch.program?.programId,record=programLibrary.find((item)=>item.id===programId);
    try{const next=programId&&!value.boundPrograms?.some((item)=>item.programId===programId)&&record?adoptStationProgram(value,record):value;
      const updated:AuthoringDutyV1={...selectedDutyValue,elements:selectedDutyValue.elements.map((element)=>element.id===elementId&&element.type==='PROGRAM'?{...element,...patch,id:element.id,type:'PROGRAM',program:patch.program??element.program}:element)};
      setValue({...next,duties:next.duties.map((duty)=>duty.id===updated.id?{...updated,elements:sortDutyTasks(next,updated)}:duty)});
    }catch(failure){onError(String(failure));}
  };
  const referenceEvent=(eventId:string)=>{if(!selectedDutyValue||!value.events?.some((event)=>event.id===eventId&&event.program))return;const updated:AuthoringDutyV1={...selectedDutyValue,elements:[...selectedDutyValue.elements,{id:uid('element'),type:'EVENT',eventId}]};patchDuty(updated.id,{elements:sortDutyTasks(value,updated)});};

  const patchTimelineDuty=(patch:Partial<AuthoringDutyV1>)=>{if(!selectedDutyValue)return;if(patch.selector){setValue(current=>setDutyVehicleSource(current,selectedDutyValue.id,patch.selector!));return;}renameDuty({...patch,...(patch.elements?{elements:sortDutyTasks(value,{...selectedDutyValue,...patch})}:{})});};
  const selectGraphRun=(serviceIndex:number)=>{const runId=graphValue.services[serviceIndex]?.id,run=value.runs.find((candidate)=>candidate.id===runId);if(runId){setSelectedRun(runId);setFocusedCall('');}if(run)setRunDirection(run.lineDirection);if(run?.diagramLineId)setGraphLineId(run.diagramLineId);};
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
  const compileReason=(editorDraftInvalid||arrivalDraftsInvalid)?t('programs.invalidDraft'):busy?t('ui.busy'):!world?t('ui.noLine'):generated.issues.some((issue)=>issue.severity==='error')?t('ui.fixIssues'):'';
  const simulationReason=(editorDraftInvalid||arrivalDraftsInvalid)?t('programs.invalidDraft'):busy?t('ui.busy'):generated.issues.some((issue)=>issue.severity==='error')||compiled&&!compiled.validation.valid?t('ui.fixIssues'):hasConflictingProfiles?t('ui.profilesConflict'):!hasAllProfiles?t('ui.profilesMissing'):'';
  const commands:AuthoringCommandState={
    'new-run':busy?t('ui.busy'):!graphLineId?t('ui.noLine'):'',
    'copy-run':(editorDraftInvalid||arrivalDraftsInvalid)?t('programs.invalidDraft'):busy?t('ui.busy'):!selectedRunValue?t('duties.selectRun'):'',
    undo:busy?t('ui.busy'):!documentHistory.canUndo?t('ui.noUndo'):'',
    redo:busy?t('ui.busy'):!documentHistory.canRedo?t('ui.noRedo'):'',
    save:(editorDraftInvalid||arrivalDraftsInvalid)?t('programs.invalidDraft'):busy?t('ui.busy'):'',
    'save-formal':compileReason,
    compile:compileReason,simulate:simulationReason,
    publish:(editorDraftInvalid||arrivalDraftsInvalid)?t('programs.invalidDraft'):busy?t('ui.busy'):!compiled?.validation.valid?t('ui.compileFirst'):'',
  };
  const commandKey=JSON.stringify(commands);
  useEffect(()=>{onCommandState?.(commands);},[commandKey,onCommandState]);
  useEffect(()=>{
    const handle=(event:Event)=>{const command=(event as CustomEvent<AuthoringCommand>).detail;if(commands[command]!==undefined&&commands[command]){onError(commands[command]);return;}if(command==='new-run'){addRun();onPageChange?.('graph');}else if(command==='copy-run'){setCopyOffsetInvalid(false);setCopyCountInvalid(false);setCopyOpen(true);}else if(command==='undo')documentHistory.undo();else if(command==='redo')documentHistory.redo();else if(command==='save')void save();else if(command==='save-formal')void saveFormalScript();else if(command==='compile'){onPageChange?.('validation');void compile();}else if(command==='simulate'){onPageChange?.('validation');void simulate();}else if(command==='publish')void publish();};
    window.addEventListener('javarailways:authoring-command',handle);return()=>window.removeEventListener('javarailways:authoring-command',handle);
  });
  const viewRun=(id:string,callId='')=>{const run=value.runs.find((item)=>item.id===id);if(!run)return;setSelectedRun(id);setFocusedCall(callId);setFocusedArrivalStep('');setGraphLineId(run.diagramLineId??'');setRunDirection(run.lineDirection);onPageChange?.('graph');};
  const programTarget=(path:string)=>resolveProgramSourceTarget(value,path,generated.documentHashInput,compiled?{input:compiledState!.documentHashInput,timetable:compiled.timetable}:undefined);
  const visibleFocusedProgram=focusedProgram?.epoch===ownerEpoch.current&&value.boundPrograms?.some((snapshot)=>snapshot.programId===focusedProgram.target.programId&&snapshot.revision===focusedProgram.target.revision&&snapshot.contentIdentity===focusedProgram.target.contentIdentity)?focusedProgram.target:undefined;
  const navigateIssue=(path:string)=>{
    const arrival=resolveArrivalSourceTarget(value,path,generated.documentHashInput,compiled?{input:compiledState!.documentHashInput,timetable:compiled.timetable}:undefined);
    if(arrival){viewRun(arrival.runId,arrival.callId);setFocusedArrivalStep(arrival.stepId??'');return;}
    const target=programTarget(path);
    if(target){setSelectedDuty(target.dutyId);setFocusedProgram({epoch:ownerEpoch.current,target});onPageChange?.('duties');return;}

    const runIndex=/runs\[(\d+)\]/.exec(path),dutyIndex=/duties\[(\d+)\]/.exec(path),overrideIndex=/arrivalOverrides\[(\d+)\]/.exec(path);
    const runId=/run:([^/]+)/.exec(path)?.[1],dutyId=/duty:([^/]+)/.exec(path)?.[1];
    if(runIndex){const run=value.runs[Number(runIndex[1])],callIndex=/calls\[(\d+)\]/.exec(path);if(run)viewRun(run.id,callIndex?run.calls[Number(callIndex[1])]?.id:'');}
    else if(overrideIndex){const override=value.arrivalOverrides[Number(overrideIndex[1])];if(override)viewRun(override.runId,override.callId);}
    else if(runId)viewRun(runId);
    else if(dutyIndex||dutyId){setSelectedDuty(dutyId??value.duties[Number(dutyIndex![1])]?.id??'');onPageChange?.('duties');}
  };
  const renameDuty=(patch:Partial<AuthoringDutyV1>)=>{if(!selectedDutyValue)return;if(patch.id!==undefined&&patch.id!==selectedDutyValue.id&&(editorDraftInvalid||arrivalDraftsInvalid)){onError(t('programs.invalidDraft'));return;}const oldId=selectedDutyValue.id;if(patch.id!==undefined&&(!patch.id.trim()||value.duties.some((duty)=>duty.id!==oldId&&duty.id===patch.id))){onError(t('ui.invalidDutyId'));return;}patchDuty(oldId,patch);if(patch.id!==undefined){setSelectedDuty(patch.id);}};
  const selectPoint=(point:GraphPoint,anchor:{x:number;y:number})=>{
    if(busy||previewRun)return;
    const runId=graphValue.services[point.serviceIndex]?.id,run=effectiveRuns.find((candidate)=>candidate.id===runId);if(!run)return;
    const leg=point.kind==='ARRIVAL'?(point.stepIndex-1)/2:(point.stepIndex-2)/2;
    const call=run.calls[leg+(point.kind==='ARRIVAL'?1:0)];if(!call)return;
    const kind=call.status==='PASS'?'ARRIVAL':point.kind;
    const stepIndex=call.status==='PASS'&&point.kind==='DEPARTURE'?point.stepIndex-3:point.stepIndex;
    setSelectedRun(run.id);setRunDirection(run.lineDirection);setGraphLineId(run.diagramLineId??'');setFocusedCall(call.id);
    setSelectedPoint({runId:run.id,callId:call.id,kind,stepIndex,routeSignature:JSON.stringify(run.calls.map((item)=>[item.id,item.status])),anchor});
  };
  const editPointTick=(tick:number|null)=>{
    if(!selectedPoint||!pointSelectionValid||tick==null||busy)return;
    try{setValue(editAuthoringGraphTime(value,selectedPoint.runId,selectedPoint,tick));}catch(error){onError(graphTimeError(error));throw error;}
  };
  const resetTimes=()=>void execute(async()=>{
    const run=selectedRunValue,profile=simulationProfiles.find((item)=>`${item.type}:${item.id}`===resetProfile);
    if(!run?.diagramLineId||!profile||run.calls.length<2)throw new Error(t('duties.resetMissing'));
    const firstDeparture=run.calls[0]?.departureTick;
    if(firstDeparture==null)throw new Error(t('ui.originRequired'));
    const input=liveInputs.current,epoch=ownerEpoch.current;
    const result=await api.simulateLine(world,run.lineId!,authoringResetProfile(lines,run,{profileType:profile.type,profileId:profile.id}));
    if(ownerEpoch.current!==epoch||liveInputs.current.world!==input.world||liveInputs.current.documentJson!==input.documentJson||liveInputs.current.documentHashInput!==input.documentHashInput)throw new Error(t('duties.resetChanged'));
    const next=resetRunTimings(run,result,firstDeparture,fixedDwell);
    if(next.calls.some((call)=>[call.arrivalTick,call.departureTick].some((tick)=>tick!=null&&tick>=value.periodDays*24000)))throw new Error(t('duties.outsidePeriod'));
    replaceRun(next,true);
  });
  const copyRuns=()=>{
    if(!selectedRunValue||copyInvalid||editorDraftInvalid||arrivalDraftsInvalid)return;
    try{if(!Number.isSafeInteger(copyCount)||copyCount<1||copyCount>100||!Number.isSafeInteger(copyOffset))throw new Error(t('ui.invalidNumber'));const copies=allocateAuthoringIds(value,'RUN',copyCount).map((id,index)=>copyRunShifted(selectedRunValue,id,copyOffset*(index+1),()=>uid('call')));
      if(copies.some((run)=>run.calls.some((call)=>[call.arrivalTick,call.departureTick].some((tick)=>tick!=null&&(tick<0||tick>=value.periodDays*24000)))))throw new Error(t('duties.outsidePeriod'));
      addCopiedRuns(copies);setCopyOpen(false);
    }catch(error){onError(error instanceof Error?error.message:String(error));}
  };
  const fieldScope=`${ownerIdentity.current}/${ownerEpoch.current}/${documentId}/${value.id}`;
  const eventScope=JSON.stringify([fieldScope,graphLineId,page]);
  const eventStationIds=[...new Set(lines.flatMap((line)=>[...line.upStationIds,...line.downStationIds]))];
  const currentGraphLine=lines.find((line)=>line.id===graphLineId);
  const newEventStationId=currentGraphLine?.upStationIds[0]??currentGraphLine?.downStationIds[0];
  useEffect(()=>{setEventDraft(undefined);setEventMenu(undefined);setDutyOperationMenu(undefined);},[eventScope]);
  const openGraphEvent=(original?:TimetableEventPoint)=>{
    if(original&&!timetableEventUnchanged(value.events??[],original)){onError(t('events.changed'));return;}
    setSelectedPoint(undefined);setEventMenu(undefined);
    setEventDraft({scope:eventScope,original:original?{...original}:undefined,operation:original?structuredClone(original):{id:'',stationId:newEventStationId,tick:0},valid:true});
  };
  const deleteEvent=(original:TimetableEventPoint)=>{
    try{const events=deleteTimetableEvent(value.events??[],original);setValue((current)=>({...current,events}));setEventDraft(undefined);setEventMenu(undefined);}
    catch{onError(t('events.changed'));}
  };
  const applyGraphEvent=()=>{
    if(eventDraft?.scope!==eventScope||!eventDraft?.valid)return;
    const draft=eventDraft;
    try{
      const event:TimetableEventPoint={...draft.operation,id:draft.original?.id??''};
      const programId=event.program?.programId,record=programLibrary.find((item)=>item.id===programId);
      const next=programId&&!value.boundPrograms?.some((binding)=>binding.programId===programId)&&record?adoptStationProgram(value,record):value;
      const events=saveTimetableEvent(next.events??[],event,value.periodDays*24000,eventStationIds,draft.original,referencedTimetableEventIds(value));
      setValue({...next,events});setEventDraft(undefined);
    }catch{onError(t(draft.original&&!timetableEventUnchanged(value.events??[],draft.original)?'events.changed':'ui.invalidNumber'));}
  };
  const visibleEventMenu=eventMenu?.scope===eventScope&&timetableEventUnchanged(value.events??[],eventMenu.event)?eventMenu:undefined;
  const eventDraftValid=!!eventDraft&&eventDraft.scope===eventScope&&eventDraft.valid&&!validateTimedOperation(eventDraft.operation,value.periodDays*24000).length&&(!eventDraft.operation.stationId||eventStationIds.includes(eventDraft.operation.stationId))&&(!eventDraft.original||timetableEventUnchanged(value.events??[],eventDraft.original));
  const projectedOperations=projectTimetableOperations(value,simulation);
  const visibleDutyOperationMenu=dutyOperationMenu?.scope===eventScope&&projectedOperations.some((item)=>item.owner==='DUTY'&&item.dutyId===dutyOperationMenu.dutyId&&item.elementId===dutyOperationMenu.elementId&&JSON.stringify(item.operation)===JSON.stringify(dutyOperationMenu.operation))?dutyOperationMenu:undefined;
  useEffect(()=>{if(page!=='graph'||!pendingGraphEvent)return;const original=pendingGraphEvent.scope===fieldScope?value.events?.find((event)=>event.id===pendingGraphEvent.id):undefined;setPendingGraphEvent(undefined);if(original)setEventDraft({scope:eventScope,original:structuredClone(original),operation:structuredClone(original),valid:true});},[page,eventScope,fieldScope,pendingGraphEvent,value.events]);
  const openDutyOperation=(dutyId:string,elementId:string)=>{const duty=value.duties.find((item)=>item.id===dutyId),element=duty?.elements.find((item)=>item.id===elementId);if(!duty||element?.type!=='PROGRAM')return;setSelectedDuty(duty.id);setFocusedDutyElement({dutyId,elementId,scope:fieldScope});const snapshot=value.boundPrograms?.find((item)=>item.programId===element.program.programId);if(snapshot)setFocusedProgram({epoch:ownerEpoch.current,target:{dutyId,elementId,callId:elementId,programId:snapshot.programId,revision:snapshot.revision,contentIdentity:snapshot.contentIdentity}});onPageChange?.('duties');};


  const runTools=<section className="option-section"><Typography.Title level={5}>{t('ui.resetSetup')}</Typography.Title><div className="function-fields">
    <label>{t('duties.originDepartureMode')}<Select value={selectedRunValue?.originDepartureMode??'AUTO'} options={(['AUTO','CONTINUE','REVERSE'] as const).map((mode)=>({value:mode,label:t(`duties.originDeparture.${mode}`)}))} onChange={(originDepartureMode)=>selectedRunValue&&replaceRun({...selectedRunValue,originDepartureMode})}/><Typography.Text type="secondary">{t('duties.originDepartureHelp')}</Typography.Text></label>
    <label>{t('simulation.profile')}<Select value={resetProfile||undefined} onChange={setResetProfile} options={simulationProfiles.map((profile)=>({value:`${profile.type}:${profile.id}`,label:profile.displayName}))}/></label>
    <Typography.Text type="secondary">{selectedRunValue?.calls[0]?.departureTick==null?t('ui.originRequired'):`${t('ui.departure')}: ${selectedRunValue.calls[0].departureTick}`}</Typography.Text>
    <label>{t('duties.fixedDwell')}<CommittedNumberInput fieldKey={`${fieldScope}/${selectedRun}/reset-dwell`} allowEmpty={false} min={0} precision={0} value={fixedDwell} onCommit={(tick)=>setFixedDwell(tick??0)}/></label>
    <Button disabled={busy||!resetProfile||!selectedRunValue?.diagramLineId||selectedRunValue.calls[0]?.departureTick==null} onClick={resetTimes}>{t('duties.resetTimes')}</Button>
  </div></section>;


  const documentSettings=<div className="document-settings">
    <label>{t('duties.document')}<Select value={documentId||'__new__'} onChange={(id)=>void load(id==='__new__'?'':id)} options={[{value:'__new__',label:t('common.newDraft')},...documents.map((item)=>({value:item.id,label:item.id}))]}/></label>
    <label>{t('editor.timetableId')}<Input value={value.id} onChange={(event)=>{const id=event.target.value;if(!persistedFormalScriptName&&formalScriptName===defaultScriptName(value.id))setFormalScriptName(defaultScriptName(id));setValue({...value,id});}}/></label>
    <label>{t('editor.periodDays')}<CommittedNumberInput fieldKey={`${fieldScope}/period`} allowEmpty={false} min={1} precision={0} value={value.periodDays} onCommit={(period)=>{if(period!=null)setValue({...value,periodDays:period});}}/></label>
    <label>{t('duties.scriptFile')}<Input value={formalScriptName} onChange={(event)=>setFormalScriptName(event.target.value)}/></label>
    <label>{t('duties.delayThreshold')}<CommittedNumberInput fieldKey={`${fieldScope}/delay`} allowEmpty={false} min={1} precision={0} value={delayThresholdTicks} onCommit={(ticks)=>{if(ticks!=null)setDelayThresholdTicks(ticks);}}/></label>
    <Flex gap={6} wrap><Button icon={<EditOutlined/>} onClick={()=>void renameFormalScript()} disabled={busy||!persistedFormalScriptName||formalScriptName===persistedFormalScriptName}>{t('duties.renameScript')}</Button><Button danger icon={<DeleteOutlined/>} onClick={()=>void deleteFormalScript()} disabled={busy||!persistedFormalScriptName}>{t('duties.deleteScript')}</Button><Button danger onClick={()=>void remove()} disabled={!documentId||busy||!!persistedFormalScriptName}>{t('common.deleteDocument')}</Button></Flex>
  </div>;
  return <section className="duty-page studio-timetable-page">
    {page==='duties'?<DutyManagement focusedDutyElement={focusedDutyElement?.scope===fieldScope?focusedDutyElement:undefined} onOperationValidityChange={(dutyId,elementId,valid)=>onDraftValidityChange(`duty/${dutyId}/${elementId}`,valid)} onReferenceEvent={referenceEvent} onOpenGraphEvent={(id)=>{setPendingGraphEvent({id,scope:fieldScope});onPageChange?.('graph');}} simulation={simulation} onSimulate={()=>void simulate()} simulationReason={simulationReason} busy={busy} document={value} onDocumentChange={setValue} onOpenFormation={(dutyId,eventId)=>{setSelectedDuty(dutyId);const element=value.duties.find(d=>d.id===dutyId)?.elements.find(e=>e.type==='FORMATION'&&e.eventId===eventId);if(element)setFocusedDutyElement({dutyId,elementId:element.id,scope:fieldScope});}} onAddFormation={addFormation} onPatchFormation={patchFormation} onFormationSource={setFormationSource} focusedProgram={visibleFocusedProgram} onOpenProgramLibrary={onOpenProgramLibrary} programLibrary={programLibrary} boundPrograms={value.boundPrograms} programError={programError} programLoading={programLoading} onRefreshPrograms={()=>void loadPrograms()} onAddProgram={addProgram} onAdoptProgram={adoptProgram} onPatchProgram={patchProgram} fieldScope={fieldScope} duties={value.duties} runs={value.runs} selectedId={selectedDuty} metadata={metadata} onSelect={setSelectedDuty} onAdd={addDuty}
      onDelete={()=>{if(!selectedDutyValue||!window.confirm(t('ui.confirmDuty')))return;setValue((current)=>({...current,operationalTrips:current.operationalTrips?.map((trip)=>({...trip,segments:trip.segments.filter((s)=>s.serviceId!==selectedDutyValue.id)})),duties:current.duties.filter((duty)=>duty.id!==selectedDutyValue.id)}));setSelectedDuty('');}}
      onPatch={patchTimelineDuty} onAssign={assignRun} onRemove={removeElement} onViewRun={viewRun}/>:page==='validation'?<AuthoringValidation fieldScope={fieldScope} duties={value.duties} issues={generated.issues} compiled={compiled} compileError={compileFailure?.key===generated.documentHashInput?compileFailure.message:''}
      simulation={simulation} profiles={simulationProfiles} assignments={profileAssignments} threshold={delayThresholdTicks} conflicting={hasConflictingProfiles} busy={busy} compileReason={compileReason} simulationReason={simulationReason} delayedOrigin={delayedOrigin} source={generated.source}
      onCompile={()=>void compile()} onSimulate={()=>void simulate()} onAssignment={(id,profile)=>{const separator=profile.indexOf(':');if(separator>0)setValue((current)=>({...current,duties:current.duties.map((duty)=>duty.id===id?{...duty,referenceVehicle:{type:profile.slice(0,separator) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',id:profile.slice(separator+1)}}:duty)}));}} onThreshold={setDelayThresholdTicks} onNavigate={navigateIssue} canNavigate={(path)=>!!programTarget(path)||!!resolveArrivalSourceTarget(value,path,generated.documentHashInput,compiled?{input:compiledState!.documentHashInput,timetable:compiled.timetable}:undefined)}/>:<>

    <TimetableWorkspace
      lines={lines} activeLineId={graphLineId} runs={lineRuns} selectedRunId={selectedRun} search={runSearch} direction={runDirection}
      onLineChange={(lineId)=>{setGraphLineId(lineId);const first=value.runs.find((run)=>run.diagramLineId===lineId&&run.lineDirection===runDirection);setSelectedRun(first?.id??'');}}
      onDirectionChange={(direction)=>{setRunDirection(direction);const first=value.runs.find((run)=>run.diagramLineId===graphLineId&&run.lineDirection===direction);setSelectedRun(first?.id??'');}}
      onSearchChange={setRunSearch} onSelectRun={(id)=>{setSelectedRun(id);setFocusedCall('');}} onAddRun={addRun} assignmentFor={(runId)=>assigned.get(runId)}
      graph={<Graph onEditEvent={openGraphEvent} onEventContext={(context)=>{setEventDraft(undefined);setSelectedPoint(undefined);setDutyOperationMenu(undefined);setEventMenu({...context,scope:eventScope});}} operations={projectedOperations} onDutyOperationContext={(context)=>{setEventMenu(undefined);setEventDraft(undefined);setSelectedPoint(undefined);setDutyOperationMenu({...context,scope:eventScope});}} onOpenDutyOperation={openDutyOperation} value={graphValue} metadata={metadata} line={lines.find((line)=>line.id===graphLineId)} graphLineId={graphLineId||undefined}
        highlightedServiceIds={highlightedRunIds} previewServiceIds={previewRunIds} onSelectPoint={selectPoint} onDismissPoint={()=>{if(!moveOpen)setSelectedPoint(undefined);setEventMenu(undefined);setDutyOperationMenu(undefined);}}/>}
      inspector={!selectedRunValue?<p className="empty inspector-empty">{t('duties.selectRun')}</p>:<RunRouteEditor events={value.events} stationPrograms={value.stationPrograms} onStationProgramChange={(program)=>setValue((current)=>({...current,stationPrograms:[...(current.stationPrograms??[]).filter((item)=>item.stationId!==program.stationId),program]}))} programDraftScope={programDraftScope} onArrivalValidityChange={onArrivalValidityChange} focusedArrivalStep={focusedArrivalStep} onUndo={documentHistory.undo} onRedo={documentHistory.redo} onRename={renameRun} fieldScope={fieldScope} focusedCallId={focusedCall} tools={runTools} issues={generated.issues.filter((issue)=>issue.path.startsWith(`runs[${value.runs.findIndex((run)=>run.id===selectedRunValue.id)}]`))} run={selectedRunValue} lines={lines} metadata={metadata} busy={busy} onError={onError} onChange={replaceRun} onDelete={deleteSelectedRun}/>}
      graphActions={<><Button icon={<PlusOutlined/>} onClick={()=>{void loadPrograms();openGraphEvent();}}>{t('events.add')}</Button><Popover trigger="click" placement="bottomRight" title={t('common.documentSettings')} content={documentSettings}><Button type="text" icon={<SettingOutlined/>}/></Popover></>}
      graphPreviewActive={Boolean(previewRun)} saved={JSON.stringify(value)===savedDocumentJson.current}/><TimetableEventsEditor operations={projectedOperations} onEdit={openGraphEvent} onOpenDuty={openDutyOperation} stationNames={stationNames}/></>}

    {visibleEventMenu&&<Dropdown open autoFocus trigger={['click']} placement="bottomLeft" onOpenChange={(open)=>{if(!open)setEventMenu(undefined);}} menu={{'aria-label':t('events.menu'),items:[{key:'edit',label:t('events.edit'),icon:<EditOutlined/>},{key:'delete',label:t('common.delete'),icon:<DeleteOutlined/>,danger:true}],onClick:({key})=>{
      if(eventMenu?.scope!==eventScope)return;
      if(!timetableEventUnchanged(value.events??[],eventMenu.event)){onError(t('events.changed'));setEventMenu(undefined);return;}
      if(key==='edit')openGraphEvent(eventMenu.event);else if(key==='delete')deleteEvent(eventMenu.event);
    }}}><span className="graph-event-menu-anchor" style={{left:visibleEventMenu.anchor.x,top:visibleEventMenu.anchor.y}}/></Dropdown>}

    {visibleDutyOperationMenu&&<Dropdown open autoFocus trigger={['click']} placement="bottomLeft" onOpenChange={(open)=>{if(!open)setDutyOperationMenu(undefined);}} menu={{'aria-label':t('events.menu'),items:[{key:'owner',label:t('timed.openDuty')}],onClick:()=>{openDutyOperation(visibleDutyOperationMenu.dutyId,visibleDutyOperationMenu.elementId);setDutyOperationMenu(undefined);}}}><span className="graph-event-menu-anchor" style={{left:visibleDutyOperationMenu.anchor.x,top:visibleDutyOperationMenu.anchor.y}}/></Dropdown>}
    <Modal title={t(eventDraft?.original?'events.edit':'events.add')} open={Boolean(eventDraft&&eventDraft.scope===eventScope)} onCancel={()=>setEventDraft(undefined)} onOk={applyGraphEvent} okText={t('common.save')} okButtonProps={{disabled:!eventDraftValid}} destroyOnHidden footer={(_, {OkBtn,CancelBtn})=><Flex justify="space-between"><span>{eventDraft?.original&&<Button danger icon={<DeleteOutlined/>} disabled={eventDraft.scope!==eventScope||!timetableEventUnchanged(value.events??[],eventDraft.original)} onClick={()=>{if(eventDraft.scope===eventScope&&eventDraft.original)deleteEvent(eventDraft.original);}}>{t('common.delete')}</Button>}</span><Space><CancelBtn/><OkBtn/></Space></Flex>}>
      <Flex wrap gap={12} align="center"><Button loading={programLoading} onClick={()=>void loadPrograms()}>{t('programs.refresh')}</Button>{programError&&<Typography.Text type="danger" role="alert">{programError}</Typography.Text>}</Flex>
      {eventDraft&&<TimedOperationFields key={`${eventScope}/${eventDraft.original?.id??'new'}`} fieldScope={`${fieldScope}/graph-event/${eventDraft.original?.id??'new'}`} operation={eventDraft.operation} onChange={(operation)=>setEventDraft((current)=>current?{...current,operation}:current)} stationIds={eventStationIds} stationNames={stationNames} bindings={value.boundPrograms??[]} library={programLibrary} allowMarker onValidityChange={(valid)=>setEventDraft((current)=>current&&current.valid!==valid?{...current,valid}:current)} measuredTicks={(()=>{const projection=projectedOperations.find((item)=>item.owner==='GRAPH'&&item.operation.id===eventDraft.original?.id);return JSON.stringify([eventDraft.original?.tick,eventDraft.original?.program])===JSON.stringify([eventDraft.operation.tick,eventDraft.operation.program])&&projection?.measured&&projection.endTick!==undefined?projection.endTick-projection.startTick:undefined;})()} onSimulate={()=>void simulate()} simulationReason={!eventDraft.original||JSON.stringify([eventDraft.original.tick,eventDraft.original.program])!==JSON.stringify([eventDraft.operation.tick,eventDraft.operation.program])?t('timed.saveFirst'):simulationReason} busy={busy} onOpenProgramLibrary={onOpenProgramLibrary}/>}

    </Modal>

    {page==='graph'&&selectedPoint&&pointCall&&!moveOpen&&<div className="graph-node-toolbar panel-surface" role="dialog" aria-label={t('ui.nodeEditor')} style={{left:Math.max(12,Math.min(selectedPoint.anchor.x+12,typeof window==='undefined'?1000:window.innerWidth-330)),top:Math.max(54,Math.min(selectedPoint.anchor.y+12,typeof window==='undefined'?500:window.innerHeight-205))}}>
      <Flex align="center" justify="space-between"><Typography.Text strong>{selectedPoint.runId} · {stationNames.get(pointCall.stationId)||pointCall.stationId}</Typography.Text><Button type="text" aria-label={t('ui.closeNode')} onClick={()=>setSelectedPoint(undefined)}>×</Button></Flex>
      <label>{t(selectedPoint.kind==='ARRIVAL'?'move.arrival':'move.departure')}<CommittedNumberInput fieldKey={`${fieldScope}/${selectedPoint.runId}/${selectedPoint.callId}/${selectedPoint.kind}/node`} aria-label={t('ui.nodeTick')} min={nodeMinimumTick} max={nodeMaximumTick} precision={0} allowEmpty={false} value={pointTick} onCommit={editPointTick}/></label>
      <Typography.Text type="secondary">{t('ui.allowedRange')}: {nodeMinimumTick}–{nodeMaximumTick} Tick · {t('editor.minimumDwell')}: {nodeMinimumDwell}</Typography.Text>
      <Button onClick={openMove} disabled={busy||pointTick==null}>{t('ui.shiftSuffix')}</Button>{pointTick==null&&<Typography.Text type="secondary">{t('move.noTimedCall')}</Typography.Text>}
    </div>}
    <Modal title={t('ui.shiftSuffix')} open={moveOpen} onCancel={closeMove} onOk={applyMove} okText={t('move.apply')} okButtonProps={{disabled:!moveCalculation.preview||moveInvalid}} destroyOnHidden>
      <div className="move-dialog">
        <Typography.Text>{selectedPoint?.runId} · {pointCall?stationNames.get(pointCall.stationId)||pointCall.stationId:''} · {t(selectedPoint?.kind==='ARRIVAL'?'move.arrival':'move.departure')}</Typography.Text>
        <Typography.Text type="secondary">{t('ui.suffixHint')}</Typography.Text>
        <label>{t('move.mode')}<Segmented block value={moveMode} onChange={(next)=>{const mode=next as RunMoveMode;setMoveMode(mode);setMoveInvalid(false);setMoveValue(mode==='OFFSET'?0:pointTick??'');}} options={[{value:'OFFSET',label:t('move.offset')},{value:'ABSOLUTE',label:t('move.toTime')}]}/></label>
        <label>{moveMode==='OFFSET'?t('move.offsetTicks'):t('move.targetTick')}<CommittedNumberInput fieldKey={`${fieldScope}/${selectedPoint?.runId}/${selectedPoint?.callId}/${selectedPoint?.kind}/${moveMode}/suffix`} allowEmpty={false} value={moveValue===''?null:moveValue} min={moveMode==='ABSOLUTE'?0:undefined} precision={0} onValidityChange={(valid)=>setMoveInvalid(!valid)} onCommit={(next)=>setMoveValue(next??'')} addonAfter="tick"/></label>
        {moveMode==='ABSOLUTE'&&moveValue!==''&&<Typography.Text type="secondary">{formatTimetableTick(moveValue,locale)}</Typography.Text>}
        {moveCalculation.preview&&<div className="move-preview"><Typography.Text strong>{t('move.preview')}</Typography.Text><Typography.Text>{t('move.delta')}: {moveCalculation.preview.delta>0?'+':''}{moveCalculation.preview.delta} ticks · {t('move.affected')}: {moveCalculation.preview.affectedEvents}</Typography.Text><Typography.Text>{moveCalculation.preview.anchorTick} → {moveCalculation.preview.targetTick}</Typography.Text></div>}
        {moveCalculation.error&&<Typography.Text type="danger">{moveCalculation.error}</Typography.Text>}
      </div>
    </Modal>
    <Modal title={t('ui.copyRun')} open={copyOpen} onCancel={()=>setCopyOpen(false)} onOk={copyRuns} okText={t('duties.copyShift')} okButtonProps={{disabled:busy||!selectedRunValue||copyInvalid}} destroyOnHidden>
      <div className="move-dialog"><Typography.Text strong>{selectedRunValue?.id}</Typography.Text><Typography.Text type="secondary">{t('duties.copyUnassigned')}</Typography.Text>
        <label>{t('duties.copyOffset')}<CommittedNumberInput fieldKey={`${fieldScope}/${selectedRun}/copy-offset`} allowEmpty={false} precision={0} value={copyOffset} onValidityChange={(valid)=>setCopyOffsetInvalid(!valid)} onCommit={(tick)=>{if(tick!=null)setCopyOffset(tick);}}/></label>
        <label>{t('duties.copyCount')}<CommittedNumberInput fieldKey={`${fieldScope}/${selectedRun}/copy-count`} allowEmpty={false} min={1} max={100} precision={0} value={copyCount} onValidityChange={(valid)=>setCopyCountInvalid(!valid)} onCommit={(count)=>{if(count!=null)setCopyCount(count);}}/></label>
      </div>
    </Modal>
  </section>;
}
