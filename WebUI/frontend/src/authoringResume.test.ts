import {afterEach,describe,expect,it,vi} from 'vitest';
import {emptyAuthoringDocument} from './authoring';
import {loadLastAuthoringDocument,parseLastAuthoringDocument,rememberAuthoringDocument,resumeAuthoringWorkspace} from './authoringResume';
import type {EditorDocumentSummary} from './types';

const summary:EditorDocumentSummary={id:'saved',updatedAt:'2026-10-23T00:00:00Z'};
const deferred=<T,>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>((done)=>{resolve=done;});return {promise,resolve};};
const options=()=>({documentId:'saved',hasRecovery:false,isOwnerCurrent:()=>true,isCurrent:()=>true,list:vi.fn(async()=>[summary]),load:vi.fn(async()=>({...emptyAuthoringDocument(),id:'server-copy'})),onList:vi.fn(),onMissing:vi.fn(),onResume:vi.fn()});
afterEach(()=>vi.unstubAllGlobals());

describe('last saved authoring document',()=>{
  it('scopes the pointer to owner and world and rejects corrupt values',()=>{
    const raw=JSON.stringify({version:1,owner:'Alice',world:'world',documentId:'saved'});
    expect(parseLastAuthoringDocument(raw,'Alice','world')).toBe('saved');
    expect(parseLastAuthoringDocument(raw,'Bob','world')).toBeUndefined();
    expect(parseLastAuthoringDocument(raw,'Alice','other')).toBeUndefined();
    for(const value of ['{','null','[]',JSON.stringify({version:2,owner:'Alice',world:'world',documentId:'saved'}),JSON.stringify({version:1,owner:'Alice',world:'world',documentId:'../bad'})])expect(parseLastAuthoringDocument(value,'Alice','world')).toBeUndefined();
  });
  it('remembers synchronously without storing a stale document snapshot, and clears explicitly',()=>{
    const storage=new Map<string,string>();
    vi.stubGlobal('window',{localStorage:{getItem:(key:string)=>storage.get(key)??null,setItem:(key:string,value:string)=>storage.set(key,value),removeItem:(key:string)=>storage.delete(key)}});
    rememberAuthoringDocument('Alice','world','saved');
    expect(loadLastAuthoringDocument('Alice','world')).toBe('saved');
    expect(loadLastAuthoringDocument('Alice','other')).toBeUndefined();
    expect(loadLastAuthoringDocument('Bob','world')).toBeUndefined();
    expect(JSON.parse([...storage.values()][0])).toEqual({version:1,owner:'Alice',world:'world',documentId:'saved'});
    rememberAuthoringDocument('Alice','world','');expect(loadLastAuthoringDocument('Alice','world')).toBeUndefined();
  });
  it('tolerates unavailable storage and SSR',()=>{
    expect(loadLastAuthoringDocument('Alice','world')).toBeUndefined();
    expect(()=>rememberAuthoringDocument('Alice','world','saved')).not.toThrow();
    vi.stubGlobal('window',{get localStorage(){throw new Error('denied');}});
    expect(loadLastAuthoringDocument('Alice','world')).toBeUndefined();
    expect(()=>rememberAuthoringDocument('Alice','world','saved')).not.toThrow();
    expect(()=>rememberAuthoringDocument('Alice','world','')).not.toThrow();
  });
});

describe('saved workspace resumption',()=>{
  it('reloads server truth after listing the remembered document',async()=>{
    const config=options();await resumeAuthoringWorkspace(config);
    expect(config.load).toHaveBeenCalledWith('saved');
    expect(config.onResume).toHaveBeenCalledWith('saved',expect.objectContaining({id:'server-copy'}));
  });
  it('preserves dirty recovery and never selects an arbitrary production document',async()=>{
    for(const config of [{...options(),hasRecovery:true},{...options(),documentId:undefined}]){
      await resumeAuthoringWorkspace(config);expect(config.onList).toHaveBeenCalled();expect(config.load).not.toHaveBeenCalled();expect(config.onResume).not.toHaveBeenCalled();
    }
  });
  it('clears only after an authoritative missing list result, retaining pointer on transient GET failure',async()=>{
    const missing={...options(),list:vi.fn(async()=>[])};await resumeAuthoringWorkspace(missing);
    expect(missing.onMissing).toHaveBeenCalledOnce();expect(missing.load).not.toHaveBeenCalled();
    const failed={...options(),load:vi.fn(async()=>{throw new Error('offline');})};
    await expect(resumeAuthoringWorkspace(failed)).rejects.toThrow('offline');expect(failed.onMissing).not.toHaveBeenCalled();expect(failed.onResume).not.toHaveBeenCalled();
  });
  it('still lists documents but does not start GET or clear the pointer after intervening edits or commands',async()=>{
    for(const documents of [[summary],[]]){
      const pending=deferred<EditorDocumentSummary[]>();let current=true;
      const config={...options(),list:()=>pending.promise,isCurrent:()=>current};
      const result=resumeAuthoringWorkspace(config);current=false;pending.resolve(documents);await result;
      expect(config.onList).toHaveBeenCalledWith(documents);expect(config.load).not.toHaveBeenCalled();expect(config.onMissing).not.toHaveBeenCalled();
    }
  });
  it('ignores a list response after the owner unmounts',async()=>{
    const pending=deferred<EditorDocumentSummary[]>();let mounted=true;
    const config={...options(),list:()=>pending.promise,isOwnerCurrent:()=>mounted};
    const result=resumeAuthoringWorkspace(config);mounted=false;pending.resolve([summary]);await result;
    expect(config.onList).not.toHaveBeenCalled();expect(config.load).not.toHaveBeenCalled();
  });
  it('does not overwrite edits or a replacement owner after GET has begun',async()=>{
    const pending=deferred<ReturnType<typeof emptyAuthoringDocument>>();let current=true;
    const config={...options(),load:vi.fn(()=>pending.promise),isCurrent:()=>current};
    const result=resumeAuthoringWorkspace(config);await Promise.resolve();expect(config.load).toHaveBeenCalledOnce();
    current=false;pending.resolve(emptyAuthoringDocument());await result;expect(config.onResume).not.toHaveBeenCalled();
  });
});
