import type { MetadataObject,MetadataValue } from './types';

export type MetadataScope='station'|'line'|'run';
export type ConventionField='name'|'transfer'|'transfers'|'badge'|'themeColor'|'line'|'class';
export const metadataConventionFields:Record<MetadataScope,readonly ConventionField[]>={
  station:['name','transfer','transfers'],
  line:['name','badge','themeColor'],
  run:['line','class'],
};

/** Patch only the selected convention; arbitrary metadata and other translations survive. */
export function updateMetadataField(metadata:MetadataObject,key:ConventionField,value:MetadataValue|undefined):MetadataObject {
  const next={...metadata};
  if(value===undefined)delete next[key];else next[key]=value;
  return next;
}

export function isLocalizedMetadata(value:MetadataValue|undefined):value is MetadataObject {
  return value!==null&&typeof value==='object'&&!Array.isArray(value)
    &&(value.zh===undefined||typeof value.zh==='string')&&(value.en===undefined||typeof value.en==='string');
}

export function updateMetadataTranslation(metadata:MetadataObject,key:ConventionField,language:'zh'|'en',value:string):MetadataObject {
  const current=metadata[key];
  return updateMetadataField(metadata,key,{...(isLocalizedMetadata(current)?current:{}),[language]:value});
}
