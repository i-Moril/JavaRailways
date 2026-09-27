package work.art1st.javarailways.simpleato.testing

import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.acceptingTimetableAppliedPlanService

/** Installs the Basic and SimpleATO world-local stores needed by SimpleATO tests. */
internal object TestSimpleATOWorldDataRegistry {
    fun getOrCreate(world: World): BasicWorldData {
        val basicData = TestBasicWorldDataRegistry.getOrCreate(world)
        if (world.simpleAtoWorldDataOrNull == null) {
            registerSimpleAto(world, basicData)
        }
        return basicData
    }

    fun register(world: World, basicData: BasicWorldData) {
        TestBasicWorldDataRegistry.register(world, basicData)
        registerSimpleAto(world, basicData)
    }

    fun unregister(world: World) {
        TestBasicWorldDataRegistry.unregister(world)
    }

    private fun registerSimpleAto(world: World, basicData: BasicWorldData) {
        val worldId = basicData.atoCoordinator.worldId
        val restrictions = TrainRestrictionSnapshotStore(worldId)
        val inspection = InspectionRuntime(
            publisher = restrictions,
            sourceUnavailable = { _, _, failure -> throw failure }
        )
        val signalEpoch = SignalEpochRuntime(worldId, restrictions, restrictions::completeEpoch)
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = UnavailableRouteAdmissionTestContext,
            signalRestrictionSnapshots = restrictions,
            inspectionRuntime = inspection,
            appliedPlanService = acceptingTimetableAppliedPlanService()
        )
        val plugin = SimpleATOPlugin.instanceOrNull()
            ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
        world.registerExtensionData(
            plugin,
            SimpleATOWorldData(
                dispatcher,
                signalEpoch,
                restrictions,
                inspection,
                basicData.railObjects
            )
        )
    }
}
