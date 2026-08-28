import { useEffect, useReducer, useState } from 'react';
import { api } from './api';
import { emptyAction, emptyTimetable, reducer } from './editor';
import { DutyEditor } from './DutyEditor';
import { Graph } from './Graph';
import { filterTimetableForDiagramLine } from './graph';
import { RailMap } from './RailMap';
import {
  actionLabel, addRowAction, assignLineToService, canInitializeServiceRows, deleteRowAction, deleteServiceRow, insertServiceRow,
  moveServiceRow, updateGeneratedArrival, updateGeneratedDeparture, updateRowAction, updateServiceRow,
} from './schedule';
import { useI18n } from './i18n';
import { useTheme } from './theme';
import type {
  ActionType,
  DraftSummary,
  LineCollection,
  LineDirection,
  LineSimulationResult,
  LineV1,
  SimulationProfile,
  RenderedTimetableScript,
  SavedTimetableV1,
  ServiceEditorRowV1,
  TimetableScriptSummary,
  TimetableActionV1,
  TimetableStepV1,
  ValidationIssue,
  WorldMetadata,
  WorldSummary,
} from './types';
import './styles.css';

const actionTypes = [
  'DESTINATION', 'STOP', 'HALT', 'REVERSE', 'TAKE_FROM_DEPOT', 'TAKE_FROM_WAYPOINT', 'STORE_AT_DEPOT',
  'DESTINATION_OPTIONS', 'STATION_DESTINATION_OPTIONS', 'EXTENSION',
] as const satisfies readonly ActionType[];
export type View = 'graph' | 'map' | 'lines' | 'scripts' | 'simulation';
export const DEFAULT_VIEW:View='map';
export const navigationForAuthentication=(authenticated:boolean|null):View[]=>authenticated
  ?['map','graph','lines','scripts','simulation']
  :['map'];

export default function App() {
  const { locale, setLocale, t } = useI18n();
  const { theme, setTheme } = useTheme();
  const [state, dispatch] = useReducer(reducer, undefined, emptyTimetable);
  const [worlds, setWorlds] = useState<WorldSummary[]>([]);
  const [world, setWorld] = useState('');
  const [metadata, setMetadata] = useState<WorldMetadata>({});
  const [drafts, setDrafts] = useState<DraftSummary[]>([]);
  const [lineCollection, setLineCollection] = useState<LineCollection>({ revision: 0, lines: [] });
  const [draftId, setDraftId] = useState('');
  const [view, setView] = useState<View>(DEFAULT_VIEW);
  const [authenticated,setAuthenticated]=useState<boolean|null>(null);
  const [authenticatedPlayer,setAuthenticatedPlayer]=useState('');
  const [error, setError] = useState('');
  const [issues, setIssues] = useState<ValidationIssue[]>([]);
  const [busy, setBusy] = useState(false);

  const run = async <T,>(operation: () => Promise<T>) => {
    setBusy(true);
    setError('');
    try { return await operation(); }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      return undefined;
    } finally { setBusy(false); }
  };

  useEffect(() => {
    let cancelled=false;
    void (async()=>{
      setBusy(true);setError('');
      try{
        const loginToken=new URLSearchParams(window.location.hash.slice(1)).get('token');
        let loginError='';
        if(loginToken){
          window.history.replaceState(null,'',`${window.location.pathname}${window.location.search}`);
          const response=await fetch('/auth/exchange',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:loginToken})});
          if(!response.ok){const body=await response.json().catch(()=>({})) as {error?:string};loginError=body.error||`Login failed (${response.status})`;}
        }
        const session=await api.session();
        const values=await api.worlds();
        if(cancelled)return;
        setAuthenticated(session.authenticated);setAuthenticatedPlayer(session.playerName||'');setWorlds(values);
        setWorld(values.find((candidate)=>candidate.id===session.worldId)?.id||values[0]?.id||'');
        if(loginError)setError(loginError);
      }catch(failure){if(!cancelled){setAuthenticated(false);setError(failure instanceof Error?failure.message:String(failure));}}
      finally{if(!cancelled)setBusy(false);}
    })();
    return()=>{cancelled=true;};
  }, []);

  useEffect(()=>{
    const revoke=()=>{setAuthenticated(false);setAuthenticatedPlayer('');setView('map');setMetadata({});setDrafts([]);setLineCollection({revision:0,lines:[]});};
    window.addEventListener('javarailways:unauthorized',revoke);
    return()=>window.removeEventListener('javarailways:unauthorized',revoke);
  },[]);

  useEffect(()=>{
    if(!authenticated)return;
    const timer=window.setInterval(()=>{void api.session().then((session)=>{if(!session.authenticated){window.dispatchEvent(new Event('javarailways:unauthorized'));}}).catch(()=>undefined);},60_000);
    return()=>window.clearInterval(timer);
  },[authenticated]);

  useEffect(() => {
    if (!world) return;
    if(!authenticated){setMetadata({});setDrafts([]);setLineCollection({revision:0,lines:[]});setDraftId('');return;}
    void run(async () => {
      const [reference, savedDrafts, savedLines] = await Promise.all([
        api.metadata(world), api.drafts(world), api.lines(world),
      ]);
      setMetadata(reference); setDrafts(savedDrafts); setLineCollection(savedLines); setDraftId('');
    });
  }, [world,authenticated]);

  const load = async (id: string) => {
    setDraftId(id);
    if (!id) { dispatch({ type: 'LOAD', value: emptyTimetable() }); return; }
    const value = await run(() => api.draft(world, id));
    if (value) dispatch({ type: 'LOAD', value });
  };
  const save = async () => {
    if (!draftId) {
      const created = await run(() => api.createDraft(world, state));
      if (created) { setDraftId(created.id); setDrafts(await api.drafts(world)); }
    } else await run(() => api.saveDraft(world, draftId, state));
  };
  const removeDraft = async () => {
    if (!draftId) return;
    await run(() => api.deleteDraft(world, draftId));
    setDraftId(''); dispatch({ type: 'LOAD', value: emptyTimetable() });
    setDrafts(await api.drafts(world));
  };
  const validate = async () => {
    const result = await run(() => api.validate(world, state));
    if (!result) return;
    const messages = [...(result.errors || []), ...(result.warnings || [])];
    setIssues(messages.length ? messages : [{ message: t('editor.noIssues'), severity: 'success' }]);
  };
  const navigation=navigationForAuthentication(authenticated);
  return <main className="app-shell">
    <header className="topbar">
      <div className="brand"><h1>{t('app.title')}</h1><span>{t('app.subtitle')}</span></div>
      <nav aria-label={t('nav.primary')}>
        {navigation.map((item) =>
          <button key={item} className={view === item ? 'active' : ''} onClick={() => setView(item)}>{t(`nav.${item}`)}</button>)}
      </nav>
      <div className="top-controls">
        <span className={`auth-state ${authenticated?'authenticated':'guest'}`}>{authenticated?t('auth.authenticated')+(authenticatedPlayer?` · ${authenticatedPlayer}`:''):t('auth.guest')}</span>
        <label>{t('common.world')}<select value={world} onChange={(event) => setWorld(event.target.value)}>
          {worlds.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}
        </select></label>
        <label>{t('common.language')}<select value={locale} onChange={(event) => setLocale(event.target.value as 'zh-CN'|'en-US')}>
          <option value="zh-CN">中文</option><option value="en-US">English</option>
        </select></label>
        <label>{t('common.theme')}<select value={theme} onChange={(event)=>setTheme(event.target.value as 'dark'|'light')}>
          <option value="dark">{t('theme.dark')}</option><option value="light">{t('theme.light')}</option>
        </select></label>
      </div>
    </header>

    <section className="commandbar">
      <span className="readonly-banner">{!authenticated?t('auth.mapOnly'):view==='scripts'?t('scripts.readOnly'):view==='graph'?t('duties.workspace'):t('map.public')}</span>
    </section>

    <section className="notice-strip">
      {error && <div className="error" role="alert">{error}</div>}
      {issues.map((issue, index) => <div key={index} className={issue.severity}>{issue.path && <code>{issue.path}</code>} {issue.message}</div>)}
    </section>

    {authenticated&&<section className="persistent-graph-view" hidden={view!=='graph'}><DutyEditor world={world} recoveryOwner={authenticatedPlayer} lines={lineCollection.lines} metadata={metadata} onError={setError}/></section>}
    {view === 'map' && <RailMap world={world} onError={setError} />}
    {view === 'lines' && <LinesPage world={world} metadata={metadata} collection={lineCollection}
      onCollection={setLineCollection} run={run} />}
    {view === 'scripts' && <ScriptsPage world={world} metadata={metadata} run={run} />}
    {view === 'simulation' && <SimulationPage world={world} lines={lineCollection.lines} metadata={metadata} run={run} />}

    <DataLists metadata={metadata} />
  </main>;
}

function ServiceScheduleTable({ service, metadata, onChange }: {
  service:SavedTimetableV1['services'][number]; metadata:WorldMetadata; onChange:(value:SavedTimetableV1['services'][number])=>void;
}) {
  const { t } = useI18n();
  const rows=service.rows || [];
  const extensions=metadata.extensions?.actions || [];
  const hasTake=rows.some((row)=>row.depotOperation==='TAKE_FROM_DEPOT');
  const hasStore=rows.some((row)=>row.depotOperation==='STORE_AT_DEPOT');
  const patch=(row:ServiceEditorRowV1,value:Partial<ServiceEditorRowV1>)=>onChange(updateServiceRow(service,row.id,value,metadata));
  const addAction=(row:ServiceEditorRowV1,value:string) => {
    if(!value) return;
    const action=value.startsWith('extension|') ? emptyAction('EXTENSION') : emptyAction(value as ActionType);
    if(value.startsWith('extension|')) { const [,typeId,version]=value.split('|'); action.extensionCall={typeId,version:+version,parameters:{}}; }
    onChange(addRowAction(service,row.id,action,metadata));
  };
  return <div className="schedule-editor row-backed">
    <div className="schedule-header"><span>{t('editor.rowType')}</span><span>{t('editor.stopStatus')}</span><span>{t('editor.platformOrTarget')}</span><span>{t('editor.arrivalTime')}</span><span>{t('editor.departureTime')}</span><span>{t('editor.rowActions')}</span><span /></div>
    {rows.map((row,index) => <div className={`schedule-row ${row.status==='DISABLED'?'disabled':''}`} key={row.id}>
      <strong>{row.type==='STATION' ? (metadata.stations?.find((item)=>item.stationId===row.stationId)?.stationDisplayName || row.stationId) : t(`rowType.${row.type}` as Parameters<typeof t>[0])}</strong>
      {row.type==='STATION'||row.type==='SPECIAL_DESTINATION' ? <select value={row.status || 'STOP'} onChange={(event)=>patch(row,{status:event.target.value as ServiceEditorRowV1['status']})}>
        <option value="ORIGIN">{t('status.origin')}</option><option value="STOP">{t('status.stop')}</option><option value="PASS">{t('status.pass')}</option><option value="TERMINAL">{t('status.terminal')}</option><option value="DISABLED">{t('status.disabled')}</option>
      </select> : row.type==='DEPOT_OPERATION' ? <span>{t(`actions.${row.depotOperation || 'TAKE_FROM_DEPOT'}` as Parameters<typeof t>[0])}</span> : <span>—</span>}
      {row.type==='STATION' ? <select value={row.berthId || ''} disabled={row.status==='DISABLED'} onChange={(event)=>patch(row,{berthId:event.target.value})}>
        {(metadata.stations||[]).filter((item)=>item.stationId===row.stationId).map((berth)=><option key={berth.destinationId} value={berth.berthId}>{berth.berthId}</option>)}
      </select> : row.type==='SPECIAL_DESTINATION' ? <input list="points" value={row.destinationId || ''} onChange={(event)=>patch(row,{destinationId:event.target.value})} />
        : row.type==='DEPOT_OPERATION' ? <input list="depots" value={row.depotId || ''} disabled={row.depotOperation==='STORE_AT_DEPOT'} onChange={(event)=>patch(row,{depotId:event.target.value})} /> : <span>—</span>}
      {row.type==='STATION'||row.type==='SPECIAL_DESTINATION' ? <input type="number" min="0" value={row.arrivalTick ?? ''} placeholder="—" onChange={(event)=>patch(row,{arrivalTick:event.target.value===''?null:Math.max(0,+event.target.value)})} /> : <span>—</span>}
      <input type="number" min="0" value={(row.type==='ACTION_ONLY'||row.type==='DEPOT_OPERATION'?row.tick:row.departureTick) ?? ''} placeholder="—" onChange={(event)=>patch(row,row.type==='ACTION_ONLY'||row.type==='DEPOT_OPERATION'?{tick:Math.max(0,+event.target.value)}:{departureTick:Math.max(0,+event.target.value)})} />
      <div className="schedule-actions">
        {row.actions.map((action,actionIndex)=><span className="action-chip" key={actionIndex}>{actionLabel(action)}
          {action.type==='STOP'&&<input type="number" min="1" value={action.stopTicks || 20} onChange={(event)=>onChange(updateRowAction(service,row.id,actionIndex,{...action,stopTicks:Math.max(1,+event.target.value)},metadata))}/>}
          {action.type==='EXTENSION'&&<textarea aria-label={t('editor.extensionParameters')} defaultValue={JSON.stringify(action.extensionCall?.parameters||{})} onBlur={(event)=>{try{onChange(updateRowAction(service,row.id,actionIndex,{...action,extensionCall:{...action.extensionCall!,parameters:JSON.parse(event.target.value) as Record<string,unknown>}},metadata));}catch{event.currentTarget.setCustomValidity(t('editor.invalidJson'));event.currentTarget.reportValidity();}}} onInput={(event)=>event.currentTarget.setCustomValidity('')}/>}
          <button className="danger" title={t('editor.deleteAction')} onClick={()=>onChange(deleteRowAction(service,row.id,actionIndex,metadata))}>×</button></span>)}
        {row.type!=='DEPOT_OPERATION'&&row.status!=='PASS'&&row.status!=='DISABLED'&&<select value="" onChange={(event)=>addAction(row,event.target.value)}><option value="">＋</option>
          <option value="REVERSE">{t('actions.REVERSE')}</option><option value="STOP">{t('actions.WAIT')}</option><option value="HALT">{t('actions.HALT')}</option>
          {extensions.map((item)=><option key={`${item.typeId}@${item.version}`} value={`extension|${item.typeId}|${item.version}`}>{item.title}</option>)}
        </select>}
      </div>
      <div className="row-controls"><button disabled={index===0||row.type==='DEPOT_OPERATION'} onClick={()=>onChange(moveServiceRow(service,row.id,-1,metadata))}>↑</button><button disabled={index===rows.length-1||row.type==='DEPOT_OPERATION'} onClick={()=>onChange(moveServiceRow(service,row.id,1,metadata))}>↓</button>
        <select value="" title={t('editor.insertRow')} onChange={(event)=>{if(event.target.value)onChange(insertServiceRow(service,index+1,event.target.value as ServiceEditorRowV1['type'],metadata));}}><option value="">＋</option><option value="SPECIAL_DESTINATION">{t('rowType.SPECIAL_DESTINATION')}</option><option value="DEPOT_OPERATION" disabled={hasTake&&hasStore}>{t('rowType.DEPOT_OPERATION')}</option><option value="ACTION_ONLY">{t('rowType.ACTION_ONLY')}</option></select>
        {row.type!=='STATION'&&<button className="danger" onClick={()=>onChange(deleteServiceRow(service,row.id,metadata))}>×</button>}</div>
    </div>)}
    <div className="custom-row-buttons"><span>{t('editor.insertRow')}</span>
      <button onClick={()=>onChange(insertServiceRow(service,rows.length,'SPECIAL_DESTINATION',metadata))}>{t('rowType.SPECIAL_DESTINATION')}</button>
      <button disabled={hasTake&&hasStore} onClick={()=>onChange(insertServiceRow(service,rows.length,'DEPOT_OPERATION',metadata))}>{t('rowType.DEPOT_OPERATION')}</button>
      <button onClick={()=>onChange(insertServiceRow(service,rows.length,'ACTION_ONLY',metadata))}>{t('rowType.ACTION_ONLY')}</button>
    </div>
    <p className="schedule-note">{t('editor.generatedRouteNote')} {t('editor.arrivalVisualOnly')}</p>
  </div>;
}

function ServicesPage({ state, metadata, lines, selected, dispatch, replaceStep, setSelected }: {
  state: SavedTimetableV1; metadata: WorldMetadata; lines:LineV1[]; selected?: {service:number;step:number};
  dispatch: React.Dispatch<Parameters<typeof reducer>[1]>;
  replaceStep: (service:number, step:number, kind:'CHECK'|ActionType)=>void;
  setSelected: (value:{service:number;step:number}|undefined)=>void;
}) {
  const { t } = useI18n();
  return <section className="page-scroll">
    <div className="page-heading"><h2>{t('editor.services')}</h2><button onClick={() => dispatch({ type: 'ADD_SERVICE' })}>+ {t('editor.addService')}</button></div>
    {state.services.length === 0 && <p className="empty">{t('editor.noServices')}</p>}
    {state.services.map((service, serviceIndex) => <article className="service card" key={serviceIndex}>
      <div className="service-head">
        <input aria-label={t('editor.serviceId')} value={service.id} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { id: event.target.value } })} />
        <select aria-label={t('lines.title')} value={service.diagramLineId || ''} onChange={(event) => {
          const line=lines.find((item) => item.id===event.target.value);
          if(line&&service.rows&&!window.confirm(t('editor.confirmResetRows'))) return;
          const updated=line ? (canInitializeServiceRows(service) ? assignLineToService(service,line,service.lineDirection||'UP',metadata) : {...service,diagramLineId:line.id}) : { ...service,diagramLineId:null,lineDirection:null,rows:null };
          dispatch({ type:'PATCH_SERVICE', service:serviceIndex, patch:updated });
        }}>
          <option value="">{t('common.none')}</option>{lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
        </select>
        {service.diagramLineId&&<select aria-label={t('editor.lineDirection')} value={service.lineDirection||'UP'} onChange={(event)=>{const line=lines.find((item)=>item.id===service.diagramLineId);if(!line)return;if(!service.rows){dispatch({type:'PATCH_SERVICE',service:serviceIndex,patch:{lineDirection:event.target.value as LineDirection}});return;}if(window.confirm(t('editor.confirmResetRows')))dispatch({type:'PATCH_SERVICE',service:serviceIndex,patch:assignLineToService(service,line,event.target.value as LineDirection,metadata)});}}><option value="UP">{t('direction.UP')}</option><option value="DOWN">{t('direction.DOWN')}</option></select>}
        <select value={service.selectorType} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { selectorType: event.target.value as 'ANY'|'TRAIN_ID', trainId: event.target.value === 'ANY' ? null : service.trainId } })}>
          <option value="ANY">{t('editor.dynamicTrain')}</option><option value="TRAIN_ID">{t('editor.fixedTrain')}</option>
        </select>
        {service.selectorType === 'TRAIN_ID' && <input list="trains" placeholder={t('editor.trainId')} value={service.trainId || ''} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { trainId: event.target.value } })} />}
        <select value={service.activation.type} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { activation: { type: event.target.value as 'SCHEDULED'|'MANUAL' } } })}>
          <option value="SCHEDULED">{t('editor.scheduled')}</option><option value="MANUAL">{t('editor.manual')}</option>
        </select>
        {!service.rows&&<button onClick={() => dispatch({ type: 'ADD_STEP', service: serviceIndex })}>+ {t('editor.addAction')}</button>}
        <button className="danger" onClick={() => { dispatch({ type: 'DELETE_SERVICE', service: serviceIndex }); setSelected(undefined); }}>{t('common.delete')}</button>
      </div>
      {service.rows?<p className="empty">{t('editor.generatedRouteNote')}</p>:<table><thead><tr><th>{t('editor.tick')}</th><th>{t('editor.actionType')}</th><th>{t('editor.target')}</th><th>{t('editor.value')}</th><th /></tr></thead>
        <tbody>{service.steps.map((step, stepIndex) => <ActionRow key={stepIndex} step={step}
          selected={selected?.service === serviceIndex && selected.step === stepIndex} metadata={metadata}
          onSelect={() => setSelected({ service: serviceIndex, step: stepIndex })}
          onReplace={(kind) => replaceStep(serviceIndex, stepIndex, kind)}
          onTick={(tick) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, tick })}
          onAction={(action) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, action })}
          onCheck={(wayPointId) => dispatch({ type: 'REPLACE_STEP', service: serviceIndex, step: stepIndex, value: { type: 'CHECK', wayPointId } })}
          onDelete={() => dispatch({ type: 'DELETE_STEP', service: serviceIndex, step: stepIndex })} />)}</tbody>
      </table>}
    </article>)}
  </section>;
}

export function LinesPage({ world, metadata, collection, onCollection, run }: {
  world:string; metadata:WorldMetadata; collection:LineCollection;
  onCollection:(value:LineCollection)=>void;
  run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState('');
  const [editing, setEditing] = useState<LineV1>({ id:'', displayName:'', upStationIds:[], downStationIds:[], color:'#56c5ff' });
  const stations = Array.from(new Map((metadata.stations || []).map((station) => [station.stationId, station])).values());
  const select = (id:string) => {
    setSelectedId(id);
    const line = collection.lines.find((item) => item.id === id);
    setEditing(line ? { ...line, upStationIds:[...line.upStationIds], downStationIds:[...line.downStationIds] } : {
      id:`line-${collection.lines.length + 1}`,
      displayName:`Line ${collection.lines.length + 1}`,
      upStationIds:[], downStationIds:[], color:'#56c5ff',
    });
  };
  const updateStations = (direction:LineDirection,stationIds:string[]) => {
    setEditing({ ...editing,[direction==='UP'?'upStationIds':'downStationIds']:stationIds });
  };
  const saveLine = async () => {
    const result = await run(() => api.saveLine(world, editing, collection.revision));
    if (!result) return;
    const lines = [...collection.lines.filter((line) => line.id !== result.line.id), result.line]
      .sort((a,b) => a.id.localeCompare(b.id));
    onCollection({ revision:result.revision, lines }); setSelectedId(result.line.id);
  };
  const deleteLine = async () => {
    if (!selectedId) return;
    const result = await run(() => api.deleteLine(world, selectedId, collection.revision));
    if (!result) return;
    onCollection({ revision:result.revision, lines:collection.lines.filter((line) => line.id !== selectedId) });
    setSelectedId(''); setEditing({ id:'', displayName:'', upStationIds:[], downStationIds:[], color:'#56c5ff' });
  };
  return <section className="page-scroll line-page">
    <aside className="line-list card">
      <div className="section-title"><h2>{t('lines.title')}</h2><button onClick={() => select('')}>+</button></div>
      {collection.lines.map((line) => <button key={line.id} className={selectedId === line.id ? 'active' : ''} onClick={() => select(line.id)}>
        <i style={{ background:line.color || '#56c5ff' }} />{line.displayName}
      </button>)}
    </aside>
    <article className="line-editor card">
      <div className="fields-grid">
        <label>{t('lines.id')}<input value={editing.id} disabled={Boolean(selectedId)} onChange={(event) => setEditing({ ...editing, id:event.target.value })} /></label>
        <label>{t('lines.name')}<input value={editing.displayName} onChange={(event) => setEditing({ ...editing, displayName:event.target.value })} /></label>
        <label>{t('lines.color')}<input type="color" value={editing.color || '#56c5ff'} onChange={(event) => setEditing({ ...editing, color:event.target.value })} /></label>
      </div>
      <div className="direction-patterns">{(['UP','DOWN'] as LineDirection[]).map((direction) => {
        const values=direction==='UP'?editing.upStationIds:editing.downStationIds;
        return <section className="direction-pattern" key={direction}><div className="section-title"><h3>{t(`direction.${direction}`)}</h3><select value="" onChange={(event) => {
          if(event.target.value&&!values.includes(event.target.value)) updateStations(direction,[...values,event.target.value]);
        }}><option value="">+ {t('lines.addStation')}</option>{stations.filter((station)=>!values.includes(station.stationId)).map((station)=><option key={station.stationId} value={station.stationId}>{station.stationDisplayName||station.stationId}</option>)}</select></div>
        <ol className="station-order">{values.map((stationId,index)=>{const station=stations.find((item)=>item.stationId===stationId);return <li key={stationId}><span>{station?.stationDisplayName||stationId}<small>{stationId}</small></span>
          <button disabled={index===0} onClick={()=>updateStations(direction,move(values,index,index-1))}>↑</button><button disabled={index===values.length-1} onClick={()=>updateStations(direction,move(values,index,index+1))}>↓</button>
          <button className="danger" onClick={()=>updateStations(direction,values.filter((id)=>id!==stationId))}>×</button></li>;})}</ol></section>;
      })}</div>
      <div className="line-actions">
        <button className="primary" disabled={editing.upStationIds.length < 2 || editing.downStationIds.length < 2 || !editing.id || !editing.displayName} onClick={() => void saveLine()}>{t('common.save')}</button>
        <button className="danger" disabled={!selectedId} onClick={() => void deleteLine()}>{t('common.delete')}</button>
      </div>
    </article>
  </section>;
}

function move<T>(items:T[], from:number, to:number):T[] {
  const copy = [...items]; const [item] = copy.splice(from,1); copy.splice(to,0,item); return copy;
}

function ScriptsPage({ world, metadata, run }: {
  world:string; metadata:WorldMetadata; run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [scripts,setScripts] = useState<TimetableScriptSummary[]>([]);
  const [selected,setSelected] = useState('');
  const [rendered,setRendered] = useState<RenderedTimetableScript>();
  const [diagramLineId,setDiagramLineId] = useState('');
  useEffect(() => {
    if (!world) return;
    void run(() => api.scripts(world)).then((items) => {
      setScripts(items || []); setSelected(''); setRendered(undefined); setDiagramLineId('');
    });
  }, [world]);
  const select = async (name:string) => {
    setSelected(name); setRendered(undefined); setDiagramLineId('');
    if (!name) return;
    const result=await run(() => api.renderScript(world,name));
    setRendered(result);
    setDiagramLineId(result?.timetable.diagramLines?.[0]?.id||'');
  };
  return <section className="page-scroll scripts-page">
    <aside className="card script-list"><h2>{t('scripts.title')}</h2>
      {scripts.length === 0 && <p className="empty">{t('scripts.empty')}</p>}
      {scripts.map((script) => <button key={script.name} className={selected === script.name ? 'active' : ''} onClick={() => void select(script.name)}>
        <strong>{script.name}</strong><small>{Math.ceil(script.sizeBytes/1024)} KiB</small>
      </button>)}
    </aside>
    <article className="script-preview">
      {!rendered ? <div className="placeholder"><p>{t('scripts.select')}</p></div> : <>
        <div className="card script-status"><h2>{rendered.name}</h2><span className={rendered.validation.valid ? 'valid' : 'invalid'}>
          {rendered.validation.valid ? t('scripts.valid') : t('scripts.invalid')}
        </span>{rendered.validation.errors.map((error,index) => <p key={index}>{error}</p>)}</div>
        <div className="script-columns">
          <section className="card source-view"><h3>{t('scripts.source')}</h3><pre>{rendered.source}</pre></section>
          <section className="card readonly-graph"><div className="readonly-graph-header"><h3>{t('scripts.rendered')}</h3>
            {!!rendered.timetable.diagramLines?.length&&<label>{t('lines.title')}<select value={diagramLineId} onChange={(event)=>setDiagramLineId(event.target.value)}>
              {rendered.timetable.diagramLines.map((line)=><option key={line.id} value={line.id}>{line.id}</option>)}
            </select></label>}
          </div>
            <Graph
              value={diagramLineId?filterTimetableForDiagramLine(rendered.timetable,diagramLineId):rendered.timetable}
              metadata={metadata} graphLineId={diagramLineId||undefined}
            />
          </section>
        </div>
      </>}
    </article>
  </section>;
}

function SimulationPage({ world, lines, metadata, run }: {
  world:string; lines:LineV1[]; metadata:WorldMetadata; run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [lineId,setLineId] = useState('');
  const [profileKey,setProfileKey] = useState('');
  const [direction,setDirection] = useState<LineDirection>('UP');
  const [result,setResult] = useState<LineSimulationResult>();
  const profiles = metadata.simulationProfiles || [];
  const selectedProfile = profiles.find((candidate) => `${candidate.type}:${candidate.id}` === profileKey);
  return <section className="page-scroll simulation-page">
    <article className="card simulation-config"><h2>{t('simulation.title')}</h2>
      <label>{t('simulation.line')}<select value={lineId} onChange={(event) => { setLineId(event.target.value); setResult(undefined); }}>
        <option value="">{t('common.select')}</option>{lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
      </select></label>
      <label>{t('editor.lineDirection')}<select value={direction} onChange={(event)=>{setDirection(event.target.value as LineDirection);setResult(undefined);}}><option value="UP">{t('direction.UP')}</option><option value="DOWN">{t('direction.DOWN')}</option></select></label>
      <label>{t('simulation.profile')}<select value={profileKey} onChange={(event) => { setProfileKey(event.target.value); setResult(undefined); }}>
        <option value="">{t('common.select')}</option>
        <optgroup label={t('simulation.worldTrains')}>{profiles.filter((profile) => profile.type === 'WORLD_TRAIN').map((profile) =>
          <option key={`WORLD_TRAIN:${profile.id}`} value={`WORLD_TRAIN:${profile.id}`}>{profile.displayName}</option>)}</optgroup>
        <optgroup label={t('simulation.templates')}>{profiles.filter((profile) => profile.type === 'TRAIN_TEMPLATE').map((profile) =>
          <option key={`TRAIN_TEMPLATE:${profile.id}`} value={`TRAIN_TEMPLATE:${profile.id}`}>{profile.displayName} · {profile.carCount}</option>)}</optgroup>
      </select></label>
      <button className="primary" disabled={!lineId || !selectedProfile} onClick={async () => {
        if (!selectedProfile) return;
        const profile:SimulationProfile = { profileType:selectedProfile.type, profileId:selectedProfile.id, direction };
        setResult(await run(() => api.simulateLine(world,lineId,profile)));
      }}>{t('simulation.run')}</button>
    </article>
    <article className="card simulation-results"><h2>{t('simulation.results')}</h2>
      {!result ? <p className="empty">{t('simulation.pending')}</p> : <>
        <div className="result-total"><strong>{formatDuration(result.totalTravelSeconds)}</strong><span>{t('simulation.total')}</span></div>
        <table><thead><tr><th>{t('simulation.segment')}</th><th>{t('simulation.distance')}</th><th>{t('simulation.time')}</th><th>{t('simulation.peakSpeed')}</th></tr></thead>
          <tbody>{result.legs.map((leg,index) => <tr key={index}><td>{leg.fromStationId} → {leg.toStationId}</td><td>{Math.round(leg.lengthMeters)} m</td><td>{formatDuration(leg.travelSeconds)}</td><td>{leg.peakSpeedKmh.toFixed(1)} km/h</td></tr>)}</tbody></table>
      </>}
    </article>
  </section>;
}

function formatDuration(seconds:number):string {
  const rounded = Math.round(seconds); return `${Math.floor(rounded/60)}:${String(rounded%60).padStart(2,'0')}`;
}

function DataLists({ metadata }: {metadata:WorldMetadata}) {
  return <>
    <datalist id="points">{[...(metadata.wayPoints || []), ...(metadata.stations || []).map((berth) => berth.destinationId)].map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="stations">{Array.from(new Set((metadata.stations || []).map((berth) => berth.stationId))).map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="depots">{metadata.depots?.map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="trains">{metadata.trains?.map((id) => <option key={id} value={id} />)}</datalist>
  </>;
}

interface ActionEditorProps {
  step: TimetableStepV1; metadata: WorldMetadata;
  onReplace:(kind:'CHECK'|ActionType)=>void; onTick:(tick:number)=>void;
  onAction:(action:Partial<TimetableActionV1>)=>void; onCheck:(id:string)=>void;
  onStationOperation?:(type:'REVERSE'|'STOP'|'HALT')=>void;
}

function ActionInspector(props: ActionEditorProps) {
  const { t } = useI18n();
  const { step, metadata, onReplace, onTick, onAction, onCheck } = props;
  const action = step.action;
  const kind = step.type === 'CHECK' ? 'CHECK' : action?.type || 'DESTINATION';
  return <div className="action-inspector fields-vertical">
    <label>{t('editor.actionType')}<select value={kind} onChange={(event) => onReplace(event.target.value as 'CHECK'|ActionType)}>
      <option value="CHECK">{t('actions.CHECK')}</option>{actionTypes.map((type) => <option key={type} value={type}>{t(`actions.${type}`)}</option>)}
    </select></label>
    {step.type === 'AT' && <label>{t('editor.tick')}<input type="number" min="0" value={step.tick ?? 0} onChange={(event) => onTick(Math.max(0, +event.target.value))} /></label>}
    <ActionFields step={step} metadata={metadata} onAction={onAction} onCheck={onCheck} onStationOperation={props.onStationOperation} />
  </div>;
}

function ActionFields({ step, metadata, onAction, onCheck, onStationOperation }: Pick<ActionEditorProps,'step'|'metadata'|'onAction'|'onCheck'|'onStationOperation'>) {
  const { t } = useI18n();
  const action = step.action;
  if (step.type === 'CHECK') return <label>{t('editor.target')}<input list="points" value={step.wayPointId || ''} onChange={(event) => onCheck(event.target.value)} /></label>;
  if (!action) return null;
  if (action.type === 'HALT' || action.type === 'REVERSE') return null;
  if (action.type === 'STOP') return <label>{t('editor.value')}<input type="number" min="1" value={action.stopTicks ?? 20} onChange={(event) => onAction({ stopTicks: Math.max(1, +event.target.value) })} /></label>;
  if (action.type === 'EXTENSION') {
    const providers=metadata.extensions?.actions || [];
    const call=action.extensionCall || { typeId:'', version:1, parameters:{} };
    return <>
      <label>{t('editor.extensionAction')}<select value={`${call.typeId}@${call.version}`} onChange={(event) => {
        const provider=providers.find((item) => `${item.typeId}@${item.version}`===event.target.value);
        if(provider) onAction({ extensionCall:{ typeId:provider.typeId, version:provider.version, parameters:{} } });
      }}><option value="">{t('common.select')}</option>{providers.map((provider) => <option key={`${provider.typeId}@${provider.version}`} value={`${provider.typeId}@${provider.version}`}>{provider.title}</option>)}</select></label>
      <label>{t('editor.extensionParameters')}<textarea key={`${call.typeId}@${call.version}:${JSON.stringify(call.parameters)}`} defaultValue={JSON.stringify(call.parameters,null,2)} onBlur={(event) => {
        try { onAction({ extensionCall:{ ...call, parameters:JSON.parse(event.target.value) as Record<string,unknown> } }); } catch { event.currentTarget.setCustomValidity(t('editor.invalidJson')); event.currentTarget.reportValidity(); }
      }} onInput={(event) => event.currentTarget.setCustomValidity('')} /></label>
    </>;
  }
  if (action.type === 'TAKE_FROM_WAYPOINT') return <label>{t('editor.target')}<input list="points" value={action.wayPointIds.join(', ')} onChange={(event) => onAction({ wayPointIds: splitList(event.target.value) })} /></label>;
  if (action.type === 'TAKE_FROM_DEPOT' || action.type === 'STORE_AT_DEPOT') {
    const points = metadata.depotDetails?.find((depot) => depot.id === action.depotId)?.stablingPoints || [];
    return <>
      <label>{t('editor.target')}<input list="depots" value={action.depotId || ''} onChange={(event) => onAction({ depotId: event.target.value, stablingPointIds:[] })} /></label>
      {action.type === 'STORE_AT_DEPOT' && (points.length ? <fieldset><legend>{t('editor.stablingIds')}</legend>{points.map((point) =>
        <label className="checkbox-label" key={point.id}><input type="checkbox" checked={action.stablingPointIds.includes(point.id)} onChange={(event) => onAction({
          stablingPointIds:event.target.checked ? [...action.stablingPointIds,point.id] : action.stablingPointIds.filter((id) => id !== point.id),
        })} />{point.id}</label>)}</fieldset>
        : <label>{t('editor.stablingIds')}<input value={action.stablingPointIds.join(', ')} onChange={(event) => onAction({ stablingPointIds: splitList(event.target.value) })} /></label>)}
    </>;
  }
  if (action.type === 'STATION_DESTINATION_OPTIONS') return <>
    <fieldset><legend>{t('editor.arrival')}</legend>
      <label>{t('editor.target')}<input list="stations" value={action.stationId || ''} onChange={(event) => onAction({ stationId: event.target.value, berthId: null })} /></label>
      <label>{t('editor.selectBerth')}<select value={action.berthId || ''} onChange={(event) => onAction({ berthId: event.target.value })}><option value="">{t('common.select')}</option>
        {(metadata.stations || []).filter((berth) => berth.stationId === action.stationId).map((berth) => <option key={berth.destinationId} value={berth.berthId}>{berth.berthId}</option>)}
      </select></label>
      <OptionalNumber label={t('editor.arrivalTime')} value={action.arrivalTick} onChange={(value) => onAction({ arrivalTick: value })} />
      <OptionalNumber label={t('editor.stoppingOffset')} value={action.stoppingOffsetMetersBefore} onChange={(value) => onAction({ stoppingOffsetMetersBefore: value })} />
    </fieldset>
    <fieldset><legend>{t('editor.beforeDeparture')}</legend>
      <OptionalNumber label={t('editor.minimumDwell')} value={action.minimumDwellTicks} onChange={(value) => onAction({ minimumDwellTicks: value })} />
      <label>{t('editor.doorSide')}<select value={action.doorSideOverride || ''} onChange={(event) => onAction({ doorSideOverride: (event.target.value || null) as TimetableActionV1['doorSideOverride'] })}>
        <option value="">{t('editor.defaultValue')}</option><option>NONE</option><option>LEFT</option><option>RIGHT</option><option>BOTH</option>
      </select></label>
      {onStationOperation && <div className="station-operation-buttons"><span>{t('editor.afterArrival')}</span>
        <button onClick={() => onStationOperation('REVERSE')}>{t('actions.REVERSE')}</button>
        <button onClick={() => onStationOperation('STOP')}>{t('actions.WAIT')}</button>
        <button onClick={() => onStationOperation('HALT')}>{t('actions.HALT')}</button>
      </div>}
    </fieldset>
  </>;
  const points = action.wayPointIds || [];
  return <>
    <label>{t('editor.target')}<input list="points" value={points.at(-1) || ''} onChange={(event) => onAction({ wayPointIds: [...points.slice(0, -1), event.target.value] })} /></label>
    {action.type === 'DESTINATION' && <label>{t('editor.viaPoints')}<input value={points.slice(0, -1).join(', ')} onChange={(event) => onAction({ wayPointIds: [...splitList(event.target.value), points.at(-1) || ''] })} /></label>}
    {action.type === 'DESTINATION_OPTIONS' && <>
      <OptionalNumber label={t('editor.arrivalTime')} value={action.arrivalTick} onChange={(value) => onAction({ arrivalTick: value })} />
      <OptionalNumber label={t('editor.minimumDwell')} value={action.minimumDwellTicks} onChange={(value) => onAction({ minimumDwellTicks: value })} />
      <OptionalNumber label={t('editor.stoppingOffset')} value={action.stoppingOffsetMetersBefore} onChange={(value) => onAction({ stoppingOffsetMetersBefore: value })} />
    </>}
  </>;
}

function OptionalNumber({ label, value, onChange }: {label:string;value?:number|null;onChange:(value:number|null)=>void}) {
  return <label>{label}<input type="number" value={value ?? ''} onChange={(event) => onChange(event.target.value === '' ? null : +event.target.value)} /></label>;
}
function splitList(value:string) { return value.split(',').map((item) => item.trim()).filter(Boolean); }

function ActionRow({ step, selected, metadata, onSelect, onReplace, onTick, onAction, onCheck, onDelete }: ActionEditorProps & {selected:boolean;onSelect:()=>void;onDelete:()=>void}) {
  const { t } = useI18n();
  const action = step.action;
  const kind = step.type === 'CHECK' ? 'CHECK' : action?.type || 'DESTINATION';
  const target = action?.wayPointIds?.at(-1) || action?.depotId || action?.stationId || '';
  return <tr className={selected ? 'selected' : ''} onClick={onSelect}>
    <td>{step.type === 'AT' ? <input type="number" min="0" value={step.tick ?? 0} onChange={(event) => onTick(Math.max(0, +event.target.value))} /> : '—'}</td>
    <td><select value={kind} onChange={(event) => onReplace(event.target.value as 'CHECK'|ActionType)}>
      <option value="CHECK">{t('actions.CHECK')}</option>{actionTypes.map((type) => <option key={type} value={type}>{t(`actions.${type}`)}</option>)}
    </select></td>
    <td>{step.type === 'CHECK' ? <input list="points" value={step.wayPointId || ''} onChange={(event) => onCheck(event.target.value)} />
      : <input list={action?.type.includes('DEPOT') ? 'depots' : action?.type === 'STATION_DESTINATION_OPTIONS' ? 'stations' : 'points'} value={target} onChange={(event) => {
        if (action?.type.includes('DEPOT')) onAction({ depotId: event.target.value });
        else if (action?.type === 'STATION_DESTINATION_OPTIONS') onAction({ stationId: event.target.value });
        else { const points = action?.wayPointIds || []; onAction({ wayPointIds: [...points.slice(0,-1), event.target.value] }); }
      }} />}</td>
    <td>{action?.type === 'STOP' ? <input type="number" min="1" value={action.stopTicks ?? 20} onChange={(event) => onAction({ stopTicks: Math.max(1, +event.target.value) })} />
      : action?.type === 'STATION_DESTINATION_OPTIONS' ? <select value={action.berthId || ''} onChange={(event) => onAction({ berthId: event.target.value })}><option value="">{t('editor.selectBerth')}</option>
        {(metadata.stations || []).filter((berth) => berth.stationId === action.stationId).map((berth) => <option key={berth.destinationId} value={berth.berthId}>{berth.berthId}</option>)}</select> : '—'}</td>
    <td><button className="danger" onClick={(event) => { event.stopPropagation(); onDelete(); }}>×</button></td>
  </tr>;
}
