/** Read-only WebUI authoring types for the sandboxed ScriptWorker DSL. */
interface MineRailsTimetableBuilder {
  setPeriodDays(days: number): this;
  /** @deprecated Use setPeriodDays; ticks must be a positive multiple of 24000. */
  setPeriod(ticks: number): this;
  service(id: string, build: MineRailsServiceCallback): this;
  service(id: string, trainId: string | null, build: MineRailsServiceCallback): this;
  service(id: string, trainId: string | null, options: MineRailsServiceOptions, build: MineRailsServiceCallback): this;
  predicate(typeId: string, parameters: Readonly<Record<string, unknown>>): MineRailsCondition;
  predicate(typeId: string, version: number, parameters: Readonly<Record<string, unknown>>): MineRailsCondition;
  all(...conditions: MineRailsCondition[]): MineRailsCondition;
  any(...conditions: MineRailsCondition[]): MineRailsCondition;
  not(condition: MineRailsCondition): MineRailsCondition;
}
type MineRailsServiceCallback = (service: MineRailsServiceBuilder) => void;
interface MineRailsCondition { readonly __mineRailsCondition: unique symbol }
interface MineRailsServiceOptions {
  readonly lineId?: string;
  readonly lineDirection?: 'UP' | 'DOWN';
  readonly activation?:
    | { readonly type: 'scheduled' | 'manual' }
    | { readonly type: 'conditional'; readonly condition: MineRailsCondition; readonly falseBehavior?: 'SKIP_CYCLE' | 'WAIT_UNTIL_TRUE' };
}
interface MineRailsServiceBuilder {
  check(wayPointId: string): this;
  at(departureTick: number): MineRailsTimedActionBuilder;
  pass(target: string | readonly string[]): this;
  destination(target: string, options?: MineRailsDestinationOptions): this;
  /** Wait for a duration without destination, station, or door semantics. */
  wait(ticks: number): this;
  reverse(): this;
  halt(): this;
}
interface MineRailsDepotSelectorOptions { readonly tags?: readonly string[]; readonly minimumCars?: number; readonly maximumCars?: number; readonly minimumSpeedMetersPerSecond?: number; readonly capabilities?: readonly string[] }
interface MineRailsDepotStorageOptions { readonly stablingPoints?: readonly string[] }
type MineRailsDoorSide = 'NONE' | 'LEFT' | 'RIGHT' | 'BOTH' | 'none' | 'left' | 'right' | 'both';
interface MineRailsDestinationOptions {
  readonly minimumDwellTicks?: number;
  readonly stoppingOffsetMetersBefore?: number;
  readonly doorSide?: MineRailsDoorSide;
}
interface MineRailsRuntimeActions { action(typeId: string, parameters: Readonly<Record<string, unknown>>): this }
interface MineRailsTimedActionBuilder {
  pass(target: string | readonly string[]): MineRailsServiceBuilder;
  destination(target: string, options?: MineRailsDestinationOptions): MineRailsServiceBuilder;
  /** Wait for a duration without destination, station, or door semantics. */
  wait(ticks: number): MineRailsServiceBuilder;
  reverse(): MineRailsServiceBuilder; halt(): MineRailsServiceBuilder;
  /** Compatibility alias for takeFromDepot. */ depot(depotId: string, options?: MineRailsDepotSelectorOptions): MineRailsServiceBuilder;
  takeFromDepot(depotId: string, options?: MineRailsDepotSelectorOptions): MineRailsServiceBuilder;
  storeAtDepot(depotId: string, options?: MineRailsDepotStorageOptions): MineRailsServiceBuilder;
  action(typeId: string, parameters: Readonly<Record<string, unknown>>): MineRailsServiceBuilder;
  action(typeId: string, version: number, parameters: Readonly<Record<string, unknown>>): MineRailsServiceBuilder;
  whenCondition(condition: MineRailsCondition, then: (actions: MineRailsRuntimeActions) => void, otherwise?: (actions: MineRailsRuntimeActions) => void): MineRailsServiceBuilder;
}
declare function main(timetable: MineRailsTimetableBuilder): void;
