import type { MetadataObject } from './types';

export type MetadataParseResult = {value:MetadataObject;error?:undefined} | {value?:undefined;error:'invalidJson'|'objectRequired'|'finiteNumbers';detail?:string};
// Parse data only; never merge user keys into application objects or evaluate text.
export function parseMetadata(text:string):MetadataParseResult {
  let value:unknown;
  try { value=JSON.parse(text); } catch(failure) { return {error:'invalidJson',detail:failure instanceof SyntaxError?failure.message:undefined}; }
  return metadataValueResult(value);
}

export function metadataValueResult(value:unknown):MetadataParseResult {
  if(value===null||typeof value!=='object'||Array.isArray(value))return {error:'objectRequired'};
  const pending:unknown[]=[value];
  while(pending.length){
    const current=pending.pop();
    if(typeof current==='number'&&!Number.isFinite(current))return {error:'finiteNumbers'};
    if(current&&typeof current==='object')for(const child of Object.values(current))pending.push(child);
  }
  return {value:value as MetadataObject};
}
