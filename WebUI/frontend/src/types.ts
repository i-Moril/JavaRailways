export type TrainSelectorType = 'ANY' | 'TRAIN_ID';
export type StepType = 'CHECK' | 'AT';
export type ActionType = 'BEGIN_OPERATIONAL_TRIP' | 'BEGIN_STATION_STOP' | 'SET_DISPLAY' | 'RESET_DISPLAY' | 'TAKE_FROM_FORMATION_OUTPUT' | 'COUPLE' | 'SPLIT' | 'TAKE_FROM_DEPOT' | 'TAKE_FROM_WAYPOINT' | 'STORE_AT_DEPOT' | 'DESTINATION' | 'DESTINATION_OPTIONS' | 'STOP' | 'HALT' | 'REVERSE' | 'WAIT_UNTIL' | 'STATION_DESTINATION_OPTIONS' | 'SELECT_STOP' | 'EXTENSION' | 'CONDITIONAL';
export interface ExtensionCallV1 { typeId: string; version: number; parameters: Record<string, unknown> }
export interface TimetableConditionV1 { type: 'PREDICATE'|'ALL'|'ANY'|'NOT'; call?: ExtensionCallV1|null; conditions: TimetableConditionV1[] }
export type TrainInfoField='NAME'|'CAR_COUNT'|'DIRECTION';
export type TrainInfoBindings=Record<string,TrainInfoField>;
export interface StationProgramActionSnapshot {
  trainInfo?:TrainInfoBindings;
  type:'OPEN_DOORS'|'CLOSE_DOORS'|'END'|'REVERSE'|'WAIT_UNTIL'|'WAIT_DURATION'|'ASYNC'|'AWAIT'|'SET_DISPLAY'|'RESET_DISPLAY'|'EXTENSION';
  tick?:number|null; durationTicks?:number|null; taskId?:string|null; steps:StationProgramActionSnapshot[];
  values:ProgramJsonObject; removeKeys:string[]; extensionCall?:ExtensionCallV1|null;
}
export interface StationStopProgramsSnapshot {
  stationId:string; callId:string; role:'ORIGIN'|'STOP'|'TERMINAL';
  arrival:StationProgramActionSnapshot[]; departurePreparation:StationProgramActionSnapshot[];
  preparationLead:StationPreparationLead; plannedDepartureTick?:number|null; destinationId?:string|null;
  doorSide?:'NONE'|'LEFT'|'RIGHT'|'BOTH'|null; minimumDwellTicks?:number|null;
}
export interface TimetableActionV1 { trainInfo?:TrainInfoBindings; type: ActionType; operationalTripId?:string|null; runId?:string|null; unitSlots?:number[]; displayValues?:ProgramJsonObject; removeDisplayKeys?:string[]; eventId?:string|null; outputId?:string|null; depotId?: string|null; arrivalTick?:number|null; requiredTags: string[]; minimumCars?: number|null; maximumCars?: number|null; minimumSpeedMetersPerSecond?: number|null; requiredCapabilities: string[]; stablingPointIds: string[]; wayPointIds: string[]; stopTicks?: number|null; stationId?: string|null; berthId?: string|null; berthCandidates?:{berthId:string;priority:number}[]; selectionPolicy?:'FIXED_PREFERENCE'|'AVAILABLE_PRIORITY'|null; minimumDwellTicks?: number|null; stoppingOffsetMetersBefore?: number|null; doorSideOverride?: 'NONE'|'LEFT'|'RIGHT'|'BOTH'|null; extensionCall?: ExtensionCallV1|null; condition?: TimetableConditionV1|null; stopPrograms?:StationStopProgramsSnapshot|null; thenSteps: TimetableStepV1[]; elseSteps: TimetableStepV1[] }
export interface TimetableStepV1 { type: StepType; tick?: number|null; wayPointId?: string|null; action?: TimetableActionV1|null }
export type LineDirection = 'UP'|'DOWN';
export interface TimetableRunCallV1 {id:string;stationId:string;destinationId:string;status:'ORIGIN'|'STOP'|'PASS'|'TERMINAL';viaIds?:string[];arrivalTick?:number|null;departureTick?:number|null}
export interface TimetableRunSegmentV1 {runId:string;lineId:string;diagramLineId?:string|null;lineDirection?:LineDirection|null;startStepIndex:number;endStepIndexExclusive:number;calls:TimetableRunCallV1[]}
export interface OperationalTripSegment {serviceId:string;runId:string;unitSlots:number[]}
export interface OperationalTrip {id:string;defaults:ProgramJsonObject;segments:OperationalTripSegment[]}
export interface TimetableServiceV1 { runSegments?:TimetableRunSegmentV1[]; id: string; lineId?:string|null; diagramLineId?:string|null; lineDirection?:LineDirection|null; selectorType: TrainSelectorType; trainId?: string|null; steps: TimetableStepV1[]; activation: { type: 'SCHEDULED'|'MANUAL'|'CONDITIONAL'; condition?: TimetableConditionV1|null; falseBehavior?: string|null } }
export interface DiagramLineV1 { id:string; upStationIds:string[]; downStationIds:string[] }
export type ObjectSelectorType = 'REGEX'|'AND'|'OR'|'NOT'|'TAGS';
export interface ObjectSelectorV1 { type:ObjectSelectorType; pattern?:string|null; selectors:ObjectSelectorV1[]; tags:string[] }
export interface InitialPositionV1 { trainSelector:ObjectSelectorV1; wayPointSelector:ObjectSelectorV1 }
export interface TimetableBranchEntry {arm:'THEN'|'ELSE';index:number}
export interface CompilationTraceV1 { serviceId:string; stepIndex:number; branchPath?:TimetableBranchEntry[]; origin:string }
export interface TimetableLineSpeedLimitV1 { lineId:string; maximumSpeedMetersPerSecond:number }
export interface SavedFormationOutputV1 {id:string;disposition:{type:'HOLD'|'DUTY';dutyId?:string|null}}
export interface SavedFormationEventV1 {
 id:string;kind:'COUPLE'|'SPLIT';coordinatorDutyId:string;approachingDutyId?:string|null;stationaryDutyId?:string|null;
 stationaryEnd?:'HEAD'|'TAIL'|null;rendezvous?:{approachDestinationId:string;stationaryDestinationId:string}|null;
 maximumApproachSpeedMetersPerSecond?:number|null;output?:SavedFormationOutputV1|null;
 partition?:{from:'HEAD';firstUnitCount:number}|null;outputs?:(SavedFormationOutputV1&{group:'FIRST'|'REST'})[];
}
export interface SavedTimetableV1 { operationalTrips?:OperationalTrip[]; formationEvents?:SavedFormationEventV1[]; id: string; periodDays: number; services: TimetableServiceV1[]; diagramLines?:DiagramLineV1[]; initialPositions?:InitialPositionV1[]; compilationTrace?:CompilationTraceV1[]; lineSpeedLimits?:TimetableLineSpeedLimitV1[] }
export interface WebSessionStatus {
 authenticated:boolean; playerName?:string; worldId?:string; expiresAtEpochMillis?:number;
}
export interface WorldSummary { id: string; name?: string }
export interface RailMapPoint { arc:number;x:number;y:number;z:number }
export interface RailMapRail { id:string;elevation:number;points:RailMapPoint[] }
export interface RailMapMarker { id:string;kind:'STATION'|'WAYPOINT'|'DEPOT'|'DESTINATION';railId:string;arcLengthMeters:number;x:number;y:number;z:number }
export interface RailMapFile { formatVersion:number;world:string;runtimeId:string;generatedAtEpochMillis:number;rails:RailMapRail[];markers:RailMapMarker[] }
export interface RailMapTrainCar { id:string;lengthMeters:number;x:number;y:number;z:number;directionX:number;directionZ:number }
export interface RailMapTrain { id:string;name:string;revision:number;speedMetersPerSecond:number;derailed:boolean;reversed:boolean;railId:string;arcLengthMeters:number;facing:'AB'|'BA';cars:RailMapTrainCar[] }
export interface StationBerthMetadata {
  usableLengthMeters?:number; stationId:string; stationDisplayName?:string; berthId:string; destinationId:string }
export interface DepotMetadata { id:string; displayName?:string; stablingPoints:{id:string;destinationId:string}[] }
export interface SimulationProfileDescriptor { type:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; id:string; displayName:string; carCount:number }
export interface ProgramSimulationWayPoint {id:string;railId:string;direction:'AB'|'BA';directionSource:'WAYPOINT'|'RAIL'}
export interface TimetableExtensionDescriptor { typeId:string; version:number; title:string; description:string; parameterSchema?:Record<string,unknown> }
export interface WorldMetadata { wayPoints?: string[]; programSimulationWayPoints?:ProgramSimulationWayPoint[]; stations?: StationBerthMetadata[]; depots?: string[]; depotDetails?:DepotMetadata[]; trains?: string[]; simulationProfiles?:SimulationProfileDescriptor[]; extensions?:{ actions?:TimetableExtensionDescriptor[] } }
export interface DraftSummary { id: string; updatedAt?: string; timetable?: SavedTimetableV1 }
export interface EditorDocumentSummary { id:string; updatedAt?:string }
export interface ValidationIssue { path?: string; message: string; severity?: string }
export interface ValidationResult { valid: boolean; errors?: ValidationIssue[]; warnings?: ValidationIssue[] }
export interface LineV1 { maximumSpeedMetersPerSecond?:number|null; id:string; displayName:string; upStationIds:string[]; downStationIds:string[]; color?:string|null }
export interface LineCollection { revision:number; lines:LineV1[] }
export interface SavedLineResult { revision:number; line:LineV1 }
export interface SimulationProfile {
 profileType:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; profileId:string; direction:LineDirection;
 timetableId?:string; serviceId?:string; runId?:string;
 originStationId?:string; terminalStationId?:string; stopStationIds?:string[]; destinationIds?:string[]; viaIds?:string[][];
 originDepartureMode?:'AUTO'|'CONTINUE'|'REVERSE';
 maximumSpeedMetersPerSecond?:number|null;
}
export interface SimulatedLineLeg { fromStationId:string; toStationId:string; lengthMeters:number; travelSeconds:number; peakSpeedKmh:number; stoppingErrorMeters:number }
export interface LineSimulationResult { lineRevision:number; totalTravelSeconds:number; legs:SimulatedLineLeg[] }
export interface TimetableSimulationProfileAssignment { serviceId:string; profileType:'WORLD_TRAIN'|'TRAIN_TEMPLATE'; profileId:string }
export interface TimetableSimulationRunResult {
  timetableId:string; serviceId:string; trainId:string; state:string; timetableElapsedTicks:number;
  scheduledActionTick?:number|null; nextScheduledTick?:number|null; lastLatenessTicks:number;
  currentRunId?:string|null; currentLineId?:string|null; unitDisplays?:UnitDisplaySnapshot[]; currentWayPointId?:string|null; nextStepIndex:number; nextStepBranchPath?:TimetableBranchEntry[]; activeStepIndex?:number|null; activeStepBranchPath?:TimetableBranchEntry[]; selectedBerthRef?:{stationId:string;berthId:string}|null; selectedBerthCommitted?:boolean; trainHeadRailId?:string|null;
  trainHeadArcLengthInAbMeters?:number|null; headProgressMeters?:number|null; message?:string|null;
}
export interface UnitStopSnapshot {serviceId:string;runId:string;call:TimetableRunCallV1;completed:boolean}
export interface UnitDisplaySnapshot {unitId:string;operationalTripId:string;cycleIndex:number;active?:boolean;values:ProgramJsonObject;plannedStops:UnitStopSnapshot[];completedStops:UnitStopSnapshot[];remainingStops:UnitStopSnapshot[];unresolvedRuns?:string[]}
export interface TimetableSimulationResult {
  status:'COMPLETED'|'DELAY_EXCEEDED'|'FAILED'|'PERIOD_ENDED'; simulatedTicks:number;
  thresholdTicks?:number; observedDelayTicks?:number; delayedServiceId?:string; delayedRunId?:string|null; delayedTaskId?:string; delayedState?:string; delayedMessage?:string; delayedStepIndex?:number; delayedStepBranchPath?:TimetableBranchEntry[]; wallClockMillis?:number;
  reason?:string; runs:TimetableSimulationRunResult[];
  tasks?:{serviceId:string;taskId:string;startTick:number;endTick?:number}[];
}
export interface TimetableScriptSummary { name:string; sizeBytes:number; modifiedAtEpochMillis:number }
export type CirculationStatus = 'PROVEN' | 'IMPOSSIBLE' | 'UNPROVEN';
export interface TimetableCirculationSummary {
  inventory:CirculationStatus; timing:CirculationStatus; capacity:CirculationStatus;
  periodicInventoryCycles?:number;
  findings:{code:string;detail:string;dutyId?:string;stepIndex?:number;blocking:boolean}[];
}
export interface TimetableValidation { valid:boolean; errors:string[]; circulation?:TimetableCirculationSummary }
export interface RenderedTimetableScript { name:string; source:string; timetable:SavedTimetableV1; validation:TimetableValidation }
export interface CompiledTimetableSource { timetable:SavedTimetableV1; validation:TimetableValidation; documentHash?:string }

export type WorldIssueKind = 'WORLD' | 'TRAIN' | 'PROTECTION_REGION' | 'BLOCK' | 'SIGNAL';
export interface WorldIssue { source:string;kind:WorldIssueKind;objectId:string;code:string;message:string;details:string|null;firstSeen:string;lastSeen:string }

export interface ServerLogSource { id:string; label:string; available:boolean }
export interface ServerLogSources { sources:ServerLogSource[]; warning?:string }
export interface ServerLogEntry { text:string; module:string|null; level:string|null; time:string|null; partial:boolean }
export interface ServerLogPage {
  source:string;
  entries:ServerLogEntry[];
  nextCursor:string|null;
  reset:boolean;
  scannedBytes:number;
  retention:string;
}
export interface ServerLogQuery { source:string; query?:string; cursor?:string }
// Shared library records are authoring inputs; runtime uses bound embedded definitions.
export type StationProgramParameterType='NUMBER'|'DURATION'|'STRING'|'ID_LIST'|'JSON_OBJECT'|'BOOLEAN';
export type ProgramJsonValue=null|boolean|number|string|ProgramJsonValue[]|ProgramJsonObject;
export interface ProgramJsonObject {[key:string]:ProgramJsonValue}
export type StationProgramLiteral=ProgramJsonValue;
export type StationProgramOperation='ADD'|'SUBTRACT'|'MULTIPLY'|'DIVIDE'|'MODULO'|'MIN'|'MAX'|'ROUND'|'FLOOR'|'CEIL'|'CONCAT'|'SUBSTRING'|'REPLACE'|'TEXT_LENGTH'|'TO_STRING'|'TO_NUMBER'|'EQUALS'|'NOT_EQUALS'|'AND'|'OR'|'NOT'|'PLAN_RUN_TYPE'|'PLAN_LINE_DIRECTION'|'PLAN_CALL_STATUS'|'PLAN_STATION_ID'|'PLAN_RUN_ID'|'PLAN_LINE_ID'|'PLAN_DUTY_ID';
export type StationPreparationLead={value:number}|{doorCloseTicks:true}|{operation:{type:'ADD'|'SUBTRACT'|'MULTIPLY'|'DIVIDE'|'MODULO'|'MIN'|'MAX'|'ROUND'|'FLOOR'|'CEIL';operands:StationPreparationLead[]}};
export type StationProgramExpression={operation:{type:StationProgramOperation;operands:StationProgramValue<StationProgramLiteral>[]}}|{object:Record<string,StationProgramValue<StationProgramLiteral>>};
export type StationProgramValue<T>={value:T}|{parameterId:string}|StationProgramExpression;
export type StationProgramJsonValue=StationProgramValue<ProgramJsonObject>|{trainInfo:TrainInfoBindings};
export type StationProgramStep=
 | {id:string;type:'SET_DISPLAY';values:StationProgramJsonValue;removeKeys?:StationProgramValue<string[]>}
 | {id:string;type:'RESET_DISPLAY'}
 | {id:string;type:'WAIT_UNTIL';tick:StationProgramValue<number>}
 | {id:string;type:'WAIT_EVENT';eventId:string;offsetTicks:number}
 | {id:string;type:'WAIT_DURATION';durationTicks:StationProgramValue<number>}
 | {id:string;type:'SELECT_STOP';stationId:StationProgramValue<string>;berthIds:StationProgramValue<string[]>;selectionPolicy:'FIXED_PREFERENCE'|'AVAILABLE_PRIORITY'}
 | {id:string;type:'BRANCH';condition:StationProgramValue<boolean>;thenSteps:StationProgramStep[];elseSteps:StationProgramStep[]}
 | {id:string;type:'EXTENSION';typeId:string;version:number;parameters:StationProgramJsonValue}
 | {id:string;type:'OPEN_DOORS';doorSideOverride?:StationProgramValue<string>|null}
 | {id:string;type:'REVERSE'|'CLOSE_DOORS'|'END'}
 | {id:string;type:'ASYNC';taskId:string;steps:StationProgramStep[]}
 | {id:string;type:'AWAIT';taskId:string}
 | {id:string;type:'MOVE';destinationId:StationProgramValue<string>;viaIds:StationProgramValue<string[]>}
 | {id:string;type:'STORE_AT_DEPOT';depotId:StationProgramValue<string>;stablingPointIds:StationProgramValue<string[]>};
export interface StationStopProgram {stationId:string;arrival:StationProgramDefinition;departurePreparation:StationProgramDefinition;preparationLead:StationPreparationLead}
export type TimedOperationDuration={type:'MANUAL';ticks:number}|{type:'SIMULATED'}|{type:'PROGRAM'};
export interface TimedOperationProgram {programId:string;arguments:Record<string,StationProgramLiteral>}
export interface TimedOperation {id:string;tick:number;stationId?:string;label?:string;duration?:TimedOperationDuration;program?:TimedOperationProgram}
export type TimetableEventPoint=TimedOperation;
export interface StationProgramDefinition {
 parameters:{id:string;type:StationProgramParameterType;default?:StationProgramLiteral}[];
 steps:StationProgramStep[];
}
export interface ProgramSimulationRequest {
 programId:string;definition:StationProgramDefinition;arguments:Record<string,StationProgramLiteral>;startTick:number;executionTimeoutMillis:number;
 profile:{type:'WORLD_TRAIN'|'TRAIN_TEMPLATE';id:string};initialPosition:{wayPointId:string;reverseTrain:boolean};
 plannedContext?:Record<string,string>;maximumDurationTicks:number;
}
export interface ProgramSimulationResult {
 status:'COMPLETED'|'FAILED'|'LIMIT_REACHED';simulatedTicks:number;durationTicks?:number;startTick?:number;endTick?:number;reason?:string;reasonCode?:string;failedProgramStepId?:string;
 initialPosition:{wayPointId:string;direction:'AB'|'BA';directionSource:'WAYPOINT'|'RAIL';reverseTrain:boolean};
}
export interface StationProgramRecord {
 formatVersion:1;id:string;name:string;definition:StationProgramDefinition;estimatedDurationTicks?:number;
}
export interface StationProgramCall {
 id:string;programId:string;tick:number;arguments:Record<string,StationProgramLiteral>;
}
