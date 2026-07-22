import type { DraftSummary, LineCollection, LinePreview, LineSimulationResult, LineV1, RailMapFile, RenderedTimetableScript, SavedLineResult, SavedTimetableV1, SimulationProfile, TimetableScriptSummary, ValidationResult, WorldMetadata, WorldSummary } from './types';
async function request<T>(url: string, init?: RequestInit): Promise<T> { const response = await fetch(url, { headers: { 'Content-Type': 'application/json', ...init?.headers }, ...init }); if (!response.ok) throw new Error((await response.text()) || `${response.status} ${response.statusText}`); return response.status === 204 ? undefined as T : response.json(); }
const base = (worldId: string) => `/api/v1/worlds/${encodeURIComponent(worldId)}`;
export const api = {
 worlds: () => request<WorldSummary[]>('/api/v1/worlds'), metadata: (worldId: string) => request<WorldMetadata>(`${base(worldId)}/metadata`),
 railMap: (worldId:string) => request<RailMapFile>(`${base(worldId)}/rail-map`),
 scripts: (worldId: string) => request<TimetableScriptSummary[]>(`${base(worldId)}/scripts`),
 renderScript: (worldId: string, name: string, id?: string) => request<RenderedTimetableScript>(`${base(worldId)}/scripts/${encodeURIComponent(name)}${id ? `?id=${encodeURIComponent(id)}` : ''}`),
 lines: (worldId: string) => request<LineCollection>(`${base(worldId)}/lines`),
 previewLine: (worldId: string, line: LineV1) => request<LinePreview>(`${base(worldId)}/lines/preview`, { method:'POST', body:JSON.stringify(line) }),
 simulateLine: (worldId: string, lineId: string, profile: SimulationProfile) => request<LineSimulationResult>(`${base(worldId)}/lines/${encodeURIComponent(lineId)}/simulate`, { method:'POST', body:JSON.stringify(profile) }),
 saveLine: (worldId: string, line: LineV1, expectedRevision: number) => request<SavedLineResult>(`${base(worldId)}/lines/${encodeURIComponent(line.id)}`, { method:'PUT', body:JSON.stringify({ line, expectedRevision }) }),
 deleteLine: (worldId: string, lineId: string, expectedRevision: number) => request<{revision:number;removed:boolean}>(`${base(worldId)}/lines/${encodeURIComponent(lineId)}`, { method:'DELETE', body:JSON.stringify({ expectedRevision }) }),
 drafts: (worldId: string) => request<DraftSummary[]>(`${base(worldId)}/drafts`),
 draft: (worldId: string, draftId: string) => request<SavedTimetableV1>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`),
 createDraft: (worldId: string, value: SavedTimetableV1) => request<DraftSummary>(`${base(worldId)}/drafts`, { method:'POST', body:JSON.stringify(value) }),
 saveDraft: (worldId: string, draftId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'PUT', body:JSON.stringify(value) }),
 deleteDraft: (worldId: string, draftId: string) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'DELETE' }),
 validate: (worldId: string, value: SavedTimetableV1) => request<ValidationResult>(`${base(worldId)}/validate`, { method:'POST', body:JSON.stringify(value) }),
 publish: (worldId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/publish`, { method:'POST', body:JSON.stringify(value) })
};
