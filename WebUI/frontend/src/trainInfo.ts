import type {TrainInfoField,TrainInfoBindings} from './types';
export const trainInfoFields:TrainInfoField[]=['NAME','CAR_COUNT','UNIT_COUNT','DIRECTION'];
export const defaultTrainInfoRows=()=>[{key:'name',field:'NAME'},{key:'carCount',field:'CAR_COUNT'},{key:'unitCount',field:'UNIT_COUNT'},{key:'direction',field:'DIRECTION'}];
export interface TrainInfoRow {key:string;field:string}
export function trainInfoError(value:unknown):string {
 if(!value||typeof value!=='object'||Array.isArray(value))return 'Train information requires an object mapping';
 const entries=Object.entries(value);
 if(entries.length<1||entries.length>64)return 'Train information requires1 to64 fields';
 for(const [key,field] of entries){if(!key.trim()||key.length>256)return 'Train information keys must be nonblank and at most256 characters';if(!trainInfoFields.includes(field as TrainInfoField))return 'Unknown train information field';}
 return '';
}
/** Rows preserve invalid/duplicate keys in the workspace until the player corrects them. */
export function trainInfoRows(raw:string):TrainInfoRow[] {
 const value:unknown=JSON.parse(raw);
 if(!Array.isArray(value)||value.some(row=>!row||typeof row!=='object'||Object.keys(row).length!==2||typeof row.key!=='string'||typeof row.field!=='string'))throw new Error('Invalid train information rows');
 return value;
}
export function trainInfoFromRows(raw:string):TrainInfoBindings {
 const rows=trainInfoRows(raw);
 if(new Set(rows.map(row=>row.key)).size!==rows.length)throw new Error('Train information keys must be unique');
 const map=Object.fromEntries(rows.map(row=>[row.key,row.field]));const error=trainInfoError(map);if(error)throw new Error(error);return map as TrainInfoBindings;
}
