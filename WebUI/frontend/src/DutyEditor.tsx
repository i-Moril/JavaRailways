import { useEffect,useMemo,useState } from 'react';
import { api } from './api';
import {
  emptyAuthoringDocument,generateTimetableSource,type AuthoringDutyElementV1,type AuthoringDutyV1,
  type AuthoringFunctionCallV1,type AuthoringRunV1,type TimetableAuthoringDocumentV1,
} from './authoring';
import { useI18n } from './i18n';
import type { CompiledTimetableSource,EditorDocumentSummary,LineV1 } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const hash=(value:string)=>{let result=2166136261;for(let index=0;index<value.length;index++){result^=value.charCodeAt(index);result=Math.imul(result,16777619);}return (result>>>0).toString(16).padStart(8,'0');};

export function DutyEditor({world,lines,onError}:{world:string;lines:LineV1[];onError:(message:string)=>void}) {
  const {t}=useI18n();
  const [documents,setDocuments]=useState<EditorDocumentSummary[]>([]);
  const [documentId,setDocumentId]=useState('');
  const [value,setValue]=useState<TimetableAuthoringDocumentV1>(emptyAuthoringDocument);
  const [selectedRun,setSelectedRun]=useState('');
  const [selectedDuty,setSelectedDuty]=useState('');
  const [compiled,setCompiled]=useState<CompiledTimetableSource>();
  const [busy,setBusy]=useState(false);
  const generated=useMemo(()=>generateTimetableSource(value),[value]);
  const assigned=new Map(value.duties.flatMap((duty)=>duty.elements.flatMap((element)=>element.type==='RUN'?[[element.runId,duty.id] as const]:[])));
  const selectedRunValue=value.runs.find((run)=>run.id===selectedRun);
  const selectedDutyValue=value.duties.find((duty)=>duty.id===selectedDuty);

  const refresh=async()=>setDocuments(await api.editorDocuments(world));
  useEffect(()=>{setDocumentId('');setValue(emptyAuthoringDocument());setSelectedRun('');setSelectedDuty('');setCompiled(undefined);if(world)void refresh().catch((error)=>onError(String(error)));},[world]);
  const execute=async(operation:()=>Promise<void>)=>{setBusy(true);onError('');try{await operation();}catch(error){onError(error instanceof Error?error.message:String(error));}finally{setBusy(false);}};
  const load=async(id:string)=>execute(async()=>{setDocumentId(id);setCompiled(undefined);if(!id){setValue(emptyAuthoringDocument());return;}setValue(await api.editorDocument(world,id));});
  const save=async()=>execute(async()=>{if(documentId)await api.saveEditorDocument(world,documentId,value);else{const created=await api.createEditorDocument(world,value);setDocumentId(created.id);}await refresh();});
  const remove=async()=>{if(!documentId)return;await execute(async()=>{await api.deleteEditorDocument(world,documentId);setDocumentId('');setValue(emptyAuthoringDocument());setCompiled(undefined);await refresh();});};
  const compile=async()=>execute(async()=>{
    if(generated.issues.some((item)=>item.severity==='error')){setCompiled(undefined);return;}
    setCompiled(await api.compileSource(world,value.id,generated.source,hash(generated.documentHashInput)));
  });
  const publish=async()=>{if(!compiled?.validation.valid)return;await execute(async()=>{await api.publish(world,compiled.timetable);});};
  const patchRun=(id:string,patch:Partial<AuthoringRunV1>)=>setValue((current)=>({...current,runs:current.runs.map((run)=>run.id===id?{...run,...patch}:run)}));
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
  const addRun=()=>{const id=`run-${value.runs.length+1}`;const run:AuthoringRunV1={id,type:'PASSENGER',calls:[{id:uid('origin'),targetId:'',departureTick:0},{id:uid('terminal'),targetId:'',arrivalTick:null}]};setValue({...value,runs:[...value.runs,run]});setSelectedRun(id);};
  const addDuty=()=>{const id=`duty-${value.duties.length+1}`;setValue({...value,duties:[...value.duties,{id,selector:{type:'TRAIN_NAME',name:''},elements:[]}]});setSelectedDuty(id);};
  const assignRun=(runId:string)=>{if(!selectedDutyValue||assigned.has(runId))return;patchDuty(selectedDutyValue.id,{elements:[...selectedDutyValue.elements,{id:uid('element'),type:'RUN',runId}]});};
  const removeElement=(elementId:string)=>{if(selectedDutyValue)patchDuty(selectedDutyValue.id,{elements:selectedDutyValue.elements.filter((item)=>item.id!==elementId)});};
  const addFunction=(call:AuthoringFunctionCallV1)=>{if(!selectedDutyValue)return;const element:AuthoringDutyElementV1={id:uid('element'),type:'FUNCTION',call};const elements=call.type==='PLACE_INITIAL'?[element,...selectedDutyValue.elements]:[...selectedDutyValue.elements,element];patchDuty(selectedDutyValue.id,{elements});};

  return <section className="duty-page page-scroll">
    <div className="duty-toolbar card">
      <label>{t('duties.document')}<select value={documentId} onChange={(event)=>void load(event.target.value)}><option value="">{t('common.newDraft')}</option>{documents.map((item)=><option key={item.id} value={item.id}>{item.id}</option>)}</select></label>
      <label>{t('editor.timetableId')}<input value={value.id} onChange={(event)=>setValue({...value,id:event.target.value})}/></label>
      <label>{t('editor.periodDays')}<input type="number" min="1" value={value.periodDays} onChange={(event)=>setValue({...value,periodDays:Math.max(1,+event.target.value)})}/></label>
      <button onClick={()=>void save()} disabled={busy}>{t('common.save')}</button><button className="danger" onClick={()=>void remove()} disabled={!documentId||busy}>{t('common.delete')}</button>
      <span className="spacer"/><button onClick={()=>void compile()} disabled={busy||generated.issues.some((item)=>item.severity==='error')}>{t('duties.compile')}</button>
      <button className="primary" onClick={()=>void publish()} disabled={busy||!compiled?.validation.valid}>{t('common.publish')}</button>
    </div>
    <div className="duty-columns">
      <aside className="card duty-list"><div className="section-title"><h2>{t('duties.runs')}</h2><button onClick={addRun}>＋</button></div>
        {value.runs.map((run)=><button className={selectedRun===run.id?'active':''} key={run.id} onClick={()=>setSelectedRun(run.id)}><strong>{run.id}</strong><small>{assigned.get(run.id)||t('duties.unassigned')}</small></button>)}
      </aside>
      <article className="card duty-run-editor">
        {!selectedRunValue?<p className="empty">{t('duties.selectRun')}</p>:<>
          <div className="section-title"><input value={selectedRunValue.id} onChange={(event)=>{const id=event.target.value;setValue((current)=>({...current,runs:current.runs.map((run)=>run.id===selectedRunValue.id?{...run,id}:run),duties:current.duties.map((duty)=>({...duty,elements:duty.elements.map((element)=>element.type==='RUN'&&element.runId===selectedRunValue.id?{...element,runId:id}:element)}))}));setSelectedRun(id);}}/><select value={selectedRunValue.diagramLineId||''} onChange={(event)=>{const line=lines.find((item)=>item.id===event.target.value);patchRun(selectedRunValue.id,{diagramLineId:event.target.value||null});if(line&&!value.diagramLines.some((item)=>item.id===line.id))setValue((current)=>({...current,diagramLines:[...current.diagramLines,{id:line.id,upStationIds:line.upStationIds,downStationIds:line.downStationIds}]}));}}><option value="">{t('common.none')}</option>{lines.map((line)=><option key={line.id} value={line.id}>{line.displayName}</option>)}</select><select value={selectedRunValue.type} onChange={(event)=>patchRun(selectedRunValue.id,{type:event.target.value as AuthoringRunV1['type']})}><option value="PASSENGER">PASSENGER</option><option value="DEADHEAD">DEADHEAD</option></select></div>
          <ol className="duty-calls">{selectedRunValue.calls.map((call,index)=><li key={call.id}><input placeholder={t('editor.target')} value={call.targetId} onChange={(event)=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.map((item)=>item.id===call.id?{...item,targetId:event.target.value}:item)})}/><input type="number" min="0" placeholder={t('editor.arrivalTime')} value={call.arrivalTick??''} onChange={(event)=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.map((item)=>item.id===call.id?{...item,arrivalTick:event.target.value===''?null:+event.target.value}:item)})}/><input type="number" min="0" placeholder={t('editor.departureTime')} value={call.departureTick??''} onChange={(event)=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.map((item)=>item.id===call.id?{...item,departureTick:event.target.value===''?null:+event.target.value}:item)})}/><input type="number" min="0" placeholder={t('editor.minimumDwell')} value={call.minimumDwellTicks??''} onChange={(event)=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.map((item)=>item.id===call.id?{...item,minimumDwellTicks:event.target.value===''?null:+event.target.value}:item)})}/><select title={t('editor.doorSide')} value={call.doorSide||''} onChange={(event)=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.map((item)=>item.id===call.id?{...item,doorSide:(event.target.value||null) as typeof call.doorSide}:item)})}><option value="">—</option><option>NONE</option><option>LEFT</option><option>RIGHT</option><option>BOTH</option></select><button className="danger" disabled={selectedRunValue.calls.length<=2} onClick={()=>patchRun(selectedRunValue.id,{calls:selectedRunValue.calls.filter((item)=>item.id!==call.id)})}>×</button>{index===selectedRunValue.calls.length-1&&<button onClick={()=>patchRun(selectedRunValue.id,{calls:[...selectedRunValue.calls,{id:uid('call'),targetId:'',arrivalTick:null,departureTick:null}]})}>＋</button>}</li>)}</ol>
          {!assigned.has(selectedRunValue.id)&&selectedDutyValue&&<button onClick={()=>assignRun(selectedRunValue.id)}>{t('duties.assign')} {selectedDutyValue.id}</button>}
        </>}
      </article>
      <aside className="card duty-chain"><div className="section-title"><h2>{t('duties.duties')}</h2><button onClick={addDuty}>＋</button></div>
        <select value={selectedDuty} onChange={(event)=>setSelectedDuty(event.target.value)}><option value="">{t('common.select')}</option>{value.duties.map((duty)=><option key={duty.id} value={duty.id}>{duty.id}</option>)}</select>
        {selectedDutyValue&&<><input value={selectedDutyValue.id} onChange={(event)=>{const id=event.target.value;patchDuty(selectedDutyValue.id,{id});setSelectedDuty(id);}}/>
          <select value={selectedDutyValue.selector.type} onChange={(event)=>{const type=event.target.value as AuthoringDutyV1['selector']['type'];patchDuty(selectedDutyValue.id,{selector:type==='TRAIN_NAME'?{type,name:''}:type==='TAKE_FROM_DEPOT'?{type,depotId:'',tick:0,tags:[]}:{type,wayPointIds:[],tick:0}});}}><option value="TRAIN_NAME">{t('editor.fixedTrain')}</option><option value="TAKE_FROM_DEPOT">{t('actions.TAKE_FROM_DEPOT')}</option><option value="TAKE_FROM_WAYPOINT">{t('actions.TAKE_FROM_WAYPOINT')}</option></select>
          {selectedDutyValue.selector.type==='TRAIN_NAME'&&<input list="trains" placeholder={t('editor.trainId')} value={selectedDutyValue.selector.name} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TRAIN_NAME',name:event.target.value}})}/>} 
          {selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'&&<><input list="depots" placeholder={t('editor.target')} value={selectedDutyValue.selector.depotId} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TAKE_FROM_DEPOT',depotId:event.target.value,tick:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.tick:0,tags:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.tags:[]}})}/><input type="number" min="0" title={t('editor.tick')} value={selectedDutyValue.selector.tick} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TAKE_FROM_DEPOT',depotId:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.depotId:'',tick:Math.max(0,+event.target.value),tags:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.tags:[]}})}/><input placeholder="tags" value={selectedDutyValue.selector.tags.join(', ')} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TAKE_FROM_DEPOT',depotId:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.depotId:'',tick:selectedDutyValue.selector.type==='TAKE_FROM_DEPOT'?selectedDutyValue.selector.tick:0,tags:event.target.value.split(',').map((item)=>item.trim()).filter(Boolean)}})}/></>} 
          {selectedDutyValue.selector.type==='TAKE_FROM_WAYPOINT'&&<><input list="points" placeholder={t('editor.target')} value={selectedDutyValue.selector.wayPointIds.join(', ')} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TAKE_FROM_WAYPOINT',wayPointIds:event.target.value.split(',').map((item)=>item.trim()).filter(Boolean),tick:selectedDutyValue.selector.type==='TAKE_FROM_WAYPOINT'?selectedDutyValue.selector.tick:0}})}/><input type="number" min="0" title={t('editor.tick')} value={selectedDutyValue.selector.tick} onChange={(event)=>patchDuty(selectedDutyValue.id,{selector:{type:'TAKE_FROM_WAYPOINT',wayPointIds:selectedDutyValue.selector.type==='TAKE_FROM_WAYPOINT'?selectedDutyValue.selector.wayPointIds:[],tick:Math.max(0,+event.target.value)}})}/></>}
          <div className="duty-function-buttons"><button onClick={()=>addFunction({id:uid('initial'),type:'PLACE_INITIAL',trainName:selectedDutyValue.selector.type==='TRAIN_NAME'?selectedDutyValue.selector.name:'',wayPointId:''})}>{t('duties.initial')}</button><button onClick={()=>addFunction({id:uid('reverse'),type:'REVERSE',tick:0})}>{t('actions.REVERSE')}</button><button onClick={()=>addFunction({id:uid('wait'),type:'WAIT',tick:0,durationTicks:20})}>{t('actions.WAIT')}</button><button onClick={()=>addFunction({id:uid('store'),type:'STORE_AT_DEPOT',tick:0,depotId:'',stablingPointIds:[]})}>{t('actions.STORE_AT_DEPOT')}</button></div>
          <ol className="duty-elements">{selectedDutyValue.elements.map((element,index)=><li key={element.id}>
            <span>{element.type==='RUN'?`RUN · ${element.runId}`:element.call.type}</span>
            {element.type==='FUNCTION'&&element.call.type==='PLACE_INITIAL'&&<input list="points" placeholder={t('editor.target')} value={element.call.wayPointId} onChange={(event)=>patchFunction(element.id,{wayPointId:event.target.value})}/>} 
            {element.type==='FUNCTION'&&'tick'in element.call&&<input type="number" min="0" title={t('editor.tick')} value={element.call.tick} onChange={(event)=>patchFunction(element.id,{tick:Math.max(0,+event.target.value)})}/>} 
            {element.type==='FUNCTION'&&element.call.type==='WAIT'&&<input type="number" min="1" title={t('editor.value')} value={element.call.durationTicks} onChange={(event)=>patchFunction(element.id,{durationTicks:Math.max(1,+event.target.value)})}/>} 
            {element.type==='FUNCTION'&&element.call.type==='STORE_AT_DEPOT'&&<input list="depots" placeholder={t('editor.target')} value={element.call.depotId} onChange={(event)=>patchFunction(element.id,{depotId:event.target.value})}/>} 
            <span className="row-controls"><button disabled={index===0} onClick={()=>moveElement(element.id,-1)}>↑</button><button disabled={index===selectedDutyValue.elements.length-1} onClick={()=>moveElement(element.id,1)}>↓</button><button className="danger" onClick={()=>removeElement(element.id)}>×</button></span>
          </li>)}</ol>
        </>}
      </aside>
    </div>
    <div className="duty-output-grid"><section className="card"><h3>{t('duties.issues')}</h3>{generated.issues.length===0?<p className="success">{t('editor.noIssues')}</p>:generated.issues.map((item,index)=><p className={item.severity} key={index}><code>{item.path}</code> {item.message}</p>)}{compiled&&<p className={compiled.validation.valid?'success':'error'}>{compiled.validation.valid?t('scripts.valid'):compiled.validation.errors.join('; ')}</p>}</section><section className="card source-view"><h3>{t('duties.generatedSource')}</h3><pre>{generated.source}</pre></section></div>
  </section>;
}
