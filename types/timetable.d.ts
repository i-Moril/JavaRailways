/** Global type declarations for the MineRails C2 timetable DSL. */

interface MineRailsTimetableBuilder {
  setPeriod(ticks: number): MineRailsTimetableBuilder;
  service(id: string, builder: MineRailsServiceBuilderCallback): MineRailsTimetableBuilder;
  service(
    id: string,
    trainSelector: string | readonly string[] | null,
    builder: MineRailsServiceBuilderCallback,
  ): MineRailsTimetableBuilder;
}

type MineRailsServiceBuilderCallback = (service: MineRailsServiceBuilder) => void;

interface MineRailsServiceBuilder {
  at(tick: number): MineRailsTimedActionBuilder;
  check(wayPointId: string): MineRailsServiceBuilder;
}

type MineRailsDoorSide = 'none' | 'left' | 'right' | 'both';

interface MineRailsStationCallOptions {
  /** Minimum dwell after BERTHED, in Dispatcher ticks. */
  minimumDwellTicks?: number;
  /** Absolute planned departure tick; must not be earlier than at(tick). */
  plannedDepartureTick?: number;
  /** Head-aligned stopping offset before the berth marker, in metres. */
  stoppingOffsetMetersBefore?: number;
  /** Metadata only in Phase C2; no door action is executed. */
  doorSide?: MineRailsDoorSide;
}

interface MineRailsTimedActionBuilder {
  goto(wayPointId: string | readonly string[]): MineRailsServiceBuilderLike;
  stop(ticks: number): MineRailsServiceBuilderLike;
  call(
    stationId: string,
    berthId: string,
    options?: MineRailsStationCallOptions,
  ): MineRailsServiceBuilderLike;
}

/** Placeholder return value; it does not currently expose chainable methods. */
interface MineRailsServiceBuilderLike {}
