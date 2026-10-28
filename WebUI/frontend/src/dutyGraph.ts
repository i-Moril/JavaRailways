import type {TimetableAuthoringDocumentV1} from './authoring';
export interface DutyGraphNode {id:string;kind:'BOUNDARY'|'COUPLE'|'SPLIT';label:string;dutyIds:string[];incoming:string[];outgoing:string[]}
export interface DutyGraphEdge {id:string;dutyId:string;runId:string;from:string;to:string;lineId?:string|null;label:string}
/** Derived only: each physical RUN has exactly one edge, including shared formations. */
export function buildDutyGraph(document:TimetableAuthoringDocumentV1):{nodes:DutyGraphNode[];edges:DutyGraphEdge[]} {
  const nodes=new Map<string,DutyGraphNode>(),edges:DutyGraphEdge[]=[];
  const node=(id:string,label:string,kind:DutyGraphNode['kind']='BOUNDARY')=>{if(!nodes.has(id))nodes.set(id,{id,label,kind,dutyIds:[],incoming:[],outgoing:[]});return id;};
  const eventNode=(id:string)=>{const event=document.formationEvents?.find((e)=>e.id===id);return node(`event:${id}`,id,event?.kind??'BOUNDARY');};
  for(const duty of document.duties){
    const tokens=duty.elements.flatMap<{kind:'RUN'|'EVENT';id:string;runId:string}>((element)=>{
      if(element.type==='RUN')return [{kind:'RUN' as const,id:element.id,runId:element.runId}];
      return element.type==='FORMATION'?[{kind:'EVENT' as const,id:element.eventId,runId:''}]:[];
    });
    let current=duty.selector.type==='TAKE_FROM_FORMATION_OUTPUT'?eventNode(duty.selector.eventId):node(`duty:${duty.id}:start`,duty.id);
    tokens.forEach((token,i)=>{
      if(token.kind==='EVENT'){current=eventNode(token.id);return;}
      const run=document.runs.find((r)=>r.id===token.runId);if(!run)return;
      const next=tokens[i+1];
      const end=next?.kind==='EVENT'?eventNode(next.id):node(`duty:${duty.id}:after:${token.id}`,run.calls.at(-1)?.stationId??run.id);
      const edge: DutyGraphEdge={id:`run:${run.id}`,dutyId:duty.id,runId:run.id,from:current,to:end,lineId:run.lineId,label:`${run.id} · ${run.lineId??'—'} · ${run.calls[0]?.stationId??'—'} → ${run.calls.at(-1)?.stationId??'—'}`};
      edges.push(edge);nodes.get(current)!.outgoing.push(edge.id);nodes.get(end)!.incoming.push(edge.id);
      for(const id of [current,end]){const n=nodes.get(id)!;if(!n.dutyIds.includes(duty.id))n.dutyIds.push(duty.id);}
      current=end;
    });
  }
  return {nodes:[...nodes.values()].filter((n)=>n.incoming.length||n.outgoing.length),edges};
}
