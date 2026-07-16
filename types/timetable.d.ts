/** Global type declarations for the MineRails timetable DSL. */

interface MineRailsTimetableBuilder {
  /** Declare a positive whole number of 24,000-tick logical game days. */
  setPeriodDays(days: number): MineRailsTimetableBuilder;
  /** @deprecated Use setPeriodDays(); ticks must be a positive multiple of 24,000. */
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
  /** Cycle-relative departure tick; must equal the paired goto tick when provided. */
  plannedDepartureTick?: number;
  /** Head-aligned stopping offset before the berth marker, in metres. */
  stoppingOffsetMetersBefore?: number;
  /** Semantic door side executed through the Phase C3 operation gateway. */
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
