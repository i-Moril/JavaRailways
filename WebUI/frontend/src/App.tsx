import { useEffect, useReducer, useState } from 'react';
import { api } from './api';
import { emptyAction, emptyTimetable, reducer } from './editor';
import { Graph } from './Graph';
import type {
  ActionType,
  DraftSummary,
  SavedTimetableV1,
  TimetableActionV1,
  TimetableStepV1,
  ValidationIssue,
  WorldMetadata,
  WorldSummary,
} from './types';
import './styles.css';

const actionTypes: ActionType[] = [
  'DESTINATION', 'STOP', 'HALT', 'REVERSE', 'TAKE_FROM_DEPOT', 'STORE_AT_DEPOT',
  'DESTINATION_OPTIONS', 'STATION_DESTINATION_OPTIONS',
];

export default function App() {
  const [state, dispatch] = useReducer(reducer, undefined, emptyTimetable);
  const [worlds, setWorlds] = useState<WorldSummary[]>([]);
  const [world, setWorld] = useState('');
  const [metadata, setMetadata] = useState<WorldMetadata>({});
  const [drafts, setDrafts] = useState<DraftSummary[]>([]);
  const [draftId, setDraftId] = useState('');
  const [selected, setSelected] = useState<{ service: number; step: number }>();
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
      const values = await api.worlds();
      setWorlds(values);
      if (values[0]) setWorld(values[0].id);
    });
  }, []);

  useEffect(() => {
    if (!world) return;
    void run(async () => {
      const [reference, savedDrafts] = await Promise.all([api.metadata(world), api.drafts(world)]);
      setMetadata(reference);
      setDrafts(savedDrafts);
      setDraftId('');
    });
  }, [world]);

  const load = async (id: string) => {
    setDraftId(id);
    if (!id) {
      dispatch({ type: 'LOAD', value: emptyTimetable() });
      return;
    }
    const value = await run(() => api.draft(world, id));
    if (value) dispatch({ type: 'LOAD', value });
  };

  const save = async () => {
    if (!draftId) {
      const created = await run(() => api.createDraft(world, state));
      if (created) {
        setDraftId(created.id);
        setDrafts(await api.drafts(world));
      }
    } else await run(() => api.saveDraft(world, draftId, state));
  };

  const removeDraft = async () => {
    if (!draftId) return;
    await run(() => api.deleteDraft(world, draftId));
    setDraftId('');
    dispatch({ type: 'LOAD', value: emptyTimetable() });
    setDrafts(await api.drafts(world));
  };

  const validate = async () => {
    const result = await run(() => api.validate(world, state));
    if (!result) return;
    const messages = [...(result.errors || []), ...(result.warnings || [])];
    setIssues(messages.length ? messages : [{ message: 'No validation issues', severity: 'success' }]);
  };

  const replaceStep = (service: number, step: number, kind: 'CHECK' | ActionType) => {
    const value: TimetableStepV1 = kind === 'CHECK'
      ? { type: 'CHECK', wayPointId: '' }
      : { type: 'AT', tick: 0, action: emptyAction(kind) };
    dispatch({ type: 'REPLACE_STEP', service, step, value });
  };

  return <main>
    <header>
      <div><h1>MineRails Timetable Editor</h1><p>Build, validate and publish a running graph.</p></div>
      <label>World<select value={world} onChange={(event) => setWorld(event.target.value)}>
        {worlds.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}
      </select></label>
    </header>

    <section className="toolbar">
      <label>Draft<select value={draftId} onChange={(event) => void load(event.target.value)}>
        <option value="">New local draft</option>
        {drafts.map((draft) => <option key={draft.id} value={draft.id}>{draft.id}</option>)}
      </select></label>
      <button onClick={() => void save()} disabled={!world || busy}>Save draft</button>
      <button className="danger" onClick={() => void removeDraft()} disabled={!draftId || busy}>Delete draft</button>
      <span className="spacer" />
      <button onClick={() => void validate()} disabled={!world || busy}>Validate</button>
      <button className="primary" onClick={() => void run(() => api.publish(world, state))} disabled={!world || busy}>Publish</button>
    </section>

    {error && <div className="error" role="alert">{error}</div>}
    {issues.length > 0 && <div className="issues">{issues.map((issue, index) =>
      <div key={index} className={issue.severity}>{issue.path && <code>{issue.path}</code>} {issue.message}</div>)}</div>}

    <section className="card fields">
      <label>Timetable ID<input value={state.id} onChange={(event) => dispatch({ type: 'SET_HEADER', id: event.target.value })} /></label>
      <label>Period (days)<input type="number" min="1" value={state.periodDays} onChange={(event) => dispatch({ type: 'SET_HEADER', periodDays: Math.max(1, +event.target.value) })} /></label>
    </section>

    <section className="card">
      <h2>Running graph</h2>
      <p className="hint">Time runs left to right; stations and waypoints run top to bottom. Drag a point to change its Tick.</p>
      <Graph
        value={state}
        onSelect={(service, step) => setSelected({ service, step })}
        onTick={(service, step, tick) => dispatch({ type: 'PATCH_STEP', service, step, tick })}
      />
    </section>

    <section className="card">
      <div className="section-title"><h2>Services & actions</h2><button onClick={() => dispatch({ type: 'ADD_SERVICE' })}>+ Service</button></div>
      {state.services.length === 0 && <p className="empty">No services yet.</p>}
      {state.services.map((service, serviceIndex) => <article className="service" key={serviceIndex}>
        <div className="service-head">
          <input aria-label="Service ID" value={service.id} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { id: event.target.value } })} />
          <select aria-label="Train selector" value={service.selectorType} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { selectorType: event.target.value as 'ANY' | 'TRAIN_ID', trainId: event.target.value === 'ANY' ? null : service.trainId } })}>
            <option value="ANY">Dynamic depot train</option><option value="TRAIN_ID">Fixed train</option>
          </select>
          {service.selectorType === 'TRAIN_ID' && <input list="trains" placeholder="Train ID" value={service.trainId || ''} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { trainId: event.target.value } })} />}
          <select value={service.activation.type} onChange={(event) => dispatch({ type: 'PATCH_SERVICE', service: serviceIndex, patch: { activation: { type: event.target.value as 'SCHEDULED' | 'MANUAL' } } })}>
            <option value="SCHEDULED">Scheduled</option><option value="MANUAL">Manual</option>
          </select>
          <button onClick={() => dispatch({ type: 'ADD_STEP', service: serviceIndex })}>+ Action</button>
          <button className="danger" onClick={() => dispatch({ type: 'DELETE_SERVICE', service: serviceIndex })}>Delete</button>
        </div>
        <table><thead><tr><th>Tick</th><th>Type</th><th>Location / target</th><th>Value</th><th /></tr></thead>
          <tbody>{service.steps.map((step, stepIndex) => <ActionRow
            key={stepIndex}
            step={step}
            selected={selected?.service === serviceIndex && selected.step === stepIndex}
            metadata={metadata}
            onSelect={() => setSelected({ service: serviceIndex, step: stepIndex })}
            onReplace={(kind) => replaceStep(serviceIndex, stepIndex, kind)}
            onTick={(tick) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, tick })}
            onAction={(action) => dispatch({ type: 'PATCH_STEP', service: serviceIndex, step: stepIndex, action })}
            onCheck={(wayPointId) => dispatch({ type: 'REPLACE_STEP', service: serviceIndex, step: stepIndex, value: { type: 'CHECK', wayPointId } })}
            onDelete={() => dispatch({ type: 'DELETE_STEP', service: serviceIndex, step: stepIndex })}
          />)}</tbody>
        </table>
      </article>)}
    </section>

    <datalist id="points">{[...(metadata.wayPoints || []), ...(metadata.stations || []).map((berth) => berth.destinationId)].map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="stations">{Array.from(new Set((metadata.stations || []).map((berth) => berth.stationId))).map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="depots">{metadata.depots?.map((id) => <option key={id} value={id} />)}</datalist>
    <datalist id="trains">{metadata.trains?.map((id) => <option key={id} value={id} />)}</datalist>
  </main>;
}

function ActionRow({ step, selected, metadata, onSelect, onReplace, onTick, onAction, onCheck, onDelete }: {
  step: TimetableStepV1;
  selected: boolean;
  metadata: WorldMetadata;
  onSelect: () => void;
  onReplace: (kind: 'CHECK' | ActionType) => void;
  onTick: (tick: number) => void;
  onAction: (action: Partial<TimetableActionV1>) => void;
  onCheck: (id: string) => void;
  onDelete: () => void;
}) {
  const action = step.action;
  const kind = step.type === 'CHECK' ? 'CHECK' : action?.type || 'DESTINATION';
  const target = action?.wayPointIds?.at(-1) || action?.depotId || action?.stationId || '';
  return <tr className={selected ? 'selected' : ''} onClick={onSelect}>
    <td>{step.type === 'AT' ? <input type="number" min="0" value={step.tick ?? 0} onChange={(event) => onTick(Math.max(0, +event.target.value))} /> : '—'}</td>
    <td><select value={kind} onChange={(event) => onReplace(event.target.value as 'CHECK' | ActionType)}>
      <option value="CHECK">CHECK</option>{actionTypes.map((type) => <option key={type}>{type}</option>)}
    </select></td>
    <td>{step.type === 'CHECK'
      ? <input list="points" value={step.wayPointId || ''} onChange={(event) => onCheck(event.target.value)} />
      : <input list={action?.type.includes('DEPOT') ? 'depots' : action?.type === 'STATION_DESTINATION_OPTIONS' ? 'stations' : 'points'} value={target} onChange={(event) => {
          if (action?.type.includes('DEPOT')) onAction({ depotId: event.target.value });
          else if (action?.type === 'STATION_DESTINATION_OPTIONS') onAction({ stationId: event.target.value });
          else onAction({ wayPointIds: [event.target.value] });
        }} />}
    </td>
    <td>{action?.type === 'STOP'
      ? <input type="number" min="1" value={action.stopTicks ?? 20} onChange={(event) => onAction({ stopTicks: Math.max(1, +event.target.value) })} />
      : action?.type === 'STATION_DESTINATION_OPTIONS'
        ? <select value={action.berthId || ''} onChange={(event) => onAction({ berthId: event.target.value })}>
            <option value="">Select berth</option>
            {(metadata.stations || []).filter((berth) => berth.stationId === action.stationId).map((berth) =>
              <option key={berth.destinationId} value={berth.berthId}>{berth.berthId}</option>)}
          </select>
        : '—'}</td>
    <td><button className="danger" onClick={(event) => { event.stopPropagation(); onDelete(); }}>×</button></td>
  </tr>;
}
