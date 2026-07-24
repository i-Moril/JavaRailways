import type { ActionType, SavedTimetableV1, TimetableActionV1, TimetableServiceV1, TimetableStepV1 } from './types';
export const emptyAction = (type: ActionType = 'DESTINATION'): TimetableActionV1 => ({
 type,
 depotId: type === 'TAKE_FROM_DEPOT' || type === 'STORE_AT_DEPOT' ? '' : null,
 requiredTags: [],
 requiredCapabilities: [],
 stablingPointIds: [],
 wayPointIds: type === 'DESTINATION' || type === 'DESTINATION_OPTIONS' || type === 'TAKE_FROM_WAYPOINT' ? [''] : [],
 stopTicks: type === 'STOP' ? 20 : null,
 stationId: type === 'STATION_DESTINATION_OPTIONS' ? '' : null,
 berthId: type === 'STATION_DESTINATION_OPTIONS' ? '' : null,
 thenActions: [],
 elseActions: [],
});
export const emptyTimetable = (): SavedTimetableV1 => ({ id:'new-timetable', periodDays:1, services:[] });
export type EditorAction =
 | {type:'LOAD'; value:SavedTimetableV1} | {type:'SET_HEADER'; id?:string; periodDays?:number}
 | {type:'ADD_SERVICE'} | {type:'DELETE_SERVICE'; service:number} | {type:'PATCH_SERVICE'; service:number; patch:Partial<TimetableServiceV1>}
 | {type:'ADD_STEP'; service:number; actionType?:ActionType} | {type:'INSERT_STEP'; service:number; step:number; value:TimetableStepV1} | {type:'DELETE_STEP'; service:number; step:number}
 | {type:'PATCH_STEP'; service:number; step:number; tick?:number; action?:Partial<TimetableActionV1>}
 | {type:'REPLACE_STEP'; service:number; step:number; value:TimetableStepV1};
export function reducer(state: SavedTimetableV1, action: EditorAction): SavedTimetableV1 {
 if(action.type==='LOAD') return action.value;
 if(action.type==='SET_HEADER') return {...state, id:action.id ?? state.id, periodDays:action.periodDays ?? state.periodDays};
 if(action.type==='ADD_SERVICE') return {...state, services:[...state.services,{id:`service-${state.services.length+1}`,selectorType:'TRAIN_ID',trainId:'',steps:[{type:'CHECK',wayPointId:''},{type:'AT',tick:0,action:emptyAction()}],activation:{type:'SCHEDULED'}}]};
 if(action.type==='DELETE_SERVICE') return {...state,services:state.services.filter((_,i)=>i!==action.service)};
 return {...state,services:state.services.map((service,si)=>{
  if(si!==action.service)return service;
  if(action.type==='PATCH_SERVICE')return {...service,...action.patch};
  if(action.type==='ADD_STEP')return {...service,steps:[...service.steps,{type:'AT',tick:0,action:emptyAction(action.actionType)}]};
  if(action.type==='INSERT_STEP')return {...service,steps:[...service.steps.slice(0,action.step),action.value,...service.steps.slice(action.step)]};
  if(action.type==='DELETE_STEP')return {...service,steps:service.steps.filter((_,i)=>i!==action.step)};
  if(action.type==='PATCH_STEP')return {...service,steps:service.steps.map((step,i)=>i===action.step?{...step,tick:action.tick??step.tick,action:action.action?{...step.action!,...action.action}:step.action}:step)};
  if(action.type==='REPLACE_STEP')return {...service,steps:service.steps.map((step,i)=>i===action.step?action.value:step)};
  return service;
 })};
}
