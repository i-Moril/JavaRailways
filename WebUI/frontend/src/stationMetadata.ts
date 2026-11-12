import type { Locale } from './i18n';
import type { StationMetadataObject } from './types';

export { parseMetadata as parseStationMetadata } from './metadata';
export type { MetadataParseResult } from './metadata';

export interface MetadataEditorState {
  baseline:StationMetadataObject;
  draft:string;
  incoming?:StationMetadataObject;
  saving:boolean;
  saved:boolean;
  error?:unknown;
}
export type MetadataEditorAction =
  | {type:'edit';draft:string}
  | {type:'refresh';metadata:StationMetadataObject}
  | {type:'rebase'}
  | {type:'saving'}
  | {type:'saved';metadata:StationMetadataObject;submitted:string}
  | {type:'failed';error:unknown};
export const initialMetadataEditor=(metadata:StationMetadataObject):MetadataEditorState=>({baseline:metadata,draft:JSON.stringify(metadata,null,2),saving:false,saved:false});
export function metadataEditorReducer(state:MetadataEditorState,action:MetadataEditorAction):MetadataEditorState {
  switch(action.type){
    case 'edit':return {...state,draft:action.draft,saved:false};
    case 'refresh':return {...state,incoming:JSON.stringify(action.metadata)===JSON.stringify(state.baseline)?undefined:action.metadata};
    case 'rebase':return state.incoming?{...state,baseline:state.incoming,incoming:undefined,error:undefined,saved:false}:state;
    case 'saving':return {...state,saving:true,saved:false,error:undefined};
    case 'saved':return {...state,incoming:undefined,baseline:action.metadata,draft:state.draft===action.submitted?JSON.stringify(action.metadata,null,2):state.draft,saving:false,saved:state.draft===action.submitted,error:undefined};
    case 'failed':return {...state,saving:false,error:action.error,saved:false};
  }
}

export function stationNameLabel(id:string,name:unknown,locale:Locale):string {
  if(typeof name==='string' && name.trim()) return name;
  if(name && typeof name==='object' && !Array.isArray(name)) {
    const translations=name as Record<string,unknown>;
    const keys=locale==='zh-CN' ? ['zh','en'] : ['en','zh'];
    for(const key of keys) {
      const text=translations[key];
      if(typeof text==='string' && text.trim()) return text;
    }
  }
  return id;
}
