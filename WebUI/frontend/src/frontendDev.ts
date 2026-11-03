import type { TimetableAuthoringDocumentV1 } from './authoring';
import type {
  DraftSummary, EditorDocumentSummary, LineCollection, LineSimulationResult, LineV1, RailMapFile,
  RailMapTrain, RenderedTimetableScript, SavedTimetableV1, SimulationProfile, TimetableScriptSummary,
  TimetableSimulationProfileAssignment, TimetableSimulationResult, ValidationResult, WorldMetadata,
} from './types';

export function isFrontendDemoEnabled(isDevelopment:boolean,flag:string|undefined):boolean {
  return isDevelopment&&flag==='true';
}

export const frontendDemoEnabled=isFrontendDemoEnabled(import.meta.env.DEV,import.meta.env.VITE_FRONTEND_DEMO);
const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value)) as T;
const now=()=>new Date().toISOString();

const lineSeed:LineV1[]=[
  {id:'airport-line',maximumSpeedMetersPerSecond:27.78,displayName:'Airport Line',color:'#1677ff',upStationIds:['harbour','old-town','central','university','airport-t2'],downStationIds:['airport-t2','university','central','old-town','harbour']},
  {id:'coastal-line',displayName:'Coastal Line',color:'#13a8a8',upStationIds:['marina','museum','central','cliff-gardens','north-port'],downStationIds:['north-port','cliff-gardens','central','museum','marina']},
  {id:'university-link',displayName:'University Link',color:'#722ed1',upStationIds:['old-town','market','university'],downStationIds:['university','market','old-town']},
];
const stationIds=[...new Set(lineSeed.flatMap((line)=>[...line.upStationIds,...line.downStationIds]))];
const displayName=(id:string)=>id.split('-').map((part)=>part[0].toUpperCase()+part.slice(1)).join(' ');

const metadataSeed:WorldMetadata={
  wayPoints:['east-junction','harbour-depot-throat'],
  stations:stationIds.map((id)=>({stationId:id,stationDisplayName:displayName(id),berthId:`${id}-platform-1`,destinationId:`${id}-destination`})),
  depots:['harbour-depot'],
  depotDetails:[{id:'harbour-depot',displayName:'Harbour Depot',stablingPoints:[{id:'road-1',destinationId:'harbour-depot-road-1'}]}],
  trains:['demo-express'],
  simulationProfiles:[
    {type:'WORLD_TRAIN',id:'demo-express',displayName:'Demo Express (6 cars)',carCount:6},
    {type:'TRAIN_TEMPLATE',id:'commuter-emu-6',displayName:'Commuter EMU (6 cars)',carCount:6},
  ],extensions:{actions:[],conditions:[]},
};

const documentSeed:TimetableAuthoringDocumentV1={
  formatVersion:1,id:'weekday-demo',formalScriptName:'weekday-demo.js',periodDays:1,minimumDwellTicks:100,
  diagramLines:lineSeed.map(({id,upStationIds,downStationIds})=>({id,upStationIds,downStationIds})),
  arrivalOverrides:[],
  runs:[
    {id:'AP101',type:'PASSENGER',diagramLineId:'airport-line',lineDirection:'UP',originDepartureMode:'AUTO',calls:[
      {id:'AP101-1',stationId:'harbour',berthId:'harbour-platform-1',status:'ORIGIN',targetId:'harbour-destination',departureTick:1200},
      {id:'AP101-2',stationId:'old-town',berthId:'old-town-platform-1',status:'STOP',targetId:'old-town-destination',arrivalTick:2000,departureTick:2100},
      {id:'AP101-3',stationId:'central',berthId:'central-platform-1',status:'STOP',targetId:'central-destination',arrivalTick:2900,departureTick:3020},
      {id:'AP101-4',stationId:'university',berthId:'university-platform-1',status:'PASS',targetId:'university-destination',arrivalTick:3700},
      {id:'AP101-5',stationId:'airport-t2',berthId:'airport-t2-platform-1',status:'TERMINAL',targetId:'airport-t2-destination',arrivalTick:4700},
    ]},
    {id:'AP102',type:'PASSENGER',diagramLineId:'airport-line',lineDirection:'DOWN',originDepartureMode:'AUTO',calls:[
      {id:'AP102-1',stationId:'airport-t2',berthId:'airport-t2-platform-1',status:'ORIGIN',targetId:'airport-t2-destination',departureTick:5600},
      {id:'AP102-2',stationId:'university',berthId:'university-platform-1',status:'STOP',targetId:'university-destination',arrivalTick:6500,departureTick:6600},
      {id:'AP102-3',stationId:'central',berthId:'central-platform-1',status:'STOP',targetId:'central-destination',arrivalTick:7300,departureTick:7420},
      {id:'AP102-4',stationId:'old-town',berthId:'old-town-platform-1',status:'STOP',targetId:'old-town-destination',arrivalTick:8200,departureTick:8300},
      {id:'AP102-5',stationId:'harbour',berthId:'harbour-platform-1',status:'TERMINAL',targetId:'harbour-destination',arrivalTick:9100},
    ]},
  ],
  duties:[{id:'Duty A',selector:{type:'TRAIN_NAME',name:'demo-express'},elements:[{id:'duty-a-1',type:'RUN',runId:'AP101'},{id:'duty-a-2',type:'RUN',runId:'AP102'}]}],
};
const timetableSeed:SavedTimetableV1={id:'weekday-demo',periodDays:1,services:[],diagramLines:documentSeed.diagramLines,lineSpeedLimits:lineSeed.filter((line)=>line.maximumSpeedMetersPerSecond!=null).map((line)=>({lineId:line.id,maximumSpeedMetersPerSecond:line.maximumSpeedMetersPerSecond!}))};
const railMapSeed:RailMapFile={
  formatVersion:1,world:'demo-world',runtimeId:'frontend-demo',generatedAtEpochMillis:Date.now(),
  rails:[
    {id:'airport-main',elevation:64,points:[{arc:0,x:40,y:64,z:300},{arc:700,x:240,y:64,z:250},{arc:1500,x:460,y:64,z:170},{arc:2300,x:680,y:64,z:80},{arc:3200,x:900,y:64,z:40}]},
    {id:'coastal-main',elevation:65,points:[{arc:0,x:180,y:65,z:30},{arc:750,x:350,y:65,z:120},{arc:1450,x:460,y:65,z:170},{arc:2200,x:600,y:65,z:310},{arc:3000,x:820,y:65,z:360}]},
  ],markers:[
    {id:'harbour-destination',kind:'STATION',railId:'airport-main',arcLengthMeters:0,x:40,y:64,z:300},
    {id:'central-destination',kind:'STATION',railId:'airport-main',arcLengthMeters:1500,x:460,y:64,z:170},
    {id:'airport-t2-destination',kind:'STATION',railId:'airport-main',arcLengthMeters:3200,x:900,y:64,z:40},
    {id:'marina-destination',kind:'STATION',railId:'coastal-main',arcLengthMeters:0,x:180,y:65,z:30},
    {id:'north-port-destination',kind:'STATION',railId:'coastal-main',arcLengthMeters:3000,x:820,y:65,z:360},
    {id:'harbour-depot-road-1',kind:'DEPOT',railId:'airport-main',arcLengthMeters:120,x:75,y:64,z:290},
  ],
};
export const frontendDemoTrains:RailMapTrain[]=[{id:'demo-express',name:'AP101',revision:1,speedMetersPerSecond:18.5,derailed:false,reversed:false,railId:'airport-main',arcLengthMeters:1850,facing:'AB',cars:Array.from({length:6},(_,index)=>({id:`demo-car-${index+1}`,lengthMeters:20,x:545-index*12,y:64,z:135+index*5,directionX:0.93,directionZ:-0.37}))}];

let lines:LineCollection={revision:7,lines:clone(lineSeed)};
let documents=new Map<string,TimetableAuthoringDocumentV1>([[documentSeed.id,clone(documentSeed)]]);
let programs=new Map<string,Map<string,import('./types').StationProgramRecord>>();
let drafts=new Map<string,SavedTimetableV1>([[timetableSeed.id,clone(timetableSeed)]]);
function lineSimulation(lineId:string,profile:SimulationProfile):LineSimulationResult {
  const line=lines.lines.find((candidate)=>candidate.id===lineId);
  if(!line)throw new Error(`Unknown demo line: ${lineId}`);
  const route=profile.direction==='UP'?line.upStationIds:line.downStationIds;
  const start=profile.originStationId?route.indexOf(profile.originStationId):0;
  const end=profile.terminalStationId?route.indexOf(profile.terminalStationId):route.length-1;
  const selected=route.slice(Math.max(0,start),end>=start?end+1:route.length);
  const legs=selected.slice(0,-1).map((fromStationId,index)=>({fromStationId,toStationId:selected[index+1],lengthMeters:760+index*90,travelSeconds:62+index*7,peakSpeedKmh:82,stoppingErrorMeters:0.18}));
  return {lineRevision:lines.revision,totalTravelSeconds:legs.reduce((sum,leg)=>sum+leg.travelSeconds,0),legs};
}

export const demoApi={
  async issues(_worldId:string){return [];},
  async session(){return {authenticated:true,playerName:'Frontend Developer',worldId:'demo-world',expiresAtEpochMillis:Date.now()+86_400_000};},
  async renewSession(_signal?:AbortSignal){return {authenticated:true,playerName:'Frontend Developer',worldId:'demo-world',expiresAtEpochMillis:Date.now()+86_400_000};},
  async worlds(){return [{id:'demo-world',name:'Frontend Demo World'}];},
  async metadata(_worldId:string){return clone(metadataSeed);},async railMap(_worldId:string){return clone(railMapSeed);},
  async scripts(_worldId:string):Promise<TimetableScriptSummary[]>{return [{name:'weekday-demo.js',sizeBytes:3480,modifiedAtEpochMillis:Date.now()-3600000}];},
  async renderScript(_worldId:string,name:string,_id?:string):Promise<RenderedTimetableScript>{return {name,source:'// Frontend demo timetable\n// Editing and compilation stay in this browser session.',timetable:clone(timetableSeed),validation:{valid:true,errors:[]}};},
  async compileSource(_worldId:string,id:string,_source:string,documentHash?:string){return {timetable:{...clone(timetableSeed),id},validation:{valid:true,errors:[]},documentHash};},
  async simulateTimetable(_worldId:string,timetable:SavedTimetableV1,assignments:TimetableSimulationProfileAssignment[],_delayThresholdTicks:number,_executionTimeoutMillis:number):Promise<TimetableSimulationResult>{return {status:'COMPLETED',simulatedTicks:9600,runs:assignments.map((assignment)=>({timetableId:timetable.id,serviceId:assignment.serviceId,trainId:assignment.profileId,state:'COMPLETED',timetableElapsedTicks:9600,lastLatenessTicks:0,nextStepIndex:5}))};},
  async lines(_worldId:string){return clone(lines);},async simulateLine(_worldId:string,lineId:string,profile:SimulationProfile,_executionTimeoutMillis:number){return clone(lineSimulation(lineId,profile));},
  async saveLine(_worldId:string,line:LineV1,expectedRevision:number){if(expectedRevision!==lines.revision)throw new Error('Demo line revision changed');lines={revision:lines.revision+1,lines:[...lines.lines.filter((item)=>item.id!==line.id),clone(line)]};return {revision:lines.revision,line:clone(line)};},
  async deleteLine(_worldId:string,lineId:string,expectedRevision:number){if(expectedRevision!==lines.revision)throw new Error('Demo line revision changed');const before=lines.lines.length;lines={revision:lines.revision+1,lines:lines.lines.filter((line)=>line.id!==lineId)};return {revision:lines.revision,removed:lines.lines.length<before};},
  async drafts(_worldId:string):Promise<DraftSummary[]>{return [...drafts.values()].map((timetable)=>({id:timetable.id,updatedAt:now(),timetable:clone(timetable)}));},
  async draft(_worldId:string,draftId:string){const value=drafts.get(draftId);if(!value)throw new Error(`Unknown demo draft: ${draftId}`);return clone(value);},
  async createDraft(_worldId:string,value:SavedTimetableV1):Promise<DraftSummary>{drafts.set(value.id,clone(value));return {id:value.id,updatedAt:now(),timetable:clone(value)};},
  async saveDraft(_worldId:string,draftId:string,value:SavedTimetableV1){drafts.set(draftId,clone(value));},async deleteDraft(_worldId:string,draftId:string){drafts.delete(draftId);},
  async simulateProgram(_worldId:string,_input:import('./types').ProgramSimulationRequest):Promise<import('./types').ProgramSimulationResult>{throw new Error('Program simulation requires a live authenticated server');},
  async timetablePrograms(_worldId:string,documentId:string):Promise<import('./types').StationProgramRecord[]>{return [...(programs.get(documentId)?.values()??[])].map((program)=>clone(program));},
  async createTimetableProgram(_worldId:string,documentId:string,id:string,name:string,definition:import('./types').StationProgramDefinition,estimatedDurationTicks?:number):Promise<import('./types').StationProgramRecord>{const list=programs.get(documentId)??new Map();if(list.has(id))throw new Error('program ID already exists');const record={formatVersion:1 as const,id,name,definition:clone(definition),...(estimatedDurationTicks===undefined?{}:{estimatedDurationTicks})};list.set(id,record);programs.set(documentId,list);return clone(record);},
  async saveTimetableProgram(_worldId:string,documentId:string,id:string,name:string,definition:import('./types').StationProgramDefinition,estimatedDurationTicks?:number):Promise<import('./types').StationProgramRecord>{const list=programs.get(documentId);if(!list?.has(id))throw new Error('program not found');const record={formatVersion:1 as const,id,name,definition:clone(definition),...(estimatedDurationTicks===undefined?{}:{estimatedDurationTicks})};list.set(id,record);return clone(record);},
  async deleteTimetableProgram(_worldId:string,documentId:string,id:string):Promise<{removed:boolean}>{return {removed:programs.get(documentId)?.delete(id)??false};},
  async importTimetablePrograms(_worldId:string,documentId:string,sourceDocumentId:string,programIds?:string[]):Promise<import('./types').StationProgramRecord[]>{const source=programs.get(sourceDocumentId);if(!source)return [];const target=programs.get(documentId)??new Map();const imported=[];for(const program of source.values()){if(programIds&&!programIds.includes(program.id))continue;let id=program.id;let suffix=2;while(target.has(id))id=`${program.id}-copy-${suffix++}`;const record={...clone(program),id};target.set(id,record);imported.push(clone(record));}programs.set(documentId,target);return imported;},
  async acquireEditorLock(_worldId:string,_documentId:string){},async heartbeatEditorLock(_worldId:string,_documentId:string){},async releaseEditorLock(_worldId:string,_documentId:string){},
  async editorDocuments(_worldId:string):Promise<EditorDocumentSummary[]>{return [...documents.values()].map((document)=>({id:document.id,updatedAt:now()}));},
  async editorDocument(_worldId:string,id:string){const value=documents.get(id);if(!value)throw new Error(`Unknown demo editor document: ${id}`);return clone(value);},
  async createEditorDocument(_worldId:string,value:TimetableAuthoringDocumentV1){documents.set(value.id,clone(value));return {id:value.id};},async saveEditorDocument(_worldId:string,id:string,value:TimetableAuthoringDocumentV1){documents.set(id,clone(value));},async deleteEditorDocument(_worldId:string,id:string){documents.delete(id);},
  async saveFormalScript(_worldId:string,_documentId:string,fileName:string,_source:string,_document:TimetableAuthoringDocumentV1,overwrite=false){return {name:fileName,replaced:overwrite};},async renameFormalScript(_worldId:string,_documentId:string,_sourceName:string,targetName:string,_document:TimetableAuthoringDocumentV1){return {name:targetName};},async deleteFormalScript(_worldId:string,_documentId:string,_fileName:string){return {removed:true};},
  async validate(_worldId:string,_value:SavedTimetableV1):Promise<ValidationResult>{return {valid:true,errors:[],warnings:[]};},async publish(_worldId:string,_value:SavedTimetableV1){},
};
