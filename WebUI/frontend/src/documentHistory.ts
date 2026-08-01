import { useState,type Dispatch,type SetStateAction } from 'react';

export interface DocumentHistory<T> { past:T[];present:T;future:T[] }

export const createDocumentHistory=<T>(present:T):DocumentHistory<T>=>({past:[],present,future:[]});

export function updateDocumentHistory<T>(history:DocumentHistory<T>,next:T,limit=100):DocumentHistory<T> {
  if(!Number.isSafeInteger(limit)||limit<1)throw new Error('history limit must be a positive integer');
  if(Object.is(next,history.present))return history;
  return {past:[...history.past.slice(-(limit-1)),history.present],present:next,future:[]};
}

export function undoDocumentHistory<T>(history:DocumentHistory<T>):DocumentHistory<T> {
  if(!history.past.length)return history;
  return {past:history.past.slice(0,-1),present:history.past.at(-1)!,future:[history.present,...history.future]};
}

export function redoDocumentHistory<T>(history:DocumentHistory<T>):DocumentHistory<T> {
  if(!history.future.length)return history;
  return {past:[...history.past,history.present],present:history.future[0],future:history.future.slice(1)};
}

export function useDocumentHistory<T>(initial:()=>T) {
  const [history,setHistory]=useState<DocumentHistory<T>>(()=>createDocumentHistory(initial()));
  const setValue:Dispatch<SetStateAction<T>>=(update)=>setHistory((current)=>{
    const next=typeof update==='function'?(update as (value:T)=>T)(current.present):update;
    return updateDocumentHistory(current,next);
  });
  return {
    value:history.present,
    setValue,
    reset:(value:T)=>setHistory(createDocumentHistory(value)),
    undo:()=>setHistory(undoDocumentHistory),
    redo:()=>setHistory(redoDocumentHistory),
    canUndo:history.past.length>0,
    canRedo:history.future.length>0,
  };
}
