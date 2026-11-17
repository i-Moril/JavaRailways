package work.art1st.javarailways.simpleato.runtime

import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.station.StationCallResolverRegistry
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.api.coupling.CouplingModeSettings
import work.art1st.javarailways.simpleato.coupling.CouplingModeRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.UUID

/** The existing Core/Basic/ATO domain chain, owned by one world in either execution mode. */
class OperationalWorldRuntime private constructor(
    private val world: CoreWorld,
    val worldId: String,
    val signalRestrictions: TrainRestrictionSnapshotStore,
    val inspectionRuntime: InspectionRuntime,
    val signalEpochRuntime: SignalEpochRuntime,
    val basicData: BasicWorldData,
    val atoCoordinator: AtoWorldCoordinator
) : AutoCloseable {
    lateinit var data: SimpleATOWorldData
        private set
    private var topologySubscription: AutoCloseable? = null
    private var speedMonitor: SpeedSafetyMonitor? = null
    private var closed = false
    private var couplingRuntime: CouplingModeRuntime? = null

    fun attachSpeedSafety(config: SpeedSafetyMonitor.Config) {
        check(!closed && speedMonitor == null) { "speed safety must be attached once before startup" }
        val monitor = SpeedSafetyMonitor(world, config)
        world.physicsCoordinator.registerPublishedFrameProcessor(monitor)
        speedMonitor = monitor
    }

    /** Called inside the existing frame boundary, only after the exact successful ticket. */
    fun afterPublication(
        physicsEpoch: Long,
        publishObservation: () -> Unit,
        beforeDispatcher: () -> Unit = {},
        operationalTick: Long? = null
    ) {
        if (closed) throw work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt(
            "A live world consumed a closed operational runtime: $worldId")
        work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.ensureRunning()
        data.controlRuntime.retireMissing(world.allTrains)
        publishObservation()
        world.publishPendingDerailEvents()
        speedMonitor?.drainRequests(physicsEpoch).orEmpty().forEach { request ->
            val train = world.getTrain(request.trainId) ?: return@forEach
            if (train.publishedFrame.physicsEpoch == request.observedPhysicsEpoch) train.derail(request.reason)
        }
        beforeDispatcher()
        couplingRuntime?.tick()
        if (operationalTick == null) data.dispatcher.tick(world)
        else data.dispatcher.tick(world, operationalTick)
        world.tickManager.postTick()
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        if (this::data.isInitialized) attempt { data.dispatcher.cancelPendingActionsForShutdown(world) }
        attempt { couplingRuntime?.close() }
        if (this::data.isInitialized) attempt { data.controlRuntime.close() }
        basicData.commandSupervisor = null
        attempt { topologySubscription?.close() }
        attempt { speedMonitor?.let(world.physicsCoordinator::unregisterPublishedFrameProcessor) }

        failure?.let { throw it }
    }

    companion object {
        fun create(
            world: CoreWorld,
            worldId: String,
            basicDataFactory: ((Set<UUID>) -> Unit) -> BasicWorldData,
            timetableExtensions: TimetableExtensionRegistry = TimetableExtensionRegistry(),
            stationCallResolvers: StationCallResolverRegistry = StationCallResolverRegistry(),
            reportStall: (AtoStallReport) -> Unit = {},
            candidateFacts: (work.art1st.javarailways.core.api.physics.train.Train) ->
                work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts? = { null },
            couplingModeSettings: CouplingModeSettings = CouplingModeSettings(),
            couplingAdmissionContextProvider: TimetableRouteAdmissionContextProvider = TimetableRouteAdmissionContextProvider { null },
            couplingControlReleased: (Train) -> Unit = {},
            dispatcherFactory: (OperationalWorldRuntime) -> TimetableDispatcher
        ): OperationalWorldRuntime {
            val restrictions = TrainRestrictionSnapshotStore(worldId)
            val inspection = InspectionRuntime(restrictions) { _, _, _ -> }
            val signal = SignalEpochRuntime(
                worldId, restrictions, restrictions::completeEpoch, inspection::markDirty
            )
            val basic = basicDataFactory(signal::publishOperatingDirectionChanges)
            world.registerExtensionData(BasicPlugin.INSTANCE, basic)
            val runtime = OperationalWorldRuntime(
                world, worldId, restrictions, inspection, signal, basic,
                AtoWorldCoordinator(worldId, basic.safetyInterlock, reportStall)
            )
            try {
                val coupling = CouplingModeRuntime(world, couplingModeSettings, couplingAdmissionContextProvider, couplingControlReleased)
                runtime.couplingRuntime = coupling
                runtime.data = SimpleATOWorldData(
                    runtime.atoCoordinator, dispatcherFactory(runtime), signal, restrictions, inspection,
                    basic.railObjects, basic.safetyInterlock,
                    depots = work.art1st.javarailways.simpleato.api.depot.DepotStore {
                        world.allTrains.flatMap { train -> train.composition.units.map { unit ->
                            work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit(unit.unitId, unit.carIds)
                        } }
                    },
                    candidateFacts = candidateFacts,
                    timetableExtensions = timetableExtensions, stationCallResolvers = stationCallResolvers, operationalRuntime = runtime,
                    couplingModes = coupling
                )
                world.registerExtensionData(SimpleATOPlugin.INSTANCE, runtime.data)
                basic.commandSupervisor = runtime.data.controlRuntime
                runtime.topologySubscription = world.registerTopologyChangeListener { change ->
                    inspection.invalidateTopology(change)
                    signal.invalidateTopology(change)
                }
                world.physicsCoordinator.registerRailwayEpochProcessor(signal)
                return runtime
            } catch (failure: Throwable) {
                try { runtime.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }
}
