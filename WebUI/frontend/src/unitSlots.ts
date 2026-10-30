export const MAX_UNIT_SLOT = 2147483647;
export const MAX_SELECTED_UNITS = 128;

export function isUnitSlots(value:unknown):value is number[]{
  return Array.isArray(value)&&value.length>0&&value.length<=MAX_SELECTED_UNITS&&value.every((slot)=>Number.isInteger(slot)&&slot>0&&slot<=MAX_UNIT_SLOT)&&new Set(value).size===value.length;
}
export function parseUnitSlots(raw:string):number[]|undefined{
  try{const value:unknown=JSON.parse(raw);return isUnitSlots(value)?value:undefined;}catch{return undefined;}
}
export function unitSlotCandidate(raw:string,selected:number[]):number|undefined{
  if(!/^\d+$/.test(raw.trim())||selected.length>=MAX_SELECTED_UNITS)return undefined;
  const slot=Number(raw.trim());
  return Number.isInteger(slot)&&slot>0&&slot<=MAX_UNIT_SLOT&&!selected.includes(slot)?slot:undefined;
}
