export type TrainSelectorType = 'ANY' | 'TRAIN_ID';
export type StepType = 'CHECK' | 'AT';
export type ActionType = 'TAKE_FROM_DEPOT' | 'TAKE_FROM_WAYPOINT' | 'STORE_AT_DEPOT' | 'DESTINATION' | 'DESTINATION_OPTIONS' | 'STOP' | 'HALT' | 'REVERSE' | 'STATION_DESTINATION_OPTIONS' | 'EXTENSION' | 'CONDITIONAL';
export interface ExtensionCallV1 { typeId: string; version: number; parameters: Record<string, unknown> }
export interface TimetableConditionV1 { type: 'PREDICATE'|'ALL'|'ANY'|'NOT'; call?: ExtensionCallV1|null; conditions: TimetableConditionV1[] }
export interface TimetableActionV1 { type: ActionType; depotId?: string|null; arrivalTick?:number|null; requiredTags: string[]; minimumCars?: number|null; maximumCars?: number|null; minimumSpeedMetersPerSecond?: number|null; requiredCapabilities: string[]; stablingPointIds: string[]; wayPointIds: string[]; stopTicks?: number|null; stationId?: string|null; berthId?: string|null; minimumDwellTicks?: number|null; stoppingOffsetMetersBefore?: number|null; doorSideOverride?: 'NONE'|'LEFT'|'RIGHT'|'BOTH'|null; extensionCall?: ExtensionCallV1|null; condition?: TimetableConditionV1|null; thenActions: TimetableActionV1[]; elseActions: TimetableActionV1[] }
export interface TimetableStepV1 { type: StepType; tick?: number|null; wayPointId?: string|null; action?: TimetableActionV1|null }
export type LineDirection = 'UP'|'DOWN';
export type ServiceRowType = 'STATION'|'SPECIAL_DESTINATION'|'DEPOT_OPERATION'|'ACTION_ONLY';
export type ServiceStopStatus = 'ORIGIN'|'STOP'|'PASS'|'TERMINAL'|'DISABLED';
export interface ServiceEditorRowV1 {
  id:string; type:ServiceRowType; status?:ServiceStopStatus|null; stationId?:string|null; berthId?:string|null;
  destinationId?:string|null; tick?:number|null; arrivalTick?:number|null; departureTick?:number|null;
  depotOperation?:'TAKE_FROM_DEPOT'|'STORE_AT_DEPOT'|null; depotId?:string|null; actions:TimetableActionV1[];
}
export interface TimetableServiceV1 { id: string; diagramLineId?:string|null; /** @deprecated Read compatibility only. */ lineId?:string|null; lineDirection?:LineDirection|null; rows?:ServiceEditorRowV1[]|null; selectorType: TrainSelectorType; trainId?: string|null; steps: TimetableStepV1[]; activation: { type: 'SCHEDULED'|'MANUAL'|'CONDITIONAL'; condition?: TimetableConditionV1|null; falseBehavior?: string|null } }
export interface DiagramLineV1 { id:string; upStationIds:string[]; downStationIds:string[] }
export type ObjectSelectorType = 'REGEX'|'AND'|'OR'|'NOT'|'TAGS';
export interface ObjectSelectorV1 { type:ObjectSelectorType; pattern?:string|null; selectors:ObjectSelectorV1[]; tags:string[] }
export interface InitialPositionV1 { trainSelector:ObjectSelectorV1; wayPointSelector:ObjectSelectorV1 }
export interface CompilationTraceV1 { serviceId:string; stepIndex:number; origin:string }
export interface SavedTimetableV1 { id: string; periodDays: number; services: TimetableServiceV1[]; diagramLines?:DiagramLineV1[]; initialPositions?:InitialPositionV1[]; compilationTrace?:CompilationTraceV1[] }
export interface WebSessionStatus {
 authenticated:boolean; playerName?:string; worldId?:string; expiresAtEpochMillis?:number;
}
export interface WorldSummary { id: string; name?: string }
export interface RailMapPoint { arc:number;x:number;y:number;z:number }
export interface RailMapRail { id:string;elevation:number;points:RailMapPoint[] }
export interface RailMapMarker { id:string;label:string;kind:'STATION'|'WAYPOINT'|'DEPOT'|'DESTINATION';railId:string;arcLengthMeters:number;x:number;y:number;z:number }
export interface RailMapFile { formatVersion:number;world:string;runtimeId:string;generatedAtEpochMillis:number;rails:RailMapRail[];markers:RailMapMarker[] }
export interface RailMapTrainCar { id:string;lengthMeters:number;x:number;y:number;z:number;directionX:number;directionZ:number }
export interface RailMapTrain { id:string;name:string;revision:number;speedMetersPerSecond:number;derailed:boolean;reversed:boolean;railId:string;arcLengthMeters:number;facing:'AB'|'BA';cars:RailMapTrainCar[] }
export interface StationBerthMetadata { stationId:string; stationDisplayName?:string; berthId:string; destinationId:string }
export interface DepotMetadata { id:string; displayName?:string; stablingPoints:{id:string;destinationId:string}[] }
export interface SimulationProfileDescriptor { type:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; id:string; displayName:string; carCount:number }
export interface TimetableExtensionDescriptor { typeId:string; version:number; title:string; description:string; parameterSchema?:Record<string,unknown> }
export interface WorldMetadata { wayPoints?: string[]; stations?: StationBerthMetadata[]; depots?: string[]; depotDetails?:DepotMetadata[]; trains?: string[]; simulationProfiles?:SimulationProfileDescriptor[]; extensions?:{ actions?:TimetableExtensionDescriptor[]; conditions?:TimetableExtensionDescriptor[] } }
export interface DraftSummary { id: string; updatedAt?: string; timetable?: SavedTimetableV1 }
export interface EditorDocumentSummary { id:string; updatedAt?:string }
export interface ValidationIssue { path?: string; message: string; severity?: string }
export interface ValidationResult { valid: boolean; errors?: ValidationIssue[]; warnings?: ValidationIssue[] }
export interface LineV1 { id:string; displayName:string; upStationIds:string[]; downStationIds:string[]; stationIds?:string[]; color?:string|null }
export interface LineCollection { revision:number; lines:LineV1[] }
export interface LineLegPreview { fromStationId:string; toStationId:string; fromDestinationId:string; toDestinationId:string; lengthMeters:number }
export interface DirectionLinePreview { valid:boolean; totalLengthMeters?:number; legs?:LineLegPreview[]; legIndex?:number; error?:string }
export interface LinePreview { valid:boolean; directions?:Partial<Record<LineDirection,DirectionLinePreview>>; error?:string }
export interface SavedLineResult { revision:number; line:LineV1; preview:LinePreview }
export interface SimulationProfile {
 profileType:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; profileId:string; direction:LineDirection;
 originStationId?:string; terminalStationId?:string; stopStationIds?:string[];
}
export interface SimulatedLineLeg { fromStationId:string; toStationId:string; lengthMeters:number; travelSeconds:number; peakSpeedKmh:number; stoppingErrorMeters:number }
export interface LineSimulationResult { lineRevision:number; totalTravelSeconds:number; legs:SimulatedLineLeg[] }
export interface TimetableSimulationProfileAssignment { serviceId:string; profileType:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; profileId:string }
export interface TimetableSimulationRunResult {
  timetableId:string; serviceId:string; trainId:string; state:string; timetableElapsedTicks:number;
  scheduledActionTick?:number|null; nextScheduledTick?:number|null; lastLatenessTicks:number;
  currentWayPointId?:string|null; nextStepIndex:number; trainHeadRailId?:string|null;
  trainHeadArcLengthInAbMeters?:number|null; headProgressMeters?:number|null; message?:string|null;
}
export interface TimetableSimulationResult {
  status:'COMPLETED'|'DELAY_EXCEEDED'|'FAILED'|'PERIOD_ENDED'; simulatedTicks:number;
  thresholdTicks?:number; observedDelayTicks?:number; delayedServiceId?:string; delayedStepIndex?:number;
  reason?:string; runs:TimetableSimulationRunResult[];
}
export interface TimetableScriptSummary { name:string; sizeBytes:number; modifiedAtEpochMillis:number }
export interface RenderedTimetableScript { name:string; source:string; timetable:SavedTimetableV1; validation:{valid:boolean;errors:string[]} }
export interface CompiledTimetableSource { timetable:SavedTimetableV1; validation:{valid:boolean;errors:string[]}; documentHash?:string }
