import {currentLocale,translate,type Locale} from './i18n';

const errorKeys={
 BRIDGE_TIMEOUT:'errors.bridgeTimeout',BRIDGE_UNAVAILABLE:'errors.bridgeUnavailable',
 BRIDGE_INVALID_RESPONSE:'errors.bridgeInvalidResponse',STORAGE_UNAVAILABLE:'errors.storageUnavailable',
 REQUEST_INTERRUPTED:'errors.requestInterrupted',INTERNAL_ERROR:'errors.internal',SERVICE_UNAVAILABLE:'errors.unavailable',
 INVALID_REQUEST:'errors.invalid',AUTH_REQUIRED:'errors.auth',FORBIDDEN:'errors.forbidden',NOT_FOUND:'errors.notFound',CONFLICT:'errors.conflict',
 SIMULATION_TIMEOUT:'errors.simulationTimeout',SIMULATION_QUEUE_FULL:'errors.simulationQueueFull',
 SIMULATION_REJECTED:'errors.simulationRejected',NETWORK_ERROR:'errors.network',INVALID_RESPONSE:'errors.bridgeInvalidResponse',
 SIMULATION_LIMIT_REACHED:'errors.simulationLimit',REQUEST_TIMEOUT:'errors.timeout',TOO_MANY_REQUESTS:'errors.rateLimited',
 REQUEST_REJECTED:'errors.rejected',SIMULATION_FAILED:'errors.simulationFailed',SIMULATION_TIMING_UNAVAILABLE:'errors.simulationTimingUnavailable',
 INFRASTRUCTURE_INVARIANT_VIOLATION:'errors.infrastructureInvariant',InfrastructureInvariantViolation:'errors.infrastructureInvariant',
} as const;
const statusCodes:Record<number,string>={400:'INVALID_REQUEST',401:'AUTH_REQUIRED',403:'FORBIDDEN',404:'NOT_FOUND',409:'CONFLICT',422:'REQUEST_REJECTED',429:'TOO_MANY_REQUESTS',500:'INTERNAL_ERROR',503:'SERVICE_UNAVAILABLE',504:'REQUEST_TIMEOUT'};
// Error bodies are diagnostic text, never HTML or a serialized object for players.
function diagnostic(value:unknown):string|undefined{
 if(typeof value!=='string')return undefined;
 const text=value.trim();
 if(!text||/^[\[{<]/.test(text))return undefined;
 return text;
}
export class ApiError extends Error {
 constructor(readonly status:number,readonly code:string,readonly detail?:string,readonly requestId?:string){super();this.name='ApiError';}
 override get message():string{return this.localized(currentLocale());}
 localized(locale:Locale):string{
  const key=Object.hasOwn(errorKeys,this.code)?errorKeys[this.code as keyof typeof errorKeys]:undefined;
  const summary=key?translate(locale,key):`${translate(locale,'errors.http')} (HTTP ${this.status})`;
  // Infrastructure failures already have a precise localized explanation. Validation/domain
  // failures retain their actual server diagnostic rather than replacing it with a generic label.
  const showDetail=this.detail&&(!key||['INVALID_REQUEST','SIMULATION_REJECTED','REQUEST_REJECTED','CONFLICT','AUTH_REQUIRED','NOT_FOUND','FORBIDDEN'].includes(this.code));
  return [summary,showDetail?`${translate(locale,'errors.details')}: ${this.detail}`:undefined,
   this.requestId?`${translate(locale,'errors.requestId')}: ${this.requestId}`:undefined].filter(Boolean).join('\n');
 }
}
export function parseApiError(status:number,body:string):ApiError{
 let payload:unknown;
 try{payload=JSON.parse(body);}catch{return new ApiError(status,statusCodes[status]??'HTTP_ERROR',diagnostic(body));}
 if(!payload||typeof payload!=='object'||Array.isArray(payload))return new ApiError(status,statusCodes[status]??'HTTP_ERROR');
 const value=payload as Record<string,unknown>;
 return new ApiError(status,typeof value.code==='string'?value.code:(statusCodes[status]??'HTTP_ERROR'),
  diagnostic(value.error),typeof value.requestId==='string'&&/^[a-zA-Z0-9-]{1,128}$/.test(value.requestId)?value.requestId:undefined);
}
export function simulationFailureReason(locale:Locale,reason?:string,reasonCode?:string):string|undefined{
 const key=reasonCode&&Object.hasOwn(errorKeys,reasonCode)?errorKeys[reasonCode as keyof typeof errorKeys]:undefined;
 const summary=key?translate(locale,key):undefined;
 const detail=diagnostic(reason);
 return [summary,detail?`${translate(locale,'errors.details')}: ${detail}`:undefined].filter(Boolean).join('\n')||undefined;
}
