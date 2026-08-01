/** Read-only WebUI authoring types for the sandboxed ScriptWorker DSL. */
interface JavaRailwaysTimetableBuilder {
  setPeriodDays(days: number): this;
  /** @deprecated Use setPeriodDays; ticks must be a positive multiple of 24000. */
  setPeriod(ticks: number): this;
  /** Declares visual-only station lanes for a running graph. */
  diagramLine(lineId: string, options: JavaRailwaysDiagramLineOptions): this;
  service(id: string, build: JavaRailwaysServiceCallback): this;
  service(id: string, selector: JavaRailwaysTrainSelector | null, build: JavaRailwaysServiceCallback): this;
  service(id: string, selector: JavaRailwaysTrainSelector | null, options: JavaRailwaysServiceOptions, build: JavaRailwaysServiceCallback): this;
  takeFromDepot(depotId: string, tick: number): JavaRailwaysAcquisitionSelector;
  takeFromDepot(depotId: string, options: JavaRailwaysDepotSelectorOptions, tick: number): JavaRailwaysAcquisitionSelector;
  takeFromWaypoint(wayPointId: string | readonly string[], tick: number): JavaRailwaysAcquisitionSelector;
  setInitialPosition(trainSelector: JavaRailwaysObjectSelector, wayPointSelector: JavaRailwaysObjectSelector): this;
  and(...selectors: JavaRailwaysObjectSelector[]): JavaRailwaysCombinedSelector;
  or(...selectors: JavaRailwaysObjectSelector[]): JavaRailwaysCombinedSelector;
  not(selector: JavaRailwaysObjectSelector): JavaRailwaysCombinedSelector;
  predicate(typeId: string, parameters: Readonly<Record<string, unknown>>): JavaRailwaysCondition;
  predicate(typeId: string, version: number, parameters: Readonly<Record<string, unknown>>): JavaRailwaysCondition;
  all(...conditions: JavaRailwaysCondition[]): JavaRailwaysCondition;
  any(...conditions: JavaRailwaysCondition[]): JavaRailwaysCondition;
  not(condition: JavaRailwaysCondition): JavaRailwaysCondition;
}
type JavaRailwaysServiceCallback = (service: JavaRailwaysServiceBuilder) => void;
/** A string selects the world-unique, player-assigned train name. */
type JavaRailwaysTrainSelector = string | JavaRailwaysAcquisitionSelector;
interface JavaRailwaysAcquisitionSelector { readonly __javaRailwaysAcquisitionSelector: unique symbol }
type JavaRailwaysObjectSelector = string | JavaRailwaysCombinedSelector;
interface JavaRailwaysCombinedSelector { readonly __javaRailwaysCombinedSelector: unique symbol }
interface JavaRailwaysCondition { readonly __javaRailwaysCondition: unique symbol }
interface JavaRailwaysServiceOptions {
  /** Visual-only running-graph grouping; unrelated to infrastructure LineId. */
  readonly diagramLineId?: string;
  /** @deprecated Use diagramLineId. */
  readonly lineId?: string;
  readonly lineDirection?: 'UP' | 'DOWN';
  readonly activation?:
    | { readonly type: 'scheduled' | 'manual' }
    | { readonly type: 'conditional'; readonly condition: JavaRailwaysCondition; readonly falseBehavior?: 'SKIP_CYCLE' | 'WAIT_UNTIL_TRUE' };
}
interface JavaRailwaysServiceBuilder {
  check(wayPointId: string): this;
  at(departureTick: number): JavaRailwaysTimedActionBuilder;
  pass(target: string | readonly string[]): this;
  destination(target: string, options?: JavaRailwaysDestinationOptions): this;
  /** Wait for a duration without destination, station, or door semantics. */
  wait(ticks: number): this;
  reverse(): this;
  halt(): this;
}
interface JavaRailwaysDepotSelectorOptions { readonly tags?: readonly string[]; readonly minimumCars?: number; readonly maximumCars?: number; readonly minimumSpeedMetersPerSecond?: number; readonly capabilities?: readonly string[] }
interface JavaRailwaysDepotStorageOptions { readonly stablingPoints?: readonly string[] }
type JavaRailwaysDoorSide = 'NONE' | 'LEFT' | 'RIGHT' | 'BOTH' | 'none' | 'left' | 'right' | 'both';
interface JavaRailwaysDiagramLineOptions {
  readonly upStations: readonly string[];
  readonly downStations: readonly string[];
}
interface JavaRailwaysDestinationOptions {
  /** Visual-only arrival time used by running graphs; it does not schedule runtime actions. */
  readonly arrivalTick?: number;
  readonly minimumDwellTicks?: number;
  readonly stoppingOffsetMetersBefore?: number;
  readonly doorSide?: JavaRailwaysDoorSide;
}
interface JavaRailwaysRuntimeActions { action(typeId: string, parameters: Readonly<Record<string, unknown>>): this }
interface JavaRailwaysTimedActionBuilder {
  pass(target: string | readonly string[]): JavaRailwaysServiceBuilder;
  destination(target: string, options?: JavaRailwaysDestinationOptions): JavaRailwaysServiceBuilder;
  /** Wait for a duration without destination, station, or door semantics. */
  wait(ticks: number): JavaRailwaysServiceBuilder;
  reverse(): JavaRailwaysServiceBuilder; halt(): JavaRailwaysServiceBuilder;
  storeAtDepot(depotId: string, options?: JavaRailwaysDepotStorageOptions): JavaRailwaysServiceBuilder;
  action(typeId: string, parameters: Readonly<Record<string, unknown>>): JavaRailwaysServiceBuilder;
  action(typeId: string, version: number, parameters: Readonly<Record<string, unknown>>): JavaRailwaysServiceBuilder;
  whenCondition(condition: JavaRailwaysCondition, then: (actions: JavaRailwaysRuntimeActions) => void, otherwise?: (actions: JavaRailwaysRuntimeActions) => void): JavaRailwaysServiceBuilder;
}
declare function main(timetable: JavaRailwaysTimetableBuilder): void;
