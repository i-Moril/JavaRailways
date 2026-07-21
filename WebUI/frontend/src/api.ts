import type { DraftSummary, SavedTimetableV1, ValidationResult, WorldMetadata, WorldSummary } from './types';
async function request<T>(url: string, init?: RequestInit): Promise<T> { const response = await fetch(url, { headers: { 'Content-Type': 'application/json', ...init?.headers }, ...init }); if (!response.ok) throw new Error((await response.text()) || `${response.status} ${response.statusText}`); return response.status === 204 ? undefined as T : response.json(); }
const base = (worldId: string) => `/api/v1/worlds/${encodeURIComponent(worldId)}`;
export const api = {
 worlds: () => request<WorldSummary[]>('/api/v1/worlds'), metadata: (worldId: string) => request<WorldMetadata>(`${base(worldId)}/metadata`),
 drafts: (worldId: string) => request<DraftSummary[]>(`${base(worldId)}/drafts`),
 draft: (worldId: string, draftId: string) => request<SavedTimetableV1>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`),
 createDraft: (worldId: string, value: SavedTimetableV1) => request<DraftSummary>(`${base(worldId)}/drafts`, { method:'POST', body:JSON.stringify(value) }),
 saveDraft: (worldId: string, draftId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'PUT', body:JSON.stringify(value) }),
 deleteDraft: (worldId: string, draftId: string) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'DELETE' }),
 validate: (worldId: string, value: SavedTimetableV1) => request<ValidationResult>(`${base(worldId)}/validate`, { method:'POST', body:JSON.stringify(value) }),
 publish: (worldId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/publish`, { method:'POST', body:JSON.stringify(value) })
};
