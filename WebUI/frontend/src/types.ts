export type TrainSelectorType = 'ANY' | 'TRAIN_ID';
export type StepType = 'CHECK' | 'AT';
export type ActionType = 'TAKE_FROM_DEPOT' | 'STORE_AT_DEPOT' | 'DESTINATION' | 'DESTINATION_OPTIONS' | 'STOP' | 'HALT' | 'REVERSE' | 'STATION_DESTINATION_OPTIONS' | 'EXTENSION' | 'CONDITIONAL';
export interface ExtensionCallV1 { typeId: string; version: number; parameters: Record<string, unknown> }
export interface TimetableConditionV1 { type: 'PREDICATE'|'ALL'|'ANY'|'NOT'; call?: ExtensionCallV1|null; conditions: TimetableConditionV1[] }
export interface TimetableActionV1 { type: ActionType; depotId?: string|null; requiredTags: string[]; minimumCars?: number|null; maximumCars?: number|null; minimumSpeedMetersPerSecond?: number|null; requiredCapabilities: string[]; stablingPointIds: string[]; wayPointIds: string[]; stopTicks?: number|null; stationId?: string|null; berthId?: string|null; minimumDwellTicks?: number|null; stoppingOffsetMetersBefore?: number|null; doorSideOverride?: 'LEFT'|'RIGHT'|'BOTH'|null; extensionCall?: ExtensionCallV1|null; condition?: TimetableConditionV1|null; thenActions: TimetableActionV1[]; elseActions: TimetableActionV1[] }
export interface TimetableStepV1 { type: StepType; tick?: number|null; wayPointId?: string|null; action?: TimetableActionV1|null }
export interface TimetableServiceV1 { id: string; lineId?:string|null; selectorType: TrainSelectorType; trainId?: string|null; steps: TimetableStepV1[]; activation: { type: 'SCHEDULED'|'MANUAL'|'CONDITIONAL'; condition?: TimetableConditionV1|null; falseBehavior?: string|null } }
export interface SavedTimetableV1 { id: string; periodDays: number; services: TimetableServiceV1[] }
export interface WorldSummary { id: string; name?: string }
export interface StationBerthMetadata { stationId:string; stationDisplayName?:string; berthId:string; destinationId:string }
export interface DepotMetadata { id:string; displayName?:string; stablingPoints:{id:string;destinationId:string}[] }
export interface WorldMetadata { wayPoints?: string[]; stations?: StationBerthMetadata[]; depots?: string[]; depotDetails?:DepotMetadata[]; trains?: string[] }
export interface DraftSummary { id: string; updatedAt?: string; timetable?: SavedTimetableV1 }
export interface ValidationIssue { path?: string; message: string; severity?: string }
export interface ValidationResult { valid: boolean; errors?: ValidationIssue[]; warnings?: ValidationIssue[] }
export interface LineV1 { id:string; displayName:string; stationIds:string[]; color?:string|null }
export interface LineCollection { revision:number; lines:LineV1[] }
export interface LineLegPreview { fromStationId:string; toStationId:string; fromDestinationId:string; toDestinationId:string; lengthMeters:number }
export interface LinePreview { valid:boolean; totalLengthMeters?:number; legs?:LineLegPreview[]; legIndex?:number; error?:string }
export interface SavedLineResult { revision:number; line:LineV1; preview:LinePreview }
export interface SimulationProfile { maxSpeedKmh:number; carCount:number; poweredCars:number; carMassKilograms:number; tractionForceNewtons:number; tractionPowerWatts:number; brakeForceNewtons:number }
export interface SimulatedLineLeg { fromStationId:string; toStationId:string; lengthMeters:number; travelSeconds:number; peakSpeedKmh:number; stoppingErrorMeters:number }
export interface LineSimulationResult { lineRevision:number; totalTravelSeconds:number; legs:SimulatedLineLeg[] }
