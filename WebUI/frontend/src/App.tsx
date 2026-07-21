import { useEffect, useReducer, useState } from 'react';
import { api } from './api';
import { emptyAction, emptyTimetable, reducer } from './editor';
import { Graph } from './Graph';
import { useI18n } from './i18n';
import type {
  ActionType,
  DraftSummary,
  LineCollection,
  LinePreview,
  LineSimulationResult,
  LineV1,
  SimulationProfile,
  SavedTimetableV1,
  TimetableActionV1,
  TimetableStepV1,
  ValidationIssue,
  WorldMetadata,
  WorldSummary,
} from './types';
import './styles.css';

const actionTypes = [
  'DESTINATION', 'STOP', 'HALT', 'REVERSE', 'TAKE_FROM_DEPOT', 'STORE_AT_DEPOT',
  'DESTINATION_OPTIONS', 'STATION_DESTINATION_OPTIONS',
] as const satisfies readonly ActionType[];
type View = 'graph' | 'lines' | 'services' | 'simulation';

export default function App() {
  const { locale, setLocale, t } = useI18n();
  const [state, dispatch] = useReducer(reducer, undefined, emptyTimetable);
  const [worlds, setWorlds] = useState<WorldSummary[]>([]);
  const [world, setWorld] = useState('');
  const [metadata, setMetadata] = useState<WorldMetadata>({});
  const [drafts, setDrafts] = useState<DraftSummary[]>([]);
  const [lineCollection, setLineCollection] = useState<LineCollection>({ revision: 0, lines: [] });
  const [graphLineId, setGraphLineId] = useState('');
  const [draftId, setDraftId] = useState('');
  const [selected, setSelected] = useState<{ service: number; step: number }>();
  const [view, setView] = useState<View>('graph');
  const [sidebarOpen, setSidebarOpen] = useState(true);
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
    void run(async () => {
      const loginToken = new URLSearchParams(window.location.hash.slice(1)).get('token');
      if (loginToken) {
        window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`);
        const response = await fetch('/auth/exchange', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token: loginToken }),
        });
        if (!response.ok) {
          const body = await response.json().catch(() => ({})) as { error?: string };
          throw new Error(body.error || `Login failed (${response.status})`);
        }
      }
      const values = await api.worlds();
      setWorlds(values);
      if (values[0]) setWorld(values[0].id);
    });
  }, []);

  useEffect(() => {
    if (!world) return;
    setSelected(undefined);
    void run(async () => {
      const [reference, savedDrafts, savedLines] = await Promise.all([
        api.metadata(world), api.drafts(world), api.lines(world),
      ]);
      setMetadata(reference); setDrafts(savedDrafts); setLineCollection(savedLines); setDraftId('');
      setGraphLineId((current) => savedLines.lines.some((line) => line.id === current) ? current : (savedLines.lines[0]?.id || ''));
    });
  }, [world]);

  const load = async (id: string) => {
    setDraftId(id); setSelected(undefined);
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
    setDraftId(''); setSelected(undefined); dispatch({ type: 'LOAD', value: emptyTimetable() });
    setDrafts(await api.drafts(world));
  };
  const validate = async () => {
    const result = await run(() => api.validate(world, state));
    if (!result) return;
    const messages = [...(result.errors || []), ...(result.warnings || [])];
    setIssues(messages.length ? messages : [{ message: t('editor.noIssues'), severity: 'success' }]);
  };
  const replaceStep = (service: number, step: number, kind: 'CHECK' | ActionType) => {
    const value: TimetableStepV1 = kind === 'CHECK'
      ? { type: 'CHECK', wayPointId: '' }
      : { type: 'AT', tick: 0, action: emptyAction(kind) };
    dispatch({ type: 'REPLACE_STEP', service, step, value });
  };

  const selectedService = selected ? state.services[selected.service] : undefined;
  const graphLine = lineCollection.lines.find((line) => line.id === graphLineId);
  const graphServiceIndices = state.services.map((_, index) => index)
    .filter((index) => !graphLineId || state.services[index].lineId === graphLineId);
  const graphValue = { ...state, services: graphServiceIndices.map((index) => state.services[index]) };
  const selectedStep = selectedService && selected ? selectedService.steps[selected.step] : undefined;

  return <main className="app-shell">
    <header className="topbar">
      <div className="brand"><h1>{t('app.title')}</h1><span>{t('app.subtitle')}</span></div>
      <nav aria-label={t('nav.primary')}>
        {(['graph', 'lines', 'services', 'simulation'] as View[]).map((item) =>
          <button key={item} className={view === item ? 'active' : ''} onClick={() => setView(item)}>{t(`nav.${item}`)}</button>)}
      </nav>
      <div className="top-controls">
        <label>{t('common.world')}<select value={world} onChange={(event) => setWorld(event.target.value)}>
          {worlds.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}
        </select></label>
        <label>{t('common.language')}<select value={locale} onChange={(event) => setLocale(event.target.value as 'zh-CN'|'en-US')}>
          <option value="zh-CN">中文</option><option value="en-US">English</option>
        </select></label>
      </div>
    </header>

    <section className="commandbar">
      <label>{t('common.draft')}<select value={draftId} onChange={(event) => void load(event.target.value)}>
        <option value="">{t('common.newDraft')}</option>
        {drafts.map((draft) => <option key={draft.id} value={draft.id}>{draft.id}</option>)}
      </select></label>
      <button onClick={() => void save()} disabled={!world || busy}>{t('common.save')}</button>
      <button className="danger" onClick={() => void removeDraft()} disabled={!draftId || busy}>{t('common.delete')}</button>
      <span className="spacer" />
      <button onClick={() => void validate()} disabled={!world || busy}>{t('common.validate')}</button>
      <button className="primary" onClick={() => void run(() => api.publish(world, state))} disabled={!world || busy}>{t('common.publish')}</button>
    </section>

    {(error || issues.length > 0) && <section className="notice-strip">
      {error && <div className="error" role="alert">{error}</div>}
      {issues.map((issue, index) => <div key={index} className={issue.severity}>{issue.path && <code>{issue.path}</code>} {issue.message}</div>)}
    </section>}

    {view === 'graph' && <section className={`workspace ${sidebarOpen ? '' : 'sidebar-collapsed'}`}>
      <div className="graph-workspace">
        <div className="graph-overlay"><strong>{t('editor.graph')}</strong>
          <select value={graphLineId} onChange={(event) => setGraphLineId(event.target.value)}><option value="">{t('lines.all')}</option>
            {lineCollection.lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}</select>
          <span>{t('editor.graphHint')}</span></div>
        <Graph value={graphValue} metadata={metadata} line={graphLine}
          onSelect={(service, step) => { setSelected({ service:graphServiceIndices[service], step }); setSidebarOpen(true); }}
          onTick={(service, step, tick) => dispatch({ type: 'PATCH_STEP', service:graphServiceIndices[service], step, tick })} />
      </div>
      <button className="sidebar-toggle" aria-expanded={sidebarOpen} aria-controls="editor-sidebar"
        onClick={() => setSidebarOpen((open) => !open)}>{sidebarOpen ? '›' : '‹'}<span>{sidebarOpen ? t('editor.sidebarHide') : t('editor.sidebarShow')}</span></button>
      <aside id="editor-sidebar" className="editor-sidebar">
        <div className="sidebar-section fields-vertical">
          <label>{t('editor.timetableId')}<input value={state.id} onChange={(event) => dispatch({ type: 'SET_HEADER', id: event.target.value })} /></label>
          <label>{t('editor.periodDays')}<input type="number" min="1" value={state.periodDays} onChange={(event) => dispatch({ type: 'SET_HEADER', periodDays: Math.max(1, +event.target.value) })} /></label>
        </div>
        <div className="sidebar-section">
          <div className="section-title"><h2>{t('editor.services')}</h2><button onClick={() => dispatch({ type: 'ADD_SERVICE' })}>+</button></div>
          <div className="service-list">{state.services.map((service, serviceIndex) =>
            <button key={serviceIndex} className={selected?.service === serviceIndex ? 'active' : ''}
              onClick={() => setSelected({ service: serviceIndex, step: 0 })}>{service.id}</button>)}</div>
        </div>
        {selectedService && selected && <div className="sidebar-section fields-vertical">
          <label>{t('editor.serviceId')}<input value={selectedService.id} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: selected.service, patch: { id: event.target.value } })} /></label>
          <label>{t('lines.title')}<select value={selectedService.lineId || ''} onChange={(event) => dispatch({ type:'PATCH_SERVICE', service:selected.service, patch:{ lineId:event.target.value || null } })}>
            <option value="">{t('common.none')}</option>{lineCollection.lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
          </select></label>
          <div className="step-list">{selectedService.steps.map((step, stepIndex) =>
            <button key={stepIndex} className={selected.step === stepIndex ? 'active' : ''} onClick={() => setSelected({ service: selected.service, step: stepIndex })}>
              {stepIndex + 1}. {step.type === 'CHECK' ? t('actions.CHECK') : t(`actions.${step.action?.type || 'DESTINATION'}` as Parameters<typeof t>[0])}
            </button>)}</div>
          {selectedStep && <ActionInspector step={selectedStep} metadata={metadata}
            onReplace={(kind) => replaceStep(selected.service, selected.step, kind)}
            onTick={(tick) => dispatch({ type: 'PATCH_STEP', service: selected.service, step: selected.step, tick })}
            onAction={(action) => dispatch({ type: 'PATCH_STEP', service: selected.service, step: selected.step, action })}
            onCheck={(wayPointId) => dispatch({ type: 'REPLACE_STEP', service: selected.service, step: selected.step, value: { type: 'CHECK', wayPointId } })}
            onStationOperation={(type) => {
              const movementIndex = selectedService.steps.findIndex((candidate, index) =>
                index > selected.step && candidate.type === 'AT' && candidate.action?.type === 'DESTINATION');
              if (movementIndex < 0) return;
              const tick = selectedService.steps[movementIndex].tick ?? 0;
              dispatch({ type:'INSERT_STEP', service:selected.service, step:movementIndex + 1,
                value:{ type:'AT', tick, action:emptyAction(type) } });
              setSelected({ service:selected.service, step:movementIndex + 1 });
            }} />}
        </div>}
      </aside>
    </section>}

    {view === 'services' && <ServicesPage state={state} metadata={metadata} lines={lineCollection.lines} selected={selected} dispatch={dispatch}
      replaceStep={replaceStep} setSelected={setSelected} />}
    {view === 'lines' && <LinesPage world={world} metadata={metadata} collection={lineCollection}
      onCollection={setLineCollection} run={run} />}
    {view === 'simulation' && <SimulationPage world={world} lines={lineCollection.lines} run={run} />}

    <DataLists metadata={metadata} />
  </main>;
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
        <select aria-label={t('lines.title')} value={service.lineId || ''} onChange={(event) => dispatch({ type:'PATCH_SERVICE', service:serviceIndex, patch:{ lineId:event.target.value || null } })}>
          <option value="">{t('common.none')}</option>{lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
        </select>
        <select value={service.selectorType} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { selectorType: event.target.value as 'ANY'|'TRAIN_ID', trainId: event.target.value === 'ANY' ? null : service.trainId } })}>
          <option value="ANY">{t('editor.dynamicTrain')}</option><option value="TRAIN_ID">{t('editor.fixedTrain')}</option>
        </select>
        {service.selectorType === 'TRAIN_ID' && <input list="trains" placeholder={t('editor.trainId')} value={service.trainId || ''} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { trainId: event.target.value } })} />}
        <select value={service.activation.type} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { activation: { type: event.target.value as 'SCHEDULED'|'MANUAL' } } })}>
          <option value="SCHEDULED">{t('editor.scheduled')}</option><option value="MANUAL">{t('editor.manual')}</option>
        </select>
        <button onClick={() => dispatch({ type: 'ADD_STEP', service: serviceIndex })}>+ {t('editor.addAction')}</button>
        <button className="danger" onClick={() => { dispatch({ type: 'DELETE_SERVICE', service: serviceIndex }); setSelected(undefined); }}>{t('common.delete')}</button>
      </div>
      <table><thead><tr><th>{t('editor.tick')}</th><th>{t('editor.actionType')}</th><th>{t('editor.target')}</th><th>{t('editor.value')}</th><th /></tr></thead>
        <tbody>{service.steps.map((step, stepIndex) => <ActionRow key={stepIndex} step={step}
          selected={selected?.service === serviceIndex && selected.step === stepIndex} metadata={metadata}
          onSelect={() => setSelected({ service: serviceIndex, step: stepIndex })}
          onReplace={(kind) => replaceStep(serviceIndex, stepIndex, kind)}
          onTick={(tick) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, tick })}
          onAction={(action) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, action })}
          onCheck={(wayPointId) => dispatch({ type: 'REPLACE_STEP', service: serviceIndex, step: stepIndex, value: { type: 'CHECK', wayPointId } })}
          onDelete={() => dispatch({ type: 'DELETE_STEP', service: serviceIndex, step: stepIndex })} />)}</tbody>
      </table>
    </article>)}
  </section>;
}

function LinesPage({ world, metadata, collection, onCollection, run }: {
  world:string; metadata:WorldMetadata; collection:LineCollection;
  onCollection:(value:LineCollection)=>void;
  run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState('');
  const [editing, setEditing] = useState<LineV1>({ id:'', displayName:'', stationIds:[], color:'#56c5ff' });
  const [preview, setPreview] = useState<LinePreview>();
  const stations = Array.from(new Map((metadata.stations || []).map((station) => [station.stationId, station])).values());
  const select = (id:string) => {
    setSelectedId(id); setPreview(undefined);
    const line = collection.lines.find((item) => item.id === id);
    setEditing(line ? { ...line, stationIds:[...line.stationIds] } : {
      id:`line-${collection.lines.length + 1}`,
      displayName:`Line ${collection.lines.length + 1}`,
      stationIds:[], color:'#56c5ff',
    });
  };
  const updateStations = (stationIds:string[]) => { setEditing({ ...editing, stationIds }); setPreview(undefined); };
  const saveLine = async () => {
    const result = await run(() => api.saveLine(world, editing, collection.revision));
    if (!result) return;
    const lines = [...collection.lines.filter((line) => line.id !== result.line.id), result.line]
      .sort((a,b) => a.id.localeCompare(b.id));
    onCollection({ revision:result.revision, lines }); setSelectedId(result.line.id); setPreview(result.preview);
  };
  const deleteLine = async () => {
    if (!selectedId) return;
    const result = await run(() => api.deleteLine(world, selectedId, collection.revision));
    if (!result) return;
    onCollection({ revision:result.revision, lines:collection.lines.filter((line) => line.id !== selectedId) });
    setSelectedId(''); setEditing({ id:'', displayName:'', stationIds:[], color:'#56c5ff' }); setPreview(undefined);
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
      <div className="section-title"><h3>{t('lines.orderedStations')}</h3><select value="" onChange={(event) => {
        if (event.target.value && !editing.stationIds.includes(event.target.value)) updateStations([...editing.stationIds, event.target.value]);
      }}><option value="">+ {t('lines.addStation')}</option>{stations.filter((station) => !editing.stationIds.includes(station.stationId)).map((station) =>
        <option key={station.stationId} value={station.stationId}>{station.stationDisplayName || station.stationId}</option>)}</select></div>
      <ol className="station-order">{editing.stationIds.map((stationId,index) => {
        const station = stations.find((item) => item.stationId === stationId);
        return <li key={stationId}><span>{station?.stationDisplayName || stationId}<small>{stationId}</small></span>
          <button disabled={index===0} onClick={() => updateStations(move(editing.stationIds,index,index-1))}>↑</button>
          <button disabled={index===editing.stationIds.length-1} onClick={() => updateStations(move(editing.stationIds,index,index+1))}>↓</button>
          <button className="danger" onClick={() => updateStations(editing.stationIds.filter((id) => id !== stationId))}>×</button></li>;
      })}</ol>
      <div className="line-actions">
        <button disabled={editing.stationIds.length < 2} onClick={async () => setPreview(await run(() => api.previewLine(world, editing)))}>{t('lines.preview')}</button>
        <button className="primary" disabled={editing.stationIds.length < 2 || !editing.id || !editing.displayName} onClick={() => void saveLine()}>{t('common.save')}</button>
        <button className="danger" disabled={!selectedId} onClick={() => void deleteLine()}>{t('common.delete')}</button>
      </div>
      {preview && <div className={preview.valid ? 'line-preview valid' : 'line-preview invalid'}>
        {preview.valid ? <><strong>{t('lines.routeValid')}</strong><span>{Math.round(preview.totalLengthMeters || 0)} m</span>
          <ul>{preview.legs?.map((leg,index) => <li key={index}>{leg.fromStationId} → {leg.toStationId}: {Math.round(leg.lengthMeters)} m</li>)}</ul></>
          : <><strong>{t('lines.routeInvalid')}</strong><span>{preview.error}</span></>}
      </div>}
    </article>
  </section>;
}

function move<T>(items:T[], from:number, to:number):T[] {
  const copy = [...items]; const [item] = copy.splice(from,1); copy.splice(to,0,item); return copy;
}

function SimulationPage({ world, lines, run }: {
  world:string; lines:LineV1[]; run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [lineId,setLineId] = useState('');
  const [profile,setProfile] = useState<SimulationProfile>({
    maxSpeedKmh:72, carCount:6, poweredCars:4, carMassKilograms:32000,
    tractionForceNewtons:40000, tractionPowerWatts:500000, brakeForceNewtons:45000,
  });
  const [result,setResult] = useState<LineSimulationResult>();
  const number = (key:keyof SimulationProfile, minimum:number) => (event:React.ChangeEvent<HTMLInputElement>) =>
    setProfile({ ...profile, [key]:Math.max(minimum,+event.target.value) });
  return <section className="page-scroll simulation-page">
    <article className="card simulation-config"><h2>{t('simulation.title')}</h2>
      <label>{t('simulation.line')}<select value={lineId} onChange={(event) => { setLineId(event.target.value); setResult(undefined); }}>
        <option value="">{t('common.select')}</option>{lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
      </select></label>
      <div className="fields-grid simulation-fields">
        <label>{t('simulation.maxSpeed')}<input type="number" min="1" value={profile.maxSpeedKmh} onChange={number('maxSpeedKmh',1)} /></label>
        <label>{t('simulation.cars')}<input type="number" min="1" max="64" value={profile.carCount} onChange={number('carCount',1)} /></label>
        <label>{t('simulation.poweredCars')}<input type="number" min="1" max={profile.carCount} value={profile.poweredCars} onChange={(event) => setProfile({...profile,poweredCars:Math.min(profile.carCount,Math.max(1,+event.target.value))})} /></label>
        <label>{t('simulation.carMass')}<input type="number" min="100" value={profile.carMassKilograms} onChange={number('carMassKilograms',100)} /></label>
        <label>{t('simulation.tractionForce')}<input type="number" min="1" value={profile.tractionForceNewtons} onChange={number('tractionForceNewtons',1)} /></label>
        <label>{t('simulation.tractionPower')}<input type="number" min="1" value={profile.tractionPowerWatts} onChange={number('tractionPowerWatts',1)} /></label>
        <label>{t('simulation.brakeForce')}<input type="number" min="1" value={profile.brakeForceNewtons} onChange={number('brakeForceNewtons',1)} /></label>
      </div>
      <button className="primary" disabled={!lineId} onClick={async () => setResult(await run(() => api.simulateLine(world,lineId,profile)))}>{t('simulation.run')}</button>
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
      <OptionalNumber label={t('editor.stoppingOffset')} value={action.stoppingOffsetMetersBefore} onChange={(value) => onAction({ stoppingOffsetMetersBefore: value })} />
    </fieldset>
    <fieldset><legend>{t('editor.beforeDeparture')}</legend>
      <OptionalNumber label={t('editor.minimumDwell')} value={action.minimumDwellTicks} onChange={(value) => onAction({ minimumDwellTicks: value })} />
      <label>{t('editor.doorSide')}<select value={action.doorSideOverride || ''} onChange={(event) => onAction({ doorSideOverride: (event.target.value || null) as TimetableActionV1['doorSideOverride'] })}>
        <option value="">{t('editor.defaultValue')}</option><option>LEFT</option><option>RIGHT</option><option>BOTH</option>
      </select></label>
      {onStationOperation && <div className="station-operation-buttons"><span>{t('editor.afterArrival')}</span>
        <button onClick={() => onStationOperation('REVERSE')}>{t('actions.REVERSE')}</button>
        <button onClick={() => onStationOperation('STOP')}>{t('actions.STOP')}</button>
        <button onClick={() => onStationOperation('HALT')}>{t('actions.HALT')}</button>
      </div>}
    </fieldset>
  </>;
  const points = action.wayPointIds || [];
  return <>
    <label>{t('editor.target')}<input list="points" value={points.at(-1) || ''} onChange={(event) => onAction({ wayPointIds: [...points.slice(0, -1), event.target.value] })} /></label>
    {action.type === 'DESTINATION' && <label>{t('editor.viaPoints')}<input value={points.slice(0, -1).join(', ')} onChange={(event) => onAction({ wayPointIds: [...splitList(event.target.value), points.at(-1) || ''] })} /></label>}
    {action.type === 'DESTINATION_OPTIONS' && <>
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
