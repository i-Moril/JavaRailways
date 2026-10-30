package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg
import work.art1st.javarailways.basic.api.route.PlannedRoute
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver
import work.art1st.javarailways.simpleato.api.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.simpleato.api.depot.DepotTrainAllocation
import work.art1st.javarailways.simpleato.api.depot.DepotStorageReservation
import work.art1st.javarailways.simpleato.api.depot.DepotStorageReservationResult
import work.art1st.javarailways.simpleato.api.depot.DepotTrainAllocationResult
import work.art1st.javarailways.simpleato.api.depot.RollingStockState
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds
import work.art1st.javarailways.simpleato.depot.DepotResetDispatchApplyException
import work.art1st.javarailways.simpleato.depot.DepotResetDispatchParticipant
import work.art1st.javarailways.simpleato.depot.DepotResetDispatchSummary
import work.art1st.javarailways.basic.api.operation.RejectingTrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.DwellPlanResolver
import work.art1st.javarailways.simpleato.api.station.ResolvedStationCall
import work.art1st.javarailways.simpleato.api.station.StationBerthAllocationKey
import work.art1st.javarailways.simpleato.api.station.StationBerthAllocationResult
import work.art1st.javarailways.simpleato.api.station.StationBerthAllocator
import work.art1st.javarailways.simpleato.api.station.StationBerthOwner
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.api.station.StationCallNotificationType
import work.art1st.javarailways.simpleato.api.station.StationCallRequest
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolver
import work.art1st.javarailways.simpleato.api.station.StationCallSubmissionResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableActivationFalseBehavior
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionScope
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionState
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionTicket
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionActionStartResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableRuntimeContext
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.simpleato.api.timetable.TimetableTrainContext
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.controller.SimpleAtoPublishedState
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.admission.RollingRouteAuthorityPlanner
import work.art1st.javarailways.simpleato.admission.RouteAuthorityAdmissionClient
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.infrastructure.PhysicalRouteDependencyCompilation
import work.art1st.javarailways.basic.infrastructure.PhysicalRouteDependencyCompiler
import work.art1st.javarailways.basic.route.toSnapshot
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenMissionRouteAssembler
import work.art1st.javarailways.simpleato.routing.FrozenMissionRouteRejection
import work.art1st.javarailways.simpleato.routing.FrozenMissionRouteResult
import work.art1st.javarailways.simpleato.routing.TerminalRouteMargin
import work.art1st.javarailways.simpleato.routing.TerminalMarginProposal
import work.art1st.javarailways.simpleato.routing.RouteOperatingDirectionLimit
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionContext
import work.art1st.javarailways.simpleato.persistence.SavedActiveRunCheckpointV1
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableClockV1
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simpleato.routing.MissionRouteLayout
import work.art1st.javarailways.simpleato.routing.RouteAllocationTiming
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.rail.IntervalOccupancyState
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailIntervalReleaseDetector
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.ArrayDeque
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

fun interface TimetableAtoFactory {
    fun create(train: Train, world: World): SimpleATO
}

/** One coherent observation and its authenticated Basic admission entry point for this tick. */
data class TimetableRouteAdmissionContext(
    val observation: RailwayObservation,
    val service: RouteResourceAdmissionService
)

/**
 * Supplies the already-published world observation and one stable authenticated service binding.
 *
 * A null result means that this tick has no coherent observation yet. The dispatcher stays
 * fail-closed until a coherent observation and its matching admission service are available.
 */
fun interface TimetableRouteAdmissionContextProvider {
    fun current(): TimetableRouteAdmissionContext?
}

data class TimetableServiceRunId(val timetableId: String, val serviceId: String) {
    init {
        require(timetableId.isNotBlank() && serviceId.isNotBlank())
    }

    override fun toString(): String = "$timetableId/$serviceId"
}

enum class TimetableControlMode {
    AUTOMATIC,
    MANUAL_ROUTED,
    FULL_MANUAL
}

enum class TimetableServiceRunState {
    WAITING_MISSION,
    WAITING_TIMETABLE_TIME,
    WAITING_ROUTE,
    RUNNING,
    WAITING_BERTH,
    APPROACHING,
    BERTHED,
    DOORS_OPENING,
    DWELLING,
    DOORS_CLOSING,
    READY_TO_DEPART,
    DEPARTING,
    DEPARTED,
    STORING,
    HALTING,
    REVERSING,
    WAITING_EXTENSION_ACTION,
    MANUAL_INTERVENTION,
    SUSPENDED,
    REPLAN_REQUIRED,
    STOPPING,
    STOPPED,
    COMPLETED,
    FAILED
}

data class TimetableWaypointProgress(
    val runId: TimetableServiceRunId,
    val cycleIndex: Long,
    val waypointId: String,
    val pass: Boolean,
    val timetableElapsedTicks: Long
)

fun interface TimetableWaypointProgressListener {
    fun onReached(progress: TimetableWaypointProgress)
}

data class TimetableTaskTiming(val taskId: String, val startTick: Long, val endTick: Long? = null)

data class TimetableServiceRunSnapshot(
    val id: TimetableServiceRunId,
    val trainId: String,
    val missionRevision: Long,
    val state: TimetableServiceRunState,
    val routeGrantActive: Boolean,
    val cycleIndex: Long = 0L,
    val timetableElapsedTicks: Long = 0L,
    val scheduledActionTick: Long? = null,
    val lastLatenessTicks: Long = 0L,
    val currentWayPointId: String? = null,
    val nextStepIndex: Int = 0,
    val nextStepBranchPath: List<TimetableBranchEntry> = emptyList(),
    val activeStepIndex: Int? = null,
    val activeStepBranchPath: List<TimetableBranchEntry> = emptyList(),
    val nextScheduledTick: Long? = null,
    val trainHeadRailId: String? = null,
    val trainHeadArcLengthInAbMeters: Double? = null,
    val headProgressMeters: Double? = null,
    val tailProgressMeters: Double? = null,
    val stationCallId: String? = null,
    val berthRef: StationBerthRef? = null,
    val berthHeld: Boolean = false,
    val selectedBerthRef: StationBerthRef? = null,
    val selectedBerthCommitted: Boolean = false,
    val operationTicketId: String? = null,
    val operationTicketState: TrainOperationTicketState? = null,
    val operationTicketError: String? = null,
    val controlTransferOwner: String? = null,
    val controlMode: TimetableControlMode = TimetableControlMode.AUTOMATIC,
    val message: String? = null,
    val currentRunId: String? = null,
    val currentLineId: work.art1st.javarailways.simpleato.api.line.LineId? = null,
    val taskTimings: List<TimetableTaskTiming> = emptyList(),
    val unitDisplays: List<TimetableUnitDisplaySnapshot> = emptyList()
)

sealed interface TimetableServiceStartResult {
    data class Started(val run: TimetableServiceRunSnapshot) : TimetableServiceStartResult
    data class Failure(val reason: String) : TimetableServiceStartResult
}

internal class TimetableDepotResetProgress {
    private var removedRunCount = 0
    private var releasedBerthCount = 0
    private val stoppedTimetableIds = LinkedHashSet<String>()

    fun recordRunRemoval() {
        removedRunCount++
    }

    fun recordBerthRelease() {
        releasedBerthCount++
    }

    fun recordStoppedTimetable(timetableId: String) {
        stoppedTimetableIds += timetableId
    }

    fun snapshot(): DepotResetDispatchSummary = DepotResetDispatchSummary(
        removedRunCount,
        releasedBerthCount,
        stoppedTimetableIds.toSet()
    )
}

data class TimetableEpochSnapshot(
    val timetableId: String,
    val epochDispatcherTick: Long,
    val periodTicks: Long
)

sealed interface TimetableEpochStartResult {
    data class Started(val epoch: TimetableEpochSnapshot) : TimetableEpochStartResult
    data class Failure(val reason: String) : TimetableEpochStartResult
}

enum class TimetableApplicationState { UNAPPLIED, APPLIED, RECOVERY_REQUIRED }

sealed interface TimetableCheckpointRestoreResult {
    data object Restored : TimetableCheckpointRestoreResult
    data class Rejected(val reason: String) : TimetableCheckpointRestoreResult
}

sealed interface TimetableStationCallScheduleResult {
    data class Scheduled(val run: TimetableServiceRunSnapshot) : TimetableStationCallScheduleResult
    data class Failure(val reason: String) : TimetableStationCallScheduleResult
}

sealed interface TimetableControlTransferResult {
    data class ManualRouted(val run: TimetableServiceRunSnapshot) : TimetableControlTransferResult
    data class Suspended(val run: TimetableServiceRunSnapshot) : TimetableControlTransferResult
    data object NotAssigned : TimetableControlTransferResult
    data class Failure(val reason: String) : TimetableControlTransferResult
}

sealed interface TimetableReplanResult {
    data class Resumed(val run: TimetableServiceRunSnapshot) : TimetableReplanResult
    data class Failure(val reason: String) : TimetableReplanResult
}

/** Main-thread runtime for explicit-train timetable services. */
class TimetableDispatcher(
    private val routeAdmissionContextProvider: TimetableRouteAdmissionContextProvider,
    private val signalRestrictionSnapshots: TrainRestrictionSnapshotProvider,
    private val inspectionRuntime: InspectionRuntime,
    private val infrastructureDependencyService: InfrastructureDependencyService,
    private val allocationTiming: RouteAllocationTiming = RouteAllocationTiming(
        switchSetupSeconds = 0.25,
        reactionTimeSeconds = 0.25,
        safetyMarginMeters = 2.0
    ),
    private val operationGateway: TrainOperationGateway = RejectingTrainOperationGateway,
    private val operationTimeoutTicks: Long = DEFAULT_OPERATION_TIMEOUT_TICKS,
    private val atoFactory: TimetableAtoFactory? = null,
    private val authorityStoppingMarginMeters: Double = AtoSafetyProfile().safetyDistanceMeters,
    val admissionRetryTicks: Int = 5,
    standaloneSimulationServices: Set<TimetableServiceRunId> = emptySet()
) : DepotResetDispatchParticipant {
    private val standaloneSimulationServices = java.util.Set.copyOf(standaloneSimulationServices)
    init {
        require(authorityStoppingMarginMeters.isFinite() && authorityStoppingMarginMeters >= 0.0)
        require(operationTimeoutTicks > 0L)
        require(admissionRetryTicks > 0)
    }
    private val missionRevision = AtomicLong(0L)
    private val admissionOwnerSequence = AtomicLong(0L)
    private val appliedPlanRevision = AtomicLong(0L)
    private val runs = LinkedHashMap<TimetableServiceRunKey, ActiveRun>()
    private val displayRuntime = TimetableDisplayRuntime()
    private val preparingCouplings = LinkedHashMap<String, PreparingCoupling>()
    private val fixedFormations = LinkedHashMap<String, DispatcherFixedFormation>()
    private val timetableClocks = LinkedHashMap<String, TimetableClock>()
    private val waypointProgressListeners =
        LinkedHashMap<WaypointProgressKey, MutableList<TimetableWaypointProgressListener>>()
    private val recoveryRequiredRuns = ArrayList<SavedActiveRunCheckpointV1>()
    // Definitions remain diagnostic only: a historical source may already have split/coupled.
    private val recoveryRequiredDefinitions = LinkedHashMap<String, Timetable>()
    private val appliedTimetablePlans = LinkedHashMap<String, AppliedTimetablePlan>()
    private val physicalStorageReservations = LinkedHashMap<StablingPointRef, String>()
    private val berthAllocator = StationBerthAllocator { ref ->
        checkNotNull(checkNotNull(boundWorld).simpleAtoWorldData.stations.berth(ref)) {
            "Station berth no longer exists: $ref"
        }.stoppingAreaRef
    }
    /** Captured logical area ownership blocks canonical configuration replacement until release. */
    fun hasStationStoppingAreaAllocation(area: work.art1st.javarailways.simpleato.api.station.StationStoppingAreaRef): Boolean =
        berthAllocator.isAllocated(area)

    private var dispatcherTick = 0L
    private var boundWorld: World? = null

    /** Maintenance precondition: immutable routes owned by active runs must not retain replaced Rail IDs. */
    fun hasLiveRouteOn(railIds: Set<java.util.UUID>): Boolean {
        if (railIds.isEmpty()) return false
        return runs.values.asSequence()
            .filterNot { it.state.isTerminal() }
            .any { run ->
                sequenceOf(run.admittedRoute, run.admissionCandidate)
                    .filterNotNull()
                    .flatMap { it.route.selection.route.segments.asSequence() }
                    .any { it.railId in railIds } ||
                    run.referencedRuntimes().asSequence()
                        .flatMap { it.mission.legs.asSequence() }
                        .flatMap { it.plannedRoute.expandedRoute.segments.asSequence() }
                        .any { it.railViewRef.railId in railIds }
            }
    }

    /** Capture stable logical state only; live locks, tickets, queues and controller objects are excluded. */
    fun saveCheckpoint(): SavedDispatcherV1 = SavedDispatcherV1(
        formatVersion = SavedDispatcherV1.CURRENT_FORMAT_VERSION,
        dispatcherTick = dispatcherTick,
        missionRevision = missionRevision.get(),
        clocks = timetableClocks.entries.map { (id, clock) ->
            SavedTimetableClockV1(
                timetableId = id,
                epochDispatcherTick = clock.epochDispatcherTick,
                periodTicks = clock.periodTicks,
                nextCycleIndex = clock.nextCycleIndex,
                disabledServiceIds = clock.disabledServiceIds.toSet(),
                pendingActivationCycles = clock.pendingActivationCycles.toMap()
            )
        }.sortedBy(SavedTimetableClockV1::timetableId),
        runs = (runs.values.map { run ->
            val diagnostics = run.ato.runtimeDiagnostics
            SavedActiveRunCheckpointV1(
                timetableId = run.id.timetableId,
                serviceId = run.id.serviceId,
                cycleIndex = run.cycleIndex,
                trainId = run.train.id,
                nextStepIndex = run.nextStepIndex,
                currentWayPointId = run.currentWayPointId,
                targetWayPointId = run.currentRuntimeOrNull?.targetWayPointId
                    ?: run.currentWayPointId,
                originalState = run.state.name,
                timetableElapsedTicks = timetableElapsedTicks(run),
                dwellTicksRemaining = run.dwellTicksRemaining,
                missionRevision = run.missionRevision,
                lastScheduledActionTick = run.lastScheduledActionTick,
                lastLatenessTicks = run.lastLatenessTicks,
                controlMode = run.controlMode.name,
                message = run.message,
                headProgressMeters = run.currentRuntimeOrNull?.headProgressMeters,
                tailProgressMeters = run.currentRuntimeOrNull?.tailProgressMeters,
                missionTotalLengthMeters = run.currentRuntimeOrNull?.layout?.totalLengthMeters,
                atoMissionRevision = diagnostics.missionRevision,
                atoOdometerProgressMeters = diagnostics.odometerProgressMeters,
                atoProjectedProgressMeters = diagnostics.projectedProgressMeters,
                movementAuthorityRevision = diagnostics.movementAuthorityRevision,
                movementAuthorityLimitMeters = diagnostics.movementAuthorityLimitMeters,
                activeStationCallId = diagnostics.activeStationCallId,
                stationTargetProgressMeters = diagnostics.stationTargetProgressMeters,
                stationPositionErrorMeters = diagnostics.stationPositionErrorMeters,
                atoPermittedSpeedMetersPerSecond = diagnostics.permittedSpeedMetersPerSecond,
                atoConstraints = diagnostics.constraints,
                stationCallSubmittedToAto = run.stationCall?.submittedToAto,
                berthHeld = run.stationCall?.allocationKey != null
            )
        } + recoveryRequiredRuns).distinctBy {
            Triple(it.timetableId, it.serviceId, it.cycleIndex)
        }
    )

    /** Restore dependencies before clocks and former runs become visible to the runtime. */
    fun restoreCheckpoint(world: World, saved: SavedDispatcherV1): TimetableCheckpointRestoreResult {
        require(saved.formatVersion == SavedDispatcherV1.CURRENT_FORMAT_VERSION) {
            "Unsupported dispatcher snapshot version: ${saved.formatVersion}"
        }
        check(runs.isEmpty() && timetableClocks.isEmpty() && appliedTimetablePlans.isEmpty() && recoveryRequiredDefinitions.isEmpty()) {
            "Dispatcher must be empty before restore"
        }
        require(saved.dispatcherTick >= 0L && saved.missionRevision >= 0L)
        bindWorld(world)
        val installed = ArrayList<Pair<String, AppliedTimetablePlan>>()
        val diagnosticDefinitions = LinkedHashMap<String, Timetable>()
        val timetableIds = (saved.clocks.map { it.timetableId } + saved.runs.map { it.timetableId })
            .distinct()
            .sorted()
        for (timetableId in timetableIds) {
            val definition = world.simpleAtoWorldData.timetables[timetableId]
            if (definition != null && definition.formationEvents.isNotEmpty()) {
                // Do not reconstruct a pre-event physical composition or call it a route proof.
                // Existing Core/Depot recovery fences protect the actually published outputs.
                diagnosticDefinitions[timetableId] = definition
                continue
            }
            val plan = when (val result = compileAndInstallAppliedPlan(world, timetableId, null)) {
                is AppliedPlanInstallResult.Installed -> result.plan
                is AppliedPlanInstallResult.Rejected -> {
                    val rollbackFailures = installed.asReversed().mapNotNull { (id, applied) ->
                        rollbackAppliedPlan(id, applied, null)?.let { failure ->
                            stallTimetableRuntime(world, TimetableServiceRunId(id, "<applied-plan>"),
                                "InfrastructureDependencyFailure", "checkpoint dependency rollback failed: $failure")
                            "${applied.owner.ownerId}: $failure"
                        }
                    }
                    return TimetableCheckpointRestoreResult.Rejected(buildString {
                        append("could not restore applied timetable '$timetableId': ${result.reason}")
                        if (rollbackFailures.isNotEmpty()) {
                            append("; dependency rollback failed: ${rollbackFailures.joinToString()} ")
                        }
                    }.trim())
                }
            }
            if (plan != null) installed += timetableId to plan
        }
        dispatcherTick = saved.dispatcherTick
        missionRevision.set(saved.missionRevision)
        saved.clocks.forEach { clock ->
            timetableClocks[clock.timetableId] = TimetableClock(
                epochDispatcherTick = clock.epochDispatcherTick,
                periodTicks = clock.periodTicks,
                nextCycleIndex = clock.nextCycleIndex,
                disabledServiceIds = LinkedHashSet(clock.disabledServiceIds +
                    diagnosticDefinitions[clock.timetableId]?.services.orEmpty().map { it.id }),
                pendingActivationCycles = if (clock.timetableId in diagnosticDefinitions) LinkedHashMap()
                    else LinkedHashMap(clock.pendingActivationCycles)
            )
        }
        recoveryRequiredDefinitions.putAll(diagnosticDefinitions)
        // Keep a disabled diagnostic clock even if a historical checkpoint only held run records.
        // This round-trips the recovery requirement through the existing saved boundary.
        diagnosticDefinitions.forEach { (id, definition) ->
            timetableClocks.getOrPut(id) { TimetableClock(saved.dispatcherTick, definition.periodTicks) }
                .disabledServiceIds.addAll(definition.services.map { it.id })
        }
        recoveryRequiredRuns.clear()
        recoveryRequiredRuns.addAll(saved.runs)
        saved.runs.forEach { run ->
            timetableClocks[run.timetableId]?.disabledServiceIds?.add(run.serviceId)
        }
        appliedTimetablePlans.putAll(installed)
        return TimetableCheckpointRestoreResult.Restored
    }

    fun recoveryRequiredRuns(): List<SavedActiveRunCheckpointV1> = recoveryRequiredRuns.toList()

    /** Structural recovery has no applied route proof and cannot execute from this definition. */
    fun recoveryRequiredTimetableDefinitions(): List<Timetable> = recoveryRequiredDefinitions.values.sortedBy { it.id }

    fun applicationState(timetableId: String): TimetableApplicationState = when {
        timetableId in recoveryRequiredDefinitions -> TimetableApplicationState.RECOVERY_REQUIRED
        timetableId in appliedTimetablePlans -> TimetableApplicationState.APPLIED
        else -> TimetableApplicationState.UNAPPLIED
    }

    @MainThread
    fun observeWaypointProgress(
        id: TimetableServiceRunId,
        cycleIndex: Long = 0L,
        listener: TimetableWaypointProgressListener
    ): AutoCloseable {
        val key = WaypointProgressKey(id, cycleIndex)
        waypointProgressListeners.getOrPut(key, ::ArrayList).add(listener)
        return AutoCloseable {
            waypointProgressListeners[key]?.let { listeners ->
                listeners.remove(listener)
                if (listeners.isEmpty()) waypointProgressListeners.remove(key)
            }
        }
    }

    /** Establish one shared cycle-zero epoch before an administrator starts timetable services. */
    @MainThread
    fun beginTimetable(
        world: World,
        timetableId: String
    ): TimetableEpochStartResult {
        ensureOwner(world)
        bindWorld(world)
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) {
            return TimetableEpochStartResult.Failure(
                "world ATO is stalled; inspect and reset before starting a timetable"
            )
        }
        val timetable = world.simpleAtoWorldData.timetables[timetableId]
            ?: return TimetableEpochStartResult.Failure("timetable not found: $timetableId")
        if (timetableId in recoveryRequiredDefinitions) {
            return TimetableEpochStartResult.Failure("structural timetable requires explicit recovery handling before a fresh apply")
        }
        val validation = validator(world, timetableId).validate(timetable)
        if (!validation.isSuccess) {
            return TimetableEpochStartResult.Failure(validation.errors.joinToString("; "))
        }
        val blocking = runs.values.firstOrNull {
            it.id.timetableId == timetableId && !it.canBeReplaced() && !it.isStoredTerminal()
        }
        if (blocking != null) {
            return TimetableEpochStartResult.Failure(
                "timetable still has an active or resource-holding run: ${blocking.id}"
            )
        }
        if ((fixedFormations.values.any { it.source.id.timetableId == timetableId } || preparingCouplings.values.any { it.coordinator.id.timetableId == timetableId })) {
            return TimetableEpochStartResult.Failure("timetable still owns structural output protections")
        }
        val previousPlan = appliedTimetablePlans[timetableId]
        val installedPlan = when (
            val installed = compileAndInstallAppliedPlan(world, timetableId, previousPlan, validation)
        ) {
            is AppliedPlanInstallResult.Installed -> installed.plan
            is AppliedPlanInstallResult.Rejected ->
                return TimetableEpochStartResult.Failure(installed.reason)
        }
        runs.entries.removeIf { (key, run) ->
            key.id.timetableId == timetableId && run.canBeReplaced()
        }
        when (val initialPositions = TimetableInitialPositioner(world).apply(timetable, dispatcherTick)) {
            is TimetableInitialPositionResult.Applied -> Unit
            is TimetableInitialPositionResult.Failure -> {
                val rollbackFailure = rollbackAppliedPlan(timetableId, installedPlan, previousPlan)
                if (rollbackFailure != null) {
                    if (installedPlan == null) {
                        appliedTimetablePlans.remove(timetableId)
                    } else {
                        appliedTimetablePlans[timetableId] = installedPlan
                    }
                    stallTimetableRuntime(
                        world,
                        TimetableServiceRunId(timetableId, "<applied-plan>"),
                        "InfrastructureDependencyFailure",
                        "initial-position failure could not restore the previous applied dependency: " +
                            rollbackFailure
                    )
                }
                return TimetableEpochStartResult.Failure(buildString {
                    append("initial position reset failed: ${initialPositions.reason}")
                    rollbackFailure?.let { append("; applied dependency rollback failed: $it") }
                })
            }
        }
        val clock = TimetableClock(
            epochDispatcherTick = dispatcherTick,
            periodTicks = timetable.periodTicks,
            nextCycleIndex = 1L
        )
        timetableClocks[timetableId] = clock
        if (installedPlan == null) {
            appliedTimetablePlans.remove(timetableId)
        } else {
            appliedTimetablePlans[timetableId] = installedPlan
        }
        return TimetableEpochStartResult.Started(
            TimetableEpochSnapshot(timetableId, clock.epochDispatcherTick, clock.periodTicks)
        )
    }

    /** Queue automatic services in cycle zero; manual services remain explicit-only. */
    @MainThread
    fun scheduleInitialCycle(timetableId: String): Boolean {
        ensureOwner()
        if (timetableId in recoveryRequiredDefinitions) return false
        val clock = timetableClocks[timetableId] ?: return false
        check(clock.epochDispatcherTick == dispatcherTick) {
            "initial cycle must be scheduled immediately after beginTimetable"
        }
        clock.nextCycleIndex = 0L
        return true
    }

    @MainThread
    fun startService(
        world: World,
        timetableId: String,
        serviceId: String
    ): TimetableServiceStartResult {
        ensureOwner(world)
        if (timetableId in recoveryRequiredDefinitions) {
            return TimetableServiceStartResult.Failure("structural timetable is recovery-required and has no applied route proof")
        }
        timetableClocks[timetableId]?.disabledServiceIds?.remove(serviceId)
        return startServiceForCycle(world, timetableId, serviceId, null)
    }

    /** Stop automatic recurrence without force-releasing any active run resources. */
    @MainThread
    fun stopFutureCycles(timetableId: String, serviceId: String? = null): Boolean {
        ensureOwner()
        // Already disabled. Preserve the diagnostic clock until explicit recovery disposal;
        // otherwise a save/reload could erase the unapplied recovery state without handling it.
        if (timetableId in recoveryRequiredDefinitions) return false
        val clock = timetableClocks[timetableId] ?: return false
        val changed = if (serviceId == null) {
            timetableClocks.remove(timetableId) != null
        } else {
            clock.disabledServiceIds.add(serviceId)
        }
        if (changed && serviceId == null) releaseDrainedAppliedPlans()
        return changed
    }

    private fun startServiceForCycle(
        world: World,
        timetableId: String,
        serviceId: String,
        requestedCycleIndex: Long?
    ): TimetableServiceStartResult {
        bindWorld(world)
        if (timetableId in recoveryRequiredDefinitions) {
            return TimetableServiceStartResult.Failure("structural timetable is recovery-required and has no applied route proof")
        }
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) {
            return TimetableServiceStartResult.Failure(
                "world ATO is stalled; inspect and reset before starting a service"
            )
        }
        val runId = TimetableServiceRunId(timetableId, serviceId)
        val timetable = world.simpleAtoWorldData.timetables[timetableId]
            ?: return TimetableServiceStartResult.Failure("timetable not found: $timetableId")
        val clock = timetableClocks.getOrPut(timetableId) {
            TimetableClock(dispatcherTick, timetable.periodTicks)
        }
        val service = timetable.services.firstOrNull { it.id == serviceId }
            ?: return TimetableServiceStartResult.Failure("service not found: $runId")
        if ((service.steps.firstOrNull() as? TimetableStep.At)?.action is TimetableAction.TakeFromFormationOutput) {
            return TimetableServiceStartResult.Failure("waiting for the declared formation output: $runId")
        }
        val applied = appliedTimetablePlans[timetableId]
        if (applied != null && applied.definition != timetable) {
            return TimetableServiceStartResult.Failure("timetable changed after apply; apply its current definition before starting services")
        }
        if (timetable.formationEvents.isNotEmpty() && applied == null) {
            return TimetableServiceStartResult.Failure("apply the complete formation timetable before starting services")
        }
        val serviceValidation = if (applied != null && timetable.formationEvents.isNotEmpty())
            TimetableValidationResult(emptyList(), emptyList()) else validator(world, timetableId).validateForServiceStart(timetable, service.id)
        val validationErrors = serviceValidation.errors
        if (validationErrors.isNotEmpty()) {
            return TimetableServiceStartResult.Failure(validationErrors.joinToString("; "))
        }
        val currentCycleIndex = ((dispatcherTick - clock.epochDispatcherTick).coerceAtLeast(0L) /
            clock.periodTicks)
        val cycleIndex = requestedCycleIndex ?: currentCycleIndex
        require(cycleIndex >= 0L)
        if (cycleIndex > currentCycleIndex) {
            return TimetableServiceStartResult.Failure(
                "cycle $cycleIndex has not reached its start Tick"
            )
        }
        val runKey = TimetableServiceRunKey(runId, clock.epochDispatcherTick, cycleIndex)
        runs[runKey]?.let { existing ->
            return TimetableServiceStartResult.Started(existing.snapshot())
        }
        val cycleStartDispatcherTick = Math.addExact(
            clock.epochDispatcherTick,
            Math.multiplyExact(cycleIndex, clock.periodTicks)
        )
        val acquisitionStep = service.steps.firstOrNull() as? TimetableStep.At
        val acquisitionTick = acquisitionStep?.takeIf {
            it.action is TimetableAction.TakeFromDepot || it.action is TimetableAction.TakeFromWaypoint
        }?.tick
        if (acquisitionTick != null &&
            dispatcherTick - cycleStartDispatcherTick < acquisitionTick
        ) {
            return TimetableServiceStartResult.Failure(
                "waiting for train acquisition tick $acquisitionTick: $runId"
            )
        }
        val selected = selectTrainForService(world, timetable, cycleIndex, runId, service)
            ?: return TimetableServiceStartResult.Failure(
                if (world.simpleAtoWorldData.atoCoordinator.isStalled) {
                    "world stalled because train acquisition failed: $runId"
                } else {
                    "service requires TrainSelector.TrainId, takeFromDepot, or takeFromWaypoint: $runId"
                }
            )
        val trainId = selected.trainId
        fun startupFailure(reason: String): TimetableServiceStartResult.Failure {
            if (selected.depotReservationPending) {
                selected.depotAllocation?.let(world.simpleAtoWorldData.depots::cancelReservation)
                stallDepotAllocation(world, runId, reason)
            } else if (selected.dynamicAcquisition) {
                stallTimetableRuntime(world, runId, "TrainAcquisitionFailure", reason)
            }
            return TimetableServiceStartResult.Failure(reason)
        }
        if (runs.values.any {
                it.train.id == trainId && !it.canBeReplaced() && !it.isStoredTerminal()
            }
        ) {
            return startupFailure("train is already assigned to a service: $trainId")
        }
        val train = world.getTrain(trainId) as? CoreTrain
            ?: return startupFailure("train not found: $trainId")
        if (train.isDerailed) return startupFailure("train is derailed: $trainId")

        val existingController = train.controller
        if (existingController != null && existingController !is SimpleATO) {
            val reason = "train already has another controller: $trainId"
            if (runs.values.any { it.train.id == trainId && it.isStoredTerminal() }) {
                stallDepotAllocation(world, runId, "$reason while a stored route remains tail-held")
            }
            return startupFailure(reason)
        }
        val replaceableTrainRuns = runs.values.filter {
            it.train.id == trainId && (it.canBeReplaced() || it.isStoredTerminal())
        }
        if (replaceableTrainRuns.any(ActiveRun::isStoredTerminal) && existingController == null) {
            val reason = "stored train $trainId lost the ATO that owns its tail-held route"
            stallDepotAllocation(world, runId, reason)
            return startupFailure(reason)
        }
        if (existingController is SimpleATO) {
            replaceableTrainRuns.forEach {
                existingController.removeSpeedLimit(stopKey(it))
                existingController.removeSpeedLimit(stationPendingHoldKey(it))
                existingController.removeSpeedLimit(scheduleHoldKey(it))
                existingController.removeSpeedLimit(timetableLineSpeedKey(it.id))
            }
        }
        val initialOriginWayPointId = selected.originWayPointId
            ?: service.steps.asSequence()
                .filterIsInstance<TimetableStep.Check>()
                .firstOrNull()
                ?.wayPointId
            ?: return startupFailure("validated service has no initial check/acquisition origin: $runId")
        val originFailure = validateAtWayPoint(world, train, initialOriginWayPointId)
        if (originFailure != null) return startupFailure(originFailure)

        val ato = existingController ?: run {
            val factory = atoFactory
                ?: return startupFailure("timetable ATO factory is not configured")
            try {
                factory.create(train, world)
            } catch (failure: Exception) {
                return startupFailure(
                    "failed to create timetable ATO: " +
                        (failure.message ?: failure::class.java.simpleName)
                )
            }
        }
        if (ato.authorityStoppingMarginMeters != authorityStoppingMarginMeters) {
            return startupFailure("controller safety distance does not match the Dispatcher world configuration")
        }
        applyTimetableLineSpeedLimit(ato, timetable, service, runId)
        val controllerOwned = existingController == null || replaceableTrainRuns.any {
            it.ato === ato && it.controllerOwned
        }
        if (existingController == null) train.setController(ato)
        val acquisitionConsumed = service.steps.firstOrNull().let { step ->
            step is TimetableStep.At &&
                (step.action is TimetableAction.TakeFromDepot ||
                    step.action is TimetableAction.TakeFromWaypoint)
        }
        val run = ActiveRun(
            id = runId,
            service = service,
            retainedRoutes = appliedTimetablePlans[timetableId]?.retainedRoutes ?: serviceValidation.retainedRoutes,
            train = train,
            ato = ato,
            controllerOwned = controllerOwned,
            missionRevision = 0L,
            nextStepIndex = if (acquisitionConsumed) 1 else 0,
            currentWayPointId = initialOriginWayPointId,
            currentRuntime = null,
            state = TimetableServiceRunState.WAITING_MISSION,
            cycleIndex = cycleIndex,
            cycleStartDispatcherTick = cycleStartDispatcherTick,
            admissionOwnerSequence = admissionOwnerSequence.incrementAndGet(),
            infrastructureDependencyService = infrastructureDependencyService,
            lastObservedDispatcherTick = dispatcherTick,
            depotAllocation = selected.depotAllocation,
            depotReservationPending = selected.depotReservationPending,
            depotDepartureRuntime = null,
            depotDepartureReleased = selected.depotAllocation == null || !selected.depotReservationPending
        )
        run.stationDoorsSecured = when {
            replaceableTrainRuns.any { it.stationDoorsSecured == false } -> false
            replaceableTrainRuns.any { it.stationDoorsSecured == true } -> true
            else -> null
        }
        run.structural = applied?.structural ?: serviceValidation.structural
        if (timetable.formationEvents.isNotEmpty()) {
            val candidates = run.structural.sources.keys.map { it.sourceCandidate }.filter {
                it.lineage.isEmpty() && it.initialTrainId == train.id && it.originId == initialOriginWayPointId
            }.distinct()
            run.structuralCandidate = candidates.singleOrNull()
                ?: return startupFailure("acquired train has no unique validated structural candidate")
        }
        // The client owns the monotonic restriction stream published by this ATO. Keep it across
        // sequential services even after the old Basic grant ended; a new client would restart
        // that stream at revision zero and be rejected by the controller's retained source state.
        val inheritedAdmission = replaceableTrainRuns.filter {
            it.ato === ato && it.admissionClient != null
        }
        check(inheritedAdmission.size <= 1) {
            "train $trainId has multiple Basic route-admission clients for one ATO"
        }
        inheritedAdmission.singleOrNull()?.let { previous ->
            run.admissionClient = previous.admissionClient
            run.formationAdmissionBinding = previous.formationAdmissionBinding
            run.routeEditProtection = checkNotNull(previous.routeEditProtection) {
                "previous run already transferred its frozen-route edit protection"
            }
            previous.routeEditProtection = null
            run.admissionEnded = previous.admissionEnded
            if (!previous.admissionEnded) {
                run.admittedRoute = previous.admittedRoute
                run.admissionCandidate = previous.admissionCandidate
                run.nextAdmissionAttemptTick = previous.nextAdmissionAttemptTick
            }
            previous.admissionClient = null
            previous.admittedRoute = null
            previous.admissionCandidate = null
            previous.admissionEnded = true
        }
        replaceableTrainRuns.forEach { previous ->
            runs.entries.removeIf { it.value === previous }
        }
        runs[runKey] = run
        advanceService(world, run)
        if (run.state == TimetableServiceRunState.FAILED) {
            return TimetableServiceStartResult.Failure(
                run.message ?: "failed to start service: $runId"
            )
        }
        return TimetableServiceStartResult.Started(run.snapshot())
    }

    /** Attach one explicit StationCall to the active Mission before the train departs its origin. */
    @MainThread
    fun scheduleStationCall(
        world: World,
        id: TimetableServiceRunId,
        call: StationCall
    ): TimetableStationCallScheduleResult {
        val run = findRun(id)
            ?: return TimetableStationCallScheduleResult.Failure("service run not found: $id")
        if (run.stationCall != null) {
            return TimetableStationCallScheduleResult.Failure("service already has an active StationCall: $id")
        }
        if (call.missionRevision != run.missionRevision) {
            return TimetableStationCallScheduleResult.Failure(
                "StationCall revision ${call.missionRevision} does not match Mission ${run.missionRevision}"
            )
        }
        val request = StationCallRequest(
            serviceRunId = run.instanceId,
            origin = (run.currentRuntime.mission.origin as? AtoMissionOrigin.FormationOutput)
                ?: WayPointRef(run.currentWayPointId),
            call = call
        )
        val missionStartDirection = run.currentRuntime.mission.legs.firstOrNull()
            ?.plannedRoute?.expandedRoute?.segments?.firstOrNull()?.railViewRef?.direction
            ?: return TimetableStationCallScheduleResult.Failure(
                "active Mission has no directed route for StationCall ${call.callId}"
            )
        val resolved = when (val result = StationCallResolver(world).resolve(
            request,
            trainLengthMeters = run.train.cars.sumOf { it.length },
            requiredStartDirection = missionStartDirection,
            retainedRoute = stationCallRoutePrefix(world, request.origin.id, call,
                run.currentRuntime.mission.legs.map { it.plannedRoute.expandedRoute })
                ?: return TimetableStationCallScheduleResult.Failure("StationBerth ${call.berthRef} is not on the active Mission route"),
            retainedTopology = world.topologyState
        )) {
            is StationCallResolutionResult.Success -> result.resolved
            is StationCallResolutionResult.Failure -> {
                return TimetableStationCallScheduleResult.Failure(result.message)
            }
        }
        val berth = world.simpleAtoWorldData.stations.berth(call.berthRef)
            ?: return TimetableStationCallScheduleResult.Failure("berth not found: ${call.berthRef}")
        val arrivalDirection = resolved.plannedRoute.expandedRoute.segments.last().railViewRef.direction
        val missionDistance = run.currentRuntime.layout.project(
            RailPositionSnapshot(
                railId = berth.location.rail.id,
                facing = arrivalDirection,
                arcLengthInAbMeters = berth.location.arcLengthIn(ViewDirection.AB)
            )
        ) ?: return TimetableStationCallScheduleResult.Failure(
            "StationBerth ${call.berthRef} is not on the active Mission route"
        )
        if (abs(missionDistance - resolved.plannedRoute.expandedRoute.totalLength) >
            POSITION_TOLERANCE_METERS
        ) {
            return TimetableStationCallScheduleResult.Failure(
                "StationCall route is not the active Mission prefix for ${call.berthRef}"
            )
        }
        if (run.currentRuntime.headProgressMeters >
            missionDistance + run.ato.missionStopToleranceMeters
        ) {
            return TimetableStationCallScheduleResult.Failure(
                "StationCall berth ${call.berthRef} was already passed on the active Mission"
            )
        }
        if (run.waitingUntilTimetableTick != null && !run.waitingStepPending) {
            run.waitingUntilTimetableTick = null
            run.ato.removeSpeedLimit(scheduleHoldKey(run))
        }
        run.ato.setSpeedLimit(stationPendingHoldKey(run), 0.0)
        val berthArcAb = berth.location.arcLengthIn(ViewDirection.AB)
        val berthRailLength = berth.location.rail.getView(ViewDirection.AB).curve.totalArcLength
        val clearance = resolved.positionToleranceMeters + resolved.releaseDistanceMetersAfter
        run.stationCall = DispatcherStationCall(
            resolved = resolved,
            missionRuntime = run.currentRuntime,
            missionDistanceMeters = missionDistance,
            releaseDetector = RailIntervalReleaseDetector(
                listOf(
                    RailInterval(
                        berth.location.rail.id,
                        (berthArcAb - clearance).coerceAtLeast(0.0),
                        (berthArcAb + clearance).coerceAtMost(berthRailLength)
                    )
                )
            )
        )
        run.state = TimetableServiceRunState.WAITING_BERTH
        run.message = "waiting for berth ${call.berthRef}"
        val failure = tryAllocateStationCall(run)
        return if (failure == null) {
            TimetableStationCallScheduleResult.Scheduled(run.snapshot())
        } else {
            TimetableStationCallScheduleResult.Failure(failure)
        }
    }

    private fun scheduleStationCallSpec(
        world: World,
        run: ActiveRun,
        spec: StationCallSpec
    ): String? {
        val berthRef = runCatching { spec.action.berthRef }.getOrElse {
            return it.message ?: "invalid station berth"
        }
        val station = world.simpleAtoWorldData.stations[berthRef.stationId]
            ?: return "station not found: ${berthRef.stationId}"
        val berth = world.simpleAtoWorldData.stations.berth(berthRef)
            ?: return "berth not found: $berthRef"
        val dwellPlan = DwellPlanResolver.resolve(
            station = station,
            berth = berth,
            minimumDwellTicksOverride = spec.action.minimumDwellTicks
        )
        val call = StationCall(
            callId = "${run.instanceId}/station/${spec.address}",
            missionRevision = run.missionRevision,
            stationId = berthRef.stationId,
            berthRef = berthRef,
            plannedArrivalTick = null,
            dwellPlan = dwellPlan,
            stoppingOffsetMetersBeforeOverride = spec.action.stoppingOffsetMetersBefore,
            doorSideOverride = spec.action.doorSideOverride
        )
        return when (val result = scheduleStationCall(world, run.id, call)) {
            is TimetableStationCallScheduleResult.Scheduled -> {
                run.stationCall?.let { stationCall ->
                    stationCall.waitForNextTimetableAction = true
                    stationCall.stopPrograms = spec.action.stopPrograms
                }
                null
            }
            is TimetableStationCallScheduleResult.Failure -> result.reason
        }
    }

    /**
     * External operation cancellation after this world's physics and generic workers terminated.
     * Serialized by the actual World owner; no platform permission or live frame scope is needed.
     * Does not mutate controllers, grants or world resources.
     */
    fun cancelPendingActionsForShutdown(world: CoreWorld): Unit = world.executionOwner.run {
        check(boundWorld == null || boundWorld === world) { "dispatcher belongs to a different world" }
        val failures = mutableListOf<Throwable>()
        runs.values.toList().forEach { run ->
            fun attempt(action: () -> String?) {
                try { action()?.let { failures += IllegalStateException("${run.instanceId}: $it") } }
                catch (failure: Throwable) { failures += failure }
            }
            attempt { run.stopExecution?.runtime?.cancelOutstanding() }
            attempt { cancelExtensionAction(run) }
            listOfNotNull(run.stationCall?.operationTicket, run.destinationStop?.operationTicket, run.haltOperation?.ticket)
                .distinct().forEach { ticket -> attempt { operationTicket(ticket, ticket.request).cancel() } }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("world operation shutdown cancellation failed", failures.first()).also { aggregate ->
            failures.drop(1).forEach(aggregate::addSuppressed)
        }
    }

    /** Transfer speed control while retaining Dispatcher route assistance and tail release. */
    @MainThread
    fun enterManualRoutedControl(
        world: World,
        trainId: String,
        transferOwner: String
    ): TimetableControlTransferResult {
        ensureOwner(world)
        require(trainId.isNotBlank())
        require(transferOwner.isNotBlank() && transferOwner == transferOwner.trim())
        bindWorld(world)
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
        if (run.formationBlocked) return TimetableControlTransferResult.Failure("fixed formation operation owns this participant")
        if (run.stopSelection?.let { !it.committed } == true) return TimetableControlTransferResult.Failure("station selection owns a pending candidate")
        if (run.controlTransferOwner != null && run.controlTransferOwner != transferOwner) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is already controlled by ${run.controlTransferOwner}"
            )
        }
        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED &&
            run.controlTransferOwner == transferOwner
        ) {
            return TimetableControlTransferResult.ManualRouted(run.snapshot())
        }
        if (run.state == TimetableServiceRunState.REPLAN_REQUIRED &&
            run.controlTransferOwner == null
        ) {
            run.controlTransferOwner = transferOwner
            run.controlMode = TimetableControlMode.FULL_MANUAL
            run.manualController = null
            run.state = TimetableServiceRunState.SUSPENDED
            run.message = "FULL_MANUAL reacquired by $transferOwner; explicit replan remains required for ATO"
            return TimetableControlTransferResult.Suspended(run.snapshot())
        }
        if (run.controlMode == TimetableControlMode.FULL_MANUAL) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is suspended by ${run.controlTransferOwner ?: "unknown owner"}"
            )
        }
        cancelPendingOperation(run)?.let {
            return TimetableControlTransferResult.Failure(it)
        }
        cancelStationCallForControlTransfer(run)
        if (run.train.controller === run.ato) run.train.setController(null)
        run.controlTransferOwner = transferOwner
        run.controlMode = TimetableControlMode.MANUAL_ROUTED
        run.message = "MANUAL_ROUTED by $transferOwner; Dispatcher route assistance remains active"
        return TimetableControlTransferResult.ManualRouted(run.snapshot())
    }

    /** Bind the exact platform manual controller after MANUAL_ROUTED transfer installation. */
    @MainThread
    fun bindManualRoutedController(
        trainId: String,
        transferOwner: String,
        controller: TrainController
    ): Boolean {
        ensureOwner()
        val run = runs.values.firstOrNull {
            it.train.id == trainId && it.controlMode == TimetableControlMode.MANUAL_ROUTED
        } ?: return false
        if (run.controlTransferOwner != transferOwner || run.train.controller !== controller) {
            return false
        }
        run.manualController = controller
        return true
    }

    /** Disable new timetable route requests while retaining tail-based release of old resources. */
    @MainThread
    fun switchToFullManual(trainId: String, transferOwner: String): TimetableControlTransferResult {
        ensureOwner()
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
        if (run.formationBlocked) return TimetableControlTransferResult.Failure("fixed formation operation owns this participant")
        if (run.stopSelection?.let { !it.committed } == true) return TimetableControlTransferResult.Failure("station selection owns a pending candidate")
        if (run.controlTransferOwner != transferOwner ||
            run.controlMode != TimetableControlMode.MANUAL_ROUTED
        ) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is not MANUAL_ROUTED by $transferOwner"
            )
        }
        cancelPendingOperation(run)?.let {
            return TimetableControlTransferResult.Failure(it)
        }
        cancelStationCallForControlTransfer(run)
        run.controlMode = TimetableControlMode.FULL_MANUAL
        run.manualController = null
        run.state = TimetableServiceRunState.SUSPENDED
        run.message = "FULL_MANUAL by $transferOwner; no new route resources will be requested"
        return TimetableControlTransferResult.Suspended(run.snapshot())
    }

    /**
     * Detach the active ATO before a platform controller takes manual control.
     * Confirmed route and berth resources remain owned until published tail progress releases them.
     */
    @MainThread
    fun suspendForControlTransfer(
        world: World,
        trainId: String,
        transferOwner: String
    ): TimetableControlTransferResult {
        ensureOwner(world)
        require(trainId.isNotBlank())
        require(transferOwner.isNotBlank() && transferOwner == transferOwner.trim())
        bindWorld(world)
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
        if (run.formationBlocked) return TimetableControlTransferResult.Failure("fixed formation operation owns this participant")
        if (run.stopSelection?.let { !it.committed } == true) return TimetableControlTransferResult.Failure("station selection owns a pending candidate")
        if (run.state == TimetableServiceRunState.SUSPENDED &&
            run.controlTransferOwner == transferOwner
        ) {
            return TimetableControlTransferResult.Suspended(run.snapshot())
        }
        if (run.state == TimetableServiceRunState.REPLAN_REQUIRED &&
            run.controlTransferOwner == null
        ) {
            run.controlTransferOwner = transferOwner
            run.controlMode = TimetableControlMode.FULL_MANUAL
            run.manualController = null
            run.state = TimetableServiceRunState.SUSPENDED
            run.message = "FULL_MANUAL reacquired by $transferOwner; explicit replan remains required for ATO"
            return TimetableControlTransferResult.Suspended(run.snapshot())
        }
        if (run.state == TimetableServiceRunState.SUSPENDED) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is already suspended by ${run.controlTransferOwner ?: "unknown owner"}"
            )
        }
        cancelPendingOperation(run)?.let {
            return TimetableControlTransferResult.Failure(it)
        }
        cancelStationCallForControlTransfer(run)
        if (run.train.controller === run.ato) run.train.setController(null)
        run.controlTransferOwner = transferOwner
        run.controlMode = TimetableControlMode.FULL_MANUAL
        run.manualController = null
        run.state = TimetableServiceRunState.SUSPENDED
        run.message = "manual control transferred to $transferOwner; resources remain tail-released"
        return TimetableControlTransferResult.Suspended(run.snapshot())
    }

    /** Mark manual control as released. Re-entry still requires an explicit replan command. */
    @MainThread
    fun releaseControlTransfer(trainId: String, transferOwner: String): Boolean {
        ensureOwner()
        val run = runs.values.firstOrNull {
            it.train.id == trainId && it.controlTransferOwner == transferOwner &&
                it.controlMode != TimetableControlMode.AUTOMATIC
        } ?: return false
        if (run.controlTransferOwner != transferOwner) return false
        run.controlTransferOwner = null
        run.controlMode = TimetableControlMode.FULL_MANUAL
        run.manualController = null
        run.state = TimetableServiceRunState.REPLAN_REQUIRED
        run.message = "manual control released; explicit replan from a stopped WayPoint is required"
        return true
    }

    /**
     * Replan the interrupted movement from an administrator-selected current WayPoint to its
     * original destination. The transfer must be released and all old resources tail-released.
     */
    @MainThread
    fun replanService(
        world: World,
        id: TimetableServiceRunId,
        originWayPointId: String,
        cycleIndex: Long? = null
    ): TimetableReplanResult {
        ensureOwner(world)
        bindWorld(world)
        val run = findRun(id, cycleIndex)
            ?: return TimetableReplanResult.Failure("service run not found: $id")
        if (run.stationDoorsSecured == false) return TimetableReplanResult.Failure("replan requires confirmed closed doors")
        if (run.stopExecution != null) return TimetableReplanResult.Failure("interrupted station program requires explicit recovery and discard")
        if (run.physicalOriginPending) {
            return TimetableReplanResult.Failure("formation output has not reached a named stopping marker; use its retained continuation")
        }
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) {
            return TimetableReplanResult.Failure("world ATO is stalled; inspect and reset before replanning")
        }
        if (run.state != TimetableServiceRunState.REPLAN_REQUIRED ||
            run.controlTransferOwner != null
        ) {
            return TimetableReplanResult.Failure("service is not ready for replanning: $id (${run.state})")
        }
        if (run.admissionClient != null && !run.admissionEnded) {
            return TimetableReplanResult.Failure(
                "old Basic route resources are still held by the train tail"
            )
        }
        if (run.stationCall?.allocationKey != null) {
            return TimetableReplanResult.Failure(
                "old StationBerth resource is still held by the train tail"
            )
        }
        if (run.stationCall != null) {
            return TimetableReplanResult.Failure("cancelled StationCall has not completed resource cleanup")
        }
        if (run.train.isDerailed) {
            return TimetableReplanResult.Failure("train is derailed: ${run.train.id}")
        }
        if (run.train.speed != 0.0) {
            return TimetableReplanResult.Failure(
                "train must be stopped before replanning: speed=${run.train.speed}"
            )
        }
        validateAtWayPoint(world, run.train, originWayPointId)?.let {
            return TimetableReplanResult.Failure(it)
        }
        val timetable = world.simpleAtoWorldData.timetables[run.id.timetableId]
            ?: return TimetableReplanResult.Failure("timetable not found: ${run.id.timetableId}")
        val factory = atoFactory
            ?: return TimetableReplanResult.Failure("timetable ATO factory is not configured")
        val replacementAto = try {
            factory.create(run.train, world)
        } catch (failure: Exception) {
            return TimetableReplanResult.Failure(
                "failed to create replacement ATO: ${failure.message ?: failure::class.java.simpleName}"
            )
        }
        if (replacementAto.authorityStoppingMarginMeters != authorityStoppingMarginMeters) {
            return TimetableReplanResult.Failure("replacement controller safety distance does not match the Dispatcher world configuration")
        }
        applyTimetableLineSpeedLimit(replacementAto, timetable, run.service, run.id, run.activeStepAddress?.topLevelIndex ?: run.nextStepIndex)
        val target = WayPointRef(run.currentRuntime.targetWayPointId)
        if (target.id == originWayPointId) {
            run.ato = replacementAto
            run.controllerOwned = true
            run.pendingStationCallSpec = null
            run.train.setController(replacementAto)
            run.controlMode = TimetableControlMode.AUTOMATIC
            run.manualController = null
            run.currentWayPointId = target.id
            run.physicalOriginPending = false
            run.state = TimetableServiceRunState.WAITING_MISSION
            run.message = "replan origin is the interrupted destination; advancing service"
            advanceService(world, run)
            return TimetableReplanResult.Resumed(run.snapshot())
        }
        val plan = MovementPlan(
            origin = WayPointRef(originWayPointId),
            points = listOf(AtoMissionPoint.Stop(target)),
            target = target,
            priorityTick = run.currentRuntime.priorityTick,
            actionTick = timetableElapsedTicks(run),
            address = run.currentRuntime.address,
            successor = run.programCursor,
            stationCallSpec = null,
            destinationOptions = null
        )
        val request = AtoMissionRequest(run.id.toString(), nextMissionRevision(), plan.origin, plan.points,
            requiredStartDirection = capturePublishedTrainSnapshot(run).head.facing,
            requiredGoalDirections = plan.points.map { world.simpleAtoWorldData.destinations[it.target.id]?.approachDirection },
            requireUniquePath = true, pruneNonTargetWaypoints = true)
        val revalidated = when (val result = AtoMissionResolver(world).resolve(request)) {
            is AtoMissionResolutionResult.Success -> result.mission
            is AtoMissionResolutionResult.TargetUnresolved -> return TimetableReplanResult.Failure("mission target not found: ${result.target.id}")
            is AtoMissionResolutionResult.RouteUnavailable -> return TimetableReplanResult.Failure("route unavailable: ${result.reason}")
        }
        val replanTopology = world.topologyState
        val replanLegs = revalidated.legs.map { leg -> ValidatedTimetableRoute(leg.plannedRoute.expandedRoute,
            (leg.point as? AtoMissionPoint.Stop)?.offsetMetersBefore, replanTopology) }
        val replanRoute = ExpandedRoute(plan.origin.id, plan.target.id, replanLegs.flatMap { it.route.segments })
        when (val protection = checkNotNull(run.routeEditProtection).protect(replanTopology,
            replanRoute.toSnapshot("${run.instanceId}/replan", revalidated.revision, replanTopology.revision))) {
            is FrozenRouteProtectionResult.Rejected -> return TimetableReplanResult.Failure(protection.detail)
            is FrozenRouteProtectionResult.Protected -> Unit
        }
        val submission = submitMission(world, replacementAto, run.id, plan.copy(explicitReplanLegs = replanLegs), emptyMap())
        val accepted = submission.accepted
            ?: return TimetableReplanResult.Failure(submission.failure!!)
        val runtime = MissionRuntime(
            mission = accepted.mission,
            layout = accepted.layout,
            priorityTick = plan.priorityTick,
            targetWayPointId = target.id,
            address = plan.address,
            lastOdometerMeters = replacementAto.lifetimeTravelDistanceMeters
        )
        run.ato = replacementAto
        run.controllerOwned = true
        run.pendingStationCallSpec = null
        run.currentWayPointId = originWayPointId
        run.physicalOriginPending = false
        run.currentRuntime = runtime
        run.activeStepAddress = runtime.address
        run.missionRevision = accepted.mission.revision
        run.controlMode = TimetableControlMode.AUTOMATIC
        run.manualController = null
        run.state = TimetableServiceRunState.WAITING_MISSION
        run.message = "replanned from $originWayPointId to ${target.id}"
        run.train.setController(replacementAto)
        run.admissionClient = null
        run.admittedRoute = null
        run.admissionCandidate = null
        run.admissionEnded = false
        publishInitialMovementAuthority(run, runtime)
        return TimetableReplanResult.Resumed(run.snapshot())
    }

    /** Called on the main thread after the current physics epoch has published. */
    @MainThread
    fun tick(world: World) {
        ensureOwner(world)
        dispatcherTick++
        val coordinator = world.simpleAtoWorldData.atoCoordinator
        if (coordinator.isStalled) {
            runs.values.filter { it.state.isTerminal() }.forEach {
                waypointProgressListeners.remove(it.waypointProgressKey())
            }
            return
        }
        bindWorld(world)
        val routeAdmissionContext = routeAdmissionContextProvider.current()
        val routeSelectionContext = routeAdmissionContext?.observation?.let(::FrozenRouteSelectionContext)

        progressPreparingCouplings(world, routeAdmissionContext)
        progressFixedFormations(world, routeAdmissionContext)
        runs.values.toList().forEach { run ->
            if (coordinator.isStalled) return
            run.lastObservedDispatcherTick = dispatcherTick
            if (run.formationBlocked) return@forEach
            if (run.pendingFormationActivation != null) {
                try {
                    if (!activateFixedOutput(world, run)) return@forEach
                } catch (failure: Exception) {
                    stallRun(world, run, "FormationRecoveryRequired", failure.message ?: failure.javaClass.name)
                    return@forEach
                }
            }
            if (run.formationSourceAdmissionWaiting) {
                advanceService(world, run)
                return@forEach
            }
            if (run.state.isTerminal()) {
                progressSharedAdmissionOnly(world, run, routeAdmissionContext)
                tryEndSharedAdmission(world, run)
                waypointProgressListeners.remove(run.waypointProgressKey())
                return@forEach
            }
            val manualRouted = run.controlMode == TimetableControlMode.MANUAL_ROUTED
            val fullManual = run.controlMode == TimetableControlMode.FULL_MANUAL ||
                run.state == TimetableServiceRunState.SUSPENDED ||
                run.state == TimetableServiceRunState.REPLAN_REQUIRED
            val controlTransferred = manualRouted || fullManual
            if (run.currentUnitIds.isNotEmpty()) refreshUnitDisplays(world,run)
            val publishedAtoState = run.ato.publishedState
            run.lastPublishedAtoState = publishedAtoState
            val snapshot = if (controlTransferred) {
                capturePublishedTrainSnapshot(run)
            } else {
                publishedAtoState.trainSnapshot
            }
            if (!fullManual) when (run.state) {
                TimetableServiceRunState.BERTHED, TimetableServiceRunState.DOORS_OPENING,
                TimetableServiceRunState.DWELLING, TimetableServiceRunState.DOORS_CLOSING,
                TimetableServiceRunState.READY_TO_DEPART, TimetableServiceRunState.HALTING,
                TimetableServiceRunState.WAITING_EXTENSION_ACTION ->
                    if (!validateCommittedAuthority(world, run)) return@forEach
                else -> Unit
            }
            if (!controlTransferred) {
                processStationNotifications(run)
                if (coordinator.isStalled) return
            }
            updateTailProgress(run, snapshot)
            releaseDepotDeparture(world, run)
            if (run.state == TimetableServiceRunState.WAITING_EXTENSION_ACTION) {
                pollExtensionAction(world, run)
                return@forEach
            }
            if (coordinator.isStalled) return
            releasePassedBerth(run)
            if (coordinator.isStalled) return
            if (run.stationCall == null) {
                run.deferredStationCallSpec?.let { spec ->
                    run.deferredStationCallSpec = null
                    scheduleStationCallSpec(world, run, spec)?.let { reason ->
                        stallRun(world, run, "InfrastructureInvariantViolation", reason)
                        return@forEach
                    }
                }
            }

            if (run.state.isTerminal()) {
                finishControllerIfReleased(run, snapshot)
                return@forEach
            }
            if (run.train.isDerailed) {
                val derailReason = snapshot.derailReason ?: run.train.derailReason ?: "reason unavailable"
                if (manualRouted) {
                    stallManualRouted(
                        world,
                        run,
                        snapshot,
                        "MANUAL_ROUTED train derailed: $derailReason"
                    )
                } else {
                    stallRun(
                        world,
                        run,
                        "TrainDerailment",
                        "active timetable train derailed: $derailReason",
                        snapshot
                    )
                }
                return@forEach
            }
            if (fullManual) {
                progressSharedAdmissionOnly(world, run, routeAdmissionContext)
                tryEndSharedAdmission(world, run)
                return@forEach
            }
            if (manualRouted) {
                processManualRoutedWithAdmission(
                    world,
                    run,
                    snapshot,
                    routeAdmissionContext,
                    routeSelectionContext
                )
                return@forEach
            }
            if (run.train.controller !== run.ato) {
                stallRun(
                    world,
                    run,
                    "ControllerIdentityViolation",
                    "active timetable ATO controller was replaced",
                    snapshot
                )
                return@forEach
            }
            run.stopSelection?.takeIf { !it.committed && it.key == null &&
                run.state !in setOf(TimetableServiceRunState.STOPPING, TimetableServiceRunState.STOPPED) }?.let { selection ->
                run.programCursor = selection.cursor
                prepareSelectedStop(world, run, selection.step, selection.action)?.let { startMovementPlan(world, run, it) }
                return@forEach
            }
            if (run.state == TimetableServiceRunState.WAITING_TIMETABLE_TIME) {
                val scheduledTick = run.waitingUntilTimetableTick
                if (scheduledTick == null) {
                    stallRun(
                        world,
                        run,
                        "ResourceIdentityViolation",
                        "WAITING_TIMETABLE_TIME run has no scheduled Tick"
                    )
                    return@forEach
                }
                if (timetableElapsedTicks(run) < scheduledTick) return@forEach
                run.waitingUntilTimetableTick = null
                run.ato.removeSpeedLimit(scheduleHoldKey(run))
                run.state = TimetableServiceRunState.WAITING_MISSION
                run.message = null
                if (run.waitingStepPending) {
                    run.waitingStepPending = false
                    advanceService(world, run)
                    return@forEach
                }
                recordScheduledAction(run, scheduledTick)
                run.pendingStationCallSpec?.let { spec ->
                    run.pendingStationCallSpec = null
                    scheduleStationCallSpec(world, run, spec)?.let { reason ->
                        stallRun(world, run, "InfrastructureInvariantViolation", reason)
                        return@forEach
                    }
                    run.requiredNextStartDirection = null
                }
            }
            if (run.state == TimetableServiceRunState.STOPPING) {
                if (run.ato.isStopped(snapshot.speedMetersPerSecond)) {
                    retireRunDisplays(world,run)
                    run.state = TimetableServiceRunState.STOPPED
                    run.message = run.message ?: "stopped by operator"
                    finishControllerIfReleased(run, snapshot)
                }
                return@forEach
            }
            if (run.stopExecution != null) {
                if (run.state == TimetableServiceRunState.REVERSING) processReverse(world, run, routeSelectionContext)
                else if (!validateCommittedAuthority(world, run)) return@forEach
                if (run.state != TimetableServiceRunState.FAILED) processStopProgram(world, run)
                return@forEach
            }
            val stationCall = run.stationCall
            if (stationCall != null && !stationCall.submittedToAto) {
                tryAllocateStationCall(run)
                if (!stationCall.submittedToAto) return@forEach
                if (run.waitingUntilTimetableTick?.let { timetableElapsedTicks(run) < it } == true) {
                    run.state = TimetableServiceRunState.WAITING_TIMETABLE_TIME
                    run.message = "StationCall active; waiting for timetable tick ${run.waitingUntilTimetableTick}"
                    return@forEach
                }
            }
            if (stationCall != null &&
                (run.state == TimetableServiceRunState.DOORS_OPENING ||
                    run.state == TimetableServiceRunState.DOORS_CLOSING)
            ) {
                if (processStationOperation(run, stationCall)) return@forEach
            }
            val destinationStop = run.destinationStop
            if (destinationStop != null &&
                (run.state == TimetableServiceRunState.DOORS_OPENING ||
                    run.state == TimetableServiceRunState.DOORS_CLOSING)
            ) {
                if (processDestinationOperation(world, run, destinationStop)) return@forEach
            }
            if (run.state == TimetableServiceRunState.HALTING) {
                processHaltOperation(world, run)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.REVERSING) {
                processReverse(world, run, routeSelectionContext)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.MANUAL_INTERVENTION) return@forEach
            if (run.state == TimetableServiceRunState.BERTHED &&
                stationCall != null && dispatcherTick > stationCall.stateEnteredDispatcherTick
            ) {
                if (stationCall.stopPrograms != null) {
                    run.currentWayPointId = stationCall.stopPrograms!!.destinationId
                        ?: OperationalDestinationIds.stationBerth(stationCall.resolved.berthRef.stationId, stationCall.resolved.berthRef.berthId)
                    run.physicalOriginPending = false
                    beginStopProgram(world, run, stationCall.stopPrograms!!, stationCall.resolved.doorSide,
                        stationCall.resolved.call.dwellPlan.minimumDwellTicks, StopProgramLocation.STATION)
                } else if (stationCall.resolved.doorSide == DoorSide.NONE) {
                    enterStationDwelling(run, stationCall)
                } else {
                    beginStationOperation(
                        run,
                        stationCall,
                        TrainOperation.OpenDoors(stationCall.resolved.doorSide),
                        TimetableServiceRunState.DOORS_OPENING
                    )
                }
                return@forEach
            }
            if (run.state == TimetableServiceRunState.DWELLING &&
                stationCall != null && !stationCall.departed
            ) {
                if (dispatcherTick >= stationCall.earliestDepartureDispatcherTick) {
                    val nextTick = if (stationCall.waitForNextTimetableAction) {
                        nextActionTick(run)
                    } else {
                        0L
                    }
                    if (nextTick == null) {
                        completeOpenTerminalStation(run, stationCall)
                    } else if (timetableElapsedTicks(run) >= nextTick) {
                        if (stationCall.resolved.doorSide == DoorSide.NONE) {
                            enterReadyToDepart(run, stationCall)
                        } else {
                            beginStationOperation(
                                run,
                                stationCall,
                                TrainOperation.CloseDoors(stationCall.resolved.doorSide),
                                TimetableServiceRunState.DOORS_CLOSING
                            )
                        }
                    }
                    return@forEach
                }
            } else if (run.state == TimetableServiceRunState.DWELLING && destinationStop != null) {
                run.dwellTicksRemaining = (destinationStop.earliestDepartureDispatcherTick - dispatcherTick).coerceAtLeast(0L)
                if (run.dwellTicksRemaining == 0L) {
                    val nextTick = nextActionTick(run)
                    if (nextTick == null) {
                        completeOpenTerminalDestination(run, destinationStop)
                    } else if (timetableElapsedTicks(run) >= nextTick) {
                        val side = destinationStop.options.doorSide ?: DoorSide.NONE
                        if (side == DoorSide.NONE) {
                            finishDestinationStop(world, run, destinationStop)
                        } else {
                            beginDestinationOperation(
                                world,
                                run,
                                destinationStop,
                                TrainOperation.CloseDoors(side),
                                TimetableServiceRunState.DOORS_CLOSING
                            )
                        }
                    }
                }
                return@forEach
            } else if (run.state == TimetableServiceRunState.DWELLING) {
                if (run.dwellTicksRemaining > 0L) run.dwellTicksRemaining--
                if (run.dwellTicksRemaining == 0L) advanceService(world, run)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.READY_TO_DEPART && stationCall != null) {
                if (isTerminalStationCall(stationCall)) {
                    run.currentWayPointId = stationCall.missionRuntime.targetWayPointId
                    run.physicalOriginPending = false
                    if (run.programCursor.isComplete) {
                        run.state = TimetableServiceRunState.COMPLETED
                        run.message = "service completed berthed at ${stationCall.resolved.berthRef}"
                    } else {
                        advanceService(world, run)
                    }
                    return@forEach
                }
                if (hasAuthorityBeyondStation(run, stationCall)) {
                    stationCall.plannedDepartureTimetableTick?.let {
                        recordScheduledAction(run, it)
                    }
                    run.ato.grantStationDeparture(
                        stationCall.resolved.call.callId,
                        stationCall.resolved.call.missionRevision
                    )
                    stationCall.departureGranted = true
                    stationCall.stateEnteredDispatcherTick = dispatcherTick
                    run.state = TimetableServiceRunState.DEPARTING
                    run.message = "departing ${stationCall.resolved.berthRef}"
                } else {
                    run.message = "doors secured; waiting for route beyond ${stationCall.resolved.berthRef}"
                }
            }
            if (run.state == TimetableServiceRunState.DEPARTED && stationCall != null &&
                dispatcherTick > stationCall.stateEnteredDispatcherTick
            ) {
                run.state = TimetableServiceRunState.RUNNING
                run.message = null
                if (stationCall.allocationKey == null) run.stationCall = null
            }

            val mission = publishedAtoState.currentMission
            if (mission?.revision != run.missionRevision) {
                setRouteState(run, TimetableServiceRunState.WAITING_MISSION, null)
                return@forEach
            }
            val runtime = run.currentRuntime
            val headProgress = runtime.layout.project(snapshot.head, runtime.headProgressMeters)
                ?: physicalMissionProgress(world, run, runtime, snapshot)
                ?: terminalProgressFallback(world, run, runtime)
            if (headProgress == null) {
                stallRun(
                    world,
                    run,
                    "InfrastructureInvariantViolation",
                    "train head is outside planned Mission route",
                    snapshot
                )
                return@forEach
            }
            runtime.headProgressMeters = headProgress
            runtime.tailProgressMeters = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
                ?: runtime.tailProgressMeters
            reportWaypointProgress(world, run, runtime, snapshot)
            if (coordinator.isStalled) return@forEach

            val completedDestinationStop = run.destinationStop?.takeIf {
                it.missionRuntime === runtime && destinationStopCompleted(run, runtime, snapshot, it)
            }
            if (completedDestinationStop != null && !completedDestinationStop.started) {
                beginDestinationStop(world, run, completedDestinationStop)
                return@forEach
            }
            if (missionCompleted(world, run, runtime, snapshot) &&
                (run.stationCall == null || run.stationCall?.departed == true) &&
                run.destinationStop == null
            ) {
                run.ato.setSpeedLimit(stopKey(run), 0.0)
                if (runtime === run.storageRuntime) {
                    if (!tryCompleteStorage(world, run, runtime)) {
                        return@forEach
                    }
                }
                run.currentWayPointId = runtime.targetWayPointId
                run.physicalOriginPending = false
                advanceService(world, run)
                return@forEach
            }
            if (coordinator.isStalled) return@forEach
            processSharedRouteAdmission(
                world,
                run,
                runtime,
                snapshot,
                routeAdmissionContext,
                routeSelectionContext
            )
        }

        if (coordinator.isStalled) return
        inspectionRuntime.retainTrains(
            runs.values.asSequence()
                .filter { !it.admissionEnded && (it.admittedRoute != null || it.admissionCandidate != null) }
                .map { it.train.id }
                .toSet()
        )
        releaseDrainedAppliedPlans()
        if (coordinator.isStalled) return
        startDueCycles(world)
    }

    private fun evaluateAutomaticActivation(
        world: World,
        timetableId: String,
        service: TimetableService,
        cycleIndex: Long,
        clock: TimetableClock
    ): AutomaticActivation = when (val activation = service.activation) {
        TimetableServiceActivation.Scheduled -> AutomaticActivation.START
        TimetableServiceActivation.Manual -> AutomaticActivation.SKIP
        is TimetableServiceActivation.Conditional -> {
            val cycleStart = Math.addExact(
                clock.epochDispatcherTick,
                Math.multiplyExact(cycleIndex, clock.periodTicks)
            )
            val elapsed = (dispatcherTick - cycleStart).coerceAtLeast(0L)
            val context = TimetableRuntimeContext(
                timetableId = timetableId,
                serviceId = service.id,
                cycleIndex = cycleIndex,
                timetableElapsedTicks = elapsed,
                scheduledTick = null,
                latenessTicks = 0L,
                currentWayPointId = null,
                train = null
            )
            when (val result = world.simpleAtoWorldData.timetableExtensions.evaluate(
                activation.condition,
                context,
                TimetableConditionScope.SERVICE_ACTIVATION
            )) {
                TimetableConditionResult.True -> AutomaticActivation.START
                TimetableConditionResult.False -> when (activation.falseBehavior) {
                    TimetableActivationFalseBehavior.SKIP_CYCLE -> AutomaticActivation.SKIP
                    TimetableActivationFalseBehavior.WAIT_UNTIL_TRUE ->
                        AutomaticActivation.WAIT("activation condition is false")
                }
                is TimetableConditionResult.Unavailable -> when (activation.falseBehavior) {
                    TimetableActivationFalseBehavior.WAIT_UNTIL_TRUE ->
                        AutomaticActivation.WAIT(result.reason)
                    TimetableActivationFalseBehavior.SKIP_CYCLE ->
                        AutomaticActivation.ERROR(result.reason)
                }
                is TimetableConditionResult.Error -> AutomaticActivation.ERROR(result.message)
            }
        }
    }

    private fun startDueCycles(world: World) {
        for ((timetableId, clock) in timetableClocks.toList().sortedBy { it.first }) {
            if (timetableId in recoveryRequiredDefinitions) continue
            val timetable = world.simpleAtoWorldData.timetables[timetableId]
            if (timetable == null) {
                stallTimetableRuntime(
                    world,
                    TimetableServiceRunId(timetableId, "<cycle-${clock.nextCycleIndex}>"),
                    "TimetableDisappeared",
                    "active timetable disappeared: $timetableId"
                )
                return
            }

            // A waiting service retains its original cycle, but does not block unrelated services
            // or the timetable clock from creating later cycles.
            for ((serviceId, cycleIndex) in clock.pendingActivationCycles.toMap()) {
                if (serviceId in clock.disabledServiceIds) {
                    clock.pendingActivationCycles.remove(serviceId)
                    continue
                }
                val service = timetable.services.firstOrNull { it.id == serviceId }
                if (service == null) {
                    stallTimetableRuntime(
                        world,
                        TimetableServiceRunId(timetableId, serviceId),
                        "TimetableDisappeared",
                        "pending activation service disappeared: $timetableId/$serviceId"
                    )
                    return
                }
                val runId = TimetableServiceRunId(timetableId, serviceId)
                val runKey = TimetableServiceRunKey(runId, clock.epochDispatcherTick, cycleIndex)
                if (runs.containsKey(runKey)) {
                    clock.pendingActivationCycles.remove(serviceId)
                    continue
                }
                when (val activation = evaluateAutomaticActivation(
                    world, timetableId, service, cycleIndex, clock
                )) {
                    AutomaticActivation.START -> when (val result = startServiceForCycle(
                        world, timetableId, serviceId, cycleIndex
                    )) {
                        is TimetableServiceStartResult.Started -> {
                            clock.pendingActivationCycles.remove(serviceId)
                            clock.lastCycleStartFailure = null
                        }
                        is TimetableServiceStartResult.Failure -> {
                            clock.lastCycleStartFailure = result.reason
                            if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
                        }
                    }
                    AutomaticActivation.SKIP -> clock.pendingActivationCycles.remove(serviceId)
                    is AutomaticActivation.WAIT -> clock.lastCycleStartFailure = activation.reason
                    is AutomaticActivation.ERROR -> {
                        stallTimetableRuntime(
                            world, runId, "TimetableConditionFailure", activation.reason
                        )
                        return
                    }
                }
            }

            val elapsed = (dispatcherTick - clock.epochDispatcherTick).coerceAtLeast(0L)
            val currentCycleIndex = elapsed / clock.periodTicks
            while (clock.nextCycleIndex <= currentCycleIndex) {
                val cycleIndex = clock.nextCycleIndex
                var complete = true
                for (service in timetable.services) {
                    if (service.id in clock.disabledServiceIds ||
                        service.id in clock.pendingActivationCycles
                    ) continue
                    val runId = TimetableServiceRunId(timetableId, service.id)
                    val runKey = TimetableServiceRunKey(runId, clock.epochDispatcherTick, cycleIndex)
                    if (runs.containsKey(runKey)) continue
                    when (val activation = evaluateAutomaticActivation(
                        world, timetableId, service, cycleIndex, clock
                    )) {
                        AutomaticActivation.START -> Unit
                        AutomaticActivation.SKIP -> continue
                        is AutomaticActivation.WAIT -> {
                            clock.pendingActivationCycles[service.id] = cycleIndex
                            clock.lastCycleStartFailure = activation.reason
                            continue
                        }
                        is AutomaticActivation.ERROR -> {
                            stallTimetableRuntime(
                                world, runId, "TimetableConditionFailure", activation.reason
                            )
                            return
                        }
                    }
                    when (val result = startServiceForCycle(
                        world, timetableId, service.id, cycleIndex
                    )) {
                        is TimetableServiceStartResult.Started -> Unit
                        is TimetableServiceStartResult.Failure -> {
                            clock.lastCycleStartFailure = result.reason
                            complete = false
                            if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
                        }
                    }
                }
                if (!complete) return
                clock.nextCycleIndex = Math.incrementExact(cycleIndex)
                if (clock.pendingActivationCycles.isEmpty()) clock.lastCycleStartFailure = null
            }
        }
    }

    @MainThread
    fun stopService(
        world: World,
        id: TimetableServiceRunId,
        cycleIndex: Long? = null
    ): Boolean {
        ensureOwner(world)
        bindWorld(world)
        val run = findRun(id, cycleIndex) ?: return false
        if (run.canBeReplaced() || run.state == TimetableServiceRunState.STOPPED ||
            run.isStoredTerminal()
        ) return true
        preparingCouplings.values.singleOrNull { it.moving === run || it.stationary === run }?.let { preparation ->
            preparingCouplings.remove(preparation.id)
            listOf(preparation.moving, preparation.stationary).forEach { participant ->
                participant.formationBlocked = false
                participant.formationSourceAdmissionWaiting = false
                participant.ato.setSpeedLimit(stopKey(participant), 0.0)
                retireRunDisplays(world,participant)
                participant.state = TimetableServiceRunState.STOPPED
                participant.message = "coupling preparation cancelled; source resources retained"
            }
            return true
        }
        cancelPendingOperation(run)?.let { reason ->
            stallRun(world, run, "TrainOperationFailure", reason)
            return false
        }
        run.stopSelection?.takeIf { !it.committed }?.cancelled = true
        run.stopSelection?.takeIf { !it.committed && run.admissionClient?.state?.pending != true }?.let { selection ->
            selection.key?.let { check(berthAllocator.release(it)) { "tentative station allocation disappeared" } }
            run.stopSelection = null
            run.ato.removeSpeedLimit(selectionHoldKey(run))
        }
        run.ato.setSpeedLimit(stopKey(run), 0.0)
        run.state = TimetableServiceRunState.STOPPING
        run.message = "operator stop requested; route remains held until the tail clears"
        return true
    }

    @MainThread
    fun status(
        id: TimetableServiceRunId,
        cycleIndex: Long? = null
    ): TimetableServiceRunSnapshot? = findRun(id, cycleIndex)?.snapshot()

    @MainThread
    fun statuses(): List<TimetableServiceRunSnapshot> = runs.values
        .sortedWith(compareBy({ it.id.timetableId }, { it.id.serviceId }, { it.cycleStartDispatcherTick }))
        .map(ActiveRun::snapshot)

    /** Actual registered membership also resolves held formation children with no running duty. */
    @MainThread
    fun unitDisplaysForTrain(world: World, trainId: String, timetableId: String, cycleIndex: Long): List<TimetableUnitDisplaySnapshot> {
        ensureOwner(world)
        check(boundWorld === world) { "display runtime belongs to a different or unbound world" }
        val train = requireNotNull(world.getTrain(trainId) as? CoreTrain) { "train not found: $trainId" }
        val timetable = requireNotNull(world.simpleAtoWorldData.timetables[timetableId]) { "timetable not found: $timetableId" }
        val cars = train.cars.map { it.id }.let { if (train.isReversed) it.reversed() else it }
        val units = world.simpleAtoWorldData.depots.fixedUnitsForFormation(cars).map { it.id }
        return displayRuntime.snapshots(timetable,cycleIndex,units)
    }

    private fun findRun(id: TimetableServiceRunId, cycleIndex: Long? = null): ActiveRun? =
        runs.values
            .filter { it.id == id && (cycleIndex == null || it.cycleIndex == cycleIndex) }
            .maxByOrNull { it.cycleStartDispatcherTick }

    /**
     * Discard terminal failed runs while the World Stall is still latched. This prevents a failed
     * SimpleATO, station hold, or resource allocation from surviving an administrator ATO reset.
     * Healthy runs remain untouched and may resume after the world latch is reset.
     */
    @MainThread
    fun discardFailedRunsForAtoReset(world: World): Int {
        ensureOwner(world)
        check(world.simpleAtoWorldData.atoCoordinator.isStalled) {
            "failed runs may only be discarded while the World ATO is stalled"
        }
        val failedRuns = runs.values.filter { it.state == TimetableServiceRunState.FAILED }
        if (failedRuns.isEmpty()) return 0
        failedRuns.forEach { run -> discardRun(world, run) }
        return failedRuns.size
    }

    /**
     * Destructively discard every run and clock owned by one timetable. This is the backend for an
     * administrator-confirmed force reset. It refuses runs with an active Basic grant; controllers,
     * berth locks, operations and storage reservations are removed only after route resources have
     * already tail-released through the admission runtime.
     */
    @MainThread
    fun forceDiscardTimetable(world: World, timetableId: String): Int {
        ensureOwner(world)
        require(timetableId.isNotBlank())
        bindWorld(world)
        check((fixedFormations.values.none { it.source.id.timetableId == timetableId } && preparingCouplings.values.none { it.coordinator.id.timetableId == timetableId })) {
            "cannot discard a timetable while structural output protections remain active"
        }
        val selected = runs.values.filter { it.id.timetableId == timetableId }
        // Validate the whole selection before discarding any run or its controller.
        selected.forEach { run ->
            check(run.admissionClient == null || run.admissionEnded) {
                "cannot discard ${run.id} while Basic route resources remain active"
            }
        }
        selected.forEach { run -> discardRun(world, run) }
        val recovered = recoveryRequiredRuns.count { it.timetableId == timetableId }
        recoveryRequiredRuns.removeIf { it.timetableId == timetableId }
        recoveryRequiredDefinitions.remove(timetableId)
        timetableClocks.remove(timetableId)
        releaseDrainedAppliedPlans()
        return selected.size + recovered
    }

    /**
     * Abandon a released ad-hoc navigation before another `/jr navigate` is started. Normal
     * timetable services are deliberately excluded: they still require explicit replan/depot
     * recovery. The caller has already released any managed manual-control session.
     */
    @MainThread
    fun discardReleasedManualNavigation(world: World, trainId: String): Int {
        ensureOwner(world)
        require(trainId.isNotBlank())
        val obsolete = runs.values.filter { run ->
            run.train.id == trainId && run.id.timetableId.startsWith(MANUAL_NAVIGATION_PREFIX) &&
                run.controlTransferOwner == null &&
                (run.state == TimetableServiceRunState.REPLAN_REQUIRED ||
                    run.state == TimetableServiceRunState.COMPLETED)
        }
        obsolete.forEach { run -> discardRun(world, run) }
        return obsolete.size
    }

    private fun discardRun(world: World, run: ActiveRun) {
        bindWorld(world)
        run.stopExecution?.runtime?.cancelOutstanding()?.let { error("cannot discard pending station work: $it") }
        check(run.admissionClient == null || run.admissionEnded) {
            "cannot discard ${run.id} while Basic route resources remain active"
        }
        listOfNotNull(
            run.stationCall?.operationTicket,
            run.destinationStop?.operationTicket,
            run.haltOperation?.ticket
        ).distinct().forEach { ticket -> runCatching { ticket.cancel() } }
        run.stopSelection?.takeIf { !it.committed }?.key?.let { berthAllocator.release(it) }
        run.stopSelection = null
        run.ato.removeSpeedLimit(selectionHoldKey(run))
        run.stationCall?.allocationKey?.let { key ->
            berthAllocator.release(key)
            run.stationCall?.allocationKey = null
        }
        run.storageReservation?.let { reservation ->
            if (run.physicalStorageOnly) {
                physicalStorageReservations.remove(reservation.stablingPointRef, run.instanceId)
            } else {
                world.simpleAtoWorldData.depots.cancelStorage(reservation)
            }
            run.storageReservation = null
        }
        run.ato.removeSpeedLimit(stopKey(run))
        run.ato.removeSpeedLimit(stationProgramHoldKey(run))
        run.ato.removeSpeedLimit(stationPendingHoldKey(run))
        run.ato.removeSpeedLimit(scheduleHoldKey(run))
        run.ato.removeSpeedLimit(timetableLineSpeedKey(run.id))
        if (run.train.controller === run.ato) run.train.setController(null)
        waypointProgressListeners.remove(run.waypointProgressKey())
        runs.entries.removeIf { (_, candidate) -> candidate === run }
        displayRuntime.retainCycles(runs.values.map { it.id.timetableId to it.cycleIndex }.toSet() +
            fixedFormations.values.map { it.source.id.timetableId to it.source.cycleIndex })
    }

    /** Preflight forced resource cleanup used only by an administrator-confirmed Depot Reset. */
    @MainThread
    override fun validateDepotReset(world: World, trainIds: Set<String>): String? {
        bindWorld(world)
        val selectedRuns = runs.values.filter { it.train.id in trainIds }
        if (selectedRuns.any { it.admissionClient != null && !it.admissionEnded }) {
            return "Basic route resources must tail-release before Depot Reset"
        }
        val berthKeys = berthAllocator.allocations().toSet()
        val missingBerth = selectedRuns.mapNotNull { it.stationCall?.allocationKey }
            .firstOrNull { it !in berthKeys }
        if (missingBerth != null) return "StationBerth allocation disappeared: $missingBerth"
        return null
    }

    /**
     * Force-stop selected runs after every reset preflight and train relocation succeeded.
     * The caller must keep the World Stall latched; this operation never resets ATO state.
     */
    @MainThread
    override fun applyDepotReset(world: World, trainIds: Set<String>): DepotResetDispatchSummary {
        ensureOwner(world)
        check(world.simpleAtoWorldData.atoCoordinator.isStalled) {
            "Depot Reset is only legal while the World ATO is stalled"
        }
        validateDepotReset(world, trainIds)?.let { error(it) }
        val selectedRuns = runs.values.filter { it.train.id in trainIds }
        val progress = TimetableDepotResetProgress()
        try {
            selectedRuns.forEach { run ->
                cancelPendingOperation(run)?.let(::error)
            }
            val berthKeys = berthAllocator.allocations()
                .filter { it.owner.trainId in trainIds }
            berthKeys.forEach { key ->
                check(berthAllocator.release(key)) { "StationBerth disappeared during reset: $key" }
                progress.recordBerthRelease()
                selectedRuns.forEach { run ->
                    if (run.stationCall?.allocationKey == key) run.stationCall?.allocationKey = null
                }
            }
            selectedRuns.forEach { run ->
                run.ato.removeSpeedLimit(stopKey(run))
                run.ato.removeSpeedLimit(stationPendingHoldKey(run))
                run.ato.removeSpeedLimit(scheduleHoldKey(run))
                run.ato.removeSpeedLimit(timetableLineSpeedKey(run.id))
                if (run.train.controller === run.ato) run.train.setController(null)
            }
            val stoppedTimetables = selectedRuns.mapTo(LinkedHashSet()) { it.id.timetableId }
            stoppedTimetables.forEach { timetableId ->
                timetableClocks.remove(timetableId)
                progress.recordStoppedTimetable(timetableId)
            }
            runs.entries.removeIf { (_, run) ->
                (run.train.id in trainIds).also { removed ->
                    if (removed) progress.recordRunRemoval()
                }
            }
            releaseDrainedAppliedPlans()
            return progress.snapshot()
        } catch (failure: Exception) {
            throw DepotResetDispatchApplyException(progress.snapshot(), failure)
        }
    }

    private fun tryAllocateStationCall(run: ActiveRun): String? {
        val stationCall = run.stationCall ?: return null
        if (stationCall.submittedToAto) return null
        val call = stationCall.resolved.call
        val key = stationCall.allocationKey ?: StationBerthAllocationKey(
            owner = StationBerthOwner(run.instanceId, run.train.id, call.callId),
            berthRef = call.berthRef,
            missionRevision = call.missionRevision
        )
        return when (val result = berthAllocator.allocate(key)) {
            is StationBerthAllocationResult.Confirmed -> {
                stationCall.allocationKey = result.key
                when (val submission = run.ato.submitStationCall(stationCall.resolved)) {
                    is StationCallSubmissionResult.Accepted -> {
                        stationCall.resolved = submission.resolved
                        stationCall.submittedToAto = true
                        run.ato.removeSpeedLimit(stationPendingHoldKey(run))
                        run.message = "StationCall ${call.callId} awaiting physics activation"
                        null
                    }
                    is StationCallSubmissionResult.StaleRevision -> {
                        check(berthAllocator.release(result.key)) {
                            "new StationBerth allocation disappeared during stale submission"
                        }
                        stationCall.allocationKey = null
                        val reason = "StationCall revision ${submission.submittedRevision} is stale"
                        stallRun(
                            requireNotNull(boundWorld),
                            run,
                            "ResourceIdentityViolation",
                            reason
                        )
                        reason
                    }
                    is StationCallSubmissionResult.ResolutionFailure -> {
                        check(berthAllocator.release(result.key)) {
                            "new StationBerth allocation disappeared during failed submission"
                        }
                        stationCall.allocationKey = null
                        stallRun(
                            requireNotNull(boundWorld),
                            run,
                            "InfrastructureInvariantViolation",
                            submission.message
                        )
                        submission.message
                    }
                }
            }
            is StationBerthAllocationResult.Conflict -> {
                run.state = TimetableServiceRunState.WAITING_BERTH
                run.message = "berth ${result.berthRef} held by ${result.heldBy.owner.serviceRunId}"
                null
            }
        }
    }

    private fun cancelStationCallForControlTransfer(run: ActiveRun) {
        run.destinationStop = null
        val stationCall = run.stationCall ?: return
        stationCall.cancelledForControlTransfer = true
        run.ato.removeSpeedLimit(stationPendingHoldKey(run))
        if (stationCall.allocationKey == null) {
            run.stationCall = null
        }
    }

    private fun beginStationOperation(
        run: ActiveRun,
        stationCall: DispatcherStationCall,
        operation: TrainOperation,
        state: TimetableServiceRunState
    ) {
        if (!validateCommittedAuthority(requireNotNull(boundWorld), run)) return
        val phase = when (operation) {
            is TrainOperation.OpenDoors -> "open-doors"
            is TrainOperation.CloseDoors -> "close-doors"
            is TrainOperation.SetCabLights -> "cab-lights"
            is TrainOperation.ExecuteModelAction -> "model-action-${operation.actionName}"
            is TrainOperation.Announce -> "announce"
        }
        cancelPendingOperation(run)?.let { reason ->
            enterManualIntervention(run, stationCall, reason)
            return
        }
        stationCall.operationTicket = null
        stationCall.operationTicketId = null
        stationCall.operationStartedDispatcherTick = Long.MAX_VALUE
        val call = stationCall.resolved.call
        val request = TrainOperationRequest(
            requestId = "${call.callId}:$phase:${call.missionRevision}",
            serviceRunId = run.instanceId,
            stationCallId = call.callId,
            trainId = run.train.id,
            missionRevision = call.missionRevision,
            operation = operation
        )
        val result = try {
            operationGateway.execute(request)
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "beginStationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            enterManualIntervention(
                run,
                stationCall,
                "$phase gateway exception: ${failure.message ?: failure::class.java.simpleName}"
            )
            return
        }
        when (result) {
            is TrainOperationExecutionResult.Accepted -> {
                if (result.ticket.request != request) {
                    enterManualIntervention(
                        run,
                        stationCall,
                        withCancellationResult(
                            "$phase returned a ticket for another request",
                            result.ticket,
                            request.requestId
                        )
                    )
                    return
                }
                val initialSnapshot = try {
                    result.ticket.snapshot()
                } catch (failure: Exception) {
                    LOGGER.log(java.util.logging.Level.WARNING, "beginStationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
                    enterManualIntervention(
                        run,
                        stationCall,
                        withCancellationResult(
                            "$phase ticket exception: " +
                                (failure.message ?: failure::class.java.simpleName),
                            result.ticket,
                            request.requestId
                        )
                    )
                    return
                }
                if (initialSnapshot.requestId != request.requestId) {
                    enterManualIntervention(
                        run,
                        stationCall,
                        withCancellationResult(
                            "$phase ticket request identity mismatch",
                            result.ticket,
                            initialSnapshot.ticketId
                        )
                    )
                    return
                }
                stationCall.operationTicket = result.ticket
                stationCall.operationTicketId = initialSnapshot.ticketId
                stationCall.operationStartedDispatcherTick = dispatcherTick
                stationCall.stateEnteredDispatcherTick = dispatcherTick
                run.state = state
                run.message = "$phase requested for ${stationCall.resolved.berthRef}"
            }
            is TrainOperationExecutionResult.Rejected -> enterManualIntervention(
                run,
                stationCall,
                "$phase rejected: ${result.reason}"
            )
        }
    }

    /** Returns true when this Tick produced a state transition and later phases must wait. */
    private fun processStationOperation(
        run: ActiveRun,
        stationCall: DispatcherStationCall
    ): Boolean {
        val ticket = stationCall.operationTicket
        if (ticket == null) {
            enterManualIntervention(run, stationCall, "active door operation has no ticket")
            return true
        }
        val snapshot = try {
            ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "processStationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            enterManualIntervention(
                run,
                stationCall,
                withCancellationResult(
                    "operation ticket exception: " +
                        (failure.message ?: failure::class.java.simpleName),
                    ticket,
                    stationCall.operationTicketId ?: ticket.request.requestId
                )
            )
            return true
        }
        if (snapshot.requestId != ticket.request.requestId ||
            snapshot.ticketId != stationCall.operationTicketId
        ) {
            enterManualIntervention(
                run,
                stationCall,
                withCancellationResult(
                    "operation ticket identity changed",
                    ticket,
                    snapshot.ticketId
                )
            )
            return true
        }
        when (snapshot.state) {
            TrainOperationTicketState.PENDING -> {
                if (dispatcherTick - stationCall.operationStartedDispatcherTick >= operationTimeoutTicks) {
                    enterManualIntervention(
                        run,
                        stationCall,
                        withCancellationResult(
                            "operation ${snapshot.ticketId} timed out after $operationTimeoutTicks ticks",
                            ticket,
                            snapshot.ticketId
                        )
                    )
                    return true
                }
                return false
            }
            TrainOperationTicketState.SUCCEEDED -> {
                if (run.state == TimetableServiceRunState.DOORS_OPENING) run.stationDoorsSecured = false
                if (run.state == TimetableServiceRunState.DOORS_CLOSING) run.stationDoorsSecured = true
                when (run.state) {
                    TimetableServiceRunState.DOORS_OPENING -> enterStationDwelling(run, stationCall)
                    TimetableServiceRunState.DOORS_CLOSING -> enterReadyToDepart(run, stationCall)
                    else -> enterManualIntervention(
                        run,
                        stationCall,
                        "operation ${snapshot.ticketId} completed in invalid state ${run.state}"
                    )
                }
                return true
            }
            TrainOperationTicketState.FAILED,
            TrainOperationTicketState.CANCELLED -> {
                enterManualIntervention(
                    run,
                    stationCall,
                    "operation ${snapshot.ticketId} ${snapshot.state.name.lowercase()}" +
                        snapshot.message?.let { ": $it" }.orEmpty()
                )
                return true
            }
        }
    }

    private fun enterStationDwelling(run: ActiveRun, stationCall: DispatcherStationCall) {
        stationCall.earliestDepartureDispatcherTick =
            stationCall.resolved.call.dwellPlan.earliestDepartureTick(dispatcherTick)
        stationCall.stateEnteredDispatcherTick = dispatcherTick
        run.state = TimetableServiceRunState.DWELLING
        run.message = "dwelling at ${stationCall.resolved.berthRef}"
        run.currentWayPointId = work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds.stationBerth(
            stationCall.resolved.berthRef.stationId, stationCall.resolved.berthRef.berthId)
        run.physicalOriginPending = false
    }

    private fun enterReadyToDepart(run: ActiveRun, stationCall: DispatcherStationCall) {
        stationCall.stateEnteredDispatcherTick = dispatcherTick
        run.state = TimetableServiceRunState.READY_TO_DEPART
        run.message = "ready to depart ${stationCall.resolved.berthRef}"
    }

    /** Terminal operations finish physically before their metadata-only END can be consumed. */
    private fun completeTerminalTask(run: ActiveRun) {
        val end = (run.programCursor.currentStep() as? TimetableStep.At)?.action as? TimetableAction.TaskEnd ?: return
        val timing = run.taskTimings.lastOrNull() ?: return
        check(timing.taskId == end.taskId && timing.endTick == null) { "unmatched terminal task end" }
        run.taskTimings[run.taskTimings.lastIndex] = timing.copy(endTick = timetableElapsedTicks(run))
        run.programCursor = run.programCursor.advance()
    }

    private fun nextActionTick(run: ActiveRun): Long? =
        run.remainingSteps()
            .filterIsInstance<TimetableStep.At>()
            .filter { it.action !is TimetableAction.TaskEnd }
            .firstOrNull()
            ?.tick

    private fun completeOpenTerminalStation(
        run: ActiveRun,
        stationCall: DispatcherStationCall
    ) {
        run.currentWayPointId = stationCall.missionRuntime.targetWayPointId
        run.physicalOriginPending = false
        completeTerminalTask(run)
        run.state = TimetableServiceRunState.COMPLETED
        run.message = "service completed with doors open at ${stationCall.resolved.berthRef}"
    }

    private fun completeOpenTerminalDestination(
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        run.currentWayPointId = stop.options.destinationId
        run.physicalOriginPending = false
        run.destinationStop = null
        completeTerminalTask(run)
        run.state = TimetableServiceRunState.COMPLETED
        run.message = "service completed with doors open at ${stop.options.destinationId}"
    }

    private fun destinationStopCompleted(
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot,
        stop: DispatcherDestinationStop
    ): Boolean {
        val offset = stop.options.stoppingOffsetMetersBefore ?: 0.0
        return runtime.headProgressMeters >=
            runtime.layout.totalLengthMeters - offset - run.ato.missionStopToleranceMeters &&
            run.ato.isStopped(snapshot.speedMetersPerSecond)
    }

    private fun beginDestinationStop(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        if (!validateCommittedAuthority(world, run)) return
        run.ato.setSpeedLimit(stopKey(run), 0.0)
        stop.started = true
        val role = world.simpleAtoWorldData.destinations[stop.options.destinationId]?.role as?
            work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole
        val side = stop.options.doorSide ?: if (stop.options.stopPrograms != null) role?.doorSide ?: DoorSide.NONE else DoorSide.NONE
        if (stop.options.stopPrograms != null) {
            run.currentWayPointId = stop.options.destinationId
            run.physicalOriginPending = false
            beginStopProgram(world, run, stop.options.stopPrograms, side, stop.options.minimumDwellTicks ?: role?.defaultDwellTicks ?: 0L, StopProgramLocation.DESTINATION)
        } else if (side == DoorSide.NONE) {
            enterDestinationDwelling(run, stop)
        } else {
            beginDestinationOperation(
                world,
                run,
                stop,
                TrainOperation.OpenDoors(side),
                TimetableServiceRunState.DOORS_OPENING
            )
        }
    }

    private fun enterDestinationDwelling(run: ActiveRun, stop: DispatcherDestinationStop) {
        run.dwellTicksRemaining = stop.options.minimumDwellTicks ?: 0L
        stop.earliestDepartureDispatcherTick = Math.addExact(dispatcherTick, run.dwellTicksRemaining)
        run.state = TimetableServiceRunState.DWELLING
        run.message = "dwelling at ${stop.options.destinationId}"
        run.currentWayPointId = stop.options.destinationId
        run.physicalOriginPending = false
    }

    private fun finishDestinationStop(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        if (!validateCommittedAuthority(world, run)) return
        check(run.destinationStop === stop)
        run.currentWayPointId = stop.options.destinationId
        run.physicalOriginPending = false
        run.destinationStop = null
        run.state = TimetableServiceRunState.WAITING_MISSION
        run.message = null
        advanceService(world, run)
    }

    private fun beginDestinationOperation(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop,
        operation: TrainOperation,
        state: TimetableServiceRunState
    ) {
        if (!validateCommittedAuthority(world, run)) return
        val phase = when (operation) {
            is TrainOperation.OpenDoors -> "open-doors"
            is TrainOperation.CloseDoors -> "close-doors"
            is TrainOperation.SetCabLights -> "cab-lights"
            is TrainOperation.ExecuteModelAction -> "model-action-${operation.actionName}"
            is TrainOperation.Announce -> "announce"
        }
        stop.operationTicket = null
        stop.operationTicketId = null
        stop.operationStartedDispatcherTick = Long.MAX_VALUE
        val requestId = "${run.instanceId}/destination/${stop.options.destinationId}:$phase:${run.missionRevision}"
        val request = TrainOperationRequest(
            requestId = requestId,
            serviceRunId = run.instanceId,
            stationCallId = "${run.instanceId}/destination/${stop.options.destinationId}",
            trainId = run.train.id,
            missionRevision = run.missionRevision,
            operation = operation
        )
        val result = try {
            operationGateway.execute(request)
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "beginDestinationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            stallRun(
                world,
                run,
                "TrainOperationFailure",
                "$phase gateway exception at ${stop.options.destinationId}: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
            return
        }
        when (result) {
            is TrainOperationExecutionResult.Rejected -> stallRun(
                world,
                run,
                "TrainOperationFailure",
                "$phase rejected at ${stop.options.destinationId}: ${result.reason}"
            )
            is TrainOperationExecutionResult.Accepted -> {
                if (result.ticket.request != request) {
                    result.ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "$phase returned a ticket for another destination request"
                    )
                    return
                }
                val snapshot = try {
                    result.ticket.snapshot()
                } catch (failure: Exception) {
                    LOGGER.log(java.util.logging.Level.WARNING, "beginDestinationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
                    result.ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "$phase ticket exception at ${stop.options.destinationId}: " +
                            (failure.message ?: failure::class.java.simpleName)
                    )
                    return
                }
                if (snapshot.requestId != request.requestId) {
                    result.ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "$phase ticket identity mismatch at ${stop.options.destinationId}"
                    )
                    return
                }
                stop.operationTicket = result.ticket
                stop.operationTicketId = snapshot.ticketId
                stop.operationStartedDispatcherTick = dispatcherTick
                run.state = state
                run.message = "$phase requested at ${stop.options.destinationId}"
            }
        }
    }

    private fun processDestinationOperation(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ): Boolean {
        val ticket = stop.operationTicket
        if (ticket == null) {
            stallRun(world, run, "TrainOperationFailure", "destination door operation has no ticket")
            return true
        }
        val snapshot = try {
            ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "processDestinationOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            stallRun(
                world,
                run,
                "TrainOperationFailure",
                "destination operation ticket exception: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
            return true
        }
        if (snapshot.requestId != ticket.request.requestId ||
            snapshot.ticketId != stop.operationTicketId
        ) {
            stallRun(world, run, "TrainOperationFailure", "destination operation ticket identity changed")
            return true
        }
        return when (snapshot.state) {
            TrainOperationTicketState.PENDING -> {
                if (dispatcherTick - stop.operationStartedDispatcherTick >= operationTimeoutTicks) {
                    ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "destination operation timed out after $operationTimeoutTicks ticks"
                    )
                    true
                } else false
            }
            TrainOperationTicketState.SUCCEEDED -> {
                if (run.state == TimetableServiceRunState.DOORS_OPENING) run.stationDoorsSecured = false
                if (run.state == TimetableServiceRunState.DOORS_CLOSING) run.stationDoorsSecured = true
                stop.operationTicket = null
                stop.operationTicketId = null
                when (run.state) {
                    TimetableServiceRunState.DOORS_OPENING -> enterDestinationDwelling(run, stop)
                    TimetableServiceRunState.DOORS_CLOSING -> finishDestinationStop(world, run, stop)
                    else -> stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "destination operation completed in invalid state ${run.state}"
                    )
                }
                true
            }
            TrainOperationTicketState.FAILED,
            TrainOperationTicketState.CANCELLED -> {
                stallRun(
                    world,
                    run,
                    "TrainOperationFailure",
                    "destination operation ${snapshot.state.name.lowercase()}: ${snapshot.message.orEmpty()}"
                )
                true
            }
        }
    }

    private fun beginReverse(world: World, run: ActiveRun) {
        if (!validateCommittedAuthority(world, run)) return
        val snapshot = run.lastPublishedAtoState?.trainSnapshot
            ?: run.ato.publishedState.trainSnapshot
        if (!run.ato.isStopped(snapshot.speedMetersPerSecond)) {
            stallRun(world, run, "InfrastructureInvariantViolation", "reverse requires a stopped train")
            return
        }
        try {
            val body = fixedBodyRoute(world, run.train, "${run.instanceId}/reverse-direction-check")
            val reversed = RouteSnapshot(body.routeId, body.revision, body.topologyRevision,
                body.segments.asReversed().map { segment ->
                    val rail = world.topologyState.railProjection.getValue(segment.railId).single()
                    val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
                    RouteSegmentSnapshot(segment.railId,
                        if (segment.facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB,
                        length - segment.endArcLengthMeters, length - segment.startArcLengthMeters)
                })
            routeDirectionLimit(world, reversed).requireLegalThrough(reversed.totalLengthMeters)
        } catch (failure: Exception) {
            stallRun(world, run, "OperatingDirectionViolation",
                "reversed train body violates operating directions: ${failure.message}")
            return
        }
        val admissionClient = run.admissionClient
        if (admissionClient?.state?.committedAuthority != null) {
            when (val result = admissionClient.suspendReleaseForReversal()) {
                is RouteGrantReversalResult.Accepted -> Unit
                is RouteGrantReversalResult.Rejected -> {
                    stallRun(
                        world,
                        run,
                        "RouteAdmissionFailure",
                        "route grant could not suspend release for reversal: " +
                            "${result.failure.code}: ${result.failure.detail}"
                    )
                    return
                }
            }
        }
        val outputHandoff = run.structuralEventInstanceId?.takeIf { run.train.movementHeld }
            ?.let { fixedFormations[it]?.handoff }
        try {
            outputHandoff?.beginOutputReversal(run.train.id)
        } catch (failure: Exception) {
            stallRun(world, run, "FormationRecoveryRequired",
                "held output reversal ownership could not be prepared: ${failure.message}")
            return
        }
        val stationCall = run.stationCall?.takeIf { it.berthed && !it.departed }
        val preparationRevision = run.ato.prepareForReverse(
            callId = stationCall?.resolved?.call?.callId,
            missionRevision = stationCall?.resolved?.call?.missionRevision
        )
        run.reverseOperation = DispatcherReverseOperation(
            preparationRevision = preparationRevision,
            outputHandoff = outputHandoff
        )
        run.state = TimetableServiceRunState.REVERSING
        run.message = "waiting for atomic ATO reverse preparation"
    }

    private fun processReverse(
        world: World,
        run: ActiveRun,
        selectionContext: FrozenRouteSelectionContext?
    ) {
        val reverse = run.reverseOperation
        if (reverse == null) {
            stallRun(world, run, "InfrastructureInvariantViolation", "REVERSING run has no reverse state")
            return
        }
        val reversedAfter = reverse.reversedAfter
        if (reversedAfter == null) {
            val preparedRevision = run.lastPublishedAtoState?.preparedReverseRevision
                ?: run.ato.publishedState.preparedReverseRevision
            if (preparedRevision < reverse.preparationRevision) return
            val reversedBefore = run.train.isReversed
            // Pending direction is expressed at the unchanged waypoint anchor, whose AB basis
            // may differ from the current head's rail after an earlier in-place reversal.
            val facingBefore = run.requiredNextStartDirection
                ?: (run.train.headLocation as? work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
                    ?.facing
            run.train.reverse()
            if (run.train.isReversed == reversedBefore) {
                stallRun(world, run, "InfrastructureInvariantViolation", "train direction did not reverse")
                return
            }
            try {
                reverse.outputHandoff?.recordOutputReversal(run.train.id)
            } catch (failure: Exception) {
                stallRun(world, run, "FormationRecoveryRequired",
                    "reversed held output ownership could not be recorded: ${failure.message}")
                return
            }
            run.requiredNextStartDirection = when (facingBefore) {
                ViewDirection.AB -> ViewDirection.BA
                ViewDirection.BA -> ViewDirection.AB
                null -> null
            }
            reverse.reversedAfter = run.train.isReversed
            reverse.reversedAtDispatcherTick = dispatcherTick
            run.message = "publishing atomically prepared reversed train direction"
            return
        }
        if (dispatcherTick <= requireNotNull(reverse.reversedAtDispatcherTick)) return
        if (run.train.isReversed != reversedAfter) {
            stallRun(world, run, "InfrastructureInvariantViolation", "reversed train state changed unexpectedly")
            return
        }
        val client = run.admissionClient
        if (client?.state?.reversalPending == true) {
            val committed = run.admittedRoute
            if (committed == null) {
                stallRun(
                    world,
                    run,
                    "InfrastructureInvariantViolation",
                    "suspended reversal grant has no admitted operating route"
                )
                return
            }
            if (selectionContext == null) {
                run.message = "waiting for a coherent reversed-train observation"
                return
            }
            when (val body = FrozenMissionRouteAssembler.rebaseBodyAfterReversal(
                world.topologyState,
                selectionContext,
                committed.route
            )) {
                is FrozenMissionRouteResult.Frozen -> {
                    val checkedBody = try {
                        body.route.copy(selection = directionCheckedSelection(world, body.route.selection,
                            body.route.headDistanceFromRouteStartMeters))
                    } catch (failure: Exception) {
                        stallRun(world, run, "OperatingDirectionViolation",
                            "reversed body direction could not be validated: ${failure.message}")
                        return
                    }
                    // The stopped train no longer consumes the old inspection binding. Basic still
                    // retains the old grant until the rebase succeeds, so protect the union of old
                    // and reversed routes during every retry.
                    inspectionRuntime.removeTrain(run.train.id)
                    when (val protection = checkNotNull(run.routeEditProtection).protectReversalTransition(
                        world.topologyState,
                        committed.route.selection.route,
                        body.route.selection.route
                    )) {
                        is FrozenRouteProtectionResult.Protected -> Unit
                        is FrozenRouteProtectionResult.Rejected -> {
                            stallRun(
                                world,
                                run,
                                "InfrastructureDependencyFailure",
                                "reversed frozen route could not be protected from edits: " +
                                    protection.detail
                            )
                            return
                        }
                    }
                    when (val result = client.rebaseAfterReversal(
                        checkedBody.selection,
                        RouteBoundary(body.route.headDistanceFromRouteStartMeters)
                    )) {
                        is RouteAdmissionResult.Granted -> {
                            run.admittedRoute = AdmissionRoute(checkedBody, committed.missionRevision)
                            when (val protection = checkNotNull(run.routeEditProtection).protect(
                                world.topologyState,
                                body.route.selection.route
                            )) {
                                is FrozenRouteProtectionResult.Protected -> Unit
                                is FrozenRouteProtectionResult.Rejected -> {
                                    stallRun(
                                        world,
                                        run,
                                        "InfrastructureDependencyFailure",
                                        "reversed frozen route transition could not be finalized: " +
                                            protection.detail
                                    )
                                    return
                                }
                            }
                        }
                        is RouteAdmissionResult.Pending -> error(
                            "reversal rebase cannot enter asynchronous preparation"
                        )
                        is RouteAdmissionResult.Rejected -> {
                            if (result.failure.code == RouteAdmissionFailureCode.STALE_OBSERVATION) {
                                run.message = "waiting to rebase the retained grant on a current observation"
                                return
                            }
                            stallRun(
                                world,
                                run,
                                "RouteAdmissionFailure",
                                "retained route grant could not rebase after reversal: " +
                                    "${result.failure.code}: ${result.failure.detail}"
                            )
                            return
                        }
                    }
                }
                is FrozenMissionRouteResult.Rejected -> {
                    if (body.reason == FrozenMissionRouteRejection.STALE_OBSERVATION) {
                        run.message = "waiting to assemble the reversed train body from a current observation"
                        return
                    }
                    stallRun(
                        world,
                        run,
                        "InfrastructureInvariantViolation",
                        "reversed train body could not rebase the retained grant: " +
                            "${body.reason}: ${body.detail}"
                    )
                    return
                }
            }
        }
        try {
            reverse.outputHandoff?.finishOutputReversal(run.train.id)
        } catch (failure: Exception) {
            stallRun(world, run, "FormationRecoveryRequired",
                "reversed held output handoff could not be resumed: ${failure.message}")
            return
        }
        run.admissionCandidate = null
        run.nextAdmissionAttemptTick = 0L
        run.reverseOperation = null
        run.stopExecution?.let { execution ->
            execution.reverseCompleted = true
            execution.reversed = true
            execution.operatingDoorSide = when (execution.operatingDoorSide) {
                DoorSide.LEFT -> DoorSide.RIGHT
                DoorSide.RIGHT -> DoorSide.LEFT
                else -> execution.operatingDoorSide
            }
            run.state = TimetableServiceRunState.DWELLING
            run.message = "station reversal completed; continuing station tasks"
            return
        }
        run.state = TimetableServiceRunState.WAITING_MISSION
        run.message = null
        advanceService(world, run)
    }

    private fun beginHalt(world: World, run: ActiveRun) {
        val operation = TrainOperation.ExecuteModelAction(POWER_OFF_ACTION)
        val request = TrainOperationRequest(
            requestId = "${run.instanceId}/halt:${run.missionRevision}",
            serviceRunId = run.instanceId,
            stationCallId = "${run.instanceId}/halt",
            trainId = run.train.id,
            missionRevision = run.missionRevision,
            operation = operation
        )
        val result = try {
            operationGateway.execute(request)
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "beginHalt failed run=${run.instanceId} train=${run.train.id}", failure)
            stallRun(
                world,
                run,
                "TrainOperationFailure",
                "halt power-off gateway exception: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
            return
        }
        when (result) {
            is TrainOperationExecutionResult.Rejected -> stallRun(
                world,
                run,
                "TrainOperationFailure",
                "halt power-off action rejected: ${result.reason}"
            )
            is TrainOperationExecutionResult.Accepted -> {
                if (result.ticket.request != request) {
                    result.ticket.cancel()
                    stallRun(world, run, "TrainOperationFailure", "halt returned a ticket for another request")
                    return
                }
                val snapshot = try {
                    result.ticket.snapshot()
                } catch (failure: Exception) {
                    LOGGER.log(java.util.logging.Level.WARNING, "beginHalt failed run=${run.instanceId} train=${run.train.id}", failure)
                    result.ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "halt ticket exception: ${failure.message ?: failure::class.java.simpleName}"
                    )
                    return
                }
                if (snapshot.requestId != request.requestId) {
                    result.ticket.cancel()
                    stallRun(world, run, "TrainOperationFailure", "halt ticket identity mismatch")
                    return
                }
                run.haltOperation = DispatcherHaltOperation(
                    ticket = result.ticket,
                    ticketId = snapshot.ticketId,
                    startedDispatcherTick = dispatcherTick
                )
                run.state = TimetableServiceRunState.HALTING
                run.message = "executing train model action $POWER_OFF_ACTION"
            }
        }
    }

    private fun processHaltOperation(world: World, run: ActiveRun) {
        val halt = run.haltOperation
        if (halt == null) {
            stallRun(world, run, "TrainOperationFailure", "HALTING run has no power-off operation")
            return
        }
        val snapshot = try {
            halt.ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "processHaltOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            stallRun(
                world,
                run,
                "TrainOperationFailure",
                "halt ticket exception: ${failure.message ?: failure::class.java.simpleName}"
            )
            return
        }
        if (snapshot.requestId != halt.ticket.request.requestId || snapshot.ticketId != halt.ticketId) {
            stallRun(world, run, "TrainOperationFailure", "halt ticket identity changed")
            return
        }
        when (snapshot.state) {
            TrainOperationTicketState.PENDING -> {
                if (dispatcherTick - halt.startedDispatcherTick >= operationTimeoutTicks) {
                    halt.ticket.cancel()
                    stallRun(
                        world,
                        run,
                        "TrainOperationFailure",
                        "halt power-off action timed out after $operationTimeoutTicks ticks"
                    )
                }
            }
            TrainOperationTicketState.SUCCEEDED -> {
                run.haltOperation = null
                completeTerminalTask(run)
                run.state = TimetableServiceRunState.COMPLETED
                run.message = "halted after train model action $POWER_OFF_ACTION"
            }
            TrainOperationTicketState.FAILED,
            TrainOperationTicketState.CANCELLED -> stallRun(
                world,
                run,
                "TrainOperationFailure",
                "halt power-off action ${snapshot.state.name.lowercase()}: ${snapshot.message.orEmpty()}"
            )
        }
    }

    private fun enterManualIntervention(
        run: ActiveRun,
        stationCall: DispatcherStationCall,
        reason: String
    ) {
        stationCall.stateEnteredDispatcherTick = dispatcherTick
        val world = requireNotNull(boundWorld)
        stallRun(
            world,
            run,
            "TrainOperationFailure",
            "$reason; DepartureHold remains applied"
        )
    }

    private fun processStationNotifications(run: ActiveRun) {
        run.ato.drainStationCallNotifications().forEach { notification ->
            val world = requireNotNull(boundWorld)
            if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
            val stationCall = run.stationCall ?: return@forEach
            val activeRevision = stationCall.resolved.call.missionRevision
            when {
                notification.missionRevision < activeRevision -> return@forEach
                notification.missionRevision > activeRevision -> {
                    stallRun(
                        requireNotNull(boundWorld),
                        run,
                        "ResourceIdentityViolation",
                        "StationCall notification revision is newer than active call"
                    )
                    return@forEach
                }
            }
            if (notification.callId != stationCall.resolved.call.callId) {
                stallRun(
                    requireNotNull(boundWorld),
                    run,
                    "ResourceIdentityViolation",
                    "StationCall notification ID does not match active call"
                )
                return@forEach
            }
            if (notification.physicsEpoch < stationCall.lastNotificationEpoch) return@forEach
            stationCall.lastNotificationEpoch = notification.physicsEpoch
            if (notification.type != StationCallNotificationType.APPROACHING &&
                !validateCommittedAuthority(world, run)
            ) return
            when (notification.type) {
                StationCallNotificationType.APPROACHING -> {
                    if (!stationCall.approaching) {
                        stationCall.approaching = true
                        stationCall.stateEnteredDispatcherTick = dispatcherTick
                        if (run.state != TimetableServiceRunState.WAITING_TIMETABLE_TIME) {
                            run.state = TimetableServiceRunState.APPROACHING
                            run.message = "approaching ${notification.berthRef}"
                        }
                    }
                }
                StationCallNotificationType.BERTHED -> {
                    if (!stationCall.approaching || stationCall.berthed) return@forEach
                    stationCall.berthed = true
                    stationCall.stateEnteredDispatcherTick = dispatcherTick
                    run.state = TimetableServiceRunState.BERTHED
                    run.message = "berthed at ${notification.berthRef}"
                }
                StationCallNotificationType.DEPARTED -> {
                    if (!stationCall.departureGranted || stationCall.departed) return@forEach
                    stationCall.departed = true
                    stationCall.stateEnteredDispatcherTick = dispatcherTick
                    run.state = TimetableServiceRunState.DEPARTED
                    run.message = "departed ${notification.berthRef}"
                }
                StationCallNotificationType.REVERSE_READY -> {
                    if (stationCall.departed) return@forEach
                    stationCall.departureGranted = true
                    stationCall.departed = true
                    stationCall.stateEnteredDispatcherTick = dispatcherTick
                    run.message = "StationCall ready for reverse at ${notification.berthRef}"
                }
            }
        }
    }

    private fun capturePublishedTrainSnapshot(run: ActiveRun): TrainSnapshot =
        TrainSnapshot.capture(run.train, dispatcherTick)

    private fun releasePassedBerth(run: ActiveRun) {
        val stationCall = run.stationCall ?: return
        val key = stationCall.allocationKey ?: return
        val occupancyState = stationCall.releaseDetector.update(run.train.occupiedRailIntervals)
        if (occupancyState != IntervalOccupancyState.RELEASED) return
        if (!berthAllocator.release(key)) {
            stallRun(
                requireNotNull(boundWorld),
                run,
                "ResourceIdentityViolation",
                "StationBerth allocation disappeared before tail release: $key"
            )
            return
        }
        stationCall.allocationKey = null
        if (stationCall.cancelledForControlTransfer ||
            stationCall.departed && run.state != TimetableServiceRunState.DEPARTED
        ) {
            run.stationCall = null
        }
    }

    private fun isTerminalStationCall(stationCall: DispatcherStationCall): Boolean {
        val runtime = stationCall.missionRuntime
        val finalStop = runtime.mission.legs.lastOrNull()?.point as? AtoMissionPoint.Stop ?: return false
        val stationStop = stationCall.missionDistanceMeters - stationCall.resolved.stoppingOffsetMetersBefore
        val missionStop = runtime.layout.totalLengthMeters - finalStop.offsetMetersBefore
        return abs(stationStop - missionStop) <= POSITION_TOLERANCE_METERS
    }

    private fun hasAuthorityBeyondStation(
        run: ActiveRun,
        stationCall: DispatcherStationCall
    ): Boolean {
        val tolerance = run.ato.missionStopToleranceMeters
        if (stationCall.missionRuntime.publishedAuthorityMeters <=
            stationCall.missionDistanceMeters + tolerance
        ) return false
        return true
    }

    private fun setRouteState(
        run: ActiveRun,
        state: TimetableServiceRunState,
        message: String?
    ) {
        if (!run.state.isStationOperationalState()) {
            run.state = state
            run.message = message
        } else if (message != null) {
            run.message = message
        }
    }

    /**
     * Shared-admission MANUAL_ROUTED keeps route/resource assistance but does not pretend that the
     * detached ATO constraint manager controls the platform's manual controller. The explicit
     * progress check below preserves the existing manual protection invariant against the same
     * committed Basic boundary used by automatic operation.
     */
    private fun processManualRoutedWithAdmission(
        world: World,
        run: ActiveRun,
        snapshot: TrainSnapshot,
        context: TimetableRouteAdmissionContext?,
        selectionContext: FrozenRouteSelectionContext?
    ) {
        if (run.controlTransferOwner == null || run.manualController == null ||
            run.train.controller !== run.manualController
        ) {
            stallManualRouted(world, run, snapshot, "MANUAL_ROUTED controller identity mismatch")
            return
        }
        val runtime = run.currentRuntime
        val headProgress = runtime.layout.project(snapshot.head, runtime.headProgressMeters)
            ?: physicalMissionProgress(world, run, runtime, snapshot)
            ?: terminalProgressFallback(world, run, runtime)
        if (headProgress == null) {
            stallManualRouted(world, run, snapshot, "MANUAL_ROUTED train head left PlannedRoute")
            return
        }
        runtime.headProgressMeters = headProgress
        runtime.tailProgressMeters = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
            ?: runtime.tailProgressMeters

        if (missionCompleted(world, run, runtime, snapshot) &&
            (run.stationCall == null || run.stationCall?.cancelledForControlTransfer == true)
        ) {
            if (runtime === run.storageRuntime && !tryCompleteStorage(world, run, runtime)) return
            run.currentWayPointId = runtime.targetWayPointId
            run.physicalOriginPending = false
            advanceService(world, run)
            return
        }
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
        processSharedRouteAdmission(world, run, runtime, snapshot, context, selectionContext)
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) return

        val activeRoute = run.admissionCandidate ?: run.admittedRoute ?: return
        val routeProgress = activeRoute.progressFor(runtime)
        val safeBoundary = run.admissionClient?.state?.committedAuthority
            ?.distanceFromRouteStartMeters ?: activeRoute.route.headDistanceFromRouteStartMeters
        if (routeProgress > safeBoundary + MANUAL_PROTECTION_TOLERANCE_METERS) {
            stallManualRouted(
                world,
                run,
                snapshot,
                "MANUAL_ROUTED crossed committed Basic authority at $safeBoundary route metres"
            )
        }
    }

    /** One shared-route request/progress/apply pass through Basic route admission. */
    private fun processSharedRouteAdmission(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot,
        context: TimetableRouteAdmissionContext?,
        selectionContext: FrozenRouteSelectionContext?
    ) {
        if (context == null || selectionContext == null) {
            setRouteState(run, TimetableServiceRunState.WAITING_ROUTE,
                "waiting for a coherent railway observation")
            return
        }
        if (context.observation.stamp.worldId != world.simpleAtoWorldData.atoCoordinator.worldId) {
            stallRun(
                world,
                run,
                "ObservationIdentityViolation",
                "route-admission observation belongs to ${context.observation.stamp.worldId}",
                snapshot
            )
            return
        }

        val existingClient = run.admissionClient
        val candidate: AdmissionRoute
        val client: RouteAuthorityAdmissionClient
        var result: RouteAdmissionResult
        if (existingClient?.state?.pending == true) {
            client = existingClient
            candidate = checkNotNull(run.admissionCandidate) {
                "pending route admission has no frozen candidate"
            }
            if (!applyCommittedAdmissionRestriction(
                    world,
                    run,
                    runtime,
                    context.observation,
                    snapshot,
                    candidate
                )
            ) {
                if (!world.simpleAtoWorldData.atoCoordinator.isStalled) {
                    setRouteState(
                        run,
                        TimetableServiceRunState.WAITING_ROUTE,
                        "waiting for the controller inspection horizon"
                    )
                }
                return
            }
            result = client.progress()
        } else {
            if (dispatcherTick < run.nextAdmissionAttemptTick) {
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                return
            }
            if (run.nextAdmissionAttemptTick != 0L) {
                // A rejected intent's stamp is no longer current. Keep the frozen candidate
                // geometry; routeAdmissionCandidate refreshes only its observation for this epoch.
                run.nextAdmissionAttemptTick = 0L
            }
            candidate = routeAdmissionCandidate(world, run, runtime, selectionContext) ?: run {
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                return
            }
            client = existingClient ?: RouteAuthorityAdmissionClient(
                trainId = run.train.id,
                requestId = "${run.admissionOwner.ownerId}/request",
                owner = run.admissionOwner,
                admissionService = formationAwareAdmissionService(run, context.service),
                restrictionPublisher = run.ato.trainRestrictionPublisher
            ).also {
                run.admissionClient = it
            }
            when (val protection = checkNotNull(run.routeEditProtection).protect(
                world.topologyState,
                candidate.route.selection.route
            )) {
                is FrozenRouteProtectionResult.Protected -> Unit
                is FrozenRouteProtectionResult.Rejected -> {
                    stallRun(
                        world,
                        run,
                        "InfrastructureDependencyFailure",
                        "frozen route could not be protected from edits: ${protection.detail}",
                        snapshot
                    )
                    return
                }
            }
            try {
                client.prepareContinuation(candidate.route.selection)
            } catch (failure: Exception) {
                stallRun(world, run, "RouteRestrictionFailure",
                    "committed prefix could not be projected onto the continued mission: ${failure.message}", snapshot)
                return
            }
            run.admissionCandidate = candidate
            run.admissionEnded = false
            if (!applyCommittedAdmissionRestriction(
                    world,
                    run,
                    runtime,
                    context.observation,
                    snapshot,
                    candidate
                )
            ) {
                if (!world.simpleAtoWorldData.atoCoordinator.isStalled) {
                    setRouteState(
                        run,
                        TimetableServiceRunState.WAITING_ROUTE,
                        "waiting for the controller inspection horizon"
                    )
                }
                return
            }
            val routeProgress = candidate.progressFor(runtime)
            val retainedMissionAuthority = client.state.committedAuthority?.let {
                candidate.missionAuthorityMeters(it.distanceFromRouteStartMeters, runtime)
            }
            if (runtime.publishedAuthorityMeters <= 0.0 && retainedMissionAuthority != null &&
                retainedMissionAuthority > 0.0 && run.controlMode == TimetableControlMode.AUTOMATIC
            ) {
                // The Basic-backed restriction above still ends at the old grant. Advancing the
                // operating target merely lets the train use that existing prefix while the next
                // rolling extension is not yet due; it does not create physical permission.
                run.ato.setMovementAuthority(runtime.mission.revision,
                    run.ato.terminalMovementAuthorityLimitMeters(runtime.layout.totalLengthMeters))
                runtime.publishedAuthorityMeters = retainedMissionAuthority
            }
            val nominalDesired = RollingRouteAuthorityPlanner.nextBoundary(
                routeLengthMeters = candidate.route.selection.route.totalLengthMeters,
                headProgressMeters = routeProgress,
                perceptionHorizonMeters = run.ato.routePerceptionHorizonMeters(
                    inspectionRuntime.maximumDeclaredLeadTimeSeconds(run.train.id)
                ),
                extensionTriggerMeters = run.ato.routeAllocationTriggerDistanceMeters(allocationTiming),
                admissionState = client.state
            )
            if (nominalDesired == null) {
                // A new operating stop can lie wholly inside the already granted suffix. Keep the
                // exact physical route/grant, but advance the controller's mission-coordinate map.
                // Basic correctly rejects a non-advancing request, so no request is made here.
                if (run.admittedRoute?.route?.selection?.route == candidate.route.selection.route) {
                    run.admittedRoute = candidate
                    run.admissionCandidate = null
                    client.state.committedAuthority?.let { authority ->
                        runtime.publishedAuthorityMeters = candidate.missionAuthorityMeters(
                            authority.distanceFromRouteStartMeters, runtime)
                    }
                }
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                commitSelectedStop(world, run)
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
                return
            }
            val desired = inspectionRuntime.adjustRequestedAuthorityBoundary(
                run.train.id,
                FrozenRouteBinding.from(candidate.route.selection.route),
                nominalDesired
            )
            result = client.request(candidate.route.selection, desired)
            if (result is RouteAdmissionResult.Rejected) {
                val ceiling = result.failure.retryAtOrBefore
                val choosingAvailableStop = run.stopSelection?.let { !it.committed &&
                    it.action.selection.policy == work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY } == true
                if (ceiling != null && !choosingAvailableStop) {
                    val retry = inspectionRuntime.limitRequestedAuthorityBoundary(
                        run.train.id, FrozenRouteBinding.from(candidate.route.selection.route), ceiling
                    )
                    if (retry.distanceFromRouteStartMeters > routeProgress &&
                        retry.distanceFromRouteStartMeters >
                        (client.state.committedAuthority?.distanceFromRouteStartMeters ?: 0.0) &&
                        retry.distanceFromRouteStartMeters < desired.distanceFromRouteStartMeters
                    ) result = client.request(candidate.route.selection, retry)
                }
            }
        }

        when (result) {
            is RouteAdmissionResult.Granted -> {
                run.admittedRoute = candidate
                run.admissionCandidate = null
                run.nextAdmissionAttemptTick = 0L
                runtime.publishedAuthorityMeters = candidate.missionAuthorityMeters(
                    result.grant.committedAuthority.distanceFromRouteStartMeters,
                    runtime
                )
                if (run.controlMode == TimetableControlMode.AUTOMATIC) {
                    run.ato.setMovementAuthority(
                        runtime.mission.revision,
                        run.ato.terminalMovementAuthorityLimitMeters(runtime.layout.totalLengthMeters)
                    )
                }
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                commitSelectedStop(world, run)
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
            }

            is RouteAdmissionResult.Pending -> {
                run.admissionCandidate = candidate
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                setRouteState(run, TimetableServiceRunState.WAITING_ROUTE,
                    "route resources are preparing")
            }

            is RouteAdmissionResult.Rejected -> handleAdmissionRejection(
                world,
                run,
                runtime,
                snapshot,
                context.observation,
                result
            )
        }
    }

    private fun routeAdmissionCandidate(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        context: FrozenRouteSelectionContext
    ): AdmissionRoute? {
        (run.admissionCandidate?.takeIf { it.missionRevision == runtime.mission.revision }
            ?: run.admittedRoute?.takeIf { it.missionRevision == runtime.mission.revision })?.let {
            val stamp = context.observation.stamp
            val committed = it.route.selection
            if (stamp.worldId != committed.observationStamp.worldId) {
                run.state = TimetableServiceRunState.REPLAN_REQUIRED
                run.message = "committed operating route topology changed; explicit replan is required"
                return null
            }
            if (!it.requiresTerminalCompletion) return it.withObservation(stamp)
        }

        val result = if (run.admittedRoute == null) {
            FrozenMissionRouteAssembler.assemble(
                world.topologyState,
                context,
                run.train.id,
                runtime.mission,
                runtime.headProgressMeters
            )
        } else {
            FrozenMissionRouteAssembler.append(
                world.topologyState,
                context,
                checkNotNull(run.admittedRoute).route,
                runtime.mission,
                runtime.headProgressMeters
            )
        }
        return when (result) {
            is FrozenMissionRouteResult.Frozen -> {
                // Definitions may be prepared asynchronously. Protect the selected path before
                // reading their terminal geometry, then the caller upgrades this same owner to
                // the completed path before any binding or Basic resource request is published.
                when (val protection = checkNotNull(run.routeEditProtection).protect(
                    world.topologyState, result.route.selection.route
                )) {
                    is FrozenRouteProtectionResult.Protected -> Unit
                    is FrozenRouteProtectionResult.Rejected -> {
                        stallRun(world, run, "InfrastructureDependencyFailure",
                            "selected route could not be protected before terminal completion: ${protection.detail}")
                        return null
                    }
                }
                try {
                    val stopOffset = run.stationCall?.takeIf {
                        it.missionRuntime === runtime && isTerminalStationCall(it)
                    }?.let {
                        runtime.layout.totalLengthMeters - it.missionDistanceMeters + it.resolved.stoppingOffsetMetersBefore
                    }
                        ?: (runtime.mission.legs.lastOrNull()?.point as? AtoMissionPoint.Stop)?.offsetMetersBefore
                        ?: 0.0
                    val actualStop = result.route.operationalEndRouteDistanceMeters - stopOffset
                    val proposal = terminalMarginProposal(world, result.route.selection.route,
                        actualStop)
                    when (val protection = checkNotNull(run.routeEditProtection).protect(world.topologyState, proposal.route)) {
                        is FrozenRouteProtectionResult.Protected -> Unit
                        is FrozenRouteProtectionResult.Rejected -> error(protection.detail)
                    }
                    var completed = inspectionRuntime.completeTerminalRoute(result.route.selection.route,
                        proposal.route, proposal.failure)
                    val requiredEnd = maxOf(result.route.headDistanceFromRouteStartMeters,
                        actualStop + authorityStoppingMarginMeters)
                    requireLegalTerminalPrefix(world, completed, requiredEnd,
                        result.route.selection.route.totalLengthMeters)
                    val retained = run.admittedRoute?.route?.selection?.route
                    if (retained != null && completed.revision == retained.revision &&
                        completed.segments != retained.segments) {
                        completed = RouteSnapshot(completed.routeId, Math.addExact(completed.revision, 1L),
                            completed.topologyRevision, completed.segments)
                    }
                    AdmissionRoute(result.route.copy(
                        selection = directionCheckedSelection(world,
                            result.route.selection.copy(route = completed, operatingDirectionLimit = null), requiredEnd),
                        // Physical output missions retain a safety suffix beyond their actual
                        // stop. The next physical origin must align with that stop, not its suffix.
                        operationalEndRouteDistanceMeters = if (runtime.mission.origin is AtoMissionOrigin.Physical)
                            actualStop else result.route.operationalEndRouteDistanceMeters),
                        runtime.mission.revision).also { run.admissionCandidate = it }
                } catch (failure: Exception) {
                    run.state = TimetableServiceRunState.REPLAN_REQUIRED
                    run.message = "terminal route could not be completed: ${failure.message ?: failure.javaClass.name}"
                    null
                }
            }
            is FrozenMissionRouteResult.Rejected -> {
                run.state = TimetableServiceRunState.REPLAN_REQUIRED
                run.message = "frozen route requires explicit replan: ${result.reason}: ${result.detail}"
                null
            }
        }
    }

    private fun terminalMarginProposal(world: World, route: work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot,
        operatingEnd: Double): TerminalMarginProposal {
        val topology = world.topologyState
        val restrictions = world.simpleAtoWorldData.routePlanningRestrictions.compile(topology,
            world.basicWorldData.operatingDirections.overlay(topology))
        return TerminalRouteMargin.propose(topology, restrictions, route,
            maxOf(route.totalLengthMeters, operatingEnd + authorityStoppingMarginMeters))
    }

    private fun directionCheckedSelection(world: World, selection: FrozenRouteSelectionResult.Selected,
        requiredEndRouteMeters: Double): FrozenRouteSelectionResult.Selected {
        val limit = routeDirectionLimit(world, selection.route)
        limit.requireMatches(selection)
        limit.requireLegalThrough(requiredEndRouteMeters)
        return selection.copy(operatingDirectionLimit = limit)
    }

    private fun routeDirectionLimit(world: World, route: RouteSnapshot): RouteOperatingDirectionLimit {
        val topology = world.topologyState
        val directions = world.basicWorldData.operatingDirections.snapshot(topology,
            route.segments.map { it.railId })
        return RouteOperatingDirectionLimit.calculate(world.simpleAtoWorldData.atoCoordinator.worldId,
            route, topology, directions)
    }

    private fun requireLegalTerminalPrefix(world: World, route: RouteSnapshot,
        requiredEndRouteMeters: Double, selectedEndRouteMeters: Double) {
        val topology = world.topologyState
        val restrictions = world.simpleAtoWorldData.routePlanningRestrictions.compile(topology,
            world.basicWorldData.operatingDirections.overlay(topology))
        TerminalRouteMargin.requireLegalPrefix(topology, restrictions, route,
            requiredEndRouteMeters, selectedEndRouteMeters)
    }

    private fun handleAdmissionRejection(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot,
        observation: RailwayObservation,
        result: RouteAdmissionResult.Rejected
    ) {
        applyCommittedAdmissionRestriction(world, run, runtime, observation, snapshot)
        val selection = run.stopSelection
        if (selection != null && !selection.committed && selection.key != null &&
            selection.action.selection.policy == work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY &&
            result.failure.code in setOf(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                RouteAdmissionFailureCode.RESOURCE_CONFLICT, RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE) &&
            run.admissionClient?.state?.pending != true) {
            val client = run.admissionClient
            if (client?.committedGrantSnapshot == null && client != null) {
                check(client.end()) { "Rejected station candidate still has a live admission lifecycle" }
            }
            val key = checkNotNull(selection.key)
            check(berthAllocator.release(key)) { "Tentative station selection lost its exact allocation" }
            selection.deniedCandidates += key.berthRef.berthId
            selection.key = null
            selection.retryAtTick = dispatcherTick + admissionRetryTicks
            run.admissionCandidate = null
            run.state = TimetableServiceRunState.WAITING_BERTH
            run.message = "station candidate denied before commit; trying remaining declared candidates"
            return
        }
        when (result.failure.code) {
            RouteAdmissionFailureCode.STALE_OBSERVATION,
            RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
            RouteAdmissionFailureCode.RESOURCE_CONFLICT,
            RouteAdmissionFailureCode.REQUEST_BUSY,
            RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG,
            RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE -> {
                run.nextAdmissionAttemptTick = dispatcherTick +
                    admissionRetryTicks
                setRouteState(
                    run,
                    TimetableServiceRunState.WAITING_ROUTE,
                    "route admission ${result.failure.code}: ${result.failure.detail}"
                )
            }

            RouteAdmissionFailureCode.STALE_TOPOLOGY,
            RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH -> {
                run.state = TimetableServiceRunState.REPLAN_REQUIRED
                run.message = "route admission requires explicit replan: ${result.failure.detail}"
            }

            else -> stallRun(
                world,
                run,
                "RouteAdmissionFailure",
                "route admission ${result.failure.code}: ${result.failure.detail}",
                snapshot
            )
        }
    }

    private fun applyCommittedAdmissionRestriction(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        observation: RailwayObservation,
        snapshot: TrainSnapshot,
        routeOverride: AdmissionRoute? = null
    ): Boolean {
        val activeRoute = routeOverride ?: run.admissionCandidate ?: run.admittedRoute ?: return true
        try {
            val route = activeRoute.route.selection.route
            val maximumInspectionLeadTime = inspectionRuntime.synchronizeRoute(
                observation.stamp,
                run.train.id,
                route,
                authorityStoppingMarginMeters = run.ato.authorityStoppingMarginMeters,
                publishedFrame = run.train.publishedFrame.takeIf { it.head == snapshot.head }
            )
            run.ato.bindTrainRestrictionRoute(
                observation.stamp,
                activeRoute.route,
                activeRoute.missionRevision,
                signalRestrictionSnapshots,
                inspectionRuntime.bindingFor(run.train.id)
            )
            if (inspectionRuntime.hasBindings(run.train.id) &&
                run.controlMode == TimetableControlMode.AUTOMATIC
            ) {
                val published = checkNotNull(run.lastPublishedAtoState) {
                    "inspection readiness requires the dispatcher-captured physics publication"
                }
                if (published.inspectedRoute != FrozenRouteBinding.from(route)) return false
                if (published.perceptionHorizonMeters + POSITION_TOLERANCE_METERS <
                    run.ato.inspectionLeadHorizonMeters(maximumInspectionLeadTime)
                ) return false
            }
            return true
        } catch (failure: Exception) {
            stallRun(
                world,
                run,
                "RouteRestrictionFailure",
                failure.message ?: failure::class.java.simpleName,
                snapshot
            )
            return false
        }
    }

    /** Progresses an already-created handle during terminal/manual lifecycle without a new request. */
    private fun progressSharedAdmissionOnly(
        world: World,
        run: ActiveRun,
        context: TimetableRouteAdmissionContext?
    ) {
        val client = run.admissionClient ?: return
        if (!client.state.pending || context == null) return
        when (val result = client.progress()) {
            is RouteAdmissionResult.Granted -> {
                run.admissionCandidate?.let { run.admittedRoute = it }
                run.admissionCandidate = null
            }
            is RouteAdmissionResult.Pending -> Unit
            is RouteAdmissionResult.Rejected -> {
                if (result.failure.code == RouteAdmissionFailureCode.WORLD_STALLED) {
                    stallRun(
                        world,
                        run,
                        "RouteAdmissionFailure",
                        result.failure.detail
                    )
                }
            }
        }
        run.stopSelection?.takeIf { it.cancelled && !it.committed && !client.state.pending }?.let { selection ->
            // Stopped cancellation retains any Basic grant but no longer reserves an unvisited berth.
            if (!world.simpleAtoWorldData.atoCoordinator.isStalled && !client.state.reversalPending) {
                selection.key?.let { check(berthAllocator.release(it)) { "cancelled selection allocation disappeared" } }
                run.stopSelection = null
                run.ato.removeSpeedLimit(selectionHoldKey(run))
            }
        }
    }

    private fun tryEndSharedAdmission(world: World, run: ActiveRun) {
        val client = run.admissionClient ?: return
        if (!run.admissionEnded && client.end()) {
            inspectionRuntime.removeTrain(run.train.id)
            when (val protection = checkNotNull(run.routeEditProtection).release()) {
                is FrozenRouteProtectionResult.Protected -> Unit
                is FrozenRouteProtectionResult.Rejected -> {
                    stallRun(
                        world,
                        run,
                        "InfrastructureDependencyFailure",
                        "ended frozen route remains edit-protected: ${protection.detail}"
                    )
                    return
                }
            }
            run.admissionEnded = true
            run.admittedRoute = null
            run.admissionCandidate = null
            run.nextAdmissionAttemptTick = 0L
        }
    }

    private fun physicalMissionProgress(world: World, run: ActiveRun, runtime: MissionRuntime,
        snapshot: TrainSnapshot): Double? {
        val route = (run.admissionCandidate ?: run.admittedRoute)
            ?.takeIf { it.missionRevision == runtime.mission.revision } ?: return null
        return route.route.missionProgressFromPhysicalHead(world.topologyState, snapshot.head,
            runtime.headProgressMeters)
    }

    private fun terminalProgressFallback(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime
    ): Double? {
        // Once a coherent physical route is bound, its failed projection is authoritative. The
        // legacy waypoint proximity heuristic must not turn an off-path position into completion.
        if ((run.admissionCandidate ?: run.admittedRoute)?.missionRevision == runtime.mission.revision) return null
        if (runtime.headProgressMeters <
            runtime.layout.totalLengthMeters - TIMETABLE_ORIGIN_TOLERANCE_METERS
        ) return null
        return runtime.layout.totalLengthMeters.takeIf {
            validateAtWayPoint(world, run.train, runtime.targetWayPointId) == null
        }
    }

    private fun stallManualRouted(
        world: World,
        run: ActiveRun,
        snapshot: TrainSnapshot,
        reason: String
    ) = stallRun(world, run, "ManualRoutedSafetyViolation", reason, snapshot)

    /** Transition-only proof against actual Basic authority, never clamped operating progress. */
    private fun validateCommittedAuthority(world: World, run: ActiveRun): Boolean {
        if (world.simpleAtoWorldData.atoCoordinator.isStalled) return false
        val grant = run.admissionClient?.committedGrantSnapshot ?: return true
        val route = run.admissionCandidate ?: run.admittedRoute
        val frame = run.train.publishedFrame
        if (frame.derailed || route == null) {
            stallRun(world, run, "RouteAuthorityViolation", "a route transition has no valid physical train/path")
            return false
        }
        val runtime = run.currentRuntimeOrNull
        val expected = if (runtime == null) route.route.headDistanceFromRouteStartMeters else route.progressFor(runtime)
        val proof = CompletionProof(world.topologyState, route.route.selection.route, grant.grantId,
            grant.committedAuthority.distanceFromRouteStartMeters, frame.head, expected)
        if (frame.speedMetersPerSecond == 0.0 && run.completionProof == proof) return true
        val physical = route.route.physicalHeadProgressMeters(world.topologyState, frame.head, expected)
        if (physical == null || physical > grant.committedAuthority.distanceFromRouteStartMeters + 1.0e-6) {
            stallRun(world, run, "RouteAuthorityViolation", "actual train head ${frame.head} is outside committed " +
                "Basic authority ${grant.committedAuthority.distanceFromRouteStartMeters}; physical progress=$physical")
            return false
        }
        if (frame.speedMetersPerSecond == 0.0) run.completionProof = proof
        return true
    }

    private fun stallRun(
        world: World,
        run: ActiveRun,
        errorType: String,
        reason: String,
        snapshot: TrainSnapshot = runCatching { capturePublishedTrainSnapshot(run) }
            .getOrDefault(run.ato.publishedState.trainSnapshot)
    ) {
        val addressedReason = if (errorType == "TimetableExtensionFailure" || errorType == "TimetableConditionFailure")
            "[${run.service.id}] ${run.programCursor.address ?: "completed"}: $reason" else reason
        val stationCancellation = run.stopExecution?.runtime?.cancelOutstanding()
        val effectiveReason = listOfNotNull(addressedReason, stationCancellation, cancelExtensionAction(run)).joinToString("; ")
        if (run.depotReservationPending) {
            run.depotAllocation?.let(world.simpleAtoWorldData.depots::cancelReservation)
            run.depotReservationPending = false
        }
        val failure = IllegalStateException(effectiveReason)
        world.simpleAtoWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = world.simpleAtoWorldData.atoCoordinator.worldId,
                trainId = run.train.id,
                tick = dispatcherTick,
                errorType = errorType,
                message = effectiveReason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = world.simpleAtoWorldData.atoCoordinator.initialTrain(run.train.id),
                currentTrain = snapshot,
                route = null,
                perception = null,
                constraints = emptyMap()
            )
        )
        retireRunDisplays(world,run)
        run.state = TimetableServiceRunState.FAILED
        run.message = effectiveReason
    }

    private fun publishInitialMovementAuthority(run: ActiveRun, runtime: MissionRuntime) {
        runtime.publishedAuthorityMeters = 0.0
        run.ato.setMovementAuthority(runtime.mission.revision, 0.0)
    }

    private fun updateTailProgress(run: ActiveRun, snapshot: TrainSnapshot) {
        run.referencedRuntimes().forEach { runtime ->
            val publishedOdometer = run.lastPublishedAtoState?.lifetimeTravelDistanceMeters
                ?: run.ato.publishedState.lifetimeTravelDistanceMeters
            val odometerDelta = (publishedOdometer - runtime.lastOdometerMeters).coerceAtLeast(0.0)
            runtime.lastOdometerMeters = publishedOdometer
            val projected = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
            runtime.tailProgressMeters = when {
                projected != null -> projected
                runtime.tailProgressMeters != null &&
                    runtime.tailProgressMeters!! >= runtime.layout.totalLengthMeters - POSITION_TOLERANCE_METERS ->
                    runtime.tailProgressMeters!! + odometerDelta
                else -> runtime.tailProgressMeters
            }
        }
    }

    private fun releaseDepotDeparture(world: World, run: ActiveRun) {
        val allocation = run.depotAllocation ?: return
        if (run.depotDepartureReleased) return
        // Acquisition may be followed by stopped program actions before the first mission.
        // Keep the exact departure occupancy/reservation until that mission is installed.
        if (run.depotReservationPending && run.currentRuntimeOrNull == null) return
        val runtime = run.depotDepartureRuntime
        if (runtime == null) {
            stallRun(
                world,
                run,
                "ResourceIdentityViolation",
                "Depot departure has no Mission runtime"
            )
            return
        }
        val tailProgress = runtime.tailProgressMeters ?: return
        if (tailProgress < DEPOT_DEPARTURE_RELEASE_EPSILON_METERS) return
        if (!world.simpleAtoWorldData.depots.releaseDeparture(allocation)) {
            stallRun(
                world,
                run,
                "ResourceIdentityViolation",
                "Depot departure occupancy disappeared before tail release: $allocation"
            )
            return
        }
        run.depotDepartureReleased = true
    }

    private fun tryCompleteStorage(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime
    ): Boolean {
        if (!validateCommittedAuthority(world, run)) return false
        val reservation = run.storageReservation
        if (reservation == null) {
            stallRun(
                world,
                run,
                "ResourceIdentityViolation",
                "storage Mission has no StablingPoint reservation: ${run.id}"
            )
            return false
        }
        val point = world.simpleAtoWorldData.depots.stablingPoint(reservation.stablingPointRef)
        if (point == null) {
            stallRun(
                world,
                run,
                "InfrastructureInvariantViolation",
                "reserved StablingPoint disappeared: ${reservation.stablingPointRef}"
            )
            return false
        }
        val trainLength = run.train.cars.sumOf { it.length }
        val protectedLength = point.point.usableLengthMeters ?: trainLength
        val stoppingOffset = (runtime.mission.legs.lastOrNull()?.point as? AtoMissionPoint.Stop)?.offsetMetersBefore ?: 0.0
        val storageEntryProgress = (runtime.layout.totalLengthMeters - stoppingOffset - protectedLength)
            .coerceAtLeast(0.0)
        val tailProgress = runtime.tailProgressMeters ?: return false
        if (tailProgress + POSITION_TOLERANCE_METERS < storageEntryProgress) {
            run.state = TimetableServiceRunState.STORING
            run.message = "waiting for full train envelope inside ${reservation.stablingPointRef}"
            return false
        }
        if (run.stationCall?.allocationKey != null) {
            run.state = TimetableServiceRunState.STORING
            run.message = "waiting for old StationBerth tail release before storage"
            return false
        }
        val storageCommitted = if (run.physicalStorageOnly) {
            physicalStorageReservations.remove(reservation.stablingPointRef, run.instanceId)
        } else {
            world.simpleAtoWorldData.depots.completeStorage(reservation)
        }
        if (!storageCommitted) {
            stallRun(
                world,
                run,
                "ResourceIdentityViolation",
                "StablingPoint reservation changed before storage completion: $reservation"
            )
            return false
        }
        run.storageReservation = null
        run.storageCompleted = true
        run.message = "stored at ${reservation.stablingPointRef}; terminal route remains tail-held"
        return true
    }

    private fun reportWaypointProgress(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot
    ) {
        val listeners = waypointProgressListeners[run.waypointProgressKey()]?.toList().orEmpty()
        var cumulativeDistance = 0.0
        runtime.mission.legs.forEachIndexed { index, leg ->
            cumulativeDistance += leg.plannedRoute.expandedRoute.totalLength
            if (index < runtime.reportedPointCount) return@forEachIndexed
            val reached = when (val point = leg.point) {
                is AtoMissionPoint.Via ->
                    runtime.headProgressMeters >= cumulativeDistance - POSITION_TOLERANCE_METERS
                is AtoMissionPoint.Stop -> {
                    val stationOffset = run.stationCall?.takeIf {
                        it.missionRuntime === runtime &&
                            OperationalDestinationIds.stationBerth(
                                it.resolved.berthRef.stationId,
                                it.resolved.berthRef.berthId
                            ) == point.target.id
                    }?.resolved?.stoppingOffsetMetersBefore
                    val offset = stationOffset ?: point.offsetMetersBefore
                    runtime.headProgressMeters >= cumulativeDistance - offset -
                        run.ato.missionStopToleranceMeters &&
                        run.ato.isStopped(snapshot.speedMetersPerSecond)
                }
            }
            if (!reached) return
            if (leg.point is AtoMissionPoint.Stop && !validateCommittedAuthority(world, run)) return
            runtime.reportedPointCount = index + 1
            run.currentSegment?.let { segment ->
                val call = segment.calls.firstOrNull { it.destinationId == leg.point.target.id && it.id !in run.completedCallIds }
                if (call != null) {
                    run.completedCallIds += call.id
                    displayRuntime.reached(run.id.timetableId,run.cycleIndex,run.service.id,segment.runId,call.id,run.currentUnitIds)
                    refreshUnitDisplays(world,run)
                }
            }
            val event = TimetableWaypointProgress(
                runId = run.id,
                cycleIndex = run.cycleIndex,
                waypointId = leg.point.target.id,
                pass = leg.point is AtoMissionPoint.Via,
                timetableElapsedTicks = timetableElapsedTicks(run)
            )
            listeners.forEach { listener -> runCatching { listener.onReached(event) }.onFailure { failure ->
                LOGGER.log(java.util.logging.Level.WARNING,
                    "Timetable waypoint listener failed run=${run.id} cycle=${run.cycleIndex} waypoint=${event.waypointId}", failure)
            } }
        }
    }

    private fun missionCompleted(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot
    ): Boolean {
        val offset = (runtime.mission.legs.lastOrNull()?.point as? AtoMissionPoint.Stop)?.offsetMetersBefore ?: 0.0
        return runtime.headProgressMeters >=
            runtime.layout.totalLengthMeters - offset - run.ato.missionStopToleranceMeters &&
            run.ato.isStopped(snapshot.speedMetersPerSecond) && validateCommittedAuthority(world, run)
    }

    private fun beginStopProgram(
        world: World, run: ActiveRun, programs: StationStopPrograms, side: DoorSide,
        minimumDwellTicks: Long, location: StopProgramLocation
    ) {
        check(run.stopExecution == null) { "station invocation already active" }
        val effectiveSide = programs.doorSide ?: side
        if (effectiveSide == DoorSide.NONE && run.stationDoorsSecured == false) {
            stallRun(world, run, "StationProgramFailure", "doorless stop cannot clear previously open-door safety evidence")
            return
        }
        val invocationId = "${run.instanceId}/stop/${run.stationInvocationSequence++}/${programs.callId}"
        val execution = DispatcherStopExecution(programs, effectiveSide, location, invocationId)
        run.stopExecution = execution
        execution.runtime = StationStopProgramRuntime(programs, dispatcherTick, programs.minimumDwellTicks ?: minimumDwellTicks,
            effectiveSide == DoorSide.NONE, operationTimeoutTicks, StationProgramActionHost { action, taskId, instruction ->
                startStationProgramAction(world, run, execution, action, taskId, instruction)
            })
        run.ato.setSpeedLimit(stationProgramHoldKey(run), 0.0)
        run.state = TimetableServiceRunState.DWELLING
        run.message = "station ${programs.stationId}/${programs.callId} ${programs.role.name.lowercase()} tasks"
    }

    private fun processStopProgram(world: World, run: ActiveRun) {
        val execution = checkNotNull(run.stopExecution)
        if (!run.ato.isStopped(capturePublishedTrainSnapshot(run).speedMetersPerSecond)) {
            stallRun(world, run, "StationProgramFailure", "station tasks require a stopped train")
            return
        }
        execution.runtime.tick(timetableElapsedTicks(run), dispatcherTick)
        execution.runtime.failure?.let { failure ->
            LOGGER.log(java.util.logging.Level.WARNING,
                "station program failed run=${run.instanceId} call=${execution.programs.callId}", failure)
            stallRun(world, run, "StationProgramFailure", failure.message ?: failure.javaClass.name)
            return
        }
        // A physical reversal temporarily owns run.state until publication and grant rebase finish.
        if (run.state == TimetableServiceRunState.REVERSING || run.state == TimetableServiceRunState.FAILED) return
        if (execution.runtime.allTasksComplete && execution.programs.role != StationStopRole.TERMINAL && !execution.runtime.doorsSecured) {
            stallRun(world, run, "StationProgramFailure", "station entries ended without confirmed closed doors")
            return
        }
        if (!execution.runtime.finalGateSatisfied(timetableElapsedTicks(run), dispatcherTick)) return
        run.stationDoorsSecured = execution.runtime.doorsSecured
        run.stopExecution = null
        run.ato.removeSpeedLimit(stationProgramHoldKey(run))
        when (execution.location) {
            StopProgramLocation.ORIGIN -> {
                run.state = TimetableServiceRunState.WAITING_MISSION
                advanceService(world, run)
            }
            StopProgramLocation.DESTINATION -> {
                val stop = checkNotNull(run.destinationStop)
                finishDestinationStop(world, run, stop)
            }
            StopProgramLocation.STATION -> {
                val call = checkNotNull(run.stationCall)
                if (execution.programs.role == StationStopRole.TERMINAL || execution.reversed) {
                    // A terminal call returns to the authored duty suffix, preserving its door state.
                    run.state = TimetableServiceRunState.WAITING_MISSION
                    advanceService(world, run)
                } else enterReadyToDepart(run, call)
            }
        }
    }

    private fun startStationProgramAction(
        world: World, run: ActiveRun, execution: DispatcherStopExecution, action: StationProgramAction,
        taskId: String, instruction: Int
    ): StationProgramTicket? = when (action) {
        StationProgramAction.OpenDoors, StationProgramAction.CloseDoors -> {
            val operation = if (action == StationProgramAction.OpenDoors) TrainOperation.OpenDoors(execution.operatingDoorSide)
                else TrainOperation.CloseDoors(execution.operatingDoorSide)
            val request = TrainOperationRequest(
                "${execution.invocationId}/$taskId/$instruction",
                run.instanceId, execution.invocationId, run.train.id, run.missionRevision, operation)
            val result = operationGateway.execute(request)
            check(result is TrainOperationExecutionResult.Accepted) {
                (result as TrainOperationExecutionResult.Rejected).reason
            }
            try { operationTicket(result.ticket, request) } catch (failure: Exception) {
                try { result.ticket.cancel() } catch (cancellation: Exception) { failure.addSuppressed(cancellation) }
                throw failure
            }
        }
        StationProgramAction.Reverse -> {
            execution.reverseCompleted = false
            beginReverse(world, run)
            check(run.state == TimetableServiceRunState.REVERSING) { run.message ?: "reverse could not start" }
            object : StationProgramTicket {
                override fun poll(): StationProgramTicketResult =
                    if (execution.reverseCompleted) StationProgramTicketResult.Succeeded else StationProgramTicketResult.Pending
                override fun cancel(): String? = if (run.reverseOperation == null) null else "station reversal is in progress; recovery required"
            }
        }
        is StationProgramAction.SetDisplay, is StationProgramAction.ResetDisplay -> {
            val units = registeredOperatingUnits(world, run)
            run.currentUnitIds = units
            val slots = if (action is StationProgramAction.SetDisplay) action.unitSlots else (action as StationProgramAction.ResetDisplay).unitSlots
            val selected = slots.map { slot -> requireNotNull(units.getOrNull(slot - 1)) { "absent registered unit slot $slot" } }
            if (action is StationProgramAction.SetDisplay) displayRuntime.set(run.id.timetableId, run.cycleIndex, selected, actionValues(world, run, action.values, action.trainInfo), action.removeKeys)
            else displayRuntime.set(run.id.timetableId, run.cycleIndex, selected, null)
            refreshUnitDisplays(world, run)
            null
        }
        is StationProgramAction.Extension -> {
            val provider = requireNotNull(world.simpleAtoWorldData.timetableExtensions.action(action.call.typeId, action.call.version)) {
                "action provider is not registered: ${action.call.typeId}@${action.call.version}"
            }
            val parameters = actionValues(world, run, action.call.parameters, action.trainInfo)
            if (action.trainInfo.isNotEmpty()) {
                val errors = provider.validate(parameters)
                require(errors.isEmpty()) { errors.joinToString("; ") }
            }
            val result = provider.start(parameters, extensionContext(world, run, null))
            check(result is TimetableExtensionActionStartResult.Accepted) {
                (result as TimetableExtensionActionStartResult.Rejected).reason
            }
            object : StationProgramTicket {
                override fun poll(): StationProgramTicketResult {
                    val snapshot = result.ticket.snapshot()
                    return when (snapshot.state) {
                        TimetableExtensionActionState.PENDING -> StationProgramTicketResult.Pending
                        TimetableExtensionActionState.SUCCEEDED -> StationProgramTicketResult.Succeeded
                        TimetableExtensionActionState.FAILED -> StationProgramTicketResult.Failed(snapshot.message ?: "extension failed")
                        TimetableExtensionActionState.CANCELLED -> StationProgramTicketResult.Cancelled("extension cancelled")
                    }
                }
                override fun cancel(): String? {
                    val state = try { result.ticket.snapshot().state } catch (failure: Exception) {
                        try { result.ticket.cancel() } catch (cancellation: Exception) { failure.addSuppressed(cancellation) }
                        return "extension status/cancellation cannot be confirmed: ${failure.message}"
                    }
                    if (state != TimetableExtensionActionState.PENDING) return null
                    result.ticket.cancel()
                    return if (result.ticket.snapshot().state == TimetableExtensionActionState.CANCELLED) null else "extension cancellation was not confirmed"
                }
            }
        }
        else -> error("station task scheduler must handle $action")
    }

    private fun operationTicket(ticket: TrainOperationTicket, request: TrainOperationRequest): StationProgramTicket {
        return object : StationProgramTicket {
            private var ticketId: String? = null
            private fun checkedSnapshot(): work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot {
                val snapshot = ticket.snapshot()
                check(ticket.request == request && snapshot.requestId == request.requestId) { "station operation request identity changed" }
                check(ticketId == null || snapshot.ticketId == ticketId) { "station operation ticket identity changed" }
                ticketId = snapshot.ticketId
                return snapshot
            }
            override fun poll(): StationProgramTicketResult {
                val snapshot = checkedSnapshot()
                return when (snapshot.state) {
                    TrainOperationTicketState.PENDING -> StationProgramTicketResult.Pending
                    TrainOperationTicketState.SUCCEEDED -> StationProgramTicketResult.Succeeded
                    TrainOperationTicketState.FAILED -> StationProgramTicketResult.Failed(snapshot.message ?: "train operation failed")
                    TrainOperationTicketState.CANCELLED -> StationProgramTicketResult.Cancelled("train operation cancelled")
                }
            }
            override fun cancel(): String? {
                val before = try { checkedSnapshot() } catch (failure: Exception) {
                    // Keep the task's handle and attempt cancellation even when status is unreadable.
                    try { ticket.cancel() } catch (cancellation: Exception) { failure.addSuppressed(cancellation) }
                    return "train operation status/cancellation cannot be confirmed: ${failure.message}"
                }
                if (before.state != TrainOperationTicketState.PENDING) return null
                ticket.cancel()
                return if (checkedSnapshot().state == TrainOperationTicketState.CANCELLED) null
                    else "train operation cancellation was not confirmed"
            }
        }
    }

    private fun advanceService(world: World, run: ActiveRun) {
        if (!validateCommittedAuthority(world, run)) return
        while (true) {
            if (run.programCursor.isComplete) break
            if (!enterRunSegment(world, run)) return
            run.activeStepAddress = run.programCursor.address
            when (val step = checkNotNull(run.programCursor.currentStep())) {
                is TimetableStep.Check -> {
                    val failure = validateAtWayPoint(world, run.train, step.wayPointId)
                    if (failure != null) {
                        stallRun(world, run, "InfrastructureInvariantViolation", failure)
                        return
                    }
                    run.currentWayPointId = step.wayPointId
                    run.physicalOriginPending = false
                    run.programCursor = run.programCursor.advance()
                }
                is TimetableStep.At -> when (val action = step.action) {
                    is TimetableAction.TaskStart -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        if (run.taskTimings.any { it.taskId == action.taskId || it.endTick == null }) {
                            stallRun(world, run, "TaskBoundaryViolation", "duplicate or overlapping task start")
                            return
                        }
                        run.taskTimings += TimetableTaskTiming(action.taskId, timetableElapsedTicks(run))
                        run.programCursor = run.programCursor.advance()
                    }
                    is TimetableAction.TaskEnd -> {
                        val timing = run.taskTimings.lastOrNull()
                        // Restored checkpoints intentionally have no transient measurement history.
                        if (timing == null) {
                            run.programCursor = run.programCursor.advance()
                            continue
                        }
                        if (timing.taskId != action.taskId || timing.endTick != null) {
                            stallRun(world, run, "TaskBoundaryViolation", "task end does not match active task")
                            return
                        }
                        run.taskTimings[run.taskTimings.lastIndex] = timing.copy(endTick = timetableElapsedTicks(run))
                        run.programCursor = run.programCursor.advance()
                    }
                    is TimetableAction.BeginStationStop -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        if (!run.ato.isStopped(capturePublishedTrainSnapshot(run).speedMetersPerSecond)) {
                            stallRun(world, run, "StationProgramFailure", "origin station program requires a stationary train")
                            return
                        }
                        val target = action.stopPrograms.destinationId ?: run.currentWayPointId
                        validateAtWayPoint(world, run.train, target)?.let { reason ->
                            stallRun(world, run, "StationProgramFailure", reason)
                            return
                        }
                        run.currentWayPointId = target
                        run.physicalOriginPending = false
                        run.programCursor = run.programCursor.advance()
                        val destination = world.simpleAtoWorldData.destinations[target]
                        val side = (destination?.role as? work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole)?.doorSide ?: DoorSide.NONE
                        beginStopProgram(world, run, action.stopPrograms, side, 0L, StopProgramLocation.ORIGIN)
                        return
                    }
                    is TimetableAction.BeginOperationalTrip -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run,step.tick,pendingStep=true)
                            return
                        }
                        val failure=runCatching {
                            val timetable=checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
                            val units=registeredOperatingUnits(world,run)
                            displayRuntime.begin(timetable,run.cycleIndex,run.service,action,units)
                            proveUnitFuture(world,run,timetable,run.service.runSegments.single { it.runId==action.runId },units)
                            run.currentUnitIds=units
                            refreshUnitDisplays(world,run)
                        }.exceptionOrNull()
                        if(failure!=null) { stallRun(world,run,"DisplayContextFailure",failure.message ?: failure.javaClass.name); return }
                        recordScheduledAction(run,step.tick)
                        run.programCursor=run.programCursor.advance()
                    }
                    is TimetableAction.SetDisplay, is TimetableAction.ResetDisplay -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        val failure = runCatching {
                            val units = registeredOperatingUnits(world, run)
                            run.currentUnitIds = units
                            val slots = if (action is TimetableAction.SetDisplay) action.unitSlots else (action as TimetableAction.ResetDisplay).unitSlots
                            val selected = slots.map { slot -> requireNotNull(units.getOrNull(slot - 1)) { "absent registered unit slot $slot" } }
                            if (action is TimetableAction.SetDisplay) displayRuntime.set(run.id.timetableId,run.cycleIndex,selected,actionValues(world, run, action.values, action.trainInfo),action.removeKeys)
                            else displayRuntime.set(run.id.timetableId,run.cycleIndex,selected,null)
                            refreshUnitDisplays(world,run)
                        }.exceptionOrNull()
                        if (failure != null) {
                            stallRun(world,run,"DisplayContextFailure",failure.message ?: failure.javaClass.name)
                            return
                        }
                        recordScheduledAction(run,step.tick)
                        run.programCursor = run.programCursor.advance()
                    }
                    is TimetableAction.Split -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        beginFixedSplit(world, run, action)
                        return
                    }
                    is TimetableAction.Couple -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        beginFixedCouple(world, run, action)
                        return
                    }
                    is TimetableAction.TakeFromFormationOutput -> {
                        stallRun(world, run, "FormationExecutionUnavailable",
                            "fixed formation coordinator is not yet attached")
                        return
                    }
                    is TimetableAction.StoreAtDepot -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        val allocation = run.depotAllocation
                        if (allocation != null && !run.depotDepartureReleased) {
                            stallRun(
                                world,
                                run,
                                "ResourceIdentityViolation",
                                "storeAtDepot requires the physical departure envelope to be released"
                            )
                            return
                        }
                        val structuralOutput = run.structuralOutput
                        val structuralStock = if (structuralOutput == null) null else
                            world.simpleAtoWorldData.depots.rollingStock(run.train.id)
                        val assignment = allocation?.assignmentId ?: structuralStock?.assignmentId
                        val structuralPoints = if (structuralOutput == null) null else {
                            if (structuralStock?.state != RollingStockState.IN_SERVICE || assignment == null) {
                                stallRun(world, run, "ResourceIdentityViolation", "structural output lost its IN_SERVICE assignment")
                                return
                            }
                            val candidates = run.structural.storageRoutes.filterKeys { key ->
                                key.eventId == structuralOutput.first && key.outputId == structuralOutput.second &&
                                    key.serviceId == run.service.id && key.stepIndex == run.nextStepIndex &&
                                    key.sourceCandidate == run.structuralCandidate && key.branchTrace == run.branchTrace &&
                                    key.startFacing == run.train.publishedFrame.head.facing &&
                                    key.stablingPointRef.depotId == action.depotId &&
                                    (action.stablingPointIds.isEmpty() || key.stablingPointRef.pointId in action.stablingPointIds)
                            }
                            if (candidates.isEmpty() || candidates.any { (key, evidence) ->
                                    world.simpleAtoWorldData.depots.stablingPoint(key.stablingPointRef)?.point != evidence.stablingPoint ||
                                        structuralStock.record != evidence.metadata.toRecord(run.train.id)
                                }) {
                                stallRun(world, run, "ResourceIdentityViolation", "structural storage classification or candidate proof changed")
                                return
                            }
                            candidates.keys.mapTo(linkedSetOf()) { it.stablingPointRef.pointId }
                        }
                        val reservation = if (assignment == null) {
                            val trainLength = run.train.cars.sumOf { it.length }
                            val point = world.simpleAtoWorldData.depots.stablingPoints(action.depotId)
                                .asSequence()
                                .filter {
                                    action.stablingPointIds.isEmpty() ||
                                        it.point.ref.pointId in action.stablingPointIds
                                }
                                .filter {
                                    val usableLength = it.point.usableLengthMeters
                                    usableLength == null ||
                                        usableLength + POSITION_TOLERANCE_METERS >= trainLength
                                }
                                .filter { it.point.ref !in physicalStorageReservations }
                                .filter { candidate ->
                                    world.allTrains.filterIsInstance<CoreTrain>().none { other ->
                                        other.id != run.train.id &&
                                            validateAtWayPoint(world, other, candidate.point.destinationId) == null
                                    }
                                }
                                .sortedBy { it.point.ref.pointId }
                                .firstOrNull()
                            if (point == null) {
                                stallRun(
                                    world,
                                    run,
                                    "DepotAllocationFailure",
                                    "no physically empty compatible StablingPoint in ${action.depotId}"
                                )
                                return
                            }
                            physicalStorageReservations[point.point.ref] = run.instanceId
                            run.physicalStorageOnly = true
                            DepotStorageReservation(
                                "physical:${run.instanceId}",
                                run.train.id,
                                point.point.ref
                            )
                        } else when (val result = world.simpleAtoWorldData.depots.reserveStorage(
                            trainId = run.train.id,
                            depotId = action.depotId,
                            requestedPointIds = structuralPoints ?: action.stablingPointIds,
                            assignmentId = assignment,
                            stableSeed = "${run.id.timetableId}|${run.cycleIndex}|" +
                                "${run.id.serviceId}|store|${run.nextStepIndex}"
                        )) {
                            is DepotStorageReservationResult.Reserved -> result.reservation
                            is DepotStorageReservationResult.NoCompatiblePoint -> {
                                val reason = "no compatible empty StablingPoint in ${result.depotId}; " +
                                    "requested=${result.requestedPointIds.sorted()}"
                                stallRun(world, run, "DepotAllocationFailure", reason)
                                return
                            }
                            is DepotStorageReservationResult.RollingStockUnavailable -> {
                                val reason = "RollingStock ${result.trainId} cannot be stored: ${result.reason}"
                                stallRun(world, run, "DepotAllocationFailure", reason)
                                return
                            }
                        }
                        val point = world.simpleAtoWorldData.depots
                            .stablingPoint(reservation.stablingPointRef)
                        val target = point?.point?.destinationId
                        if (target == null) {
                            if (run.physicalStorageOnly) {
                                physicalStorageReservations.remove(reservation.stablingPointRef, run.instanceId)
                            } else {
                                world.simpleAtoWorldData.depots.cancelStorage(reservation)
                            }
                            stallRun(
                                world,
                                run,
                                "InfrastructureInvariantViolation",
                                "reserved StablingPoint disappeared: ${reservation.stablingPointRef}"
                            )
                            return
                        }
                        val movementStep = TimetableStep.At(step.tick, TimetableAction.GoTo(target))
                        val plan = movement(
                            world, run.id,
                            WayPointRef(run.currentWayPointId),
                            movementStep,
                            checkNotNull(run.programCursor.address),
                            run.programCursor.advance()
                        ).copy(requiredStartDirection = run.requiredNextStartDirection)
                        val submission = submitMissionForRun(world, run, plan)
                        val accepted = submission.accepted
                        if (accepted == null) {
                            val cancelled = if (run.physicalStorageOnly) {
                                physicalStorageReservations.remove(
                                    reservation.stablingPointRef,
                                    run.instanceId
                                )
                            } else {
                                world.simpleAtoWorldData.depots.cancelStorage(reservation)
                            }
                            if (!cancelled) {
                                stallRun(
                                    world,
                                    run,
                                    "ResourceIdentityViolation",
                                    "failed storage Mission lost its reservation: $reservation"
                                )
                                return
                            }
                            stallRun(
                                world,
                                run,
                                "InfrastructureInvariantViolation",
                                submission.failure!!
                            )
                            return
                        }
                        val runtime = MissionRuntime(
                            mission = accepted.mission,
                            layout = accepted.layout,
                            priorityTick = plan.priorityTick,
                            targetWayPointId = plan.target.id,
                            address = plan.address,
                            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
                        )
                        run.currentRuntime = runtime
                        run.missionRevision = accepted.mission.revision
                        run.programCursor = plan.successor
                        run.storageReservation = reservation
                        run.storageRuntime = runtime
                        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
                            run.ato.removeSpeedLimit(stopKey(run))
                            publishInitialMovementAuthority(run, runtime)
                        }
                        run.state = TimetableServiceRunState.STORING
                        run.message = "storing at ${reservation.stablingPointRef}"
                        return
                    }
                    is TimetableAction.TakeFromDepot,
                    is TimetableAction.TakeFromWaypoint -> {
                        stallRun(
                            world,
                            run,
                            "ResourceIdentityViolation",
                            "train acquisition is only valid as the first service step"
                        )
                        return
                    }
                    TimetableAction.WaitUntil -> {
                        if (run.controlMode != TimetableControlMode.MANUAL_ROUTED) {
                            if (!run.ato.isStopped(capturePublishedTrainSnapshot(run).speedMetersPerSecond)) {
                                stallRun(world, run, "InfrastructureInvariantViolation", "waitUntil requires a stopped train")
                                return
                            }
                            run.ato.setSpeedLimit(stopKey(run), 0.0)
                            if (timetableElapsedTicks(run) < step.tick) {
                                waitForTimetableTick(run, step.tick, pendingStep = true)
                                return
                            }
                        }
                        recordScheduledAction(run, step.tick)
                        run.programCursor = run.programCursor.advance()
                    }
                    TimetableAction.Halt -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.programCursor = run.programCursor.advance()
                        beginHalt(world, run)
                        return
                    }
                    TimetableAction.Reverse -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.programCursor = run.programCursor.advance()
                        beginReverse(world, run)
                        return
                    }
                    is TimetableAction.Stop -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            recordScheduledAction(run, step.tick)
                            run.programCursor = run.programCursor.advance()
                            run.message = "MANUAL_ROUTED skipped automatic dwell hold"
                            continue
                        }
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.programCursor = run.programCursor.advance()
                        run.dwellTicksRemaining = action.ticks
                        run.ato.setSpeedLimit(stopKey(run), 0.0)
                        run.state = TimetableServiceRunState.DWELLING
                        run.message = "dwell ${action.ticks} ticks"
                        return
                    }
                    is TimetableAction.DestinationOptions -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            run.pendingDestinationOptions = null
                            run.programCursor = run.programCursor.advance()
                            run.message = "MANUAL_ROUTED skipped automatic destination options"
                            continue
                        }
                        run.pendingDestinationOptions = action
                        run.programCursor = run.programCursor.advance()
                    }
                    is TimetableAction.CallStation -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            run.pendingStationCallSpec = null
                            run.programCursor = run.programCursor.advance()
                            run.message = "MANUAL_ROUTED skipped automatic station operation"
                            continue
                        }
                        run.pendingStationCallSpec = StationCallSpec(
                            action = action,
                            address = checkNotNull(run.programCursor.address)
                        )
                        run.programCursor = run.programCursor.advance()
                    }
                    is TimetableAction.Extension -> {
                        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
                            run.ato.setSpeedLimit(stopKey(run), 0.0)
                        }
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        startExtensionAction(world, run, action, step.tick)
                        return
                    }
                    is TimetableAction.Conditional -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        val arm = when (val result = world.simpleAtoWorldData.timetableExtensions.evaluate(
                            action.condition, extensionContext(world, run, step.tick), TimetableConditionScope.RUNTIME_BRANCH)) {
                            TimetableConditionResult.True -> TimetableBranchArm.THEN
                            TimetableConditionResult.False -> TimetableBranchArm.ELSE
                            is TimetableConditionResult.Unavailable -> {
                                stallRun(world, run, "TimetableConditionFailure", result.reason)
                                return
                            }
                            is TimetableConditionResult.Error -> {
                                stallRun(world, run, "TimetableConditionFailure", result.message)
                                return
                            }
                        }
                        run.branchTrace = run.branchTrace.record(run.service.id, checkNotNull(run.programCursor.address), arm)
                        run.programCursor = run.programCursor.enter(arm)
                        continue
                    }
                    is TimetableAction.SelectStop -> {
                        val plan = prepareSelectedStop(world, run, step, action) ?: return
                        startMovementPlan(world, run, plan)
                        return
                    }
                    is TimetableAction.GoTo,
                    is TimetableAction.GoVia -> {
                        val physicalStartDirection =
                            (run.train.headLocation as
                                work.art1st.javarailways.core.physics.rail.RailLocationWithFacing).facing
                        val plan = movement(
                            world, run.id,
                            WayPointRef(run.currentWayPointId),
                            step,
                            checkNotNull(run.programCursor.address),
                            run.programCursor.advance(),
                            run.pendingStationCallSpec,
                            run.pendingDestinationOptions
                        ).copy(
                            requiredStartDirection =
                                run.requiredNextStartDirection ?: physicalStartDirection
                        )
                        startMovementPlan(world, run, plan)
                        return
                    }
                }
            }
        }
        run.state = TimetableServiceRunState.COMPLETED
        if(run.currentUnitIds.isNotEmpty()) {
            val timetable=checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
            displayRuntime.finish(timetable,run.cycleIndex,run.service.id,run.currentUnitIds)
            refreshUnitDisplays(world,run)
        }
        run.message = "service completed; route remains held until the tail clears"
    }

    private fun startExtensionAction(world: World, run: ActiveRun, action: TimetableAction.Extension, scheduledTick: Long) {
        val call = action.call
        val provider = world.simpleAtoWorldData.timetableExtensions.action(call.typeId, call.version)
        if (provider == null) {
            stallRun(world, run, "TimetableExtensionFailure", "action provider is not registered: ${call.typeId}@${call.version}")
            return
        }
        val result = runCatching {
            val parameters = actionValues(world, run, call.parameters, action.trainInfo)
            if (action.trainInfo.isNotEmpty()) {
                val errors = provider.validate(parameters)
                require(errors.isEmpty()) { errors.joinToString("; ") }
            }
            provider.start(parameters, extensionContext(world, run, scheduledTick))
        }.getOrElse {
            stallRun(world, run, "TimetableExtensionFailure", "action provider ${call.typeId}@${call.version} threw: ${it.message}")
            return
        }
        when (result) {
            is TimetableExtensionActionStartResult.Accepted -> {
                run.extensionAction = DispatcherExtensionAction(result.ticket, call.typeId, scheduledTick, dispatcherTick, run.programCursor.advance())
                run.state = TimetableServiceRunState.WAITING_EXTENSION_ACTION
                run.message = "waiting for extension action ${call.typeId}"
            }
            is TimetableExtensionActionStartResult.Rejected -> stallRun(world, run, "TimetableExtensionFailure", result.reason)
        }
    }

    private fun pollExtensionAction(world: World, run: ActiveRun) {
        val active = run.extensionAction ?: run {
            stallRun(world, run, "TimetableExtensionFailure", "extension action ticket disappeared")
            return
        }
        val snapshot = runCatching { active.ticket.snapshot() }.getOrElse {
            stallRun(
                world,
                run,
                "TimetableExtensionFailure",
                "extension action ${active.typeId} snapshot failed: " +
                    (it.message ?: it::class.java.simpleName)
            )
            return
        }
        when (snapshot.state) {
            TimetableExtensionActionState.PENDING -> {
                if (dispatcherTick - active.startedDispatcherTick >= operationTimeoutTicks) {
                    val cancellationFailure = cancelPendingOperation(run)
                    stallRun(
                        world,
                        run,
                        "TimetableExtensionFailure",
                        buildString {
                            append("extension action ${active.typeId} timed out after ")
                            append("$operationTimeoutTicks ticks")
                            cancellationFailure?.let { append("; ").append(it) }
                        }
                    )
                }
            }
            TimetableExtensionActionState.SUCCEEDED -> {
                run.programCursor = active.successor
                run.extensionAction = null
                advanceService(world, run)
            }
            TimetableExtensionActionState.FAILED -> stallRun(
                world,
                run,
                "TimetableExtensionFailure",
                snapshot.message ?: "extension action ${active.typeId} failed"
            )
            TimetableExtensionActionState.CANCELLED -> stallRun(
                world,
                run,
                "TimetableExtensionFailure",
                "extension action ${active.typeId} was unexpectedly cancelled"
            )
        }
    }

    private fun extensionContext(
        world: World,
        run: ActiveRun,
        scheduledTick: Long?
    ): TimetableRuntimeContext {
        val record = world.simpleAtoWorldData.depots.rollingStock(run.train.id)?.record
        return TimetableRuntimeContext(
            timetableId = run.id.timetableId,
            serviceId = run.id.serviceId,
            cycleIndex = run.cycleIndex,
            timetableElapsedTicks = timetableElapsedTicks(run),
            scheduledTick = scheduledTick.takeIf { run.stopExecution == null },
            latenessTicks = scheduledTick.takeIf { run.stopExecution == null }?.let { (timetableElapsedTicks(run) - it).coerceAtLeast(0L) }
                ?: run.lastLatenessTicks,
            currentWayPointId = run.currentWayPointId.takeUnless { run.physicalOriginPending },
            train = TimetableTrainContext(
                trainId = run.train.id,
                modelPackageId = record?.modelPackageId,
                tags = record?.tags.orEmpty(),
                capabilities = record?.capabilities.orEmpty(),
                carCount = record?.carCount ?: run.train.cars.size
            )
        )
    }

    private fun cancelExtensionAction(run: ActiveRun): String? {
        val active = run.extensionAction ?: return null
        val before = try {
            active.ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelExtensionAction snapshot failed run=${run.instanceId} train=${run.train.id}", failure)
            return "extension action ${active.typeId} snapshot failed before cancellation: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        if (before.state != TimetableExtensionActionState.PENDING) {
            run.extensionAction = null
            return null
        }
        try {
            active.ticket.cancel()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelExtensionAction failed run=${run.instanceId} train=${run.train.id}", failure)
            return "extension action ${active.typeId} cancellation failed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        val snapshot = try {
            active.ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelExtensionAction failed run=${run.instanceId} train=${run.train.id}", failure)
            return "extension action ${active.typeId} cancellation could not be confirmed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        if (snapshot.state != TimetableExtensionActionState.CANCELLED) {
            return "extension action ${active.typeId} remained ${snapshot.state} after cancellation"
        }
        run.extensionAction = null
        return null
    }

    private fun cancelPendingOperation(run: ActiveRun): String? {
        run.stopExecution?.runtime?.cancelOutstanding()?.let { return it }
        if (run.formationBlocked) return "fixed formation operation owns this participant; explicit recovery is required"
        cancelExtensionAction(run)?.let { return it }
        val ticket = run.stationCall?.operationTicket
            ?: run.destinationStop?.operationTicket
            ?: run.haltOperation?.ticket
            ?: return null
        val before = try {
            ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelPendingOperation failed run=${run.instanceId} train=${run.train.id}", failure)
            return "operation ticket snapshot failed before cancellation: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        val expectedTicketId = run.stationCall?.operationTicketId
            ?: run.destinationStop?.operationTicketId
            ?: run.haltOperation?.ticketId
        if (before.requestId != ticket.request.requestId ||
            expectedTicketId != null && before.ticketId != expectedTicketId
        ) {
            return "operation ticket identity changed before cancellation: " +
                "expected=${expectedTicketId ?: before.ticketId}/${ticket.request.requestId}, " +
                "actual=${before.ticketId}/${before.requestId}"
        }
        when (before.state) {
            TrainOperationTicketState.PENDING -> Unit
            TrainOperationTicketState.SUCCEEDED,
            TrainOperationTicketState.FAILED,
            TrainOperationTicketState.CANCELLED -> return null
        }
        return cancelTicketAndConfirm(ticket, before.ticketId, before.requestId)
    }

    private fun withCancellationResult(
        reason: String,
        ticket: TrainOperationTicket,
        ticketId: String
    ): String = cancelTicketAndConfirm(ticket, ticketId, ticket.request.requestId)
        ?.let { "$reason; $it" } ?: reason

    private fun cancelTicketAndConfirm(
        ticket: TrainOperationTicket,
        ticketId: String,
        requestId: String
    ): String? {
        try {
            ticket.cancel()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelTicketAndConfirm failed ticket=$ticketId request=$requestId", failure)
            return "operation $ticketId cancellation failed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        val after = try {
            ticket.snapshot()
        } catch (failure: Exception) {
            LOGGER.log(java.util.logging.Level.WARNING, "cancelTicketAndConfirm failed ticket=$ticketId request=$requestId", failure)
            return "operation $ticketId cancellation could not be confirmed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        if (after.ticketId != ticketId || after.requestId != requestId) {
            return "operation ticket identity changed while confirming cancellation: " +
                "expected=$ticketId/$requestId, actual=${after.ticketId}/${after.requestId}"
        }
        return if (after.state == TrainOperationTicketState.CANCELLED) {
            null
        } else {
            "operation $ticketId remained ${after.state.name.lowercase()} after cancellation"
        }
    }

    private fun finishControllerIfReleased(run: ActiveRun, snapshot: TrainSnapshot) {
        if (run.stationCall?.allocationKey != null ||
            run.admissionClient != null && !run.admissionEnded ||
            !run.ato.isStopped(snapshot.speedMetersPerSecond)
        ) return
        if (run.controllerOwned && run.train.controller === run.ato) {
            run.train.setController(null)
        }
    }

    private fun bindWorld(world: World) {
        require(standaloneSimulationServices.isEmpty() ||
            world.executionMode == work.art1st.javarailways.core.api.physics.WorldExecutionMode.SIMULATION) {
            "standalone simulation services cannot be used in a live world"
        }
        val currentWorld = boundWorld
        check(currentWorld == null || currentWorld === world) {
            "TimetableDispatcher cannot be shared between worlds"
        }
        if (currentWorld == null) {
            boundWorld = world
        }
    }

    private fun ensureOwner(world: World? = boundWorld) {
        (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("TimetableDispatcher mutation")
    }

    private fun validator(world: World, timetableId: String) = TimetableValidator(world,
        standaloneSimulationServices.filter { it.timetableId == timetableId }.mapTo(HashSet()) { it.serviceId })

    private fun compileAndInstallAppliedPlan(
        world: World,
        timetableId: String,
        previous: AppliedTimetablePlan?,
        validatedRoutes: TimetableValidationResult? = null
    ): AppliedPlanInstallResult {
        val timetable = world.simpleAtoWorldData.timetables[timetableId]
            ?: return AppliedPlanInstallResult.Rejected("timetable not found: $timetableId")
        val validation = validatedRoutes ?: validator(world, timetableId).validate(timetable)
        if (!validation.isSuccess) {
            return AppliedPlanInstallResult.Rejected(validation.errors.joinToString("; "))
        }
        val routes = validation.routes
        val topology = world.topologyState
        val selectedSnapshots = routes.mapIndexed { index, route ->
            route.toSnapshot(
                routeId = "simpleato:applied/${timetableOwnerToken(timetableId)}/$index",
                revision = 0L,
                topologyRevision = topology.revision
            )
        }
        val structuralSourceSnapshots = validation.structural.sources.values.map { it.envelope.retainedPath }.distinct()
        // Keep the certified structural corridors unchanged. Their forward eligibility windows
        // do not replace the global braking margin needed by the future moving candidate.
        val terminalSelections = selectedSnapshots.mapIndexed { index, route ->
            route to validation.routeStoppingOffsetsMeters[index]
        } + validation.structural.routes.values.map {
            it.proof.onward to it.stoppingOffsetFromRetainedEndMeters
        }
        val physicalDependencies = when (
            val compiled = PhysicalRouteDependencyCompiler.compile(topology,
                terminalSelections.map { it.first } + structuralSourceSnapshots)
        ) {
            is PhysicalRouteDependencyCompilation.Compiled -> compiled.dependencies
            PhysicalRouteDependencyCompilation.NoDependencies -> {
                if (previous == null) return AppliedPlanInstallResult.Installed(null)
                val removed = infrastructureDependencyService.remove(previous.owner)
                return if (removed.successful) {
                    AppliedPlanInstallResult.Installed(null)
                } else {
                    AppliedPlanInstallResult.Rejected(buildString {
                        append("could not clear applied timetable '$timetableId': ${removed.status}")
                        if (removed.detail.isNotBlank()) append(": ${removed.detail}")
                    })
                }
            }
            is PhysicalRouteDependencyCompilation.Rejected ->
                return AppliedPlanInstallResult.Rejected(
                    "could not compile applied timetable '$timetableId': ${compiled.detail}"
                )
        }
        if (!world.topologyState.preservesRails(topology, physicalDependencies.railIds)) {
            return AppliedPlanInstallResult.Rejected(
                "selected route dependencies changed while compiling applied timetable '$timetableId'"
            )
        }
        val guardOwner = InfrastructureOwner(
            "simpleato:timetable/${timetableOwnerToken(timetableId)}",
            nextAppliedPlanRevision(previous?.owner?.revision)
        )
        val guardDependencies = if (previous == null) physicalDependencies else InfrastructureDependencies(
            physicalDependencies.railIds + previous.dependencies.railIds,
            physicalDependencies.topologyNodeKeys + previous.dependencies.topologyNodeKeys,
            physicalDependencies.resourceIds + previous.dependencies.resourceIds
        )
        val guardResult = infrastructureDependencyService.set(
            InfrastructureDependencyRequest(guardOwner, guardDependencies)
        )
        if (!guardResult.successful) {
            return AppliedPlanInstallResult.Rejected(buildString {
                append("could not guard applied timetable '$timetableId': ${guardResult.status}")
                if (guardResult.detail.isNotBlank()) append(": ${guardResult.detail}")
            })
        }
        var guard = AppliedTimetablePlan(guardOwner, guardDependencies, validation.retainedRoutes, validation.structural, timetable)
        appliedTimetablePlans[timetableId] = guard
        fun rejectGuardedPlan(reason: String): AppliedPlanInstallResult.Rejected {
            val rollbackFailure = rollbackAppliedPlan(timetableId, guard, previous)
            if (rollbackFailure != null) {
                // The registry still holds the union; retain its exact owner for cleanup/recovery.
                stallTimetableRuntime(world, TimetableServiceRunId(timetableId, "<applied-plan>"),
                    "InfrastructureDependencyFailure", "applied dependency rollback failed: $rollbackFailure")
            }
            return AppliedPlanInstallResult.Rejected(reason +
                (rollbackFailure?.let { "; applied dependency rollback failed: $it" } ?: ""))
        }
        // Protect the original selection before invoking plugin geometry. A concurrent definition
        // edit must not replace the provider between completion and dependency collection. Then
        // protect all newly added rails before collecting their registered resource references.
        fun upgradeGuard(physical: InfrastructureDependencies): String? {
            val expanded = InfrastructureDependencies(guard.dependencies.railIds + physical.railIds,
                guard.dependencies.topologyNodeKeys + physical.topologyNodeKeys,
                guard.dependencies.resourceIds + physical.resourceIds)
            if (expanded == guard.dependencies) return null
            val owner = InfrastructureOwner(guard.owner.ownerId, nextAppliedPlanRevision(guard.owner.revision))
            val result = try {
                infrastructureDependencyService.set(InfrastructureDependencyRequest(owner, expanded))
            } catch (failure: Exception) { return failure.message ?: failure.javaClass.name }
            if (!result.successful) return "${result.status}: ${result.detail}"
            guard = AppliedTimetablePlan(owner, expanded, guard.retainedRoutes, guard.structural, guard.definition)
            appliedTimetablePlans[timetableId] = guard
            return null
        }
        val proposals = try {
            terminalSelections.map { (route, offset) ->
                if (offset == null || offset >= authorityStoppingMarginMeters) TerminalMarginProposal(route)
                else terminalMarginProposal(world, route, route.totalLengthMeters - offset)
            }
        } catch (failure: Exception) {
            return rejectGuardedPlan("could not complete applied timetable '$timetableId': " +
                (failure.message ?: failure.javaClass.name))
        }
        when (val physical = PhysicalRouteDependencyCompiler.compile(topology, proposals.map { it.route } + structuralSourceSnapshots)) {
            is PhysicalRouteDependencyCompilation.Compiled -> upgradeGuard(physical.dependencies)?.let {
                return rejectGuardedPlan("could not protect proposed terminal margin: $it")
            }
            is PhysicalRouteDependencyCompilation.Rejected -> return rejectGuardedPlan(physical.detail)
            PhysicalRouteDependencyCompilation.NoDependencies -> return rejectGuardedPlan("terminal proposal lost its selected path")
        }
        val snapshots = try {
            terminalSelections.zip(proposals).map { (selection, proposal) ->
                val (route, offset) = selection
                inspectionRuntime.completeTerminalRoute(route, proposal.route, proposal.failure).also {
                    val requiredEnd = if (offset == null) route.totalLengthMeters
                        else route.totalLengthMeters - offset + authorityStoppingMarginMeters
                    requireLegalTerminalPrefix(world, it, requiredEnd, route.totalLengthMeters)
                    routeDirectionLimit(world, it).requireLegalThrough(requiredEnd)
                }
            }
        } catch (failure: Exception) {
            return rejectGuardedPlan("could not complete applied timetable '$timetableId': ${failure.message}")
        }
        val completedPhysical = when (val compiled = PhysicalRouteDependencyCompiler.compile(topology, snapshots + structuralSourceSnapshots)) {
            is PhysicalRouteDependencyCompilation.Compiled -> compiled.dependencies
            is PhysicalRouteDependencyCompilation.Rejected -> return rejectGuardedPlan(
                "could not compile completed timetable '$timetableId': ${compiled.detail}")
            PhysicalRouteDependencyCompilation.NoDependencies -> return rejectGuardedPlan(
                "completed timetable '$timetableId' lost its selected physical route")
        }
        upgradeGuard(completedPhysical)?.let { return rejectGuardedPlan("could not protect completed timetable '$timetableId': $it") }
        val signalResources = try {
            inspectionRuntime.collectRouteInfrastructureDependencies(snapshots + structuralSourceSnapshots)
        } catch (failure: Exception) {
            return rejectGuardedPlan("could not compile applied timetable '$timetableId': " +
                (failure.message ?: failure.javaClass.name))
        }
        val dependencies = if (signalResources.isEmpty()) completedPhysical else InfrastructureDependencies(
            completedPhysical.railIds,
            completedPhysical.topologyNodeKeys,
            completedPhysical.resourceIds + signalResources
        )
        if (!world.topologyState.preservesRails(topology, completedPhysical.railIds)) {
            return rejectGuardedPlan("selected route dependencies changed while compiling applied timetable '$timetableId'")
        }
        if (dependencies == guard.dependencies) return AppliedPlanInstallResult.Installed(guard)
        val owner = InfrastructureOwner(guard.owner.ownerId, nextAppliedPlanRevision(guard.owner.revision))
        val result = try {
            infrastructureDependencyService.set(InfrastructureDependencyRequest(owner, dependencies))
        } catch (failure: Exception) {
            return rejectGuardedPlan("could not install applied timetable '$timetableId': " +
                (failure.message ?: failure.javaClass.name))
        }
        if (!result.successful) {
            return rejectGuardedPlan(buildString {
                append("could not install applied timetable '$timetableId': ${result.status}")
                if (result.detail.isNotBlank()) append(": ${result.detail}")
            })
        }
        val installed = AppliedTimetablePlan(owner, dependencies, validation.retainedRoutes, validation.structural, timetable)
        appliedTimetablePlans[timetableId] = installed
        return AppliedPlanInstallResult.Installed(installed)
    }

    private fun rollbackAppliedPlan(
        timetableId: String,
        installed: AppliedTimetablePlan?,
        previous: AppliedTimetablePlan?
    ): String? {
        if (previous == null) {
            if (installed == null) return null
            val removed = try {
                infrastructureDependencyService.remove(installed.owner)
            } catch (failure: Exception) {
                return failure.message ?: failure.javaClass.name
            }
            if (removed.successful) appliedTimetablePlans.remove(timetableId)
            return removed.takeUnless { it.successful }?.let {
                "${it.status}${it.detail.takeIf(String::isNotBlank)?.let { detail -> ": $detail" }.orEmpty()}"
            }
        }
        val restoredOwner = InfrastructureOwner(
            previous.owner.ownerId,
            nextAppliedPlanRevision(installed?.owner?.revision ?: previous.owner.revision)
        )
        val restored = try {
            infrastructureDependencyService.set(
                InfrastructureDependencyRequest(
                    restoredOwner,
                    previous.dependencies
                )
            )
        } catch (failure: Exception) {
            return failure.message ?: failure.javaClass.name
        }
        if (!restored.successful) {
            return "${restored.status}${restored.detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
        }
        appliedTimetablePlans[timetableId] = AppliedTimetablePlan(restoredOwner, previous.dependencies, previous.retainedRoutes, previous.structural, previous.definition)
        return null
    }

    private fun releaseDrainedAppliedPlans() {
        val world = boundWorld ?: return
        val releasable = appliedTimetablePlans.filter { (timetableId, _) ->
            timetableId !in timetableClocks &&
                (fixedFormations.values.none { it.source.id.timetableId == timetableId } && preparingCouplings.values.none { it.coordinator.id.timetableId == timetableId }) &&
                recoveryRequiredRuns.none { it.timetableId == timetableId } &&
                runs.values.filter { it.id.timetableId == timetableId }.all(ActiveRun::canBeReplaced)
        }
        for ((timetableId, plan) in releasable) {
            val result = infrastructureDependencyService.remove(plan.owner)
            if (!result.successful) {
                stallTimetableRuntime(
                    world,
                    TimetableServiceRunId(timetableId, "<applied-plan>"),
                    "InfrastructureDependencyFailure",
                    "could not remove drained applied timetable: ${result.status}: ${result.detail}"
                )
                return
            }
            appliedTimetablePlans.remove(timetableId)
        }
    }

    private fun nextAppliedPlanRevision(minimumExclusive: Long?): Long {
        while (true) {
            val current = appliedPlanRevision.get()
            val floor = minimumExclusive ?: -1L
            val next = maxOf(current, floor) + 1L
            check(next > current && next > floor) { "applied plan revision overflow" }
            if (appliedPlanRevision.compareAndSet(current, next)) return next
        }
    }

    private fun timetableOwnerToken(timetableId: String): String = UUID.nameUUIDFromBytes(
        timetableId.toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun nextMissionRevision(): Long {
        val revision = missionRevision.incrementAndGet()
        check(revision > 0L) { "mission revision overflow" }
        return revision
    }

    private fun selectTrainForService(
        world: World,
        timetable: Timetable,
        cycleIndex: Long,
        runId: TimetableServiceRunId,
        service: TimetableService
    ): SelectedServiceTrain? {
        val take = (service.steps.firstOrNull() as? TimetableStep.At)?.action
        if (take == null) {
            val trainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId ?: return null
            return SelectedServiceTrain(
                trainId, null, null, dynamicAcquisition = false, depotReservationPending = false
            )
        }
        return when (take) {
            is TimetableAction.TakeFromDepot -> selectTrainFromDepot(
                world, timetable, cycleIndex, runId, service, take
            )
            is TimetableAction.TakeFromWaypoint -> selectTrainFromWaypoints(
                world, cycleIndex, runId, take
            )
            else -> {
                val trainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId ?: return null
                SelectedServiceTrain(
                    trainId, null, null, dynamicAcquisition = false, depotReservationPending = false
                )
            }
        }
    }

    private fun selectTrainFromDepot(
        world: World,
        timetable: Timetable,
        cycleIndex: Long,
        runId: TimetableServiceRunId,
        service: TimetableService,
        take: TimetableAction.TakeFromDepot
    ): SelectedServiceTrain? {
        val store = world.simpleAtoWorldData.depots
        val points = store.stablingPoints(take.selector.depotId)
            .sortedBy { it.point.ref.pointId }
        if (points.isEmpty()) {
            stallDepotAllocation(
                world,
                runId,
                "Depot ${take.selector.depotId} has no StablingPoint destinations"
            )
            return null
        }
        val rejected = ArrayList<String>()
        points.forEach { point ->
            val wayPointId = point.point.destinationId
            val candidate = world.allTrains.asSequence()
                .filterIsInstance<CoreTrain>()
                .sortedWith(compareBy<CoreTrain>({ it.name }, { it.id }))
                .firstOrNull { train ->
                    val locationFailure = validateAtWayPoint(world, train, wayPointId)
                    val metadata = store.rollingStock(train.id)?.record
                    val maximumCars = take.selector.maximumCars
                    val minimumSpeed = take.selector.minimumSpeedMetersPerSecond
                    val metadataFailure = when {
                        train.cars.size < (take.selector.minimumCars ?: 0) -> "too few cars"
                        maximumCars != null && train.cars.size > maximumCars ->
                            "too many cars"
                        take.selector.requiredTags.isNotEmpty() &&
                            metadata?.tags?.containsAll(take.selector.requiredTags) != true -> "missing tags"
                        take.selector.requiredCapabilities.isNotEmpty() &&
                            metadata?.capabilities?.containsAll(take.selector.requiredCapabilities) != true ->
                            "missing capabilities"
                        minimumSpeed != null &&
                            (metadata?.maximumSpeedMetersPerSecond ?: 0.0) <
                            minimumSpeed -> "maximum speed too low or unknown"
                        else -> null
                    }
                    val operationalFailure = when {
                        train.isDerailed -> "derailed"
                        train.publishedFrame.speedMetersPerSecond != 0.0 -> "moving"
                        runs.values.any {
                            it.train.id == train.id && !it.canBeReplaced() && !it.isStoredTerminal()
                        } -> "assigned to another service"
                        train.controller != null && runs.values.none {
                            it.train.id == train.id && (it.canBeReplaced() || it.isStoredTerminal())
                        } -> "controlled outside a replaceable service"
                        else -> null
                    }
                    val failure = locationFailure ?: metadataFailure ?: operationalFailure
                    if (failure != null) rejected += "$wayPointId/${train.name}: $failure"
                    failure == null
                }
            if (candidate != null) {
                val assignmentId = "${runId}@cycle-$cycleIndex"
                val stockAllocation = try {
                    store.reservePhysicallySelected(
                        candidate.id,
                        point.point.ref,
                        take.selector,
                        assignmentId,
                        physicalCarIds = candidate.cars.map { it.id }
                    )
                } catch (failure: IllegalArgumentException) {
                    rejected += "$wayPointId/${candidate.name}: ${failure.message}"
                    return@forEach
                }
                return SelectedServiceTrain(
                    trainId = candidate.id,
                    originWayPointId = wayPointId,
                    depotAllocation = stockAllocation,
                    dynamicAcquisition = true,
                    depotReservationPending = stockAllocation != null
                )
            }
        }
        stallDepotAllocation(
            world,
            runId,
            "no eligible stopped physical train in Depot ${take.selector.depotId} for cycle " +
                "$cycleIndex; rejected=${rejected.ifEmpty { listOf("none present") }}"
        )
        return null
    }

    private fun selectTrainFromWaypoints(
        world: World,
        cycleIndex: Long,
        runId: TimetableServiceRunId,
        take: TimetableAction.TakeFromWaypoint
    ): SelectedServiceTrain? {
        val rejected = ArrayList<String>()
        take.wayPointIds.forEach { wayPointId ->
            val candidates = world.allTrains.asSequence()
                .filterIsInstance<CoreTrain>()
                .sortedBy { it.id }
                .mapNotNull { train ->
                    if (validateAtWayPoint(world, train, wayPointId) != null) return@mapNotNull null
                    val replaceableRun = runs.values.firstOrNull {
                        it.train.id == train.id && (it.canBeReplaced() || it.isStoredTerminal())
                    }
                    when {
                        train.isDerailed -> {
                            rejected += "$wayPointId/${train.id}: derailed"
                            null
                        }
                        train.publishedFrame.speedMetersPerSecond != 0.0 -> {
                            rejected += "$wayPointId/${train.id}: moving"
                            null
                        }
                        runs.values.any {
                            it.train.id == train.id && !it.canBeReplaced() && !it.isStoredTerminal()
                        } -> {
                            rejected += "$wayPointId/${train.id}: assigned to another service"
                            null
                        }
                        train.controller != null && replaceableRun == null -> {
                            rejected += "$wayPointId/${train.id}: controlled outside a replaceable service"
                            null
                        }
                        else -> train to replaceableRun?.depotAllocation
                    }
                }
                .toList()
            candidates.firstOrNull()?.let { (train, inheritedAllocation) ->
                return SelectedServiceTrain(
                    train.id,
                    wayPointId,
                    inheritedAllocation,
                    dynamicAcquisition = true,
                    depotReservationPending = false
                )
            }
        }
        stallTimetableRuntime(
            world,
            runId,
            "TrainAcquisitionFailure",
            "no eligible stopped train at waypoints ${take.wayPointIds} for cycle $cycleIndex; " +
                "rejected=${rejected.ifEmpty { listOf("none present") }}"
        )
        return null
    }

    private fun stallDepotAllocation(
        world: World,
        runId: TimetableServiceRunId,
        reason: String
    ) = stallTimetableRuntime(world, runId, "DepotAllocationFailure", reason)

    private fun stallTimetableRuntime(
        world: World,
        runId: TimetableServiceRunId,
        errorType: String,
        reason: String
    ) {
        val failure = IllegalStateException(reason)
        world.simpleAtoWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = world.simpleAtoWorldData.atoCoordinator.worldId,
                trainId = "<unassigned:$runId>",
                tick = dispatcherTick,
                errorType = errorType,
                message = reason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = null,
                currentTrain = null,
                route = null,
                perception = null,
                constraints = emptyMap()
            )
        )
    }

    private fun prepareSelectedStop(world: World, run: ActiveRun, step: TimetableStep.At,
        action: TimetableAction.SelectStop): MovementPlan? {
        if (run.controlMode != TimetableControlMode.AUTOMATIC) {
            stallRun(world, run, "StationSelectionFailure", "station selection requires automatic control")
            return null
        }
        if (timetableElapsedTicks(run) < step.tick) {
            waitForTimetableTick(run, step.tick, pendingStep = true)
            return null
        }
        val address = checkNotNull(run.programCursor.address)
        val selection = run.stopSelection?.takeIf { it.address == address && !it.committed }
            ?: PendingStopSelection(action, step, address, run.programCursor).also { run.stopSelection = it }
        run.ato.setSpeedLimit(selectionHoldKey(run), 0.0)
        if (dispatcherTick < selection.retryAtTick) return null
        val candidates = action.selection.candidates.filter { it.berthId !in selection.deniedCandidates }
        if (candidates.isEmpty()) {
            selection.deniedCandidates.clear()
            selection.retryAtTick = dispatcherTick + admissionRetryTicks
            run.state = TimetableServiceRunState.WAITING_BERTH
            run.message = "all station candidates are waiting for physical admission"
            return null
        }
        val revision = nextMissionRevision()
        val facing = run.requiredNextStartDirection ?: run.train.publishedFrame.head.facing
        val resolutions = candidates.associate { candidate ->
            val ref = StationBerthRef(action.selection.stationId, candidate.berthId)
            val berth = checkNotNull(world.simpleAtoWorldData.stations.berth(ref))
            val station = checkNotNull(world.simpleAtoWorldData.stations[ref.stationId])
            val target = work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds.stationBerth(ref.stationId, ref.berthId)
            val offset = action.stoppingOffsetMetersBefore ?: berth.stoppingOffsetMetersBefore
            val key = ValidatedTimetableRouteKey(run.service.id, address.topLevelIndex, run.currentWayPointId,
                0, target, facing, address = address, stoppingOffsetMeters = offset)
            val output = run.structuralOutput
            val retained = if (output == null) run.retainedRoutes[key]?.let {
                Triple<AtoMissionOrigin, ExpandedRoute, work.art1st.javarailways.core.api.physics.rail.topology.TopologyState>(
                    WayPointRef(run.currentWayPointId), it.route, it.topology)
            } else {
                val trace = run.branchTrace.recordStop(run.service.id, address, ref.berthId)
                val structuralKey = ValidatedStructuralRouteKey(output.first, output.second, run.service.id,
                    address.topLevelIndex, checkNotNull(run.structuralCandidate), facing, target,
                    address = address, stoppingOffsetMeters = offset, branchTrace = trace)
                run.structural.routes[structuralKey]?.let { evidence ->
                    evidence.requireCurrent(world)
                    val frame = run.train.publishedFrame
                    val resolved = evidence.proof.resolve(frame, world.topologyState, evidence.outputCarIds, evidence.expectedReversed)
                    val origin = AtoMissionOrigin.FormationOutput(checkNotNull(run.structuralEventInstanceId), output.second, frame.head)
                    val berthEnd = resolved.movement.totalLengthMeters - evidence.stoppingOffsetFromRetainedEndMeters + offset
                    Triple<AtoMissionOrigin, ExpandedRoute, work.art1st.javarailways.core.api.physics.rail.topology.TopologyState>(
                        origin, ExpandedRoute(origin.id, target, structuralRouteSegments(resolved.movement, 0.0, berthEnd)),
                        world.topologyState)
                }
            }
            val call = StationCall("${run.instanceId}/station/$address", revision, ref.stationId, ref,
                plannedArrivalTick = null, dwellPlan = DwellPlanResolver.resolve(station, berth, action.minimumDwellTicks),
                stoppingOffsetMetersBeforeOverride = action.stoppingOffsetMetersBefore, doorSideOverride = action.doorSideOverride)
            candidate.berthId to if (retained == null) StationCallResolutionResult.Failure(
                work.art1st.javarailways.simpleato.api.station.StationCallResolutionFailure.ROUTE_UNAVAILABLE,
                "no retained selection route for $key") else StationCallResolver(world).resolve(
                StationCallRequest(run.instanceId, retained.first, call),
                trainLengthMeters = run.train.cars.sumOf { it.length }, requiredStartDirection = facing,
                retainedRoute = ExpandedRoute(retained.second.fromWayPointId,
                    "station-call:${call.callId}:${call.berthRef}", retained.second.segments), retainedTopology = retained.third)
        }
        val result = work.art1st.javarailways.simpleato.api.station.StationStopSelector(berthAllocator).select(
            work.art1st.javarailways.simpleato.api.station.StationStopSelection(action.selection.stationId, candidates, action.selection.policy),
            StationBerthOwner(run.instanceId, run.train.id, "${run.instanceId}/station/$address"), revision, resolutions)
        return when (result) {
            is work.art1st.javarailways.simpleato.api.station.StationStopSelectionResult.Selected -> {
                selection.key = result.key
                val ref = result.resolved.berthRef
                val target = WayPointRef(work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds.stationBerth(ref.stationId, ref.berthId))
                val spec = StationCallSpec(TimetableAction.CallStation(ref.stationId.value, ref.berthId,
                    action.minimumDwellTicks, action.stoppingOffsetMetersBefore, action.doorSideOverride, action.stopPrograms), address)
                selection.spec = spec
                MovementPlan(WayPointRef(run.currentWayPointId), listOf(AtoMissionPoint.Stop(target,
                    result.resolved.stoppingOffsetMetersBefore)), target, step.tick, step.tick, address,
                    run.programCursor.advance(), spec, null, requiredStartDirection = facing, reservedMissionRevision = revision)
            }
            is work.art1st.javarailways.simpleato.api.station.StationStopSelectionResult.Waiting -> {
                selection.retryAtTick = dispatcherTick + admissionRetryTicks
                run.state = TimetableServiceRunState.WAITING_BERTH
                run.message = "waiting for declared station candidates"
                null
            }
            is work.art1st.javarailways.simpleato.api.station.StationStopSelectionResult.NoEligibleCandidate -> {
                stallRun(world, run, "StationSelectionFailure", "no eligible station candidate: ${result.rejections}")
                null
            }
            is work.art1st.javarailways.simpleato.api.station.StationStopSelectionResult.ExistingSelectionMismatch -> {
                stallRun(world, run, "StationSelectionFailure", "station selection already owns ${result.held}")
                null
            }
        }
    }

    private fun commitSelectedStop(world: World, run: ActiveRun) {
        val selection = run.stopSelection?.takeIf { !it.committed && it.key != null } ?: return
        run.branchTrace = run.branchTrace.recordStop(run.service.id, selection.address, checkNotNull(selection.key).berthRef.berthId)
        selection.committed = true
        val spec = checkNotNull(selection.spec)
        if (run.stationCall != null) run.deferredStationCallSpec = spec
        else scheduleStationCallSpec(world, run, spec)?.let {
            stallRun(world, run, "StationSelectionFailure", it)
            return
        }
        run.ato.removeSpeedLimit(selectionHoldKey(run))
    }

    private fun startMovementPlan(world: World, run: ActiveRun, plan: MovementPlan) {
        if (run.stationDoorsSecured == false) {
            stallRun(world, run, "StationProgramFailure", "movement requires successful door closing after terminal station work")
            return
        }
        val firstMissionPending = run.currentRuntimeOrNull == null
        val waitingForFirstDeparture = firstMissionPending &&
            timetableElapsedTicks(run) < plan.actionTick
        if (!firstMissionPending && timetableElapsedTicks(run) < plan.actionTick) {
            waitForTimetableTick(run, plan.actionTick, pendingStep = true)
            return
        }
        if (!waitingForFirstDeparture) recordScheduledAction(run, plan.actionTick)
        val submission = submitMissionForRun(world, run, plan)
        val accepted = submission.accepted
        if (accepted == null) {
            stallRun(
                world,
                run,
                "InfrastructureInvariantViolation",
                submission.failure!!
            )
            return
        }
        val runtime = MissionRuntime(
            mission = accepted.mission,
            layout = accepted.layout,
            priorityTick = plan.priorityTick,
            targetWayPointId = plan.target.id,
            address = plan.address,
            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
        )
        if (run.depotReservationPending) {
            val allocation = run.depotAllocation
            if (allocation == null ||
                !world.simpleAtoWorldData.depots.confirmInService(allocation)
            ) {
                stallRun(
                    world,
                    run,
                    "ResourceIdentityViolation",
                    "Depot allocation disappeared before first Mission confirmation"
                )
                return
            }
            run.depotReservationPending = false
            run.depotDepartureRuntime = runtime
        }
        run.currentRuntime = runtime
        run.missionRevision = accepted.mission.revision
        run.programCursor = plan.successor
        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
            run.ato.removeSpeedLimit(stopKey(run))
            run.ato.removeSpeedLimit(formationProgramHoldKey(run))
            publishInitialMovementAuthority(run, runtime)
        }
        run.state = TimetableServiceRunState.WAITING_MISSION
        run.message = null
        run.pendingStationCallSpec = null
        run.pendingDestinationOptions = null
        run.destinationStop = plan.destinationOptions?.let { options ->
            DispatcherDestinationStop(options, runtime)
        }
        if (run.stopSelection?.takeIf { !it.committed }?.key?.missionRevision == accepted.mission.revision) {
            run.requiredNextStartDirection = null
        } else if (waitingForFirstDeparture) {
            plan.stationCallSpec?.let { spec ->
                scheduleStationCallSpec(world, run, spec)?.let { reason ->
                    stallRun(world, run, "InfrastructureInvariantViolation", reason)
                    return
                }
            }
            run.requiredNextStartDirection = null
            waitForTimetableTick(run, plan.actionTick, pendingStep = false)
            run.ato.setSpeedLimit(scheduleHoldKey(run), 0.0)
        } else plan.stationCallSpec?.let { spec ->
            if (run.stationCall != null) {
                run.deferredStationCallSpec = spec
            } else {
                scheduleStationCallSpec(world, run, spec)?.let { reason ->
                    stallRun(world, run, "InfrastructureInvariantViolation", reason)
                }
            }
            run.requiredNextStartDirection = null
        } ?: run {
            run.requiredNextStartDirection = null
        }
        run.stationCall?.takeIf {
            isTerminalStationCall(it) && it.berthed && !it.departureGranted
        }?.let { departing ->
            run.ato.grantStationDeparture(
                departing.resolved.call.callId,
                departing.resolved.call.missionRevision
            )
            departing.departureGranted = true
            departing.stateEnteredDispatcherTick = dispatcherTick
            run.state = TimetableServiceRunState.DEPARTING
            run.message = "departing ${departing.resolved.berthRef}"
        }
        return
    }

    private fun movement(
        world: World,
        runId: TimetableServiceRunId,
        origin: WayPointRef,
        step: TimetableStep.At,
        address: TimetableStepAddress,
        successor: TimetableProgramCursor,
        stationCallSpec: StationCallSpec? = null,
        destinationOptions: TimetableAction.DestinationOptions? = null
    ): MovementPlan {
        val finalTargetId = when (val action = step.action) {
            is TimetableAction.GoTo -> action.wayPointId
            is TimetableAction.GoVia -> action.wayPointIds.last()
            else -> null
        }
        val finalStationOffset = stationCallSpec?.action?.let { call ->
            val berth = world.simpleAtoWorldData.stations.berth(call.berthRef)
            val target = finalTargetId?.let { world.basicWorldData.wayPoints[it] }
            if (berth != null && target != null && target.railId == berth.location.rail.id &&
                abs(target.arcLength - berth.location.arcLengthIn(ViewDirection.AB)) <= 1e-6)
                call.stoppingOffsetMetersBefore ?: berth.stoppingOffsetMetersBefore else null
        }
        val stopOffset = finalStationOffset ?: destinationOptions?.stoppingOffsetMetersBefore ?: 0.0
        val points = when (val action = step.action) {
            is TimetableAction.GoTo -> listOf(
                AtoMissionPoint.Stop(WayPointRef(action.wayPointId), stopOffset)
            )
            is TimetableAction.GoVia -> action.wayPointIds.mapIndexed { index, id ->
                val target = WayPointRef(id)
                if (index == action.wayPointIds.lastIndex) AtoMissionPoint.Stop(target, stopOffset)
                else AtoMissionPoint.Via(target)
            }
            is TimetableAction.BeginStationStop,
            is TimetableAction.BeginOperationalTrip,
            is TimetableAction.SetDisplay,
            is TimetableAction.ResetDisplay,
            is TimetableAction.TakeFromDepot,
            is TimetableAction.TakeFromWaypoint,
            is TimetableAction.TakeFromFormationOutput,
            is TimetableAction.Couple,
            is TimetableAction.Split,
            is TimetableAction.StoreAtDepot,
            is TimetableAction.DestinationOptions,
            is TimetableAction.TaskStart,
            is TimetableAction.TaskEnd,
            TimetableAction.WaitUntil,
            TimetableAction.Halt,
            TimetableAction.Reverse,
            is TimetableAction.Stop,
            is TimetableAction.CallStation,
            is TimetableAction.SelectStop,
            is TimetableAction.Extension,
            is TimetableAction.Conditional -> error("action is not a movement action")
        }
        require(points.isNotEmpty()) { "movement action must contain a target: $runId" }
        return MovementPlan(
            origin = origin,
            points = points,
            target = points.last().target,
            priorityTick = step.tick,
            actionTick = step.tick,
            address = address,
            successor = successor,
            stationCallSpec = stationCallSpec,
            destinationOptions = destinationOptions
        )
    }

    /** Exact current body only: this acquires protection, never a movement or a searched path. */
    private fun fixedBodyRoute(world: World, train: CoreTrain, id: String,
        revision: Long = nextMissionRevision()): RouteSnapshot =
        RouteSnapshot(id, revision, world.topologyState.revision, train.publishedFrame.bodyTraversal.map { span ->
            val rail = world.topologyState.railProjection[span.railId]!!.single()
            val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
            RouteSegmentSnapshot(span.railId, span.facing,
                if (span.facing == ViewDirection.AB) span.startArcLengthInAbMeters else length - span.startArcLengthInAbMeters,
                if (span.facing == ViewDirection.AB) span.endArcLengthInAbMeters else length - span.endArcLengthInAbMeters)
        })

    private fun ensureFixedFormationSourceGrant(world: World, run: ActiveRun): Boolean {
        val existing = run.admissionClient
        if (existing?.committedGrantSnapshot != null) {
            if (existing.state.pending) {
                when (val result = existing.progress()) {
                    is RouteAdmissionResult.Pending -> {
                        run.formationSourceAdmissionWaiting = true
                        run.message = "waiting for the source's existing admission before split"
                        return false
                    }
                    is RouteAdmissionResult.Granted -> {
                        run.admissionCandidate?.let { run.admittedRoute = it }
                        run.admissionCandidate = null
                    }
                    is RouteAdmissionResult.Rejected -> error("source admission failed before split: ${result.failure.detail}")
                }
            }
            run.formationSourceAdmissionWaiting = false
            return true
        }
        if (dispatcherTick < run.nextAdmissionAttemptTick) return false
        val context = routeAdmissionContextProvider.current() ?: run {
            run.formationSourceAdmissionWaiting = true
            run.message = "waiting for coherent split-source admission observation"
            return false
        }
        val client = run.admissionClient ?: RouteAuthorityAdmissionClient(run.train.id,
            "${run.admissionOwner.ownerId}/request", run.admissionOwner, formationAwareAdmissionService(run, context.service),
            run.ato.trainRestrictionPublisher).also { run.admissionClient = it }
        val uncheckedSelection = run.admittedRoute?.route?.selection ?: FrozenRouteSelectionResult.Selected(run.train.id,
            context.observation.stamp, fixedBodyRoute(world, run.train, "${run.instanceId}/split-source-body"))
        val body = fixedBodyRoute(world, run.train, "${run.instanceId}/split-source-direction-check")
        routeDirectionLimit(world, body).requireLegalThrough(body.totalLengthMeters)
        val selection = directionCheckedSelection(world, uncheckedSelection, 0.0)
        check(checkNotNull(run.routeEditProtection).protect(world.topologyState, selection.route) is FrozenRouteProtectionResult.Protected) {
            "split source body could not be protected against edits"
        }
        run.admittedRoute = AdmissionRoute(FrozenMissionRoute(selection, selection.route.totalLengthMeters, 0.0), run.missionRevision)
        val result = if (client.state.pending) client.progress() else client.request(
            selection.copy(observationStamp = context.observation.stamp), RouteBoundary(selection.route.totalLengthMeters))
        run.ato.setSpeedLimit(stopKey(run), 0.0)
        return when (result) {
            is RouteAdmissionResult.Granted -> { run.formationSourceAdmissionWaiting = false; true }
            is RouteAdmissionResult.Pending -> {
                run.formationSourceAdmissionWaiting = true
                run.message = "waiting for split-source body protection"
                false
            }
            is RouteAdmissionResult.Rejected -> {
                check(result.failure.code in setOf(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                    RouteAdmissionFailureCode.RESOURCE_CONFLICT, RouteAdmissionFailureCode.REQUEST_BUSY,
                    RouteAdmissionFailureCode.STALE_OBSERVATION, RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE)) {
                    "split source admission rejected: ${result.failure.detail}"
                }
                run.formationSourceAdmissionWaiting = true
                run.nextAdmissionAttemptTick = dispatcherTick + admissionRetryTicks
                run.message = "waiting for split-source protection: ${result.failure.detail}"
                false
            }
        }
    }

    /** A braking frame may end at zero speed while still recording movement during that frame. */
    private fun awaitStoppedFormationSource(run: ActiveRun): Boolean {
        val frame = run.train.publishedFrame
        check(!frame.derailed) { "formation source is derailed" }
        val stationary = frame.speedMetersPerSecond == 0.0 &&
            (frame.headTraversal + frame.tailTraversal).all {
                it.startArcLengthInAbMeters == it.endArcLengthInAbMeters
            } && frame.cars.all { car -> (car.bogies + car.state).all {
                it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
            } }
        if (!stationary) {
            run.formationSourceAdmissionWaiting = true
            run.ato.setSpeedLimit(stopKey(run), 0.0)
            run.message = "waiting for a stationary formation source publication"
        }
        return stationary
    }

    /** Preflight every authored output before requesting the irreversible physical publication. */
    private fun beginFixedSplit(world: World, run: ActiveRun, action: TimetableAction.Split) {
        try {
            movementDoorFailure(run)?.let(::error)
            check(run.controlMode == TimetableControlMode.AUTOMATIC) { "split requires automatic duty ownership" }
            check(run.stationCall?.allocationKey == null && run.storageReservation == null) {
                "split source still owns a station or storage operation"
            }
            check(!run.depotReservationPending && run.depotDepartureReleased) { "split source has not cleared its depot departure" }
            if (!awaitStoppedFormationSource(run)) return
            val timetable = checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
            val event = timetable.formationEvents.filterIsInstance<TimetableFormationEvent.Split>()
                .single { it.id == action.eventId && it.coordinatorDutyId == run.id.serviceId }
            val candidate = checkNotNull(run.structuralCandidate) { "source has no retained structural candidate" }
            val source = checkNotNull(run.structural.sources[ValidatedStructuralSourceKey(event.id, candidate, run.branchTrace)]) {
                "source has no retained structural eligibility evidence"
            }
            val frame = run.train.publishedFrame
            check(source.window.acceptsSource(frame, world.topologyState)) { "split source is outside its validated stopped window" }
            source.envelope.requireContains(frame, world.topologyState, source.sourceCarIds, source.expectedReversed)
            val stock = checkNotNull(world.simpleAtoWorldData.depots.rollingStock(run.train.id)) { "split source has no registered rolling stock" }
            check(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId != null) { "split source is not assigned IN_SERVICE rolling stock" }
            if (!ensureFixedFormationSourceGrant(world, run)) return
            val client = checkNotNull(run.admissionClient)
            check(!client.state.pending && !client.state.reversalPending) { "split source admission is still pending" }
            val grant = checkNotNull(client.committedGrantSnapshot) { "split source has no committed Basic grant" }
            val context = checkNotNull(routeAdmissionContextProvider.current()) { "split requires a coherent railway observation" }
            val instanceId = "${run.instanceId}/event/${event.id}"
            check(instanceId !in fixedFormations) { "split event was already invoked" }
            val continuations = event.outputs.mapNotNull { output ->
                val duty = (output.disposition as? FormationOutputDisposition.Duty)?.dutyId ?: return@mapNotNull null
                val service = timetable.services.single { it.id == duty }
                var index = if (duty == run.id.serviceId) run.nextStepIndex + 1 else {
                    val takeStep = service.steps.firstOrNull() as? TimetableStep.At
                    check(takeStep?.action == TimetableAction.TakeFromFormationOutput(event.id, output.id)) { "output duty acquisition is not reciprocal" }
                    check(runs.values.none { it.id == TimetableServiceRunId(run.id.timetableId, duty) &&
                        it.cycleIndex == run.cycleIndex && !it.canBeReplaced() }) { "output duty is already running" }
                    1
                }
                var reverse = false
                while (true) {
                    val prefix = service.steps.getOrNull(index) as? TimetableStep.At ?: break
                    if (prefix.action != TimetableAction.Reverse) break
                    if (timetableElapsedTicks(run) < prefix.tick) {
                        waitForTimetableTick(run, prefix.tick, pendingStep = true)
                        run.ato.setSpeedLimit(stopKey(run), 0.0)
                        run.message = "waiting for output $duty preparation tick ${prefix.tick}"
                        return
                    }
                    reverse = !reverse
                    index++
                }
                val movement = service.steps.getOrNull(index) as? TimetableStep.At
                    ?: error("output $duty must begin with a retained movement")
                val child = checkNotNull(source.outputCandidateKeys[output.id]) { "missing validated split output identity" }
                if (movement.action !is TimetableAction.GoTo && movement.action !is TimetableAction.GoVia) {
                    return@mapNotNull output.id to FixedOutputContinuation(output.id, service, index, movement, child,
                        null, frame.reversed xor reverse)
                }
                val target = structuralMovementPoints(movement.action).last().target.id
                val evidence = run.structural.routes.filterKeys { it.eventId == event.id && it.outputId == output.id &&
                    it.serviceId == duty && it.stepIndex == index && it.branchTrace == run.branchTrace && it.sourceCandidate == child && it.targetId == target &&
                    it.startFacing == (if (!reverse) frame.head.facing else if (frame.head.facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB) }.values.singleOrNull()
                    ?: error("output $duty has no unique validated physical continuation")
                evidence.requireCurrent(world)
                output.id to FixedOutputContinuation(output.id, service, index, movement, child, evidence)
            }.toMap()
            val assignments = event.outputs.associate { it.id to "$instanceId/output/${it.id}" }
            val childId = "${run.train.id}/split/${UUID.nameUUIDFromBytes(instanceId.toByteArray(StandardCharsets.UTF_8))}"
            val plan = FixedUnitSplitPlan.prepare(event, instanceId, frame, checkNotNull(stock.assignmentId),
                grant, world.simpleAtoWorldData.depots, childId, assignments,
                event.outputs.associate { it.id to (continuations[it.id]?.outputReversed ?: frame.reversed) })
            // Fail before seal if either child cannot be described by exact source metadata.
            val lengths = run.train.cars.associate { it.id to it.length }
            val outputRecords = plan.outputs.values.map { owner ->
                FormationOutputMetadata.split(stock.record, source.sourceCarIds, owner.carIds, lengths).toRecord(owner.trainId)
            }
            run.ato.setSpeedLimit(stopKey(run), 0.0)
            val operation = FixedFormationOperation.startSplit(world, world.simpleAtoWorldData.depots,
                plan, context.service) { detail -> stallRun(world, run, "FormationRecoveryRequired", detail) }
            run.formationBlocked = true
            run.message = "waiting for physical split publication: ${event.id}"
            fixedFormations[instanceId] = DispatcherFixedFormation(run, event, plan.outputs, operation, continuations,
                outputRecords, context.service)
        } catch (failure: Exception) {
            stallRun(world, run, "FormationExecutionFailure", failure.message ?: failure.javaClass.name)
        }
    }

    /** Both fixed participants must reach the same cycle/event before preparing contact. */
    private fun beginFixedCouple(world: World, run: ActiveRun, action: TimetableAction.Couple) {
        try {
            run.formationSourceAdmissionWaiting = true
            run.ato.setSpeedLimit(stopKey(run), 0.0)
            val timetable = checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
            val event = timetable.formationEvents.filterIsInstance<TimetableFormationEvent.Couple>()
                .single { it.id == action.eventId }
            val participants = listOf(event.approachingDutyId, event.stationaryDutyId).map { duty ->
                runs.values.singleOrNull { it.id == TimetableServiceRunId(run.id.timetableId, duty) && it.cycleIndex == run.cycleIndex }
                    ?: return
            }
            if (participants.any { participant ->
                val step = participant.service.steps.getOrNull(participant.nextStepIndex) as? TimetableStep.At
                step?.action != action || timetableElapsedTicks(participant) < step.tick
            }) { run.message = "waiting for fixed coupling participants: ${event.id}"; return }
            val moving = participants[0]
            val stationary = participants[1]
            val coordinator = participants.single { it.id.serviceId == event.coordinatorDutyId }
            val instance = "${coordinator.instanceId}/event/${event.id}"
            if (instance in preparingCouplings || instance in fixedFormations) return
            participants.forEach { participant ->
                movementDoorFailure(participant)?.let(::error)
                check(participant.controlMode == TimetableControlMode.AUTOMATIC && participant.stationCall?.allocationKey == null &&
                    participant.storageReservation == null && !participant.depotReservationPending && participant.depotDepartureReleased) {
                    "coupling participant still owns another operation"
                }
                participant.ato.setSpeedLimit(stopKey(participant), 0.0)
                if (!awaitStoppedFormationSource(participant)) return
                if (!ensureFixedFormationSourceGrant(world, participant)) return
            }
            val evidence = checkNotNull(run.structural.couplings[ValidatedStructuralCouplingKey(event.id,
                checkNotNull(moving.structuralCandidate), checkNotNull(stationary.structuralCandidate),
                moving.branchTrace, stationary.branchTrace)]) {
                "coupling pair has no retained contact proof"
            }
            val request = CouplingApproachRequest(moving.train.id,
                if (moving.train.isReversed) TrainEndSide.B else TrainEndSide.A, stationary.train.id,
                if ((event.stationaryEnd == FormationStationaryEnd.HEAD) xor stationary.train.isReversed) TrainEndSide.A else TrainEndSide.B)
            val inspected = world.inspectCouplingApproach(request)
            check(inspected is CouplingApproachInspection.Accepted) { "coupling contact inspection rejected: $inspected" }
            val proof = inspected.proof
            evidence.contactFeasibility.requireContact(proof, world.topologyState)
            val continuation = (event.output.disposition as? FormationOutputDisposition.Duty)?.let { disposition ->
                val service = timetable.services.single { it.id == disposition.dutyId }
                val previous = participants.singleOrNull { it.id.serviceId == service.id }
                val index = previous?.nextStepIndex?.plus(1) ?: 1
                if (previous == null) {
                    check((service.steps.firstOrNull() as? TimetableStep.At)?.action ==
                        TimetableAction.TakeFromFormationOutput(event.id, event.output.id))
                    check(runs.values.none { it.id == TimetableServiceRunId(run.id.timetableId, service.id) &&
                        it.cycleIndex == run.cycleIndex && !it.canBeReplaced() })
                }
                val step = service.steps.getOrNull(index) as? TimetableStep.At ?: error("coupled output requires a retained movement")
                val route = if (step.action is TimetableAction.GoTo || step.action is TimetableAction.GoVia) {
                    val target = structuralMovementPoints(step.action).last().target.id
                    checkNotNull(run.structural.routes[ValidatedStructuralRouteKey(event.id, event.output.id,
                        service.id, index, evidence.outputCandidateKey, proof.contactHead.facing, target,
                        branchTrace = moving.branchTrace.merge(stationary.branchTrace))]) {
                        "coupled output has no retained continuation"
                    }
                } else null
                route?.requireCurrent(world)
                FixedOutputContinuation(event.output.id, service, index, step, evidence.outputCandidateKey, route, evidence.outputReversed)
            }
            fun segments(spans: List<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot>) = spans.map { span ->
                val rail = world.topologyState.railProjection.getValue(span.railId).single()
                val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
                RouteSegmentSnapshot(span.railId, span.facing,
                    if (span.facing == ViewDirection.AB) span.startArcLengthInAbMeters else length - span.startArcLengthInAbMeters,
                    if (span.facing == ViewDirection.AB) span.endArcLengthInAbMeters else length - span.endArcLengthInAbMeters)
            }
            val revision = nextMissionRevision()
            val contactRoute = RouteSnapshot("$instance/contact", revision, world.topologyState.revision,
                segments(proof.approachingFrame.bodyTraversal + proof.headTraversal))
            check(checkNotNull(moving.routeEditProtection).protect(world.topologyState, evidence.protectedRoute) is FrozenRouteProtectionResult.Protected)
            val origin = AtoMissionOrigin.FormationContact(instance, proof.approachingFrame.head)
            val target = "$instance/contact-point"
            val mission = AtoMission(instance, revision, origin, listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef(target)),
                PlannedRoute("$instance/contact-movement", revision, ExpandedRoute(origin.id, target,
                    segments(proof.headTraversal).map { RouteSegment(RailViewRef(it.railId, it.facing), it.startArcLengthMeters, it.endArcLengthMeters) })))))
            movementDoorFailure(moving)?.let(::error)
            check(moving.ato.submitMission(mission) is AtoMissionSubmissionResult.Accepted)
            moving.missionRevision = revision
            moving.currentRuntime = MissionRuntime(mission, MissionRouteLayout(world, mission), dispatcherTick, target,
                checkNotNull(moving.programCursor.address), lastOdometerMeters = moving.ato.lifetimeTravelDistanceMeters)
            publishInitialMovementAuthority(moving, moving.currentRuntime)
            participants.forEach { it.formationBlocked = true }
            preparingCouplings[instance] = PreparingCoupling(instance, event, coordinator, moving, stationary, evidence, request,
                contactRoute, proof.distanceToContactMeters, continuation)
        } catch (failure: Exception) {
            stallRun(world, run, "FormationExecutionFailure", failure.message ?: failure.javaClass.name)
        }
    }

    private fun progressPreparingCouplings(world: World, context: TimetableRouteAdmissionContext?) {
        if (context == null) return
        for (pending in preparingCouplings.values.toList()) {
            val moving = pending.moving
            try {
                moving.lastPublishedAtoState = moving.ato.publishedState
                val selected = directionCheckedSelection(world,
                    FrozenRouteSelectionResult.Selected(moving.train.id, context.observation.stamp, pending.route),
                    pending.route.totalLengthMeters)
                val route = AdmissionRoute(FrozenMissionRoute(selected, pending.route.totalLengthMeters - pending.distance, 0.0), moving.missionRevision)
                if (!applyCommittedAdmissionRestriction(world, moving, moving.currentRuntime, context.observation,
                    capturePublishedTrainSnapshot(moving), route)) continue
                if (moving.ato.publishedState.currentMission?.revision != moving.missionRevision) continue
                val inspected = world.inspectCouplingApproach(pending.request)
                check(inspected is CouplingApproachInspection.Accepted) { "contact changed while preparing: $inspected" }
                pending.evidence.contactFeasibility.requireContact(inspected.proof, world.topologyState)
                val participants = listOf(moving, pending.stationary)
                val stocks = participants.map { checkNotNull(world.simpleAtoWorldData.depots.rollingStock(it.train.id)) }
                check(stocks.all { it.state == RollingStockState.IN_SERVICE && it.assignmentId != null })
                val plan = FixedUnitCouplePlan.prepare(pending.event, pending.id, inspected.proof,
                    stocks[0].assignmentId!!, stocks[1].assignmentId!!,
                    checkNotNull(moving.admissionClient?.committedGrantSnapshot),
                    checkNotNull(pending.stationary.admissionClient?.committedGrantSnapshot),
                    "${pending.id}/output/${pending.event.output.id}", world.simpleAtoWorldData.depots)
                check(plan.output.carIds == pending.evidence.outputCarIds && plan.output.reversed == pending.evidence.outputReversed)
                val records = listOf(FormationOutputMetadata.couple(stocks.map { it.record },
                    participants.associate { it.train.id to it.train.cars.map { car -> car.id } }, plan.output.carIds,
                    participants.flatMap { it.train.cars }.associate { it.id to it.length }).toRecord(plan.output.trainId))
                val operation = FixedFormationOperation.startCouple(world, world.simpleAtoWorldData.depots, plan, context.service) {
                    stallRun(world, pending.coordinator, "FormationRecoveryRequired", it)
                }
                val execution = DispatcherFixedFormation(pending.coordinator, pending.event,
                    mapOf(pending.event.output.id to plan.output), operation,
                    pending.continuation?.let { mapOf(it.outputId to it) } ?: emptyMap(), records, context.service, participants)
                execution.contact = CouplingContactExecution(moving, route, pending.distance)
                fixedFormations[pending.id] = execution
                preparingCouplings.remove(pending.id)
                val intent = RouteResourceIntent("${moving.admissionOwner.ownerId}/request", moving.admissionOwner,
                    moving.train.id, context.observation.stamp, pending.route, RouteBoundary(pending.route.totalLengthMeters))
                consumeCouplingAdmission(world, execution, context.service.requestCouplingApproach(operation.handle, intent))
            } catch (failure: Exception) {
                stallRun(world, pending.coordinator, "FormationRecoveryRequired", failure.message ?: failure.javaClass.name)
                return
            }
        }
    }

    private fun consumeCouplingAdmission(world: World, event: DispatcherFixedFormation, result: RouteAdmissionResult) {
        val contact = checkNotNull(event.contact)
        when (result) {
            is RouteAdmissionResult.Pending -> contact.pending = result.handle
            is RouteAdmissionResult.Rejected -> stallRun(world, event.source, "FormationRecoveryRequired", result.failure.detail)
            is RouteAdmissionResult.Granted -> {
                checkNotNull(contact.moving.admissionClient).adoptFormationGrant(contact.route.route.selection, result.grant)
                contact.moving.admittedRoute = contact.route
                contact.moving.ato.setMovementAuthority(contact.moving.missionRevision, contact.distance)
                contact.moving.ato.removeSpeedLimit(stopKey(contact.moving))
                contact.moving.ato.removeSpeedLimit(scheduleHoldKey(contact.moving))
                contact.pending = null
            }
        }
    }

    private fun progressFixedFormations(world: World, context: TimetableRouteAdmissionContext?) {
        for (event in fixedFormations.values.toList()) {
            if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
            try {
                event.contact?.pending?.let { consumeCouplingAdmission(world, event, event.admission.progress(it)) }
                if (world.simpleAtoWorldData.atoCoordinator.isStalled) return
                if (!event.installed) when (val progress = event.operation.progress()) {
                    FixedFormationOperation.Progress.Pending -> continue
                    is FixedFormationOperation.Progress.RecoveryRequired -> {
                        stallRun(world, event.source, "FormationRecoveryRequired", progress.detail)
                        return
                    }
                    is FixedFormationOperation.Progress.Held -> {
                        if (context == null) continue
                        installFixedFormationOutputs(world, event, context)
                    }
                }
                when (val progress = event.handoff?.progress()) {
                    is FixedFormationOutputHandoff.Progress.RecoveryRequired -> {
                        stallRun(world, event.source, "FormationRecoveryRequired", progress.detail)
                        return
                    }
                    else -> Unit
                }
                val state = event.admission.formationChangeState(event.operation.handle)
                if (state is FormationChangeResult.Accepted && state.snapshot.phase == FormationChangePhase.COMPLETED) {
                    event.sourceRuns.forEach { previous ->
                        check(previous.routeEditProtection?.release() !is FrozenRouteProtectionResult.Rejected) {
                            "completed formation source route edit protection could not be released"
                        }
                        previous.routeEditProtection = null
                        previous.formationBlocked = false
                        previous.admissionClient = null
                        previous.admittedRoute = null
                        previous.admissionEnded = true
                    }
                    fixedFormations.remove(event.operation.ledgerChange.eventId)
                }
            } catch (failure: Exception) {
                stallRun(world, event.source, "FormationRecoveryRequired", failure.message ?: failure.javaClass.name)
                return
            }
        }
    }

    private fun installFixedOutputMission(run: ActiveRun) {
        movementDoorFailure(run)?.let(::error)
        check(enterRunSegment(checkNotNull(boundWorld),run,run.currentRuntime.address.topLevelIndex)) { "formation output run segment failed" }
        val accepted = run.ato.submitMission(run.currentRuntime.mission)
        check(accepted is AtoMissionSubmissionResult.Accepted) { "physical output mission rejected: $accepted" }
        publishInitialMovementAuthority(run, run.currentRuntime)
    }

    /** Reservation is fixed at publication; the new duty acquires and starts only when authored. */
    private fun activateFixedOutput(world: World, run: ActiveRun): Boolean {
        val continuation = run.pendingFormationActivation ?: return true
        val acquisition = (run.service.steps.firstOrNull() as? TimetableStep.At)?.takeIf {
            it.action is TimetableAction.TakeFromFormationOutput
        }
        val acquisitionTick = acquisition?.tick ?: 0L
        if (timetableElapsedTicks(run) < acquisitionTick) {
            waitForTimetableTick(run, acquisitionTick, pendingStep = false)
            run.message = "waiting for formation output acquisition tick $acquisitionTick"
            return false
        }
        check(world.getTrain(run.train.id) === run.train && run.train.controller === run.ato) {
            "reserved formation output controller changed before acquisition"
        }
        if (continuation.evidence == null) {
            run.pendingFormationActivation = null
            run.nextStepIndex = continuation.stepIndex
            run.waitingUntilTimetableTick = null
            run.ato.removeSpeedLimit(scheduleHoldKey(run))
            run.state = TimetableServiceRunState.WAITING_MISSION
            advanceService(world, run)
            return true
        }
        installFixedOutputMission(run)
        run.pendingFormationActivation = null
        run.activeStepAddress = TimetableStepAddress(continuation.stepIndex)
        run.nextStepIndex = continuation.stepIndex + 1
        run.waitingUntilTimetableTick = null
        run.state = TimetableServiceRunState.WAITING_MISSION
        recordScheduledAction(run, acquisitionTick)
        if (timetableElapsedTicks(run) < continuation.step.tick) {
            waitForTimetableTick(run, continuation.step.tick, pendingStep = false)
            run.ato.setSpeedLimit(scheduleHoldKey(run), 0.0)
        }
        return true
    }

    private fun installFixedFormationOutputs(world: World, event: DispatcherFixedFormation, context: TimetableRouteAdmissionContext) {
        val source = event.source
        val outputs = linkedMapOf<String, Pair<ActiveRun?, RouteResourceIntent>>()
        for (definition in event.event.outputs) {
            val owner = event.outputOwners.getValue(definition.id)
            val train = world.getTrain(owner.trainId) as? CoreTrain ?: error("published split output is missing")
            val frame = train.publishedFrame
            val continuation = event.continuations[definition.id]
            val outputRun: ActiveRun?
            val route: RouteSnapshot
            val headDistance: Double
            if (continuation == null) {
                outputRun = null
                route = fixedBodyRoute(world, train, "${event.operation.ledgerChange.eventId}/hold/${definition.id}")
                headDistance = route.totalLengthMeters
            } else {
                val evidence = continuation.evidence
                evidence?.requireCurrent(world)
                val resolved = evidence?.proof?.resolve(frame, world.topologyState,
                    evidence.outputCarIds, evidence.expectedReversed)
                val physicalOrigin = AtoMissionOrigin.FormationOutput(event.operation.ledgerChange.eventId, definition.id, frame.head)
                val revision = nextMissionRevision()
                val points = if (evidence == null) emptyList() else structuralMovementPoints(continuation.step.action)
                val target = points.lastOrNull()?.target?.id ?: physicalOrigin.id
                val mission = evidence?.let { structuralMission("${source.id.timetableId}/${continuation.service.id}", revision,
                    physicalOrigin, points, it, checkNotNull(resolved)) }
                val previous = event.sourceRuns.singleOrNull { it.train === train }
                val ato = previous?.ato ?: checkNotNull(atoFactory) { "no formation output ATO factory" }.create(train, world)
                check(ato.authorityStoppingMarginMeters == authorityStoppingMarginMeters) { "output ATO safety distance differs" }
                outputRun = ActiveRun(TimetableServiceRunId(source.id.timetableId, continuation.service.id), continuation.service,
                    source.retainedRoutes, train, ato, previous?.controllerOwned ?: true,
                    revision, continuation.stepIndex + 1, physicalOrigin.id, null,
                    TimetableServiceRunState.WAITING_MISSION, source.cycleIndex, source.cycleStartDispatcherTick,
                    admissionOwnerSequence.incrementAndGet(), infrastructureDependencyService,
                    lastObservedDispatcherTick = dispatcherTick,
                    admissionOwnerOverride = previous?.admissionOwner)
                if (evidence == null) {
                    outputRun.nextStepIndex = continuation.stepIndex
                    outputRun.pendingFormationActivation = continuation
                } else if (event.sourceRuns.none { it.id.serviceId == continuation.service.id }) {
                    outputRun.nextStepIndex = 0
                    outputRun.pendingFormationActivation = continuation
                }
                // Same-duty structural handoff continues its transient invocation history.
                event.sourceRuns.singleOrNull { it.id.serviceId == continuation.service.id }?.let {
                    outputRun.taskTimings.addAll(it.taskTimings)
                }
                outputRun.stationDoorsSecured = when {
                    event.sourceRuns.any { it.stationDoorsSecured == false } -> false
                    event.sourceRuns.all { it.stationDoorsSecured == true } -> true
                    else -> null
                }
                outputRun.activeStepAddress = TimetableStepAddress(continuation.stepIndex)
                outputRun.branchTrace = event.sourceRuns.fold(StructuralBranchTrace.EMPTY) { trace, previous -> trace.merge(previous.branchTrace) }
                outputRun.structural = source.structural
                outputRun.structuralCandidate = continuation.candidate
                outputRun.structuralOutput = event.event.id to definition.id
                outputRun.structuralEventInstanceId = event.operation.ledgerChange.eventId
                outputRun.physicalOriginPending = true
                outputRun.formationAdmissionBinding = previous?.formationAdmissionBinding ?: FormationAdmissionBinding()
                outputRun.formationAdmissionBinding.handle = event.operation.handle
                outputRun.admissionClient = previous?.admissionClient
                if (mission != null) outputRun.currentRuntime = MissionRuntime(mission, MissionRouteLayout(world, mission),
                    absoluteTimetableTick(source, continuation.step.tick), target,
                    TimetableStepAddress(continuation.stepIndex), lastOdometerMeters = ato.lifetimeTravelDistanceMeters)
                // Formation publication transfers only the stopped body. The first moving
                // candidate appends its retained mission and obtains the normal terminal margin
                // before any departure grant; the eligibility window is not a braking margin.
                route = fixedBodyRoute(world, train, "${physicalOrigin.id}/admission", revision)
                headDistance = route.totalLengthMeters
                val selected = directionCheckedSelection(world,
                    FrozenRouteSelectionResult.Selected(train.id, context.observation.stamp, route),
                    headDistance)
                outputRun.admittedRoute = AdmissionRoute(FrozenMissionRoute(selected, headDistance, 0.0),
                    revision, requiresTerminalCompletion = mission != null)
            }
            val intentOwner = outputRun?.admissionOwner ?: InfrastructureOwner("simpleato:formation-hold/${UUID.nameUUIDFromBytes((event.operation.ledgerChange.eventId + "/output/" + definition.id).toByteArray(StandardCharsets.UTF_8))}", 0)
            outputs[owner.trainId] = outputRun to RouteResourceIntent("${intentOwner.ownerId}/request", intentOwner,
                train.id, context.observation.stamp, route, RouteBoundary(headDistance))
        }
        val bound = event.admission.bindFormationOutputs(event.operation.handle, outputs.values.map { it.second })
        check(bound is FormationChangeResult.Accepted) { "split output binding failed: $bound" }
        event.handoff = FixedFormationOutputHandoff.begin(world, world.simpleAtoWorldData.depots, event.operation.ledgerChange,
            event.operation.handle, event.admission, event.outputRecords, install = { output, grant ->
                val (run, intent) = outputs.getValue(output.owner.trainId)
                if (run == null) {
                    // The structural fence owns HOLD. A consumed contact mission describes the
                    // pre-coupling head and must not plan against the newly combined body.
                    val held = checkNotNull(world.getTrain(output.owner.trainId)) as CoreTrain
                    held.setController(null)
                    inspectionRuntime.removeTrain(held.id)
                    val unitIds=world.simpleAtoWorldData.depots.fixedUnitsForFormation(output.owner.carIds).map { it.id }
                    displayRuntime.retire(event.source.id.timetableId,event.source.cycleIndex,unitIds)
                } else {
                    check(checkNotNull(run.routeEditProtection).protect(world.topologyState, intent.route) is FrozenRouteProtectionResult.Protected)
                    if (event.sourceRuns.none { it.train === run.train }) run.train.setController(run.ato)
                    // The retained publisher may now serve another duty on another line.
                    // Remove only its previous timetable line keys; external/safety caps remain.
                    event.sourceRuns.filter { it.ato === run.ato }.forEach { previous ->
                        run.ato.removeSpeedLimit(timetableLineSpeedKey(previous.id))
                    }
                    val timetable = checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
                    applyTimetableLineSpeedLimit(run.ato, timetable, run.service, run.id, run.activeStepAddress?.topLevelIndex ?: run.nextStepIndex)
                    val client = run.admissionClient ?: RouteAuthorityAdmissionClient(run.train.id, intent.requestId, intent.owner,
                        formationAwareAdmissionService(run, event.admission), run.ato.trainRestrictionPublisher).also { run.admissionClient = it }
                    client.adoptFormationGrant(checkNotNull(run.admittedRoute).route.selection, grant)
                    if (run.pendingFormationActivation?.evidence == null && run.pendingFormationActivation != null) {
                        run.ato.retireMissionForFormation()
                        run.ato.setSpeedLimit(formationProgramHoldKey(run), 0.0)
                    }
                    if (run.pendingFormationActivation != null) {
                        // Deferred cursor execution starts after this run is registered below.
                        // Its published body is still fenced until the chosen route is admitted.
                        if (run.pendingFormationActivation?.evidence != null) activateFixedOutput(world, run)
                    } else {
                        installFixedOutputMission(run)
                        val scheduled = event.continuations.values.single { it.service.id == run.service.id }.step.tick
                        if (timetableElapsedTicks(run) < scheduled) {
                            waitForTimetableTick(run, scheduled, pendingStep = false)
                            run.ato.setSpeedLimit(scheduleHoldKey(run), 0.0)
                        }
                    }
                    event.sourceRuns.forEach { previous ->
                        run.ato.removeSpeedLimit(stopKey(previous))
                        run.ato.removeSpeedLimit(scheduleHoldKey(previous))
                    }
                    val clock = checkNotNull(timetableClocks[run.id.timetableId])
                    runs[TimetableServiceRunKey(run.id, clock.epochDispatcherTick, run.cycleIndex)] = run
                }
            }, failClosed = { detail -> stallRun(world, source, "FormationRecoveryRequired", detail) })
        // The old source has become a structural record, not a second owner of either output.
        event.sourceRuns.filter { previous -> runs.values.any { it === previous } }.forEach { previous ->
            previous.unitDisplays=displayRuntime.snapshots(checkNotNull(world.simpleAtoWorldData.timetables[previous.id.timetableId]),previous.cycleIndex,previous.currentUnitIds)
            val action = (previous.programCursor.currentStep() as? TimetableStep.At)?.action
            if ((action is TimetableAction.Split || action is TimetableAction.Couple) &&
                (previous.programCursor.advance().currentStep() as? TimetableStep.At)?.action is TimetableAction.TaskEnd) {
                previous.programCursor = previous.programCursor.advance()
            }
            completeTerminalTask(previous)
            previous.state = TimetableServiceRunState.COMPLETED
            previous.message = "source duty handed its formation to declared held outputs"
        }
        event.installed = true
    }

    private fun structuralMovementPoints(action: TimetableAction): List<AtoMissionPoint> = when (action) {
        is TimetableAction.GoTo -> listOf(AtoMissionPoint.Stop(WayPointRef(action.wayPointId)))
        is TimetableAction.GoVia -> action.wayPointIds.mapIndexed { index, target ->
            if (index == action.wayPointIds.lastIndex) AtoMissionPoint.Stop(WayPointRef(target))
            else AtoMissionPoint.Via(WayPointRef(target))
        }
        else -> error("structural output requires a retained movement")
    }

    private fun structuralRouteSegments(route: RouteSnapshot, start: Double, end: Double): List<RouteSegment> {
        check(start >= 0.0 && end > start && end <= route.totalLengthMeters) { "invalid certified structural route cut" }
        val segments = arrayListOf<RouteSegment>()
        var position = 0.0
        for (span in route.segments) {
            val length = span.endArcLengthMeters - span.startArcLengthMeters
            val low = maxOf(start, position)
            val high = minOf(end, position + length)
            if (high > low) segments += RouteSegment(RailViewRef(span.railId, span.facing),
                span.startArcLengthMeters + low - position, span.startArcLengthMeters + high - position)
            position += length
        }
        return segments
    }

    private fun structuralMission(runId: String, revision: Long, origin: AtoMissionOrigin,
        points: List<AtoMissionPoint>, evidence: ValidatedStructuralOutputRoute,
        resolved: ResolvedStructuralOutputRoute): AtoMission {
        check(evidence.intermediateMarkers.map { it.targetId } == points.dropLast(1).map { it.target.id }) {
            "retained structural via markers differ from the addressed movement"
        }
        val total = resolved.movement.totalLengthMeters
        val cuts = evidence.intermediateMarkers.map { total - it.distanceBeforeRetainedEndMeters } + total
        var start = 0.0
        var from = origin.id
        val legs = cuts.mapIndexed { index, end ->
            check(end > start && end <= total) { "structural marker is outside the resolved physical continuation" }
            val segments = structuralRouteSegments(resolved.movement, start, end)
            val point = if (index == cuts.lastIndex) AtoMissionPoint.Stop(points.last().target,
                evidence.stoppingOffsetFromRetainedEndMeters) else points[index]
            val expanded = ExpandedRoute(from, point.target.id, segments)
            start = end
            from = point.target.id
            AtoMissionLeg(index, point, PlannedRoute("${runId}/structural-leg/$index", revision, expanded))
        }
        return AtoMission(runId, revision, origin, legs)
    }

    private fun movementDoorFailure(run: ActiveRun): String? = when {
        run.stopExecution != null -> "station task join has not completed"
        run.stationDoorsSecured == false -> "movement requires successful door closing after station work"
        else -> null
    }

    private fun submitMissionForRun(world: World, run: ActiveRun, plan: MovementPlan): MissionSubmission {
        movementDoorFailure(run)?.let { return MissionSubmission(null, it) }
        val output = run.structuralOutput ?: return submitMission(world, run.ato, run.id, plan, run.retainedRoutes,
            automatic = run.controlMode == TimetableControlMode.AUTOMATIC)
        return try {
            check(plan.explicitReplanLegs == null) { "structural output requires its retained candidate proof" }
            check(plan.points.lastOrNull() is AtoMissionPoint.Stop) {
                "structural output continuation requires a retained final stop"
            }
            val frame = run.train.publishedFrame
            val key = ValidatedStructuralRouteKey(output.first, output.second, run.service.id,
                plan.address.topLevelIndex, checkNotNull(run.structuralCandidate), frame.head.facing, plan.target.id,
                address = plan.address, stoppingOffsetMeters = (plan.points.last() as AtoMissionPoint.Stop).offsetMetersBefore,
                branchTrace = run.stopSelection?.takeIf { it.address == plan.address && !it.committed }?.key?.let {
                    run.branchTrace.recordStop(run.service.id, plan.address, it.berthRef.berthId)
                } ?: run.branchTrace)
            val evidence = checkNotNull(run.structural.routes[key]) { "no retained structural movement for $key" }
            evidence.requireCurrent(world)
            val resolved = evidence.proof.resolve(frame, world.topologyState, evidence.outputCarIds, evidence.expectedReversed)
            val origin = AtoMissionOrigin.FormationOutput(checkNotNull(run.structuralEventInstanceId), output.second, frame.head)
            val revision = plan.reservedMissionRevision ?: nextMissionRevision()
            val mission = structuralMission(run.id.toString(), revision, origin, plan.points, evidence, resolved)
            val prepared = PreparedMission(mission, MissionRouteLayout(world, mission))
            if (run.controlMode != TimetableControlMode.AUTOMATIC) MissionSubmission(prepared, null)
            else when (val result = run.ato.submitMission(mission)) {
                is AtoMissionSubmissionResult.Accepted -> MissionSubmission(prepared, null)
                else -> MissionSubmission(null, "retained physical output mission rejected: $result")
            }
        } catch (failure: Exception) {
            MissionSubmission(null, failure.message ?: failure.javaClass.name)
        }
    }

    private fun submitMission(
        world: World,
        ato: SimpleATO,
        runId: TimetableServiceRunId,
        plan: MovementPlan,
        retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
        automatic: Boolean = true
    ): MissionSubmission {
        val revision = plan.reservedMissionRevision ?: nextMissionRevision()
        var from = world.basicWorldData.wayPoints[plan.origin.id]
            ?: return MissionSubmission(null, "mission target not found: ${plan.origin.id}")
        var direction = plan.requiredStartDirection ?: TrainSnapshot.capture(ato.train, dispatcherTick).head.facing
        val legs = ArrayList<AtoMissionLeg>()
        for ((index, point) in plan.points.withIndex()) {
            val target = world.basicWorldData.wayPoints[point.target.id]
                ?: return MissionSubmission(null, "mission target not found: ${point.target.id}")
            val key = ValidatedTimetableRouteKey(runId.serviceId, plan.address.topLevelIndex,
                plan.origin.id, index, target.id, direction, address = plan.address,
                stoppingOffsetMeters = (point as? AtoMissionPoint.Stop)?.offsetMetersBefore)
            val evidence = plan.explicitReplanLegs?.getOrNull(index) ?: retainedRoutes[key]
                ?: return MissionSubmission(null, "no validated movement evidence for $key")
            retainedRouteFailure(world, evidence.route, evidence.topology, from, target, direction,
                world.simpleAtoWorldData.destinations[target.id]?.approachDirection)?.let {
                return MissionSubmission(null, it)
            }
            // StationCall has its own exact prefix/offset and physical train-length validation.
            if (plan.stationCallSpec == null && point is AtoMissionPoint.Stop &&
                evidence.stoppingOffsetMeters != point.offsetMetersBefore) {
                return MissionSubmission(null, "validated movement stopping options changed")
            }
            if (point is AtoMissionPoint.Via && evidence.stoppingOffsetMeters != null) {
                return MissionSubmission(null, "validated movement pass options changed")
            }
            legs += AtoMissionLeg(index, point, PlannedRoute("$runId/leg/$index", revision, evidence.route))
            from = target
            direction = evidence.route.segments.last().railViewRef.direction
        }
        val resolved = AtoMission(runId.toString(), revision, plan.origin, legs)
        val layout = MissionRouteLayout(world, resolved)
        if (layout.project(TrainSnapshot.capture(ato.train, dispatcherTick).head) == null) {
            return MissionSubmission(null, "train ${ato.train.id} is not facing the planned route from ${plan.origin.id}")
        }
        if (!automatic) return MissionSubmission(PreparedMission(resolved, layout), null)
        return when (val result = ato.submitMission(resolved)) {
            is AtoMissionSubmissionResult.Accepted -> MissionSubmission(PreparedMission(result.mission, layout), null)
            is AtoMissionSubmissionResult.StaleRevision -> MissionSubmission(null,
                "mission revision ${result.submittedRevision} is stale; latest is ${result.latestAcceptedRevision}")
            is AtoMissionSubmissionResult.TargetUnresolved -> MissionSubmission(null, "mission target not found: ${result.target.id}")
            is AtoMissionSubmissionResult.RouteUnavailable -> MissionSubmission(null,
                "route unavailable ${result.from.id} -> ${result.to.id}: ${result.reason}")
        }
    }

    private fun validateAtWayPoint(world: World, train: CoreTrain, wayPointId: String): String? {
        val wayPoint = world.basicWorldData.wayPoints[wayPointId]
            ?: return "waypoint not found: $wayPointId"
        val trainArc = train.headLocation.arcLengthIn(ViewDirection.AB)
        val destination = world.simpleAtoWorldData.destinations[wayPointId]
        val facingMismatch = destination?.approachDirection?.let { requiredDirection ->
            (train.headLocation as? work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
                ?.facing != requiredDirection
        } ?: false
        return if (train.headLocation.rail.id != wayPoint.railId ||
            abs(trainArc - wayPoint.arcLength) > TIMETABLE_ORIGIN_TOLERANCE_METERS ||
            facingMismatch
        ) {
            "train ${train.id} is not at directed waypoint $wayPointId"
        } else {
            null
        }
    }

    private fun waitForTimetableTick(
        run: ActiveRun,
        scheduledTick: Long,
        pendingStep: Boolean
    ) {
        run.waitingUntilTimetableTick = scheduledTick
        run.waitingStepPending = pendingStep
        run.state = TimetableServiceRunState.WAITING_TIMETABLE_TIME
        run.message = "waiting for timetable tick $scheduledTick"
    }

    private fun recordScheduledAction(run: ActiveRun, scheduledTick: Long) {
        if (run.stopExecution != null) return // Station tasks have independent clocks.
        val elapsed = timetableElapsedTicks(run)
        run.lastScheduledActionTick = scheduledTick
        run.lastLatenessTicks = (elapsed - scheduledTick).coerceAtLeast(0L)
    }

    private fun timetableElapsedTicks(run: ActiveRun): Long =
        (dispatcherTick - run.cycleStartDispatcherTick).coerceAtLeast(0L)

    private fun absoluteTimetableTick(run: ActiveRun, relativeTick: Long): Long =
        Math.addExact(run.cycleStartDispatcherTick, relativeTick)

    private fun proveUnitFuture(world: World, run: ActiveRun, timetable: Timetable, segment: TimetableRunSegment, units: List<String>) {
        displayRuntime.proveFuture(timetable,run.cycleIndex,run.service,segment,units) { eventId, expected ->
            run.structural.couplings.filterKeys { it.eventId==eventId }.values.map { proof ->
                val cars=proof.outputCarIds.let { if(proof.outputReversed) it.reversed() else it }
                world.simpleAtoWorldData.depots.fixedUnitsForFormation(cars).map { it.id }
            }.filter { it.toSet()==expected }.distinct().singleOrNull()
        }
    }

    private fun retireRunDisplays(world: World, run: ActiveRun) {
        if(run.currentUnitIds.isEmpty() || world.getTrain(run.train.id) !== run.train) return
        val actual=registeredOperatingUnits(world,run)
        displayRuntime.retire(run.id.timetableId,run.cycleIndex,actual)
        val timetable=world.simpleAtoWorldData.timetables[run.id.timetableId] ?: return
        run.unitDisplays=displayRuntime.snapshots(timetable,run.cycleIndex,run.currentUnitIds)
    }

    private fun actionValues(world: World, run: ActiveRun, literal: kotlinx.serialization.json.JsonObject,
        bindings: Map<String, TrainInfoField>): kotlinx.serialization.json.JsonObject =
        if (bindings.isEmpty()) literal else resolveTrainInfo(bindings, run.train.publishedFrame) { cars ->
            world.simpleAtoWorldData.depots.fixedUnitsForFormation(cars).size
        }

    private fun registeredOperatingUnits(world: World, run: ActiveRun): List<String> {
        val cars = run.train.cars.map { it.id }.let { if (run.train.isReversed) it.reversed() else it }
        return world.simpleAtoWorldData.depots.fixedUnitsForFormation(cars).map { it.id }
    }

    private fun refreshUnitDisplays(world: World, run: ActiveRun) {
        val timetable = checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
        if (timetable.operationalTrips.isNotEmpty() && run.currentUnitIds.isNotEmpty()) {
            run.currentUnitIds = registeredOperatingUnits(world,run)
            run.unitDisplays = displayRuntime.snapshots(timetable,run.cycleIndex,run.currentUnitIds)
        } else run.unitDisplays = emptyList()
    }

    /** Switching only queues a replacement on the same owner; controller consumes all commands before motion. */
    private fun enterRunSegment(world: World, run: ActiveRun, stepIndex: Int = run.nextStepIndex): Boolean {
        val segment = run.service.segmentAtOrBefore(stepIndex) ?: return true
        if (stepIndex < segment.startStepIndex || stepIndex >= segment.endStepIndexExclusive) return true
        if (run.currentSegment === segment) return true
        val failure = runCatching {
            if (run.currentSegment != null) check(run.ato.isStopped(run.ato.publishedState.trainSnapshot.speedMetersPerSecond)) { "through-running requires a stopped boundary" }
            segment.calls.firstOrNull()?.let { origin ->
                val heldOrigin = run.stationCall?.let { call -> call.berthed && !call.departed &&
                    OperationalDestinationIds.stationBerth(call.resolved.berthRef.stationId,call.resolved.berthRef.berthId) == origin.destinationId } == true
                check(heldOrigin || validateAtWayPoint(world,run.train,origin.destinationId) == null) {
                    "run ${segment.runId} does not start at its actual directed origin ${origin.destinationId}"
                }
            }
            val timetable = checkNotNull(world.simpleAtoWorldData.timetables[run.id.timetableId])
            applyTimetableLineSpeedLimit(run.ato,timetable,run.service,run.id,stepIndex)
            val relevant = timetable.operationalTrips.any { trip -> trip.segments.any { it.serviceId == run.service.id && it.runId == segment.runId } }
            val units = if (relevant) registeredOperatingUnits(world,run) else emptyList()
            if(!relevant && timetable.operationalTrips.isNotEmpty()) {
                val cars=run.train.cars.map { it.id }.toSet()
                displayRuntime.retire(timetable.id,run.cycleIndex,world.simpleAtoWorldData.depots.allFixedUnits().filter { unit->unit.carIds.all { it in cars } }.map { it.id })
            }
            if (relevant) {
                displayRuntime.enter(timetable,run.cycleIndex,run.service,segment,units)
                proveUnitFuture(world,run,timetable,segment,units)
            }
            run.currentSegment = segment
            run.currentUnitIds = units
            run.completedCallIds.clear()
            segment.calls.firstOrNull()?.takeIf { it.status == TimetableServiceStopStatus.ORIGIN }?.let { call ->
                run.completedCallIds += call.id
                displayRuntime.reached(timetable.id,run.cycleIndex,run.service.id,segment.runId,call.id,units)
            }
            refreshUnitDisplays(world,run)
        }.exceptionOrNull()
        if (failure == null) return true
        stallRun(world,run,"RunSegmentContextFailure",failure.message ?: failure.javaClass.name)
        return false
    }

    private fun applyTimetableLineSpeedLimit(
        ato: SimpleATO,
        timetable: Timetable,
        service: TimetableService,
        runId: TimetableServiceRunId,
        stepIndex: Int = 0
    ) {
        val speed = LineMaximumSpeedResolver(timetable).resolve(service, stepIndex)
        if (service.runSegments.isNotEmpty()) {
            ato.setSpeedLimit(timetableLineSpeedKey(runId), requireNotNull(speed) { "segmented run has no line speed" })
            return
        }
        ato.setLineMaximumSpeed(
            timetableLineSpeedKey(runId),
            LineMaximumSpeedResolver(timetable),
            service
        )
    }

    private fun timetableLineSpeedKey(runId: TimetableServiceRunId): String =
        "timetable-line/${runId.timetableId}/${runId.serviceId}"

    private fun stopKey(run: ActiveRun): String = "dispatcher:stop/${run.instanceId}"

    private fun formationProgramHoldKey(run: ActiveRun): String = "dispatcher:formation-program/${run.instanceId}"

    private fun selectionHoldKey(run: ActiveRun): String = "dispatcher:station-selection/${run.instanceId}"

    private fun stationProgramHoldKey(run: ActiveRun): String = "dispatcher:station-program/${run.instanceId}"

    private fun stationPendingHoldKey(run: ActiveRun): String =
        "dispatcher:station-pending/${run.instanceId}"

    private fun scheduleHoldKey(run: ActiveRun): String =
        "dispatcher:timetable-time/${run.instanceId}"

    private companion object {
        val LOGGER = java.util.logging.Logger.getLogger(TimetableDispatcher::class.java.name)
        const val POSITION_TOLERANCE_METERS = 0.5
        const val RELEASE_EPSILON_METERS = 1.0e-6
        const val DEPOT_DEPARTURE_RELEASE_EPSILON_METERS = 1.0e-6
        const val DEFAULT_OPERATION_TIMEOUT_TICKS = 100L
        const val MANUAL_PROTECTION_TOLERANCE_METERS = 0.5
        const val MANUAL_NAVIGATION_PREFIX = "manual-navigate-"
        const val POWER_OFF_ACTION = "power-off"
    }

    private data class TimetableServiceRunKey(
        val id: TimetableServiceRunId,
        val epochDispatcherTick: Long,
        val cycleIndex: Long
    ) {
        init {
            require(epochDispatcherTick >= 0L)
            require(cycleIndex >= 0L)
        }
    }

    private data class WaypointProgressKey(
        val runId: TimetableServiceRunId,
        val cycleIndex: Long
    )

    private fun ActiveRun.waypointProgressKey(): WaypointProgressKey =
        WaypointProgressKey(id, cycleIndex)

    private data class TimetableClock(
        val epochDispatcherTick: Long,
        val periodTicks: Long,
        var nextCycleIndex: Long = 1L,
        var lastCycleStartFailure: String? = null,
        val disabledServiceIds: MutableSet<String> = LinkedHashSet(),
        val pendingActivationCycles: MutableMap<String, Long> = LinkedHashMap()
    )

    private data class FixedOutputContinuation(
        val outputId: String,
        val service: TimetableService,
        val stepIndex: Int,
        val step: TimetableStep.At,
        val candidate: StructuralSourceCandidateKey,
        val evidence: ValidatedStructuralOutputRoute?,
        val outputReversed: Boolean = evidence?.expectedReversed ?: false
    )

    private class PreparingCoupling(val id: String, val event: TimetableFormationEvent.Couple,
        val coordinator: ActiveRun, val moving: ActiveRun, val stationary: ActiveRun,
        val evidence: ValidatedStructuralCoupling, val request: CouplingApproachRequest,
        val route: RouteSnapshot, val distance: Double, val continuation: FixedOutputContinuation?)

    private class CouplingContactExecution(val moving: ActiveRun, val route: AdmissionRoute,
        val distance: Double, var pending: RouteAdmissionHandle? = null)

    private class DispatcherFixedFormation(
        val source: ActiveRun,
        val event: TimetableFormationEvent,
        outputOwners: Map<String, work.art1st.javarailways.simpleato.api.depot.FixedFormationOwner>,
        val operation: FixedFormationOperation,
        val continuations: Map<String, FixedOutputContinuation>,
        val outputRecords: List<work.art1st.javarailways.simpleato.api.depot.RollingStockRecord>,
        val admission: RouteResourceAdmissionService,
        sourceRuns: List<ActiveRun> = listOf(source),
        var handoff: FixedFormationOutputHandoff? = null,
        var installed: Boolean = false
    ) {
        val outputOwners = java.util.Map.copyOf(outputOwners)
        val sourceRuns = java.util.List.copyOf(sourceRuns)
        var contact: CouplingContactExecution? = null
    }

    private class FormationAdmissionBinding(var handle: FormationChangeHandle? = null)

    private fun formationAwareAdmissionService(run: ActiveRun, service: RouteResourceAdmissionService): RouteResourceAdmissionService {
        val binding = run.formationAdmissionBinding
        return object : RouteResourceAdmissionService by service {
            override fun request(intent: RouteResourceIntent): RouteAdmissionResult = binding.handle?.let {
                service.requestFormationOutput(it, intent)
            } ?: service.request(intent)
        }
    }

    private data class AppliedTimetablePlan(
        val owner: InfrastructureOwner,
        val dependencies: InfrastructureDependencies,
        val retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
        val structural: StructuralTimetableValidation,
        val definition: Timetable
    )

    private sealed interface AppliedPlanInstallResult {
        data class Installed(val plan: AppliedTimetablePlan?) : AppliedPlanInstallResult
        data class Rejected(val reason: String) : AppliedPlanInstallResult
    }

    private data class StationCallSpec(
        val action: TimetableAction.CallStation,
        val address: TimetableStepAddress
    )

    private data class SelectedServiceTrain(
        val trainId: String,
        val originWayPointId: String?,
        val depotAllocation: DepotTrainAllocation?,
        val dynamicAcquisition: Boolean,
        val depotReservationPending: Boolean
    )

    private class PendingStopSelection(val action: TimetableAction.SelectStop, val step: TimetableStep.At,
        val address: TimetableStepAddress, val cursor: TimetableProgramCursor) {
        var key: StationBerthAllocationKey? = null
        var spec: StationCallSpec? = null
        var committed = false
        var cancelled = false
        var retryAtTick = 0L
        val deniedCandidates = linkedSetOf<String>()
    }

    private data class MovementPlan(
        val origin: WayPointRef,
        val points: List<AtoMissionPoint>,
        val target: WayPointRef,
        val priorityTick: Long,
        val actionTick: Long,
        val address: TimetableStepAddress,
        val successor: TimetableProgramCursor,
        val stationCallSpec: StationCallSpec?,
        val destinationOptions: TimetableAction.DestinationOptions?,
        val requiredStartDirection: ViewDirection? = null,
        val explicitReplanLegs: List<ValidatedTimetableRoute>? = null,
        val reservedMissionRevision: Long? = null
    )

    private data class PreparedMission(
        val mission: AtoMission,
        val layout: MissionRouteLayout
    )

    private data class MissionSubmission(
        val accepted: PreparedMission?,
        val failure: String?
    )

    private data class MissionRuntime(
        val mission: AtoMission,
        val layout: MissionRouteLayout,
        val priorityTick: Long,
        val targetWayPointId: String,
        val address: TimetableStepAddress,
        var headProgressMeters: Double = 0.0,
        var tailProgressMeters: Double? = null,
        var publishedAuthorityMeters: Double = -1.0,
        var lastOdometerMeters: Double,
        var reportedPointCount: Int = 0
    )

    private data class AdmissionRoute(
        val route: FrozenMissionRoute,
        val missionRevision: Long,
        /** A stopped formation handoff has body authority, not a prepared moving route. */
        val requiresTerminalCompletion: Boolean = false
    ) {
        fun withObservation(stamp: RailwayStateStamp): AdmissionRoute = copy(
            route = route.copy(
                selection = route.selection.copy(observationStamp = stamp)
            )
        )

        fun progressFor(runtime: MissionRuntime): Double =
            if (runtime.mission.revision == missionRevision) {
                route.routeProgressMeters(runtime.headProgressMeters)
            } else {
                route.selection.route.totalLengthMeters
            }

        fun missionAuthorityMeters(boundaryMeters: Double, runtime: MissionRuntime): Double =
            if (runtime.mission.revision == missionRevision) {
                (boundaryMeters - route.headDistanceFromRouteStartMeters +
                    route.missionHeadProgressAtSelectionMeters)
                    .coerceIn(0.0, runtime.layout.totalLengthMeters)
            } else {
                runtime.layout.totalLengthMeters
            }
    }

    private data class CompletionProof(val topology: TopologyState,
        val route: work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot,
        val grant: RouteGrantId, val boundaryMeters: Double, val head: RailPositionSnapshot,
        val expectedProgressMeters: Double)

    private data class DispatcherStationCall(
        var resolved: ResolvedStationCall,
        val missionRuntime: MissionRuntime,
        val missionDistanceMeters: Double,
        var allocationKey: StationBerthAllocationKey? = null,
        var submittedToAto: Boolean = false,
        var approaching: Boolean = false,
        var berthed: Boolean = false,
        var departureGranted: Boolean = false,
        var departed: Boolean = false,
        var stateEnteredDispatcherTick: Long = 0L,
        var earliestDepartureDispatcherTick: Long = Long.MAX_VALUE,
        var operationTicket: TrainOperationTicket? = null,
        var operationTicketId: String? = null,
        var operationStartedDispatcherTick: Long = Long.MAX_VALUE,
        var plannedDepartureTimetableTick: Long? = null,
        var waitForNextTimetableAction: Boolean = false,
        var cancelledForControlTransfer: Boolean = false,
        var lastNotificationEpoch: Long = -1L,
        var stopPrograms: StationStopPrograms? = null,
        val releaseDetector: RailIntervalReleaseDetector
    )

    private data class DispatcherReverseOperation(
        val preparationRevision: Long,
        val outputHandoff: FixedFormationOutputHandoff? = null,
        var reversedAfter: Boolean? = null,
        var reversedAtDispatcherTick: Long? = null
    )

    private data class DispatcherHaltOperation(
        val ticket: TrainOperationTicket,
        val ticketId: String,
        val startedDispatcherTick: Long
    )

    private sealed interface AutomaticActivation {
        data object START : AutomaticActivation
        data object SKIP : AutomaticActivation
        data class WAIT(val reason: String) : AutomaticActivation
        data class ERROR(val reason: String) : AutomaticActivation
    }

    private data class DispatcherExtensionAction(
        val ticket: TimetableExtensionActionTicket,
        val typeId: String,
        val scheduledTick: Long,
        val startedDispatcherTick: Long,
        val successor: TimetableProgramCursor
    )

    private enum class StopProgramLocation { ORIGIN, STATION, DESTINATION }

    private class DispatcherStopExecution(
        val programs: StationStopPrograms,
        val doorSide: DoorSide,
        val location: StopProgramLocation,
        val invocationId: String
    ) {
        lateinit var runtime: StationStopProgramRuntime
        var reverseCompleted = false
        var reversed = false
        var operatingDoorSide = doorSide
    }

    private data class DispatcherDestinationStop(
        val options: TimetableAction.DestinationOptions,
        val missionRuntime: MissionRuntime,
        var earliestDepartureDispatcherTick: Long = Long.MAX_VALUE,
        var started: Boolean = false,
        var operationTicket: TrainOperationTicket? = null,
        var operationTicketId: String? = null,
        var operationStartedDispatcherTick: Long = Long.MAX_VALUE
    )

    private class ActiveRun(
        val id: TimetableServiceRunId,
        val service: TimetableService,
        val retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
        val train: CoreTrain,
        var ato: SimpleATO,
        var controllerOwned: Boolean,
        var missionRevision: Long,
        nextStepIndex: Int,
        var currentWayPointId: String,
        currentRuntime: MissionRuntime?,
        var state: TimetableServiceRunState,
        val cycleIndex: Long,
        val cycleStartDispatcherTick: Long,
        admissionOwnerSequence: Long,
        infrastructureDependencyService: InfrastructureDependencyService,
        var lastObservedDispatcherTick: Long = cycleStartDispatcherTick,
        var lastPublishedAtoState: SimpleAtoPublishedState? = null,
        var waitingUntilTimetableTick: Long? = null,
        var waitingStepPending: Boolean = false,
        var lastScheduledActionTick: Long? = null,
        var lastLatenessTicks: Long = 0L,
        var dwellTicksRemaining: Long = 0L,
        var message: String? = null,
        var stationCall: DispatcherStationCall? = null,
        var destinationStop: DispatcherDestinationStop? = null,
        var pendingDestinationOptions: TimetableAction.DestinationOptions? = null,
        var stopExecution: DispatcherStopExecution? = null,
        var stationDoorsSecured: Boolean? = null,
        var stationInvocationSequence: Long = 0L,
        var pendingStationCallSpec: StationCallSpec? = null,
        var deferredStationCallSpec: StationCallSpec? = null,
        var haltOperation: DispatcherHaltOperation? = null,
        var reverseOperation: DispatcherReverseOperation? = null,
        var extensionAction: DispatcherExtensionAction? = null,
        var requiredNextStartDirection: ViewDirection? = null,
        var controlTransferOwner: String? = null,
        var controlMode: TimetableControlMode = TimetableControlMode.AUTOMATIC,
        var manualController: TrainController? = null,
        val depotAllocation: DepotTrainAllocation? = null,
        var depotReservationPending: Boolean = false,
        var depotDepartureRuntime: MissionRuntime? = null,
        var depotDepartureReleased: Boolean = depotAllocation == null,
        var storageReservation: DepotStorageReservation? = null,
        var physicalStorageOnly: Boolean = false,
        var storageRuntime: MissionRuntime? = null,
        var storageCompleted: Boolean = false,
        admissionOwnerOverride: InfrastructureOwner? = null
    ) {
        private val program = TimetableServiceTraversal(service)
        var programCursor = TimetableProgramCursor.start(program, nextStepIndex)
        var activeStepAddress: TimetableStepAddress? = programCursor.address
        var nextStepIndex: Int
            get() = programCursor.address?.topLevelIndex ?: service.steps.size
            set(value) { programCursor = TimetableProgramCursor.start(program, value) }
        fun remainingSteps(): Sequence<TimetableStep> = sequence {
            var cursor = programCursor
            while (!cursor.isComplete) {
                yield(checkNotNull(cursor.currentStep()))
                cursor = cursor.advance()
            }
        }
        var currentSegment: TimetableRunSegment? = null
        var currentUnitIds: List<String> = emptyList()
        val taskTimings = mutableListOf<TimetableTaskTiming>()
        var unitDisplays: List<TimetableUnitDisplaySnapshot> = emptyList()
        val completedCallIds = hashSetOf<String>()
        var currentRuntimeOrNull: MissionRuntime? = currentRuntime
        var currentRuntime: MissionRuntime
            get() = checkNotNull(currentRuntimeOrNull) { "service $id has not submitted its first Mission" }
            set(value) { currentRuntimeOrNull = value }

        val instanceId: String = "$id@cycle-$cycleIndex/start-$cycleStartDispatcherTick"
        val admissionOwner = admissionOwnerOverride ?: InfrastructureOwner("simpleato:dispatcher/run-$admissionOwnerSequence", 0L)
        var routeEditProtection: FrozenRouteEditProtection? = FrozenRouteEditProtection(
            "simpleato:dispatcher/run-$admissionOwnerSequence/frozen-route",
            infrastructureDependencyService
        )
        var admissionClient: RouteAuthorityAdmissionClient? = null
        var formationAdmissionBinding = FormationAdmissionBinding()
        var structural: StructuralTimetableValidation = StructuralTimetableValidation()
        var stopSelection: PendingStopSelection? = null
        var branchTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY
        var structuralCandidate: StructuralSourceCandidateKey? = null
        var structuralOutput: Pair<String, String>? = null
        var structuralEventInstanceId: String? = null
        var physicalOriginPending: Boolean = false
        var formationBlocked: Boolean = false
        var formationSourceAdmissionWaiting: Boolean = false
        var pendingFormationActivation: FixedOutputContinuation? = null
        var admittedRoute: AdmissionRoute? = null
        var admissionCandidate: AdmissionRoute? = null
        var completionProof: CompletionProof? = null
        var nextAdmissionAttemptTick: Long = 0L
        var admissionEnded: Boolean = false

        /** Mission runtimes still referenced by an active operational boundary. */
        fun referencedRuntimes(): List<MissionRuntime> = buildList {
            fun addOnce(runtime: MissionRuntime?) {
                if (runtime != null && none { it === runtime }) add(runtime)
            }
            addOnce(currentRuntimeOrNull)
            addOnce(depotDepartureRuntime)
            addOnce(storageRuntime)
            addOnce(stationCall?.missionRuntime)
            addOnce(destinationStop?.missionRuntime)
        }

        fun canBeReplaced(): Boolean =
            state.isTerminal() &&
                (admissionClient == null || admissionEnded) &&
                stationCall?.allocationKey == null && storageReservation == null &&
                ato.isStopped(ato.publishedState.trainSnapshot.speedMetersPerSecond)

        fun isStoredTerminal(): Boolean =
            state == TimetableServiceRunState.COMPLETED && storageCompleted &&
                storageReservation == null && stationCall?.allocationKey == null &&
                ato.isStopped(ato.publishedState.trainSnapshot.speedMetersPerSecond)

        fun snapshot(): TimetableServiceRunSnapshot {
            var operationError: String? = null
            val operationTicket = stationCall?.operationTicket
                ?: destinationStop?.operationTicket
                ?: haltOperation?.ticket
            val operation = operationTicket?.let { ticket ->
                try {
                    ticket.snapshot()
                } catch (failure: Exception) {
                    operationError = "operation ticket snapshot failed: " +
                        (failure.message ?: failure::class.java.simpleName)
                    null
                }
            }
            return TimetableServiceRunSnapshot(
                id = id,
                trainId = train.id,
                missionRevision = missionRevision,
                state = state,
                routeGrantActive = admissionClient?.state?.committedAuthority != null && !admissionEnded,
                cycleIndex = cycleIndex,
                timetableElapsedTicks =
                    (lastObservedDispatcherTick - cycleStartDispatcherTick).coerceAtLeast(0L),
                scheduledActionTick = waitingUntilTimetableTick
                    ?: stationCall?.plannedDepartureTimetableTick
                    ?: lastScheduledActionTick,
                lastLatenessTicks = lastLatenessTicks,
                currentWayPointId = currentWayPointId.takeUnless { physicalOriginPending },
                nextStepIndex = nextStepIndex,
                nextStepBranchPath = programCursor.address?.branchPath ?: emptyList(),
                activeStepIndex = activeStepAddress?.topLevelIndex,
                activeStepBranchPath = activeStepAddress?.branchPath ?: emptyList(),
                nextScheduledTick = waitingUntilTimetableTick ?: if (stopExecution != null) null else remainingSteps()
                    .filterIsInstance<TimetableStep.At>()
                    .firstOrNull()
                    ?.tick,
                trainHeadRailId = ato.publishedState.trainSnapshot.head.railId.toString(),
                trainHeadArcLengthInAbMeters =
                    ato.publishedState.trainSnapshot.head.arcLengthInAbMeters,
                headProgressMeters = currentRuntimeOrNull?.headProgressMeters,
                tailProgressMeters = currentRuntimeOrNull?.tailProgressMeters,
                stationCallId = stationCall?.resolved?.call?.callId,
                berthRef = stationCall?.resolved?.berthRef,
                berthHeld = stationCall?.allocationKey != null,
                selectedBerthRef = stopSelection?.key?.berthRef,
                selectedBerthCommitted = stopSelection?.committed == true,
                operationTicketId = operation?.ticketId
                    ?: stationCall?.operationTicketId
                    ?: destinationStop?.operationTicketId
                    ?: haltOperation?.ticketId,
                operationTicketState = operation?.state,
                operationTicketError = operationError,
                controlTransferOwner = controlTransferOwner,
                controlMode = controlMode,
                message = message,
                currentRunId = currentSegment?.runId,
                currentLineId = currentSegment?.lineId ?: service.lineId,
                taskTimings = taskTimings.toList(),
                unitDisplays = unitDisplays
            )
        }
    }
}

private fun TimetableServiceRunState.isTerminal(): Boolean =
    this == TimetableServiceRunState.STOPPED ||
        this == TimetableServiceRunState.COMPLETED ||
        this == TimetableServiceRunState.FAILED

private fun TimetableServiceRunState.isStationOperationalState(): Boolean =
    this == TimetableServiceRunState.WAITING_BERTH ||
        this == TimetableServiceRunState.APPROACHING ||
        this == TimetableServiceRunState.BERTHED ||
        this == TimetableServiceRunState.DOORS_OPENING ||
        this == TimetableServiceRunState.DWELLING ||
        this == TimetableServiceRunState.DOORS_CLOSING ||
        this == TimetableServiceRunState.READY_TO_DEPART ||
        this == TimetableServiceRunState.DEPARTING ||
        this == TimetableServiceRunState.DEPARTED ||
        this == TimetableServiceRunState.MANUAL_INTERVENTION
