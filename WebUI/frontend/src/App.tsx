import { useEffect, useReducer, useState } from 'react';
import { api } from './api';
import { emptyAction, emptyTimetable, reducer } from './editor';
import { Graph } from './Graph';
import { assignLineToService, availableStationStatuses, serviceStationRows, updateStationStatus, type ServiceStationRow, type StationStopStatus } from './schedule';
import { useI18n } from './i18n';
import type {
  ActionType,
  DraftSummary,
  LineCollection,
  LinePreview,
  LineSimulationResult,
  LineV1,
  SimulationProfile,
  RenderedTimetableScript,
  SavedTimetableV1,
  TimetableScriptSummary,
  TimetableActionV1,
  TimetableStepV1,
  ValidationIssue,
  WorldMetadata,
  WorldSummary,
} from './types';
import './styles.css';

const actionTypes = [
  'DESTINATION', 'STOP', 'HALT', 'REVERSE', 'TAKE_FROM_DEPOT', 'STORE_AT_DEPOT',
  'DESTINATION_OPTIONS', 'STATION_DESTINATION_OPTIONS', 'EXTENSION',
] as const satisfies readonly ActionType[];
type View = 'graph' | 'lines' | 'services' | 'scripts' | 'simulation';

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
  const [arrivalTimes, setArrivalTimes] = useState<Record<string,number>>({});
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
        {(['graph', 'lines', 'services', 'scripts', 'simulation'] as View[]).map((item) =>
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
      {view === 'scripts' ? <span className="readonly-banner">{t('scripts.readOnly')}</span> : <>
      <label>{t('common.draft')}<select value={draftId} onChange={(event) => void load(event.target.value)}>
        <option value="">{t('common.newDraft')}</option>
        {drafts.map((draft) => <option key={draft.id} value={draft.id}>{draft.id}</option>)}
      </select></label>
      <button onClick={() => void save()} disabled={!world || busy}>{t('common.save')}</button>
      <button className="danger" onClick={() => void removeDraft()} disabled={!draftId || busy}>{t('common.delete')}</button>
      <span className="spacer" />
      <button onClick={() => void validate()} disabled={!world || busy}>{t('common.validate')}</button>
      <button className="primary" onClick={() => void run(() => api.publish(world, state))} disabled={!world || busy}>{t('common.publish')}</button>
      </>}
    </section>

    <section className="notice-strip">
      {error && <div className="error" role="alert">{error}</div>}
      {issues.map((issue, index) => <div key={index} className={issue.severity}>{issue.path && <code>{issue.path}</code>} {issue.message}</div>)}
    </section>

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
          <label>{t('lines.title')}<select value={selectedService.lineId || ''} onChange={(event) => {
            const line=lineCollection.lines.find((item) => item.id===event.target.value);
            const updated=line ? assignLineToService(selectedService,line,metadata) : { ...selectedService,lineId:null };
            dispatch({ type:'PATCH_SERVICE', service:selected.service, patch:{ lineId:updated.lineId,steps:updated.steps } });
          }}>
            <option value="">{t('common.none')}</option>{lineCollection.lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
          </select></label>
          <ServiceScheduleTable service={selectedService} serviceIndex={selected.service} line={lineCollection.lines.find((line) => line.id === selectedService.lineId)}
            metadata={metadata} selectedStep={selected.step} arrivals={arrivalTimes}
            onArrival={(stationId,tick) => setArrivalTimes((values) => ({ ...values, [`${selectedService.id}:${stationId}`]:tick }))}
            onSelect={(step) => setSelected({ service:selected.service, step })}
            onDeparture={(step,tick) => dispatch({ type:'PATCH_STEP', service:selected.service, step, tick })}
            onStatus={(stationId,status) => {
              const updated=updateStationStatus(selectedService,stationId,status,metadata);
              dispatch({ type:'PATCH_SERVICE', service:selected.service, patch:{ steps:updated.steps } });
              setSelected({ service:selected.service, step:0 });
            }}
            onAddAction={(row,typeId) => {
              const step=row.departureStepIndex ?? selectedService.steps.length;
              const action=typeId.startsWith('extension|') ? emptyAction('EXTENSION') : emptyAction(typeId as ActionType);
              if (typeId.startsWith('extension|')) {
                const [,id,version]=typeId.split('|');
                action.extensionCall={ typeId:id, version:+version, parameters:{} };
              }
              dispatch({ type:'INSERT_STEP', service:selected.service, step, value:{ type:'AT', tick:row.departureTick ?? 0, action } });
              setSelected({ service:selected.service, step });
            }} />
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
    {view === 'scripts' && <ScriptsPage world={world} metadata={metadata} run={run} />}
    {view === 'simulation' && <SimulationPage world={world} lines={lineCollection.lines} metadata={metadata} run={run} />}

    <DataLists metadata={metadata} />
  </main>;
}

function ServiceScheduleTable({ service, serviceIndex, line, metadata, selectedStep, arrivals, onArrival, onSelect, onDeparture, onStatus, onAddAction }: {
  service:SavedTimetableV1['services'][number]; serviceIndex:number; line?:LineV1; metadata:WorldMetadata; selectedStep:number;
  arrivals:Record<string,number>; onArrival:(stationId:string,tick:number)=>void; onSelect:(step:number)=>void;
  onDeparture:(step:number,tick:number)=>void; onStatus:(stationId:string,status:StationStopStatus)=>void;
  onAddAction:(row:ServiceStationRow,typeId:string)=>void;
}) {
  const { t } = useI18n();
  const rows=serviceStationRows(service,metadata,line);
  const extensions=metadata.extensions?.actions || [];
  if (!line) return <p className="empty">{t('editor.selectLineForSchedule')}</p>;
  return <div className="schedule-editor">
    <div className="schedule-header"><span>{t('editor.station')}</span><span>{t('editor.stopStatus')}</span><span>{t('editor.arrivalTime')}</span><span>{t('editor.departureTime')}</span><span>{t('editor.rowActions')}</span></div>
    {rows.map((row) => {
      const arrivalKey=`${service.id}:${row.stationId}`;
      const available=availableStationStatuses(service,row,metadata);
      return <div className="schedule-row" key={`${serviceIndex}:${row.stationId}`}>
        <strong title={row.stationId}>{row.displayName}</strong>
        <select aria-label={`${row.displayName} ${t('editor.stopStatus')}`} value={row.status} onChange={(event) => onStatus(row.stationId,event.target.value as StationStopStatus)}>
          <option value="ORIGIN" disabled={!available.includes('ORIGIN')}>{t('status.origin')}</option><option value="STOP" disabled={!available.includes('STOP')}>{t('status.stop')}</option>
          <option value="PASS" disabled={!available.includes('PASS')}>{t('status.pass')}</option><option value="TERMINAL" disabled={!available.includes('TERMINAL')}>{t('status.terminal')}</option>
        </select>
        <input aria-label={`${row.displayName} ${t('editor.arrivalTime')}`} type="number" min="0" value={arrivals[arrivalKey] ?? row.arrivalTick ?? ''}
          placeholder="—" onChange={(event) => onArrival(row.stationId,Math.max(0,+event.target.value))} />
        <input aria-label={`${row.displayName} ${t('editor.departureTime')}`} type="number" min="0" value={row.departureTick ?? ''} placeholder="—"
          disabled={row.departureStepIndex == null} onChange={(event) => row.departureStepIndex != null && onDeparture(row.departureStepIndex,Math.max(0,+event.target.value))} />
        <div className="schedule-actions">
          {row.departureStepIndex != null && <button className={selectedStep===row.departureStepIndex?'active':''} onClick={() => onSelect(row.departureStepIndex!)}>{t('actions.DESTINATION')}</button>}
          {row.actionStepIndices.map((stepIndex) => {
            const action=service.steps[stepIndex].action;
            const label=action?.type==='EXTENSION' ? action.extensionCall?.typeId || 'EXTENSION' : action?.type==='STOP' ? t('actions.WAIT') : t(`actions.${action?.type || 'DESTINATION'}` as Parameters<typeof t>[0]);
            return <button key={stepIndex} className={selectedStep===stepIndex?'active':''} onClick={() => onSelect(stepIndex)}>{label}</button>;
          })}
          <select aria-label={`${row.displayName} ${t('editor.addAction')}`} value="" onChange={(event) => { if(event.target.value) onAddAction(row,event.target.value); }}>
            <option value="">＋</option><option value="REVERSE">{t('actions.REVERSE')}</option><option value="STOP">{t('actions.WAIT')}</option><option value="HALT">{t('actions.HALT')}</option>
            {extensions.map((extension) => <option key={`${extension.typeId}@${extension.version}`} value={`extension|${extension.typeId}|${extension.version}`}>{extension.title}</option>)}
          </select>
        </div>
      </div>;
    })}
    <p className="schedule-note">{t('editor.arrivalVisualOnly')}</p>
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
        <select aria-label={t('lines.title')} value={service.lineId || ''} onChange={(event) => {
          const line=lines.find((item) => item.id===event.target.value);
          const updated=line ? assignLineToService(service,line,metadata) : { ...service,lineId:null };
          dispatch({ type:'PATCH_SERVICE', service:serviceIndex, patch:{ lineId:updated.lineId,steps:updated.steps } });
        }}>
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

function ScriptsPage({ world, metadata, run }: {
  world:string; metadata:WorldMetadata; run:<T>(operation:()=>Promise<T>)=>Promise<T|undefined>;
}) {
  const { t } = useI18n();
  const [scripts,setScripts] = useState<TimetableScriptSummary[]>([]);
  const [selected,setSelected] = useState('');
  const [rendered,setRendered] = useState<RenderedTimetableScript>();
  useEffect(() => {
    if (!world) return;
    void run(() => api.scripts(world)).then((items) => { setScripts(items || []); setSelected(''); setRendered(undefined); });
  }, [world]);
  const select = async (name:string) => {
    setSelected(name); setRendered(undefined);
    if (!name) return;
    setRendered(await run(() => api.renderScript(world,name)));
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
          <section className="card readonly-graph"><h3>{t('scripts.rendered')}</h3><Graph value={rendered.timetable} metadata={metadata} readOnly onTick={() => {}} /></section>
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
  const [result,setResult] = useState<LineSimulationResult>();
  const profiles = metadata.simulationProfiles || [];
  const selectedProfile = profiles.find((candidate) => `${candidate.type}:${candidate.id}` === profileKey);
  return <section className="page-scroll simulation-page">
    <article className="card simulation-config"><h2>{t('simulation.title')}</h2>
      <label>{t('simulation.line')}<select value={lineId} onChange={(event) => { setLineId(event.target.value); setResult(undefined); }}>
        <option value="">{t('common.select')}</option>{lines.map((line) => <option key={line.id} value={line.id}>{line.displayName}</option>)}
      </select></label>
      <label>{t('simulation.profile')}<select value={profileKey} onChange={(event) => { setProfileKey(event.target.value); setResult(undefined); }}>
        <option value="">{t('common.select')}</option>
        <optgroup label={t('simulation.worldTrains')}>{profiles.filter((profile) => profile.type === 'WORLD_TRAIN').map((profile) =>
          <option key={`WORLD_TRAIN:${profile.id}`} value={`WORLD_TRAIN:${profile.id}`}>{profile.displayName}</option>)}</optgroup>
        <optgroup label={t('simulation.templates')}>{profiles.filter((profile) => profile.type === 'TRAIN_TEMPLATE').map((profile) =>
          <option key={`TRAIN_TEMPLATE:${profile.id}`} value={`TRAIN_TEMPLATE:${profile.id}`}>{profile.displayName} · {profile.carCount}</option>)}</optgroup>
      </select></label>
      <button className="primary" disabled={!lineId || !selectedProfile} onClick={async () => {
        if (!selectedProfile) return;
        const profile:SimulationProfile = { profileType:selectedProfile.type, profileId:selectedProfile.id };
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
