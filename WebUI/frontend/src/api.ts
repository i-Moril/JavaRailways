import type { TimetableAuthoringDocumentV1 } from './authoring';
import { demoApi, frontendDemoEnabled } from './frontendDev';
import type { CompiledTimetableSource, DraftSummary, EditorDocumentSummary, LineCollection, LineSimulationResult, LineV1, RailMapFile, RenderedTimetableScript, SavedLineResult, SavedTimetableV1, SimulationProfile, TimetableScriptSummary, TimetableSimulationProfileAssignment, TimetableSimulationResult, ValidationResult, WebSessionStatus, WorldMetadata, WorldSummary, WorldIssue, ServerLogSources, ServerLogPage, ServerLogQuery } from './types';
async function request<T>(url: string, init?: RequestInit): Promise<T> { const response = await fetch(url, { headers: { 'Content-Type': 'application/json', ...init?.headers }, ...init }); if (!response.ok) { if(response.status===401&&typeof window!=='undefined')window.dispatchEvent(new Event('javarailways:unauthorized')); throw new Error((await response.text()) || `${response.status} ${response.statusText}`); } return response.status === 204 ? undefined as T : response.json(); }
const base = (worldId: string) => `/api/v1/worlds/${encodeURIComponent(worldId)}`;
const liveApi = {
 logSources:()=>request<ServerLogSources>('/api/v1/logs/sources'),
 logs:({source,query,cursor}:ServerLogQuery)=>{const params=new URLSearchParams({source});if(query)params.set('query',query);if(cursor)params.set('cursor',cursor);return request<ServerLogPage>(`/api/v1/logs?${params}`);},
 session:()=>request<WebSessionStatus>('/auth/session'),
 worlds: () => request<WorldSummary[]>('/api/v1/worlds'), metadata: (worldId: string) => request<WorldMetadata>(`${base(worldId)}/metadata`),
 railMap: (worldId:string) => request<RailMapFile>(`${base(worldId)}/rail-map`),
 issues: (worldId:string) => request<WorldIssue[]>(`${base(worldId)}/issues`),
 scripts: (worldId: string) => request<TimetableScriptSummary[]>(`${base(worldId)}/scripts`),
 renderScript: (worldId: string, name: string, id?: string) => request<RenderedTimetableScript>(`${base(worldId)}/scripts/${encodeURIComponent(name)}${id ? `?id=${encodeURIComponent(id)}` : ''}`),
 compileSource: (worldId:string,id:string,source:string,documentHash?:string) => request<CompiledTimetableSource>(`${base(worldId)}/scripts/compile`, {method:'POST',body:JSON.stringify({id,source,documentHash})}),
 simulateTimetable: (worldId:string,timetable:SavedTimetableV1,profileAssignments:TimetableSimulationProfileAssignment[],delayThresholdTicks:number) => request<TimetableSimulationResult>(`${base(worldId)}/timetables/simulate`,{method:'POST',body:JSON.stringify({timetable,profileAssignments,delayThresholdTicks})}),
 lines: (worldId: string) => request<LineCollection>(`${base(worldId)}/lines`),
 simulateLine: (worldId: string, lineId: string, profile: SimulationProfile) => request<LineSimulationResult>(`${base(worldId)}/lines/${encodeURIComponent(lineId)}/simulate`, { method:'POST', body:JSON.stringify(profile) }),
 saveLine: (worldId: string, line: LineV1, expectedRevision: number) => request<SavedLineResult>(`${base(worldId)}/lines/${encodeURIComponent(line.id)}`, { method:'PUT', body:JSON.stringify({ line, expectedRevision }) }),
 deleteLine: (worldId: string, lineId: string, expectedRevision: number) => request<{revision:number;removed:boolean}>(`${base(worldId)}/lines/${encodeURIComponent(lineId)}`, { method:'DELETE', body:JSON.stringify({ expectedRevision }) }),
 drafts: (worldId: string) => request<DraftSummary[]>(`${base(worldId)}/drafts`),
 draft: (worldId: string, draftId: string) => request<SavedTimetableV1>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`),
 createDraft: (worldId: string, value: SavedTimetableV1) => request<DraftSummary>(`${base(worldId)}/drafts`, { method:'POST', body:JSON.stringify(value) }),
 saveDraft: (worldId: string, draftId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'PUT', body:JSON.stringify(value) }),
 deleteDraft: (worldId: string, draftId: string) => request<void>(`${base(worldId)}/drafts/${encodeURIComponent(draftId)}`, { method:'DELETE' }),
 editorDocuments: (worldId:string) => request<EditorDocumentSummary[]>(`${base(worldId)}/editor-documents`),
 editorDocument: (worldId:string,id:string) => request<TimetableAuthoringDocumentV1>(`${base(worldId)}/editor-documents/${encodeURIComponent(id)}`),
 createEditorDocument: (worldId:string,value:TimetableAuthoringDocumentV1) => request<{id:string}>(`${base(worldId)}/editor-documents`,{method:'POST',body:JSON.stringify(value)}),
 saveEditorDocument: (worldId:string,id:string,value:TimetableAuthoringDocumentV1) => request<void>(`${base(worldId)}/editor-documents/${encodeURIComponent(id)}`,{method:'PUT',body:JSON.stringify(value)}),
 deleteEditorDocument: (worldId:string,id:string) => request<void>(`${base(worldId)}/editor-documents/${encodeURIComponent(id)}`,{method:'DELETE'}),
 saveFormalScript: (worldId:string,documentId:string,fileName:string,source:string,document:TimetableAuthoringDocumentV1,overwrite=false) => request<{name:string;replaced:boolean}>(`${base(worldId)}/editor-documents/${encodeURIComponent(documentId)}/formal-script`,{method:'PUT',body:JSON.stringify({fileName,source,document,overwrite})}),
 renameFormalScript: (worldId:string,documentId:string,sourceName:string,targetName:string,document:TimetableAuthoringDocumentV1) => request<{name:string}>(`${base(worldId)}/editor-documents/${encodeURIComponent(documentId)}/formal-script/rename`,{method:'POST',body:JSON.stringify({sourceName,targetName,document})}),
 deleteFormalScript: (worldId:string,documentId:string,fileName:string) => request<{removed:boolean}>(`${base(worldId)}/editor-documents/${encodeURIComponent(documentId)}/formal-script`,{method:'DELETE',body:JSON.stringify({fileName})}),
 validate: (worldId: string, value: SavedTimetableV1) => request<ValidationResult>(`${base(worldId)}/validate`, { method:'POST', body:JSON.stringify(value) }),
 publish: (worldId: string, value: SavedTimetableV1) => request<void>(`${base(worldId)}/publish`, { method:'POST', body:JSON.stringify(value) })
};
export const api:typeof liveApi=frontendDemoEnabled?{...demoApi,logSources:liveApi.logSources,logs:liveApi.logs}:liveApi;
