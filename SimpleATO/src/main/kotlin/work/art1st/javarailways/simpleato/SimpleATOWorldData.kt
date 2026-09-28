package work.art1st.javarailways.simpleato

import work.art1st.javarailways.basic.api.ato.provider.RailObjectStore
import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationStore
import work.art1st.javarailways.simpleato.api.depot.DepotStore
import work.art1st.javarailways.simpleato.api.line.LineStore
import work.art1st.javarailways.simpleato.api.station.StationStore
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.routing.OperatingDirectionStore
import work.art1st.javarailways.simpleato.routing.RoutePlanningRestrictionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableStore

/** Mutable operational runtime owned by SimpleATO for one Core world. */
data class SimpleATOWorldData(
    val atoCoordinator: AtoWorldCoordinator,
    val dispatcher: TimetableDispatcher,
    val signalEpochRuntime: SignalEpochRuntime,
    val signalRestrictions: TrainRestrictionSnapshotStore,
    val inspectionRuntime: InspectionRuntime,
    private val sharedRailObjects: RailObjectStore,
    private val sharedSafetyInterlock: WorldSafetyInterlock,
    val destinations: OperationalDestinationStore = OperationalDestinationStore(),
    val operatingDirections: OperatingDirectionStore = OperatingDirectionStore(),
    val stations: StationStore = StationStore(sharedRailObjects),
    val depots: DepotStore = DepotStore(),
    val lines: LineStore = LineStore(),
    val timetables: TimetableStore = TimetableStore(),
    val timetableExtensions: TimetableExtensionRegistry = TimetableExtensionRegistry(),
    val routePlanningRestrictions: RoutePlanningRestrictionRuntime = RoutePlanningRestrictionRuntime()
) {
    init {
        require(stations.usesRailObjectStore(sharedRailObjects)) {
            "StationStore must use the Basic world RailObjectStore"
        }
        require(atoCoordinator.safetyInterlock === sharedSafetyInterlock) {
            "AtoWorldCoordinator must use the Basic world WorldSafetyInterlock"
        }
    }
}

@OptIn(ExtensionData::class)
val World.simpleAtoWorldDataOrNull: SimpleATOWorldData?
    get() {
        val plugin = SimpleATOPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

@OptIn(ExtensionData::class)
val World.simpleAtoWorldData: SimpleATOWorldData
    get() {
        val plugin = SimpleATOPlugin.instanceOrNull()
            ?: throw IllegalStateException(
                "SimpleATOPlugin.INSTANCE is not initialized; register SimpleATO before creating worlds"
            )
        return getExtensionDataOrNull(plugin)
            ?: throw IllegalStateException("SimpleATOWorldData is not registered on this world")
    }
