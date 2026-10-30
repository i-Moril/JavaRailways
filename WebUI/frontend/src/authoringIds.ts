import type {TimetableAuthoringDocumentV1} from './authoring';
import {formationOutputs} from './formationAuthoring';

/** Visible IDs are allocated within their own namespace; dangling references reserve identities too. */
export function allocateAuthoringIds(document:TimetableAuthoringDocumentV1,kind:'RUN'|'DUTY',count=1):string[] {
  if(!Number.isSafeInteger(count)||count<0)throw new Error('ID count must be a nonnegative safe integer');
  const reserved=new Set<string>();
  if(kind==='RUN'){
    for(const run of document.runs)reserved.add(run.id);
    for(const duty of document.duties)for(const element of duty.elements)if(element.type==='RUN')reserved.add(element.runId);
    for(const override of document.arrivalOverrides)reserved.add(override.runId);
    for(const trip of document.operationalTrips??[])for(const segment of trip.segments)reserved.add(segment.runId);
  }else{
    for(const duty of document.duties)reserved.add(duty.id);
    for(const trip of document.operationalTrips??[])for(const segment of trip.segments)reserved.add(segment.serviceId);
    for(const event of document.formationEvents??[]){
      reserved.add(event.coordinatorDutyId);
      if(event.kind==='COUPLE'){reserved.add(event.approachingDutyId);reserved.add(event.stationaryDutyId);}
      for(const output of formationOutputs(event))if(output.disposition.type==='DUTY')reserved.add(output.disposition.dutyId);
    }
  }
  let maximum=0n;
  for(const id of reserved)if(/^\d+$/.test(id)){const numeric=BigInt(id);if(numeric>maximum)maximum=numeric;}
  return Array.from({length:count},()=>String(++maximum));
}
