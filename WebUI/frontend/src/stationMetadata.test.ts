import { describe,expect,it } from 'vitest';
import { initialMetadataEditor,metadataEditorReducer,parseStationMetadata } from './stationMetadata';

describe('station metadata JSON draft',()=>{
  it('retains nested and unknown custom keys without prototype merging',()=>{
    const text='{"transfers":[{"line":"two","accessible":true}],"custom":{"value":null},"__proto__":{"polluted":true},"constructor":"data"}';
    const parsed=parseStationMetadata(text);
    expect(parsed.error).toBeUndefined();
    expect(JSON.stringify(parsed.value)).toBe(text);
    expect(Object.hasOwn(parsed.value!,'__proto__')).toBe(true);
    expect(({} as {polluted?:boolean}).polluted).toBeUndefined();
  });
  it('rejects incomplete syntax, non-object roots and overflow instead of saving null',()=>{
    expect(parseStationMetadata('{"notice":')).toMatchObject({error:'invalidJson',detail:expect.any(String)});
    for(const text of ['null','[]','true','"station"','3'])expect(parseStationMetadata(text)).toEqual({error:'objectRequired'});
    expect(parseStationMetadata('{"nested":[1e999]}')).toEqual({error:'finiteNumbers'});
    expect(parseStationMetadata('{}')).toEqual({value:{}});
  });
});

describe('metadata editor state',()=>{
  it('retains malformed draft and original baseline on conflict and refresh until explicit rebase',()=>{
    const original={notice:'old'};
    let state=initialMetadataEditor(original);
    state=metadataEditorReducer(state,{type:'edit',draft:'{"unfinished":'});
    state=metadataEditorReducer(state,{type:'failed',error:new Error('conflict')});
    state=metadataEditorReducer(state,{type:'refresh',metadata:{notice:'another editor'}});
    expect(state.draft).toBe('{"unfinished":');
    expect(state.baseline).toBe(original);
    expect(state.incoming).toEqual({notice:'another editor'});
    state=metadataEditorReducer(state,{type:'rebase'});
    expect(state.baseline).toEqual({notice:'another editor'});
    expect(state.draft).toBe('{"unfinished":');
    expect(state.error).toBeUndefined();
  });
  it('accepts saved baseline without replacing edits typed while the request was in flight',()=>{
    let state=initialMetadataEditor({});
    const submitted='{"notice":"one"}';
    state=metadataEditorReducer(state,{type:'edit',draft:submitted});
    state=metadataEditorReducer(state,{type:'saving'});
    state=metadataEditorReducer(state,{type:'refresh',metadata:{notice:'stale incoming'}});
    state=metadataEditorReducer(state,{type:'edit',draft:'{"notice":"two"}'});
    state=metadataEditorReducer(state,{type:'saved',submitted,metadata:{notice:'one'}});
    expect(state.baseline).toEqual({notice:'one'});
    expect(state.draft).toBe('{"notice":"two"}');
    expect(state.saved).toBe(false);
    expect(state.saving).toBe(false);
    expect(state.incoming).toBeUndefined();
  });
});
