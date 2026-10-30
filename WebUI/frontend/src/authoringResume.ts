import type { TimetableAuthoringDocumentV1 } from './authoring';
import type { EditorDocumentSummary } from './types';

const key=(owner:string,world:string)=>`javarailways.lastAuthoringDocument.${encodeURIComponent(owner)}.${encodeURIComponent(world)}`;

export function parseLastAuthoringDocument(serialized:string|null,owner:string,world:string):string|undefined {
  if(!serialized)return undefined;
  try{
    const value=JSON.parse(serialized);
    return value?.version===1&&value.owner===owner&&value.world===world&&typeof value.documentId==='string'&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value.documentId)?value.documentId:undefined;
  }catch{return undefined;}
}

export function loadLastAuthoringDocument(owner:string,world:string):string|undefined {
  if(typeof window==='undefined'||!owner)return undefined;
  try{return parseLastAuthoringDocument(window.localStorage.getItem(key(owner,world)),owner,world);}catch{return undefined;}
}

export function rememberAuthoringDocument(owner:string,world:string,documentId:string):void {
  if(typeof window==='undefined'||!owner)return;
  try{
    if(documentId)window.localStorage.setItem(key(owner,world),JSON.stringify({version:1,owner,world,documentId}));
    else window.localStorage.removeItem(key(owner,world));
  }catch{/* Navigation hints are best effort; the server document remains authoritative. */}
}

/** One startup request spans list and GET; user actions can invalidate it at either await. */
export async function resumeAuthoringWorkspace(options:{
  documentId?:string;hasRecovery:boolean;demoAutoload?:boolean;isOwnerCurrent:()=>boolean;isCurrent:()=>boolean;
  list:()=>Promise<EditorDocumentSummary[]>;load:(id:string)=>Promise<TimetableAuthoringDocumentV1>;
  onList:(documents:EditorDocumentSummary[])=>void;onMissing:()=>void;
  onResume:(id:string,document:TimetableAuthoringDocumentV1)=>void;
}):Promise<void> {
  const documents=await options.list();
  if(!options.isOwnerCurrent())return;
  options.onList(documents);
  if(options.hasRecovery||!options.isCurrent())return;
  const id=options.documentId??(options.demoAutoload?documents[0]?.id:undefined);
  if(!id)return;
  if(!documents.some((document)=>document.id===id)){options.onMissing();return;}
  const document=await options.load(id);
  if(options.isCurrent())options.onResume(id,document);
}
