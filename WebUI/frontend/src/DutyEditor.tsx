import { useEffect,useMemo,useRef,useState,type CSSProperties } from 'react';
import { api } from './api';
import {
  authoringGraphTimetable,authoringSelectorKey,createRunFromLine,deleteRunFromDocument,emptyAuthoringDocument,generateTimetableSource,moveRunToDuty,
  updateRunGraphTick,type AuthoringDutyElementV1,type AuthoringDutyV1,type AuthoringFunctionCallV1,type AuthoringRunV1,
  type TimetableAuthoringDocumentV1,
} from './authoring';
import { useDocumentHistory } from './documentHistory';
import { Graph } from './Graph';
import { RunRouteEditor } from './RunRouteEditor';
import { useI18n } from './i18n';
import type { CompiledTimetableSource,EditorDocumentSummary,LineV1,TimetableSimulationResult,WorldMetadata } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const hash=(value:string)=>{let result=2166136261;for(let index=0;index<value.length;index++){result^=value.charCodeAt(index);result=Math.imul(result,16777619);}return (result>>>0).toString(16).padStart(8,'0');};
export const clampAuthoringSidebarWidth=(width:number,viewportWidth:number)=>Math.max(360,Math.min(width,960,Math.max(360,viewportWidth-320)));

export function DutyEditor({world,lines,metadata,onError}:{world:string;lines:LineV1[];metadata:WorldMetadata;onError:(message:string)=>void}) {
  const {t}=useI18n();
  const [documents,setDocuments]=useState<EditorDocumentSummary[]>([]);
  const [documentId,setDocumentId]=useState('');
  const documentHistory=useDocumentHistory<TimetableAuthoringDocumentV1>(emptyAuthoringDocument);
  const {value,setValue}=documentHistory;
  const [selectedRun,setSelectedRun]=useState('');
  const [selectedDuty,setSelectedDuty]=useState('');
  const [sidebarTab,setSidebarTab]=useState<'runs'|'duties'>('runs');
  const [sidebarOpen,setSidebarOpen]=useState(true);
  const [sidebarWidth,setSidebarWidth]=useState(()=>{
    if(typeof window==='undefined')return 620;
    const saved=Number(window.localStorage.getItem('javarailways.authoringSidebarWidth'));
    return clampAuthoringSidebarWidth(Number.isFinite(saved)&&saved>0?saved:620,window.innerWidth);
  });
  const sidebarResize=useRef<{startX:number;startWidth:number}|null>(null);
  const [graphLineId,setGraphLineId]=useState('');
  const [compiledState,setCompiledState]=useState<{result:CompiledTimetableSource;documentHashInput:string}>();
  const [busy,setBusy]=useState(false);
  const [profileAssignments,setProfileAssignments]=useState<Record<string,string>>({});
  const [delayThresholdTicks,setDelayThresholdTicks]=useState(1_200);
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
  const selectedDutyValue=value.duties.find((duty)=>duty.id===selectedDuty);
  const fullGraphValue=useMemo(()=>authoringGraphTimetable({...value,runs:effectiveRuns}),[value,effectiveRuns]);
  const graphValue=useMemo(()=>({...fullGraphValue,services:fullGraphValue.services.filter((service)=>!graphLineId||service.diagramLineId===graphLineId)}),[fullGraphValue,graphLineId]);
  const highlightedRunIds=useMemo(()=>new Set(sidebarTab==='duties'
    ?selectedDutyValue?.elements.flatMap((element)=>element.type==='RUN'?[element.runId]:[])??[]
    :selectedRun?[selectedRun]:[]),[sidebarTab,selectedDutyValue,selectedRun]);
  const authoringWorkspaceStyle={'--authoring-sidebar-width':`${sidebarWidth}px`} as CSSProperties;
  const delayedOrigin=simulation?.delayedServiceId==null||simulation.delayedStepIndex==null?undefined:compiled?.timetable.compilationTrace?.find((trace)=>trace.serviceId===simulation.delayedServiceId&&trace.stepIndex===simulation.delayedStepIndex)?.origin;

  const refresh=async()=>setDocuments(await api.editorDocuments(world));
  useEffect(()=>{if(typeof window!=='undefined')window.localStorage.setItem('javarailways.authoringSidebarWidth',String(sidebarWidth));},[sidebarWidth]);
  useEffect(()=>{setDocumentId('');documentHistory.reset(emptyAuthoringDocument());setSelectedRun('');setSelectedDuty('');setSidebarTab('runs');setGraphLineId('');setCompiledState(undefined);setSimulationState(undefined);setProfileAssignments({});if(world)void refresh().catch((error)=>onError(String(error)));},[world]);
  const execute=async(operation:()=>Promise<void>)=>{setBusy(true);onError('');try{await operation();}catch(error){onError(error instanceof Error?error.message:String(error));}finally{setBusy(false);}};
  const load=async(id:string)=>execute(async()=>{
    setDocumentId(id);setCompiledState(undefined);setSimulationState(undefined);
    if(!id){documentHistory.reset(emptyAuthoringDocument());setSelectedRun('');setSelectedDuty('');return;}
    const loaded=await api.editorDocument(world,id);
    documentHistory.reset(loaded);setSelectedRun(loaded.runs[0]?.id||'');setSelectedDuty(loaded.duties[0]?.id||'');
  });
  const save=async()=>execute(async()=>{if(documentId)await api.saveEditorDocument(world,documentId,value);else{const created=await api.createEditorDocument(world,value);setDocumentId(created.id);}await refresh();});
  const remove=async()=>{if(!documentId)return;await execute(async()=>{await api.deleteEditorDocument(world,documentId);setDocumentId('');documentHistory.reset(emptyAuthoringDocument());setSelectedRun('');setSelectedDuty('');setCompiledState(undefined);setSimulationState(undefined);await refresh();});};
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
  const replaceRun=(next:AuthoringRunV1,clearArrivalOverrides=false)=>setValue((current)=>{
    const sourceLine=lines.find((line)=>line.id===next.diagramLineId);
    const diagramLines=sourceLine?[...current.diagramLines.filter((line)=>line.id!==sourceLine.id),{id:sourceLine.id,upStationIds:sourceLine.upStationIds,downStationIds:sourceLine.downStationIds}]:current.diagramLines;
    return {...current,diagramLines,runs:current.runs.map((run)=>run.id===next.id?next:run),arrivalOverrides:clearArrivalOverrides?current.arrivalOverrides.filter((override)=>override.runId!==next.id):current.arrivalOverrides};
  });
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
  const addRun=()=>{const id=`run-${value.runs.length+1}`;const firstLine=lines[0];const run:AuthoringRunV1=firstLine?createRunFromLine(id,firstLine,'UP',metadata,()=>uid('call')):{id,type:'PASSENGER',diagramLineId:null,lineDirection:'UP',calls:[]};setValue((current)=>({...current,runs:[...current.runs,run],diagramLines:firstLine?[...current.diagramLines.filter((line)=>line.id!==firstLine.id),{id:firstLine.id,upStationIds:firstLine.upStationIds,downStationIds:firstLine.downStationIds}]:current.diagramLines}));setSelectedRun(id);};
  const deleteSelectedRun=()=>{if(!selectedRun||!window.confirm(t('duties.confirmDeleteRun')))return;const next=value.runs.find((run)=>run.id!==selectedRun)?.id||'';setValue((current)=>deleteRunFromDocument(current,selectedRun));setSelectedRun(next);};
  const addDuty=()=>{const id=`duty-${value.duties.length+1}`;setValue({...value,duties:[...value.duties,{id,selector:{type:'TRAIN_NAME',name:''},elements:[]}]});setSelectedDuty(id);};
  const assignRun=(runId:string)=>{if(!selectedDutyValue)return;setValue((current)=>moveRunToDuty(current,runId,selectedDutyValue.id,uid('element')));};
  const removeElement=(elementId:string)=>{if(selectedDutyValue)patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.filter((item)=>item.id!==elementId)});};
  const addFunction=(call:AuthoringFunctionCallV1)=>{if(!selectedDutyValue)return;const element:AuthoringDutyElementV1={id:uid('element'),type:'FUNCTION',call};const elements=call.type==='PLACE_INITIAL'?[element,...selectedDutyValue.elements]:[...selectedDutyValue.elements,element];patchDuty(selectedDutyValue.id,{elements});};
  const updateGraphTick=(serviceIndex:number,stepIndex:number,tick:number,kind:'DEPARTURE'|'ARRIVAL')=>{
    const runId=graphValue.services[serviceIndex]?.id;
    if(!runId)return;
    setValue((current)=>{
      const stored=current.runs.find((run)=>run.id===runId);
      if(!stored)return current;
      const effective={...stored,calls:stored.calls.map((call)=>{
        const override=current.arrivalOverrides.find((item)=>item.runId===runId&&item.callId===call.id);
        return override?{...call,arrivalTick:override.tick}:call;
      })};
      const updated=updateRunGraphTick(effective,stepIndex,kind,tick);
      return {...current,runs:current.runs.map((run)=>run.id===runId?updated:run),arrivalOverrides:current.arrivalOverrides.filter((override)=>override.runId!==runId)};
    });
  };
  const selectGraphRun=(serviceIndex:number)=>{const runId=graphValue.services[serviceIndex]?.id;if(runId)setSelectedRun(runId);};
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

  return <section className="duty-page page-scroll">
    <div className="duty-toolbar card">
      <label>{t('duties.document')}<select value={documentId} onChange={(event)=>void load(event.target.value)}><option value="">{t('common.newDraft')}</option>{documents.map((item)=><option key={item.id} value={item.id}>{item.id}</option>)}</select></label>
      <label>{t('editor.timetableId')}<input value={value.id} onChange={(event)=>setValue({...value,id:event.target.value})}/></label>
      <label>{t('editor.periodDays')}<input type="number" min="1" value={value.periodDays} onChange={(event)=>setValue({...value,periodDays:Math.max(1,+event.target.value)})}/></label>
      <button onClick={()=>void save()} disabled={busy}>{t('common.save')}</button>
      <button className="danger" onClick={()=>void remove()} disabled={!documentId||busy}>{t('common.delete')}</button>
      <button onClick={documentHistory.undo} disabled={!documentHistory.canUndo||busy}>{t('common.undo')}</button>
      <button onClick={documentHistory.redo} disabled={!documentHistory.canRedo||busy}>{t('common.redo')}</button>
      <span className="spacer"/>
      <button onClick={()=>void compile()} disabled={busy||generated.issues.some((item)=>item.severity==='error')}>{t('duties.compile')}</button>
      <label>{t('duties.delayThreshold')}<input type="number" min="1" value={delayThresholdTicks} onChange={(event)=>setDelayThresholdTicks(Math.max(1,+event.target.value))}/></label>
      <button onClick={()=>void simulate()} disabled={busy||!compiled?.validation.valid||!hasAllProfiles}>{t('duties.simulate')}</button>
      <button className="primary" onClick={()=>void publish()} disabled={busy||!compiled?.validation.valid}>{t('common.publish')}</button>
    </div>

    <div className={`authoring-workspace ${sidebarOpen?'':'sidebar-collapsed'}`} style={authoringWorkspaceStyle}>
      <section className="authoring-graph">
        <div className="graph-overlay"><strong>{t('editor.graph')}</strong>
          <select value={graphLineId} onChange={(event)=>setGraphLineId(event.target.value)}><option value="">{t('lines.all')}</option>{lines.map((line)=><option key={line.id} value={line.id}>{line.displayName}</option>)}</select>
          <span>{sidebarTab==='duties'?t('duties.highlightHint'):t('editor.graphHint')}</span>
        </div>
        <Graph value={graphValue} metadata={metadata} line={lines.find((line)=>line.id===graphLineId)} graphLineId={graphLineId||undefined}
          readOnly={sidebarTab==='duties'} highlightedServiceIds={highlightedRunIds} onSelect={(service)=>selectGraphRun(service)} onTick={updateGraphTick}/>
      </section>

      <div className="authoring-sidebar-resizer" title={t('editor.sidebarWidth')} role="separator" aria-orientation="vertical"
        aria-label={t('editor.sidebarWidth')} aria-valuemin={360} aria-valuemax={960} aria-valuenow={sidebarWidth} tabIndex={sidebarOpen?0:-1}
        onKeyDown={(event)=>{if(event.key==='ArrowLeft')setSidebarWidth((width)=>clampAuthoringSidebarWidth(width+20,window.innerWidth));if(event.key==='ArrowRight')setSidebarWidth((width)=>clampAuthoringSidebarWidth(width-20,window.innerWidth));}}
        onPointerDown={(event)=>{if(!sidebarOpen)return;sidebarResize.current={startX:event.clientX,startWidth:sidebarWidth};event.currentTarget.setPointerCapture(event.pointerId);}}
        onPointerMove={(event)=>{const resize=sidebarResize.current;if(!resize)return;setSidebarWidth(clampAuthoringSidebarWidth(resize.startWidth+resize.startX-event.clientX,window.innerWidth));}}
        onPointerUp={(event)=>{sidebarResize.current=null;event.currentTarget.releasePointerCapture(event.pointerId);}}
        onPointerCancel={()=>{sidebarResize.current=null;}}>
        <button title={sidebarOpen?t('editor.sidebarHide'):t('editor.sidebarShow')} aria-expanded={sidebarOpen}
          onPointerDown={(event)=>event.stopPropagation()} onClick={()=>setSidebarOpen((open)=>!open)}>{sidebarOpen?'›':'‹'}</button>
      </div>

      <aside className="card authoring-sidebar">
        <div className="authoring-tabs" role="tablist">
          <button role="tab" aria-selected={sidebarTab==='runs'} className={sidebarTab==='runs'?'active':''} onClick={()=>setSidebarTab('runs')}>{t('duties.runs')}</button>
          <button role="tab" aria-selected={sidebarTab==='duties'} className={sidebarTab==='duties'?'active':''} onClick={()=>setSidebarTab('duties')}>{t('duties.duties')}</button>
        </div>

        {sidebarTab==='runs'&&<div className="authoring-tab-panel">
          <div className="section-title"><h2>{t('duties.runs')}</h2><span className="section-actions"><button onClick={addRun}>＋</button><button className="danger" disabled={!selectedRun} onClick={deleteSelectedRun}>{t('common.delete')}</button></span></div>
          <div className="sidebar-run-list">{value.runs.map((run)=><button className={selectedRun===run.id?'active':''} key={run.id} onClick={()=>setSelectedRun(run.id)}><strong>{run.id}</strong><small>{assigned.get(run.id)||t('duties.unassigned')}</small></button>)}</div>
          {!selectedRunValue?<p className="empty">{t('duties.selectRun')}</p>:<>
            <RunRouteEditor world={world} run={selectedRunValue} lines={lines} metadata={metadata} simulationProfiles={simulationProfiles}
              defaultProfileKey={assigned.get(selectedRunValue.id)?profileAssignments[assigned.get(selectedRunValue.id)!]:undefined}
              existingRunIds={new Set(value.runs.map((run)=>run.id))} periodTicks={value.periodDays*24_000} busy={busy} onError={onError}
              onChange={replaceRun} onRename={renameRun} onCopy={addCopiedRuns}/>
            {assigned.get(selectedRunValue.id)!==selectedDutyValue?.id&&selectedDutyValue&&<button onClick={()=>assignRun(selectedRunValue.id)}>{t('duties.assign')} {selectedDutyValue.id}</button>}
          </>}
        </div>}

        {sidebarTab==='duties'&&<div className="authoring-tab-panel duty-chain">
          <div className="section-title"><h2>{t('duties.duties')}</h2><button onClick={addDuty}>＋</button></div>
          <select value={selectedDuty} onChange={(event)=>setSelectedDuty(event.target.value)}><option value="">{t('common.select')}</option>{value.duties.map((duty)=><option key={duty.id} value={duty.id}>{duty.id}</option>)}</select>
          {selectedDutyValue&&<>
            <input value={selectedDutyValue.id} onChange={(event)=>{const previousId=selectedDutyValue.id,id=event.target.value;patchDuty(previousId,{id});setProfileAssignments((current)=>{const next={...current};if(next[previousId]){next[id]=next[previousId];delete next[previousId];}return next;});setSelectedDuty(id);}}/>
            <label>{t('simulation.profile')}<select value={profileAssignments[selectedDutyValue.id]||''} onChange={(event)=>{const selected=event.target.value,key=authoringSelectorKey(selectedDutyValue.selector);setProfileAssignments((current)=>{const next={...current};value.duties.filter((duty)=>authoringSelectorKey(duty.selector)===key).forEach((duty)=>{next[duty.id]=selected;});return next;});}}><option value="">{t('common.select')}</option>{simulationProfiles.map((profile)=><option key={`${profile.type}:${profile.id}`} value={`${profile.type}:${profile.id}`}>{profile.displayName} · {profile.carCount}</option>)}</select></label>
            <select value={selectedDutyValue.selector.type} onChange={(event)=>{const type=event.target.value as AuthoringDutyV1['selector']['type'];patchDuty(selectedDutyValue.id,{selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:{type,wayPointIds:[],tick:0}});}}><option value="TRAIN_NAME">{t('editor.fixedTrain')}</option><option value="TAKE_FROM_DEPOT">{t('actions.TAKE_FROM_DEPOT')}</option><option value="TAKE_FROM_WAYPOINT">{t('actions.TAKE_FROM_WAYPOINT')}</option></select>
            {selectedDutyValue.selector.type==='TRAIN_NAME'&&<input list="trains" placeholder={t('editor.trainId')} value={selectedDutyValue.selector.name} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TRAIN_NAME',name:event.target.value}})}/>}
            {selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'&&<div className="duty-parameter-fields"><label>{t('duties.depot')}<input list="depots" value={selectedDutyValue.selector.depotId} onChange={(event)=>patchDepotSelector({depotId:event.target.value})}/></label><label>{t('duties.acquireTick')}<input type="number" min="0" value={selectedDutyValue.selector.tick} onChange={(event)=>patchDepotSelector({tick:Math.max(0,+event.target.value)})}/></label><label>{t('duties.requiredTags')}<input value={selectedDutyValue.selector.tags.join(', ')} onChange={(event)=>patchDepotSelector({tags:event.target.value.split(',').map((item)=>item.trim()).filter(Boolean)})}/></label></div>}
            {selectedDutyValue.selector.type==='TAKE_FROM_WAYPOINT'&&<div className="duty-parameter-fields"><label>{t('duties.waypoints')}<input list="points" value={selectedDutyValue.selector.wayPointIds.join(', ')} onChange={(event)=>patchWaypointSelector({wayPointIds:event.target.value.split(',').map((item)=>item.trim()).filter(Boolean)})}/></label><label>{t('duties.acquireTick')}<input type="number" min="0" value={selectedDutyValue.selector.tick} onChange={(event)=>patchWaypointSelector({tick:Math.max(0,+event.target.value)})}/></label></div>}

            <h3>{t('duties.runs')}</h3>
            <div className="duty-run-picker">{value.runs.map((run)=>{const owner=assigned.get(run.id),included=owner===selectedDutyValue.id;return <button key={run.id} aria-pressed={included} className={included?'included':''} onClick={()=>addRunToSelectedDuty(run.id)}><strong>{run.id}</strong><small>{included?t('duties.inSelectedDuty'):owner||t('duties.unassigned')}</small></button>;})}</div>

            <div className="duty-function-buttons"><button onClick={()=>addFunction({id:uid('initial'),type:'PLACE_INITIAL',trainName:selectedDutyValue.selector.type==='TRAIN_NAME'?selectedDutyValue.selector.name:'',wayPointId:''})}>{t('duties.initial')}</button><button onClick={()=>addFunction({id:uid('reverse'),type:'REVERSE',tick:0})}>{t('actions.REVERSE')}</button><button onClick={()=>addFunction({id:uid('wait'),type:'WAIT',tick:0,durationTicks:20})}>{t('actions.WAIT')}</button><button onClick={()=>addFunction({id:uid('store'),type:'STORE_AT_DEPOT',tick:0,depotId:'',stablingPointIds:[]})}>{t('actions.STORE_AT_DEPOT')}</button></div>
            <ol className="duty-elements">{selectedDutyValue.elements.map((element,index)=><li className={element.type==='RUN'?'run-element':''} key={element.id}>
              <strong>{element.type==='RUN'?`RUN · ${element.runId}`:t(element.call.type==='PLACE_INITIAL'?'duties.initial':`actions.${element.call.type}` as Parameters<typeof t>[0])}</strong>
              {element.type==='FUNCTION'&&<div className="duty-element-fields">
                {element.call.type==='PLACE_INITIAL'&&<label>{t('duties.initialWaypoint')}<input list="points" value={element.call.wayPointId} onChange={(event)=>patchFunction(element.id,{wayPointId:event.target.value})}/></label>}
                {'tick'in element.call&&<label>{t('duties.executeTick')}<input type="number" min="0" value={element.call.tick} onChange={(event)=>patchFunction(element.id,{tick:Math.max(0,+event.target.value)})}/></label>}
                {element.call.type==='WAIT'&&<label>{t('duties.waitDuration')}<input type="number" min="1" value={element.call.durationTicks} onChange={(event)=>patchFunction(element.id,{durationTicks:Math.max(1,+event.target.value)})}/></label>}
                {element.call.type==='STORE_AT_DEPOT'&&<><label>{t('duties.depot')}<input list="depots" value={element.call.depotId} onChange={(event)=>patchFunction(element.id,{depotId:event.target.value})}/></label><label>{t('editor.stablingIds')}<input value={element.call.stablingPointIds.join(', ')} onChange={(event)=>patchFunction(element.id,{stablingPointIds:event.target.value.split(',').map((item)=>item.trim()).filter(Boolean)})}/></label></>}
              </div>}
              <span className="row-controls"><button disabled={index===0} onClick={()=>moveElement(element.id,-1)}>↑</button><button disabled={index===selectedDutyValue.elements.length-1} onClick={()=>moveElement(element.id,1)}>↓</button><button className="danger" onClick={()=>removeElement(element.id)}>×</button></span>
            </li>)}</ol>
          </>}
        </div>}
      </aside>
    </div>

    <details className="authoring-output card">
      <summary>{t('duties.issues')} · {t('duties.generatedSource')}</summary>
      <div className="duty-output-grid"><section><h3>{t('duties.issues')}</h3>{generated.issues.length===0?<p className="success">{t('editor.noIssues')}</p>:generated.issues.map((item,index)=><p className={item.severity} key={index}><code>{item.path}</code> {item.message}</p>)}{compiled&&<p className={compiled.validation.valid?'success':'error'}>{compiled.validation.valid?t('scripts.valid'):compiled.validation.errors.join('; ')}</p>}{simulation&&<div><h3>{t('duties.simulationResult')}</h3><p className={simulation.status==='COMPLETED'?'success':simulation.status==='DELAY_EXCEEDED'?'warning':'error'}>{simulation.status} · {simulation.simulatedTicks} ticks</p>{simulation.observedDelayTicks!=null&&<p>{t('duties.detectedDelay')}: {simulation.observedDelayTicks} ticks · {delayedOrigin||`${simulation.delayedServiceId} / step ${simulation.delayedStepIndex}`}</p>}{simulation.reason&&<p className="error">{simulation.reason}</p>}</div>}</section><section className="source-view"><h3>{t('duties.generatedSource')}</h3><pre>{generated.source}</pre></section></div>
    </details>
  </section>;
}
