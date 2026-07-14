/** Global type declarations for the MineRails Phase 5 timetable DSL. */

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

interface MineRailsTimedActionBuilder {
  goto(wayPointId: string | readonly string[]): MineRailsServiceBuilderLike;
  stop(ticks: number): MineRailsServiceBuilderLike;
}

/** Placeholder return value; it does not currently expose chainable methods. */
interface MineRailsServiceBuilderLike {}
