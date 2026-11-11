package work.art1st.javarailways.simpleato.testing

import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.acceptingInfrastructureDependencyService

/** Installs the Basic and SimpleATO world-local stores needed by SimpleATO tests. */
internal object TestSimpleATOWorldDataRegistry {
    private val candidateFactQueries = java.util.IdentityHashMap<World,
        (work.art1st.javarailways.core.api.physics.train.Train) ->
            work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts?>()

    fun setCandidateFacts(world: World, query: (work.art1st.javarailways.core.api.physics.train.Train) ->
        work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts?) {
        candidateFactQueries[world] = query
    }

    fun setCandidateFacts(world: World, trainId: String,
        facts: work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts) {
        val previous = candidateFactQueries[world]
        candidateFactQueries[world] = { train -> if (train.id == trainId) facts else previous?.invoke(train) }
    }

    fun getOrCreate(world: World): BasicWorldData {
        val basicData = TestBasicWorldDataRegistry.getOrCreate(world)
        if (world.simpleAtoWorldDataOrNull == null) {
            registerSimpleAto(world, basicData)
        }
        return basicData
    }

    fun register(
        world: World,
        basicData: BasicWorldData,
        atoCoordinator: AtoWorldCoordinator = AtoWorldCoordinator(
            safetyInterlock = basicData.safetyInterlock
        )
    ) {
        TestBasicWorldDataRegistry.register(world, basicData)
        registerSimpleAto(world, basicData, atoCoordinator)
    }

    fun unregister(world: World) {
        candidateFactQueries.remove(world)
        TestBasicWorldDataRegistry.unregister(world)
    }

    private fun registerSimpleAto(
        world: World,
        basicData: BasicWorldData,
        atoCoordinator: AtoWorldCoordinator = AtoWorldCoordinator(
            safetyInterlock = basicData.safetyInterlock
        )
    ) {
        val worldId = atoCoordinator.worldId
        val restrictions = TrainRestrictionSnapshotStore(worldId)
        val inspection = InspectionRuntime(
            publisher = restrictions,
            sourceUnavailable = { _, _, failure -> throw failure }
        )
        val signalEpoch = SignalEpochRuntime(
            worldId,
            restrictions,
            restrictions::completeEpoch,
            inspection::markDirty
        )
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = UnavailableRouteAdmissionTestContext,
            signalRestrictionSnapshots = restrictions,
            inspectionRuntime = inspection,
            infrastructureDependencyService = acceptingInfrastructureDependencyService()
        )
        val plugin = SimpleATOPlugin.instanceOrNull()
            ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
        world.registerExtensionData(
            plugin,
            SimpleATOWorldData(
                atoCoordinator,
                dispatcher,
                signalEpoch,
                restrictions,
                inspection,
                basicData.railObjects,
                basicData.safetyInterlock,
                candidateFacts = { train -> candidateFactQueries[world]?.invoke(train) },
                depots = work.art1st.javarailways.simpleato.api.depot.DepotStore {
                    world.allTrains.flatMap { train -> train.composition.units.map { unit ->
                        work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit(unit.unitId, unit.carIds)
                    } }
                }
            )
        )
    }
}
