package work.art1st.javarailways.simpleato

import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.FormationMovementHold
import work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration
import work.art1st.javarailways.simpleato.api.depot.RecoveredFormationComposition
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationStore
import work.art1st.javarailways.simpleato.api.depot.DepotStore
import work.art1st.javarailways.simpleato.api.line.LineStore
import work.art1st.javarailways.simpleato.api.station.StationStore
import work.art1st.javarailways.simpleato.api.station.StationCallResolverRegistry
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.routing.RoutePlanningRestrictionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableStore
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime

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
    val stations: StationStore = StationStore(sharedRailObjects),
    val depots: DepotStore = DepotStore(),
    val lines: LineStore = LineStore(),
    val timetables: TimetableStore = TimetableStore(),
    val timetableExtensions: TimetableExtensionRegistry = TimetableExtensionRegistry(),
    val routePlanningRestrictions: RoutePlanningRestrictionRuntime = RoutePlanningRestrictionRuntime(),
    val stationCallResolvers: StationCallResolverRegistry = StationCallResolverRegistry(),
    val operationalRuntime: OperationalWorldRuntime? = null
) {
    // Capabilities are process-local. Saved event identities, never saved tokens, rebuild these.
    private val formationRecoveryMovementHolds = LinkedHashMap<String, List<FormationMovementHold>>()
    private val heldFormationMovementHolds = LinkedHashMap<String, FormationMovementHold>()

    internal fun restoreFormationRecoveryMovementHolds(world: World) {
        check(formationRecoveryMovementHolds.isEmpty() && heldFormationMovementHolds.isEmpty()) { "formation recovery fences already restored" }
        depots.recoveredFormationHolds().forEach { recovery ->
            val owners = when (recovery.composition) {
                RecoveredFormationComposition.UNCHANGED_SOURCES -> recovery.change.sources
                RecoveredFormationComposition.EXPECTED_OUTPUTS -> recovery.change.outputs.map { it.owner }
            }
            val tokens = ArrayList<FormationMovementHold>()
            // Retain installed capabilities even if a later fence rejects; never roll back protection.
            formationRecoveryMovementHolds[recovery.change.eventId] = tokens
            owners.forEach { owner ->
                when (val result = world.restoreFormationMovementHold(owner.trainId, owner.carIds)) {
                    is FormationMovementHoldRestoration.Accepted -> tokens += result.hold
                    is FormationMovementHoldRestoration.Rejected -> error(
                        "Formation recovery ${recovery.change.eventId} cannot fence ${owner.trainId}: ${result.reason}")
                }
            }
        }
        depots.heldFormationOutputs().forEach { held ->
            val owner = held.owner
            when (val result = world.restoreFormationMovementHold(owner.trainId, owner.carIds)) {
                is FormationMovementHoldRestoration.Accepted -> heldFormationMovementHolds[owner.trainId] = result.hold
                is FormationMovementHoldRestoration.Rejected -> error("Held formation ${owner.trainId} cannot be fenced: ${result.reason}")
            }
        }
    }

    init {
        signalEpochRuntime.attachInspectionRuntime(inspectionRuntime)
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
