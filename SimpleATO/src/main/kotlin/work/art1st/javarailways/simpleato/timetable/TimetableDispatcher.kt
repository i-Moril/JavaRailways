package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.ato.AtoStallReport
import work.art1st.javarailways.basic.api.ato.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionPoint
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionRequest
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionResolutionResult
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionResolver
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.depot.DepotTrainAllocation
import work.art1st.javarailways.basic.api.depot.DepotResetDispatchApplyException
import work.art1st.javarailways.basic.api.depot.DepotResetDispatchParticipant
import work.art1st.javarailways.basic.api.depot.DepotResetDispatchSummary
import work.art1st.javarailways.basic.api.depot.DepotStorageReservation
import work.art1st.javarailways.basic.api.depot.DepotStorageReservationResult
import work.art1st.javarailways.basic.api.depot.DepotTrainAllocationResult
import work.art1st.javarailways.basic.api.depot.RollingStockState
import work.art1st.javarailways.basic.api.depot.StablingPointRef
import work.art1st.javarailways.basic.api.destination.OperationalDestinationIds
import work.art1st.javarailways.basic.api.operation.RejectingTrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.DwellPlanResolver
import work.art1st.javarailways.basic.api.station.ResolvedStationCall
import work.art1st.javarailways.basic.api.station.StationBerthAllocationKey
import work.art1st.javarailways.basic.api.station.StationBerthAllocationResult
import work.art1st.javarailways.basic.api.station.StationBerthAllocator
import work.art1st.javarailways.basic.api.station.StationBerthOwner
import work.art1st.javarailways.basic.api.station.StationBerthRef
import work.art1st.javarailways.basic.api.station.StationCall
import work.art1st.javarailways.basic.api.station.StationCallNotificationType
import work.art1st.javarailways.basic.api.station.StationCallRequest
import work.art1st.javarailways.basic.api.station.StationCallResolutionResult
import work.art1st.javarailways.basic.api.station.StationCallResolver
import work.art1st.javarailways.basic.api.station.StationCallSubmissionResult
import work.art1st.javarailways.basic.api.timetable.TimetableActivationFalseBehavior
import work.art1st.javarailways.basic.api.timetable.TimetableConditionResult
import work.art1st.javarailways.basic.api.timetable.TimetableConditionScope
import work.art1st.javarailways.basic.api.timetable.TimetableExtensionActionState
import work.art1st.javarailways.basic.api.timetable.TimetableExtensionActionTicket
import work.art1st.javarailways.basic.api.timetable.TimetableExtensionActionStartResult
import work.art1st.javarailways.basic.api.timetable.TimetableRuntimeContext
import work.art1st.javarailways.basic.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.basic.api.timetable.TimetableTrainContext
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.controller.SimpleAtoPublishedState
import work.art1st.javarailways.simpleato.admission.RollingRouteAuthorityPlanner
import work.art1st.javarailways.simpleato.admission.RouteAuthorityAdmissionClient
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionRouteBinding
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenMissionRouteAssembler
import work.art1st.javarailways.simpleato.routing.FrozenMissionRouteResult
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionContext
import work.art1st.javarailways.simpleato.persistence.SavedActiveRunCheckpointV1
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableClockV1
import work.art1st.javarailways.basic.timetable.*
import work.art1st.javarailways.basic.route.MissionRouteLayout
import work.art1st.javarailways.basic.route.RouteAllocationTiming
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
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
    val nextScheduledTick: Long? = null,
    val trainHeadRailId: String? = null,
    val trainHeadArcLengthInAbMeters: Double? = null,
    val headProgressMeters: Double? = null,
    val tailProgressMeters: Double? = null,
    val stationCallId: String? = null,
    val berthRef: StationBerthRef? = null,
    val berthHeld: Boolean = false,
    val operationTicketId: String? = null,
    val operationTicketState: TrainOperationTicketState? = null,
    val operationTicketError: String? = null,
    val controlTransferOwner: String? = null,
    val controlMode: TimetableControlMode = TimetableControlMode.AUTOMATIC,
    val message: String? = null
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
    private val allocationTiming: RouteAllocationTiming = RouteAllocationTiming(
        switchSetupSeconds = 0.25,
        reactionTimeSeconds = 0.25,
        safetyMarginMeters = 2.0
    ),
    private val operationGateway: TrainOperationGateway = RejectingTrainOperationGateway,
    private val operationTimeoutTicks: Long = DEFAULT_OPERATION_TIMEOUT_TICKS,
    private val atoFactory: TimetableAtoFactory? = null
) : DepotResetDispatchParticipant {
    init {
        require(operationTimeoutTicks > 0L)
    }
    private val missionRevision = AtomicLong(0L)
    private val admissionOwnerSequence = AtomicLong(0L)
    private val runs = LinkedHashMap<TimetableServiceRunKey, ActiveRun>()
    private val timetableClocks = LinkedHashMap<String, TimetableClock>()
    private val waypointProgressListeners =
        LinkedHashMap<WaypointProgressKey, MutableList<TimetableWaypointProgressListener>>()
    private val recoveryRequiredRuns = ArrayList<SavedActiveRunCheckpointV1>()
    private val physicalStorageReservations = LinkedHashMap<StablingPointRef, String>()
    private val berthAllocator = StationBerthAllocator()
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

    /** Restore clocks and retain former active runs as explicit RECOVERY_REQUIRED checkpoints. */
    fun restoreCheckpoint(saved: SavedDispatcherV1) {
        require(saved.formatVersion == SavedDispatcherV1.CURRENT_FORMAT_VERSION) {
            "Unsupported dispatcher snapshot version: ${saved.formatVersion}"
        }
        check(runs.isEmpty() && timetableClocks.isEmpty()) { "Dispatcher must be empty before restore" }
        require(saved.dispatcherTick >= 0L && saved.missionRevision >= 0L)
        dispatcherTick = saved.dispatcherTick
        missionRevision.set(saved.missionRevision)
        saved.clocks.forEach { clock ->
            timetableClocks[clock.timetableId] = TimetableClock(
                epochDispatcherTick = clock.epochDispatcherTick,
                periodTicks = clock.periodTicks,
                nextCycleIndex = clock.nextCycleIndex,
                disabledServiceIds = LinkedHashSet(clock.disabledServiceIds),
                pendingActivationCycles = LinkedHashMap(clock.pendingActivationCycles)
            )
        }
        recoveryRequiredRuns.clear()
        recoveryRequiredRuns.addAll(saved.runs)
        saved.runs.forEach { run ->
            timetableClocks[run.timetableId]?.disabledServiceIds?.add(run.serviceId)
        }
    }

    fun recoveryRequiredRuns(): List<SavedActiveRunCheckpointV1> = recoveryRequiredRuns.toList()

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
        bindWorld(world)
        if (world.basicWorldData.atoCoordinator.isStalled) {
            return TimetableEpochStartResult.Failure(
                "world ATO is stalled; inspect and reset before starting a timetable"
            )
        }
        val timetable = world.basicWorldData.timetables[timetableId]
            ?: return TimetableEpochStartResult.Failure("timetable not found: $timetableId")
        val validation = TimetableValidator(world).validate(timetable)
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
        runs.entries.removeIf { (key, run) ->
            key.id.timetableId == timetableId && run.canBeReplaced()
        }
        when (val initialPositions = TimetableInitialPositioner(world).apply(timetable, dispatcherTick)) {
            is TimetableInitialPositionResult.Applied -> Unit
            is TimetableInitialPositionResult.Failure -> return TimetableEpochStartResult.Failure(
                "initial position reset failed: ${initialPositions.reason}"
            )
        }
        val clock = TimetableClock(
            epochDispatcherTick = dispatcherTick,
            periodTicks = timetable.periodTicks,
            nextCycleIndex = 1L
        )
        timetableClocks[timetableId] = clock
        return TimetableEpochStartResult.Started(
            TimetableEpochSnapshot(timetableId, clock.epochDispatcherTick, clock.periodTicks)
        )
    }

    /** Queue automatic services in cycle zero; manual services remain explicit-only. */
    @MainThread
    fun scheduleInitialCycle(timetableId: String): Boolean {
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
        timetableClocks[timetableId]?.disabledServiceIds?.remove(serviceId)
        return startServiceForCycle(world, timetableId, serviceId, null)
    }

    /** Stop automatic recurrence without force-releasing any active run resources. */
    @MainThread
    fun stopFutureCycles(timetableId: String, serviceId: String? = null): Boolean {
        val clock = timetableClocks[timetableId] ?: return false
        return if (serviceId == null) {
            timetableClocks.remove(timetableId) != null
        } else {
            clock.disabledServiceIds.add(serviceId)
        }
    }

    private fun startServiceForCycle(
        world: World,
        timetableId: String,
        serviceId: String,
        requestedCycleIndex: Long?
    ): TimetableServiceStartResult {
        if (world.basicWorldData.atoCoordinator.isStalled) {
            return TimetableServiceStartResult.Failure(
                "world ATO is stalled; inspect and reset before starting a service"
            )
        }
        val runId = TimetableServiceRunId(timetableId, serviceId)
        val timetable = world.basicWorldData.timetables[timetableId]
            ?: return TimetableServiceStartResult.Failure("timetable not found: $timetableId")
        val clock = timetableClocks.getOrPut(timetableId) {
            TimetableClock(dispatcherTick, timetable.periodTicks)
        }
        val service = timetable.services.firstOrNull { it.id == serviceId }
            ?: return TimetableServiceStartResult.Failure("service not found: $runId")
        val validationErrors = TimetableValidator(world).validate(
            timetable.copy(services = listOf(service))
        ).errors
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
                if (world.basicWorldData.atoCoordinator.isStalled) {
                    "world stalled because train acquisition failed: $runId"
                } else {
                    "service requires TrainSelector.TrainId, takeFromDepot, or takeFromWaypoint: $runId"
                }
            )
        val trainId = selected.trainId
        fun startupFailure(reason: String): TimetableServiceStartResult.Failure {
            if (selected.depotReservationPending) {
                selected.depotAllocation?.let(world.basicWorldData.depots::cancelReservation)
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
            lastObservedDispatcherTick = dispatcherTick,
            depotAllocation = selected.depotAllocation,
            depotReservationPending = selected.depotReservationPending,
            depotDepartureRuntime = null,
            depotDepartureReleased = selected.depotAllocation == null || !selected.depotReservationPending
        )
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
            origin = WayPointRef(run.currentWayPointId),
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
            requiredStartDirection = missionStartDirection
        )) {
            is StationCallResolutionResult.Success -> result.resolved
            is StationCallResolutionResult.Failure -> {
                return TimetableStationCallScheduleResult.Failure(result.message)
            }
        }
        val berth = world.basicWorldData.stations.berth(call.berthRef)
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
        val station = world.basicWorldData.stations[berthRef.stationId]
            ?: return "station not found: ${berthRef.stationId}"
        val berth = world.basicWorldData.stations.berth(berthRef)
            ?: return "berth not found: $berthRef"
        val dwellPlan = DwellPlanResolver.resolve(
            station = station,
            berth = berth,
            minimumDwellTicksOverride = spec.action.minimumDwellTicks
        )
        val call = StationCall(
            callId = "${run.instanceId}/station/${spec.stepIndex}",
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
                run.stationCall?.waitForNextTimetableAction = true
                null
            }
            is TimetableStationCallScheduleResult.Failure -> result.reason
        }
    }

    /** Transfer speed control while retaining Dispatcher route assistance and tail release. */
    @MainThread
    fun enterManualRoutedControl(
        world: World,
        trainId: String,
        transferOwner: String
    ): TimetableControlTransferResult {
        require(trainId.isNotBlank())
        require(transferOwner.isNotBlank() && transferOwner == transferOwner.trim())
        bindWorld(world)
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
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
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
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
        require(trainId.isNotBlank())
        require(transferOwner.isNotBlank() && transferOwner == transferOwner.trim())
        bindWorld(world)
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
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
        bindWorld(world)
        val run = findRun(id, cycleIndex)
            ?: return TimetableReplanResult.Failure("service run not found: $id")
        if (world.basicWorldData.atoCoordinator.isStalled) {
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
        val timetable = world.basicWorldData.timetables[run.id.timetableId]
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
        applyTimetableLineSpeedLimit(replacementAto, timetable, run.service, run.id)
        val target = WayPointRef(run.currentRuntime.targetWayPointId)
        if (target.id == originWayPointId) {
            run.ato = replacementAto
            run.controllerOwned = true
            run.pendingStationCallSpec = null
            run.train.setController(replacementAto)
            run.controlMode = TimetableControlMode.AUTOMATIC
            run.manualController = null
            run.currentWayPointId = target.id
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
            nextStepIndex = run.nextStepIndex,
            stationCallSpec = null,
            destinationOptions = null
        )
        val submission = submitMission(world, replacementAto, run.id, plan)
        val accepted = submission.accepted
            ?: return TimetableReplanResult.Failure(submission.failure!!)
        val runtime = MissionRuntime(
            mission = accepted.mission,
            layout = accepted.layout,
            priorityTick = plan.priorityTick,
            targetWayPointId = target.id,
            lastOdometerMeters = replacementAto.lifetimeTravelDistanceMeters
        )
        run.ato = replacementAto
        run.controllerOwned = true
        run.pendingStationCallSpec = null
        run.currentWayPointId = originWayPointId
        run.currentRuntime = runtime
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
        dispatcherTick++
        val coordinator = world.basicWorldData.atoCoordinator
        if (coordinator.isStalled) {
            runs.values.filter { it.state.isTerminal() }.forEach {
                waypointProgressListeners.remove(it.waypointProgressKey())
            }
            return
        }
        bindWorld(world)
        val routeAdmissionContext = routeAdmissionContextProvider.current()
        val routeSelectionContext = routeAdmissionContext?.observation?.let(::FrozenRouteSelectionContext)

        runs.values.toList().forEach { run ->
            if (coordinator.isStalled) return
            if (run.state.isTerminal()) {
                progressSharedAdmissionOnly(world, run, routeAdmissionContext)
                tryEndSharedAdmission(run)
                waypointProgressListeners.remove(run.waypointProgressKey())
                return@forEach
            }
            run.lastObservedDispatcherTick = dispatcherTick
            val manualRouted = run.controlMode == TimetableControlMode.MANUAL_ROUTED
            val fullManual = run.controlMode == TimetableControlMode.FULL_MANUAL ||
                run.state == TimetableServiceRunState.SUSPENDED ||
                run.state == TimetableServiceRunState.REPLAN_REQUIRED
            val controlTransferred = manualRouted || fullManual
            val publishedAtoState = run.ato.publishedState
            run.lastPublishedAtoState = publishedAtoState
            val snapshot = if (controlTransferred) {
                capturePublishedTrainSnapshot(run)
            } else {
                publishedAtoState.trainSnapshot
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
                tryEndSharedAdmission(run)
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
                    run.state = TimetableServiceRunState.STOPPED
                    run.message = run.message ?: "stopped by operator"
                    finishControllerIfReleased(run, snapshot)
                }
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
                processReverse(world, run)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.MANUAL_INTERVENTION) return@forEach
            if (run.state == TimetableServiceRunState.BERTHED &&
                stationCall != null && dispatcherTick > stationCall.stateEnteredDispatcherTick
            ) {
                if (stationCall.resolved.doorSide == DoorSide.NONE) {
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
                if (run.dwellTicksRemaining > 0L) run.dwellTicksRemaining--
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
                    if (run.nextStepIndex >= run.service.steps.size) {
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
            reportWaypointProgress(run, runtime, snapshot)

            val completedDestinationStop = run.destinationStop?.takeIf {
                it.missionRuntime === runtime && destinationStopCompleted(run, runtime, snapshot, it)
            }
            if (completedDestinationStop != null && !completedDestinationStop.started) {
                beginDestinationStop(world, run, completedDestinationStop)
                return@forEach
            }
            if (missionCompleted(run, runtime, snapshot) &&
                (run.stationCall == null || run.stationCall?.departed == true) &&
                run.destinationStop == null
            ) {
                if (runtime === run.storageRuntime) {
                    if (!tryCompleteStorage(world, run, runtime)) {
                        return@forEach
                    }
                }
                run.currentWayPointId = runtime.targetWayPointId
                advanceService(world, run)
                return@forEach
            }

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
            when (val result = world.basicWorldData.timetableExtensions.evaluate(
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
            val timetable = world.basicWorldData.timetables[timetableId]
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
                            if (world.basicWorldData.atoCoordinator.isStalled) return
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
                            if (world.basicWorldData.atoCoordinator.isStalled) return
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
        bindWorld(world)
        val run = findRun(id, cycleIndex) ?: return false
        if (run.canBeReplaced() || run.state == TimetableServiceRunState.STOPPED ||
            run.isStoredTerminal()
        ) return true
        cancelPendingOperation(run)?.let { reason ->
            stallRun(world, run, "TrainOperationFailure", reason)
            return false
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
        check(world.basicWorldData.atoCoordinator.isStalled) {
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
        require(timetableId.isNotBlank())
        bindWorld(world)
        val selected = runs.values.filter { it.id.timetableId == timetableId }
        selected.forEach { run -> discardRun(world, run) }
        val recovered = recoveryRequiredRuns.count { it.timetableId == timetableId }
        recoveryRequiredRuns.removeIf { it.timetableId == timetableId }
        timetableClocks.remove(timetableId)
        return selected.size + recovered
    }

    /**
     * Abandon a released ad-hoc navigation before another `/jr navigate` is started. Normal
     * timetable services are deliberately excluded: they still require explicit replan/depot
     * recovery. The caller has already released any managed manual-control session.
     */
    @MainThread
    fun discardReleasedManualNavigation(world: World, trainId: String): Int {
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
        check(run.admissionClient == null || run.admissionEnded) {
            "cannot discard ${run.id} while Basic route resources remain active"
        }
        listOfNotNull(
            run.stationCall?.operationTicket,
            run.destinationStop?.operationTicket,
            run.haltOperation?.ticket
        ).distinct().forEach { ticket -> runCatching { ticket.cancel() } }
        run.stationCall?.allocationKey?.let { key ->
            berthAllocator.release(key)
            run.stationCall?.allocationKey = null
        }
        run.storageReservation?.let { reservation ->
            if (run.physicalStorageOnly) {
                physicalStorageReservations.remove(reservation.stablingPointRef, run.instanceId)
            } else {
                world.basicWorldData.depots.cancelStorage(reservation)
            }
            run.storageReservation = null
        }
        run.ato.removeSpeedLimit(stopKey(run))
        run.ato.removeSpeedLimit(stationPendingHoldKey(run))
        run.ato.removeSpeedLimit(scheduleHoldKey(run))
        run.ato.removeSpeedLimit(timetableLineSpeedKey(run.id))
        if (run.train.controller === run.ato) run.train.setController(null)
        waypointProgressListeners.remove(run.waypointProgressKey())
        runs.entries.removeIf { (_, candidate) -> candidate === run }
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
        check(world.basicWorldData.atoCoordinator.isStalled) {
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
    }

    private fun enterReadyToDepart(run: ActiveRun, stationCall: DispatcherStationCall) {
        stationCall.stateEnteredDispatcherTick = dispatcherTick
        run.state = TimetableServiceRunState.READY_TO_DEPART
        run.message = "ready to depart ${stationCall.resolved.berthRef}"
    }

    private fun nextActionTick(run: ActiveRun): Long? =
        run.service.steps.drop(run.nextStepIndex)
            .filterIsInstance<TimetableStep.At>()
            .firstOrNull()
            ?.tick

    private fun completeOpenTerminalStation(
        run: ActiveRun,
        stationCall: DispatcherStationCall
    ) {
        run.currentWayPointId = stationCall.missionRuntime.targetWayPointId
        run.state = TimetableServiceRunState.COMPLETED
        run.message = "service completed with doors open at ${stationCall.resolved.berthRef}"
    }

    private fun completeOpenTerminalDestination(
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        run.currentWayPointId = stop.options.destinationId
        run.destinationStop = null
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
            runtime.layout.totalLengthMeters - offset - run.ato.missionStopToleranceMeters * 2.0 &&
            run.ato.isStopped(snapshot.speedMetersPerSecond)
    }

    private fun beginDestinationStop(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        stop.started = true
        val side = stop.options.doorSide ?: DoorSide.NONE
        if (side == DoorSide.NONE) {
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
        run.state = TimetableServiceRunState.DWELLING
        run.message = "dwelling at ${stop.options.destinationId}"
    }

    private fun finishDestinationStop(
        world: World,
        run: ActiveRun,
        stop: DispatcherDestinationStop
    ) {
        check(run.destinationStop === stop)
        run.currentWayPointId = stop.options.destinationId
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
        val snapshot = run.lastPublishedAtoState?.trainSnapshot
            ?: run.ato.publishedState.trainSnapshot
        if (!run.ato.isStopped(snapshot.speedMetersPerSecond)) {
            stallRun(world, run, "InfrastructureInvariantViolation", "reverse requires a stopped train")
            return
        }
        val stationCall = run.stationCall?.takeIf { it.berthed && !it.departed }
        val preparationRevision = run.ato.prepareForReverse(
            callId = stationCall?.resolved?.call?.callId,
            missionRevision = stationCall?.resolved?.call?.missionRevision
        )
        run.reverseOperation = DispatcherReverseOperation(
            preparationRevision = preparationRevision
        )
        run.state = TimetableServiceRunState.REVERSING
        run.message = "waiting for atomic ATO reverse preparation"
    }

    private fun processReverse(world: World, run: ActiveRun) {
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
            val facingBefore =
                (run.train.headLocation as? work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
                    ?.facing
            run.train.reverse()
            if (run.train.isReversed == reversedBefore) {
                stallRun(world, run, "InfrastructureInvariantViolation", "train direction did not reverse")
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
        run.reverseOperation = null
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

    private fun capturePublishedTrainSnapshot(run: ActiveRun): TrainSnapshot {
        val facing = (run.train.headLocation as? work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
            ?.facing ?: ViewDirection.AB
        return TrainSnapshot.capture(run.train, dispatcherTick, facing)
    }

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

    private fun isTerminalStationCall(stationCall: DispatcherStationCall): Boolean =
        abs(stationCall.missionDistanceMeters -
            stationCall.missionRuntime.layout.totalLengthMeters) <= POSITION_TOLERANCE_METERS

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
            ?: terminalProgressFallback(world, run, runtime)
        if (headProgress == null) {
            stallManualRouted(world, run, snapshot, "MANUAL_ROUTED train head left PlannedRoute")
            return
        }
        runtime.headProgressMeters = headProgress
        runtime.tailProgressMeters = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
            ?: runtime.tailProgressMeters

        if (missionCompleted(run, runtime, snapshot) &&
            (run.stationCall == null || run.stationCall?.cancelledForControlTransfer == true)
        ) {
            if (runtime === run.storageRuntime && !tryCompleteStorage(world, run, runtime)) return
            run.currentWayPointId = runtime.targetWayPointId
            advanceService(world, run)
            return
        }

        processSharedRouteAdmission(world, run, runtime, snapshot, context, selectionContext)
        if (world.basicWorldData.atoCoordinator.isStalled) return

        val activeRoute = run.admittedRoute ?: run.admissionCandidate ?: return
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
        if (context.observation.stamp.worldId != world.basicWorldData.atoCoordinator.worldId) {
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
        val result: RouteAdmissionResult
        if (existingClient?.state?.pending == true) {
            client = existingClient
            candidate = checkNotNull(run.admissionCandidate) {
                "pending route admission has no frozen candidate"
            }
            result = client.progress()
        } else {
            if (dispatcherTick < run.nextAdmissionAttemptTick) {
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                return
            }
            if (run.nextAdmissionAttemptTick != 0L) {
                // A rejected intent's observation stamp is no longer current. Preserve the route
                // geometry through admittedRoute, but rebuild the request selection for this epoch.
                run.admissionCandidate = null
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
                admissionService = context.service,
                restrictionPublisher = run.ato.trainRestrictionPublisher
            ).also {
                run.admissionClient = it
            }
            val routeProgress = candidate.progressFor(runtime)
            val desired = RollingRouteAuthorityPlanner.nextBoundary(
                routeLengthMeters = candidate.route.selection.route.totalLengthMeters,
                headProgressMeters = routeProgress,
                perceptionHorizonMeters = run.ato.routePerceptionHorizonMeters(),
                extensionTriggerMeters = run.ato.routeAllocationTriggerDistanceMeters(allocationTiming),
                admissionState = client.state
            )
            if (desired == null) {
                applyCommittedAdmissionRestriction(world, run, runtime, context.observation, snapshot)
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
                return
            }
            run.admissionCandidate = candidate
            run.admissionEnded = false
            result = client.request(candidate.route.selection, desired)
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
        run.admittedRoute?.takeIf { it.missionRevision == runtime.mission.revision }?.let {
            val stamp = context.observation.stamp
            val committed = it.route.selection
            if (stamp.worldId != committed.observationStamp.worldId ||
                stamp.topologyRevision != committed.route.topologyRevision
            ) {
                run.state = TimetableServiceRunState.REPLAN_REQUIRED
                run.message = "committed operating route topology changed; explicit replan is required"
                return null
            }
            return it.withObservation(stamp)
        }
        run.admissionCandidate?.takeIf { it.missionRevision == runtime.mission.revision }?.let { return it }

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
                runtime.mission
            )
        }
        return when (result) {
            is FrozenMissionRouteResult.Frozen -> AdmissionRoute(result.route, runtime.mission.revision)
            is FrozenMissionRouteResult.Rejected -> {
                run.state = TimetableServiceRunState.REPLAN_REQUIRED
                run.message = "frozen route requires explicit replan: ${result.reason}: ${result.detail}"
                null
            }
        }
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
        when (result.failure.code) {
            RouteAdmissionFailureCode.STALE_OBSERVATION,
            RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
            RouteAdmissionFailureCode.RESOURCE_CONFLICT,
            RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE -> {
                run.nextAdmissionAttemptTick = dispatcherTick +
                    world.basicWorldData.turnoutSafetyDefaults.retryTicks
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
        snapshot: TrainSnapshot
    ) {
        val activeRoute = run.admittedRoute ?: run.admissionCandidate ?: return
        try {
            run.ato.applyTrainRestrictions(
                currentStamp = observation.stamp,
                currentRoute = TrainRestrictionRouteBinding.from(activeRoute.route.selection.route),
                routeProgressMeters = activeRoute.progressFor(runtime)
            )
        } catch (failure: Exception) {
            stallRun(
                world,
                run,
                "RouteRestrictionFailure",
                failure.message ?: failure::class.java.simpleName,
                snapshot
            )
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
    }

    private fun tryEndSharedAdmission(run: ActiveRun) {
        val client = run.admissionClient ?: return
        if (!run.admissionEnded && client.end()) {
            run.admissionEnded = true
            run.admittedRoute = null
            run.admissionCandidate = null
            run.nextAdmissionAttemptTick = 0L
        }
    }

    private fun terminalProgressFallback(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime
    ): Double? {
        if (runtime.headProgressMeters <
            runtime.layout.totalLengthMeters - ORIGIN_TOLERANCE_METERS
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

    private fun stallRun(
        world: World,
        run: ActiveRun,
        errorType: String,
        reason: String,
        snapshot: TrainSnapshot = runCatching { capturePublishedTrainSnapshot(run) }
            .getOrDefault(run.ato.publishedState.trainSnapshot)
    ) {
        val effectiveReason = cancelExtensionAction(run)?.let { "$reason; $it" } ?: reason
        if (run.depotReservationPending) {
            run.depotAllocation?.let(world.basicWorldData.depots::cancelReservation)
            run.depotReservationPending = false
        }
        val failure = IllegalStateException(effectiveReason)
        world.basicWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = world.basicWorldData.atoCoordinator.worldId,
                trainId = run.train.id,
                tick = dispatcherTick,
                errorType = errorType,
                message = effectiveReason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = world.basicWorldData.atoCoordinator.initialTrain(run.train.id),
                currentTrain = snapshot,
                route = null,
                perception = null,
                activeProviderId = null,
                constraints = emptyMap()
            )
        )
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
        if (!world.basicWorldData.depots.releaseDeparture(allocation)) {
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
        val point = world.basicWorldData.depots.stablingPoint(reservation.stablingPointRef)
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
        val storageEntryProgress = (runtime.layout.totalLengthMeters - protectedLength)
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
            world.basicWorldData.depots.completeStorage(reservation)
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
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot
    ) {
        val listeners = waypointProgressListeners[run.waypointProgressKey()]?.toList().orEmpty()
        if (listeners.isEmpty()) return
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
                        run.ato.missionStopToleranceMeters * 2.0 &&
                        run.ato.isStopped(snapshot.speedMetersPerSecond)
                }
            }
            if (!reached) return
            runtime.reportedPointCount = index + 1
            val event = TimetableWaypointProgress(
                runId = run.id,
                cycleIndex = run.cycleIndex,
                waypointId = leg.point.target.id,
                pass = leg.point is AtoMissionPoint.Via,
                timetableElapsedTicks = timetableElapsedTicks(run)
            )
            listeners.forEach { listener -> runCatching { listener.onReached(event) } }
        }
    }

    private fun missionCompleted(
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot
    ): Boolean =
        runtime.headProgressMeters >=
            runtime.layout.totalLengthMeters - run.ato.missionStopToleranceMeters * 2.0 &&
            run.ato.isStopped(snapshot.speedMetersPerSecond)

    private fun advanceService(world: World, run: ActiveRun) {
        while (run.nextStepIndex < run.service.steps.size) {
            when (val step = run.service.steps[run.nextStepIndex]) {
                is TimetableStep.Check -> {
                    val failure = validateAtWayPoint(world, run.train, step.wayPointId)
                    if (failure != null) {
                        stallRun(world, run, "InfrastructureInvariantViolation", failure)
                        return
                    }
                    run.currentWayPointId = step.wayPointId
                    run.nextStepIndex++
                }
                is TimetableStep.At -> when (val action = step.action) {
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
                        val reservation = if (allocation == null) {
                            val trainLength = run.train.cars.sumOf { it.length }
                            val point = world.basicWorldData.depots.stablingPoints(action.depotId)
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
                        } else when (val result = world.basicWorldData.depots.reserveStorage(
                            trainId = run.train.id,
                            depotId = action.depotId,
                            requestedPointIds = action.stablingPointIds,
                            assignmentId = allocation.assignmentId,
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
                        val point = world.basicWorldData.depots
                            .stablingPoint(reservation.stablingPointRef)
                        val target = point?.point?.destinationId
                        if (target == null) {
                            if (run.physicalStorageOnly) {
                                physicalStorageReservations.remove(reservation.stablingPointRef, run.instanceId)
                            } else {
                                world.basicWorldData.depots.cancelStorage(reservation)
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
                            run.id,
                            WayPointRef(run.currentWayPointId),
                            movementStep,
                            run.nextStepIndex + 1
                        )
                        val submission = submitMissionForRun(world, run, plan)
                        val accepted = submission.accepted
                        if (accepted == null) {
                            val cancelled = if (run.physicalStorageOnly) {
                                physicalStorageReservations.remove(
                                    reservation.stablingPointRef,
                                    run.instanceId
                                )
                            } else {
                                world.basicWorldData.depots.cancelStorage(reservation)
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
                            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
                        )
                        run.currentRuntime = runtime
                        run.missionRevision = accepted.mission.revision
                        run.nextStepIndex = plan.nextStepIndex
                        run.storageReservation = reservation
                        run.storageRuntime = runtime
                        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
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
                    TimetableAction.Halt -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.nextStepIndex++
                        beginHalt(world, run)
                        return
                    }
                    TimetableAction.Reverse -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.nextStepIndex++
                        beginReverse(world, run)
                        return
                    }
                    is TimetableAction.Stop -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            recordScheduledAction(run, step.tick)
                            run.nextStepIndex++
                            run.message = "MANUAL_ROUTED skipped automatic dwell hold"
                            continue
                        }
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.nextStepIndex++
                        run.dwellTicksRemaining = action.ticks
                        run.state = TimetableServiceRunState.DWELLING
                        run.message = "dwell ${action.ticks} ticks"
                        return
                    }
                    is TimetableAction.DestinationOptions -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            run.pendingDestinationOptions = null
                            run.nextStepIndex++
                            run.message = "MANUAL_ROUTED skipped automatic destination options"
                            continue
                        }
                        run.pendingDestinationOptions = action
                        run.nextStepIndex++
                    }
                    is TimetableAction.CallStation -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            run.pendingStationCallSpec = null
                            run.nextStepIndex++
                            run.message = "MANUAL_ROUTED skipped automatic station operation"
                            continue
                        }
                        run.pendingStationCallSpec = StationCallSpec(
                            action = action,
                            stepIndex = run.nextStepIndex
                        )
                        run.nextStepIndex++
                    }
                    is TimetableAction.Extension -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.nextStepIndex++
                        run.extensionActionQueue.addLast(action)
                        startNextExtensionAction(world, run, step.tick)
                        return
                    }
                    is TimetableAction.Conditional -> {
                        if (timetableElapsedTicks(run) < step.tick) {
                            waitForTimetableTick(run, step.tick, pendingStep = true)
                            return
                        }
                        recordScheduledAction(run, step.tick)
                        run.nextStepIndex++
                        run.extensionActionQueue.addLast(action)
                        startNextExtensionAction(world, run, step.tick)
                        return
                    }
                    is TimetableAction.GoTo,
                    is TimetableAction.GoVia -> {
                        val physicalStartDirection =
                            (run.train.headLocation as
                                work.art1st.javarailways.core.physics.rail.RailLocationWithFacing).facing
                        val plan = movement(
                            run.id,
                            WayPointRef(run.currentWayPointId),
                            step,
                            run.nextStepIndex + 1,
                            run.pendingStationCallSpec,
                            run.pendingDestinationOptions
                        ).copy(
                            requiredStartDirection =
                                run.requiredNextStartDirection ?: physicalStartDirection
                        )
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
                            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
                        )
                        if (run.depotReservationPending) {
                            val allocation = run.depotAllocation
                            if (allocation == null ||
                                !world.basicWorldData.depots.confirmInService(allocation)
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
                        run.nextStepIndex = plan.nextStepIndex
                        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
                            publishInitialMovementAuthority(run, runtime)
                        }
                        run.state = TimetableServiceRunState.WAITING_MISSION
                        run.message = null
                        run.pendingStationCallSpec = null
                        run.pendingDestinationOptions = null
                        run.destinationStop = plan.destinationOptions?.let { options ->
                            DispatcherDestinationStop(options, runtime)
                        }
                        if (waitingForFirstDeparture) {
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
                }
            }
        }
        run.state = TimetableServiceRunState.COMPLETED
        run.message = "service completed; route remains held until the tail clears"
    }

    private fun startNextExtensionAction(world: World, run: ActiveRun, scheduledTick: Long) {
        while (run.extensionActionQueue.isNotEmpty()) {
            when (val action = run.extensionActionQueue.removeFirst()) {
                is TimetableAction.Conditional -> {
                    val selected = when (val result = world.basicWorldData.timetableExtensions.evaluate(
                        action.condition,
                        extensionContext(world, run, scheduledTick),
                        TimetableConditionScope.RUNTIME_BRANCH
                    )) {
                        TimetableConditionResult.True -> action.thenActions
                        TimetableConditionResult.False -> action.elseActions
                        is TimetableConditionResult.Unavailable -> {
                            stallRun(world, run, "TimetableConditionFailure", result.reason)
                            return
                        }
                        is TimetableConditionResult.Error -> {
                            stallRun(world, run, "TimetableConditionFailure", result.message)
                            return
                        }
                    }
                    selected.asReversed().forEach(run.extensionActionQueue::addFirst)
                }
                is TimetableAction.Extension -> {
                    val call = action.call
                    val provider = world.basicWorldData.timetableExtensions
                        .action(call.typeId, call.version)
                    if (provider == null) {
                        stallRun(
                            world,
                            run,
                            "TimetableExtensionFailure",
                            "action provider is not registered: ${call.typeId}@${call.version}"
                        )
                        return
                    }
                    val result = runCatching {
                        provider.start(call.parameters, extensionContext(world, run, scheduledTick))
                    }.getOrElse {
                        stallRun(
                            world,
                            run,
                            "TimetableExtensionFailure",
                            "action provider ${call.typeId}@${call.version} threw: " +
                                (it.message ?: it::class.java.simpleName)
                        )
                        return
                    }
                    when (result) {
                        is TimetableExtensionActionStartResult.Accepted -> {
                            run.extensionAction = DispatcherExtensionAction(
                                result.ticket,
                                call.typeId,
                                scheduledTick,
                                dispatcherTick
                            )
                            run.state = TimetableServiceRunState.WAITING_EXTENSION_ACTION
                            run.message = "waiting for extension action ${call.typeId}"
                            return
                        }
                        is TimetableExtensionActionStartResult.Rejected -> {
                            stallRun(world, run, "TimetableExtensionFailure", result.reason)
                            return
                        }
                    }
                }
                else -> {
                    stallRun(
                        world,
                        run,
                        "TimetableExtensionFailure",
                        "non-extension action appeared in a conditional branch: $action"
                    )
                    return
                }
            }
        }
        run.extensionAction = null
        advanceService(world, run)
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
                run.extensionAction = null
                startNextExtensionAction(world, run, active.scheduledTick)
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
        val record = world.basicWorldData.depots.rollingStock(run.train.id)?.record
        return TimetableRuntimeContext(
            timetableId = run.id.timetableId,
            serviceId = run.id.serviceId,
            cycleIndex = run.cycleIndex,
            timetableElapsedTicks = timetableElapsedTicks(run),
            scheduledTick = scheduledTick,
            latenessTicks = scheduledTick?.let { (timetableElapsedTicks(run) - it).coerceAtLeast(0L) }
                ?: run.lastLatenessTicks,
            currentWayPointId = run.currentWayPointId,
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
        try {
            active.ticket.cancel()
        } catch (failure: Exception) {
            return "extension action ${active.typeId} cancellation failed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        val snapshot = try {
            active.ticket.snapshot()
        } catch (failure: Exception) {
            return "extension action ${active.typeId} cancellation could not be confirmed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        if (snapshot.state != TimetableExtensionActionState.CANCELLED) {
            return "extension action ${active.typeId} remained ${snapshot.state} after cancellation"
        }
        run.extensionAction = null
        run.extensionActionQueue.clear()
        return null
    }

    private fun cancelPendingOperation(run: ActiveRun): String? {
        cancelExtensionAction(run)?.let { return it }
        val ticket = run.stationCall?.operationTicket
            ?: run.destinationStop?.operationTicket
            ?: run.haltOperation?.ticket
            ?: return null
        val before = try {
            ticket.snapshot()
        } catch (failure: Exception) {
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
            return "operation $ticketId cancellation failed: " +
                (failure.message ?: failure::class.java.simpleName)
        }
        val after = try {
            ticket.snapshot()
        } catch (failure: Exception) {
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
        val currentWorld = boundWorld
        check(currentWorld == null || currentWorld === world) {
            "TimetableDispatcher cannot be shared between worlds"
        }
        if (currentWorld == null) {
            boundWorld = world
        }
    }

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
        val store = world.basicWorldData.depots
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
                val stockAllocation = store.reservePhysicallySelected(
                    candidate.id,
                    point.point.ref,
                    take.selector,
                    assignmentId
                )
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
        world.basicWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = world.basicWorldData.atoCoordinator.worldId,
                trainId = "<unassigned:$runId>",
                tick = dispatcherTick,
                errorType = errorType,
                message = reason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = null,
                currentTrain = null,
                route = null,
                perception = null,
                activeProviderId = null,
                constraints = emptyMap()
            )
        )
    }

    private fun movement(
        runId: TimetableServiceRunId,
        origin: WayPointRef,
        step: TimetableStep.At,
        nextStepIndex: Int,
        stationCallSpec: StationCallSpec? = null,
        destinationOptions: TimetableAction.DestinationOptions? = null
    ): MovementPlan {
        val stopOffset = destinationOptions?.stoppingOffsetMetersBefore ?: 0.0
        val points = when (val action = step.action) {
            is TimetableAction.GoTo -> listOf(
                AtoMissionPoint.Stop(WayPointRef(action.wayPointId), stopOffset)
            )
            is TimetableAction.GoVia -> action.wayPointIds.mapIndexed { index, id ->
                val target = WayPointRef(id)
                if (index == action.wayPointIds.lastIndex) AtoMissionPoint.Stop(target, stopOffset)
                else AtoMissionPoint.Via(target)
            }
            is TimetableAction.TakeFromDepot,
            is TimetableAction.TakeFromWaypoint,
            is TimetableAction.StoreAtDepot,
            is TimetableAction.DestinationOptions,
            TimetableAction.Halt,
            TimetableAction.Reverse,
            is TimetableAction.Stop,
            is TimetableAction.CallStation,
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
            nextStepIndex = nextStepIndex,
            stationCallSpec = stationCallSpec,
            destinationOptions = destinationOptions
        )
    }

    private fun submitMissionForRun(
        world: World,
        run: ActiveRun,
        plan: MovementPlan
    ): MissionSubmission {
        if (run.controlMode == TimetableControlMode.AUTOMATIC) {
            return submitMission(world, run.ato, run.id, plan)
        }
        val revision = nextMissionRevision()
        val request = AtoMissionRequest(
            id = run.instanceId,
            revision = revision,
            origin = plan.origin,
            points = plan.points,
            requiredStartDirection = plan.requiredStartDirection
        )
        val resolved = when (val result = AtoMissionResolver(world).resolve(request)) {
            is AtoMissionResolutionResult.Success -> result.mission
            is AtoMissionResolutionResult.TargetUnresolved -> return MissionSubmission(
                null,
                "mission target not found: ${result.target.id}"
            )
            is AtoMissionResolutionResult.RouteUnavailable -> return MissionSubmission(
                null,
                "route unavailable ${result.from.id} -> ${result.to.id}: ${result.reason}"
            )
        }
        val layout = MissionRouteLayout(world, resolved)
        if (layout.project(capturePublishedTrainSnapshot(run).head) == null) {
            return MissionSubmission(
                null,
                "manual train ${run.train.id} is not facing route from ${plan.origin.id}"
            )
        }
        return MissionSubmission(PreparedMission(resolved, layout), null)
    }

    private fun submitMission(
        world: World,
        ato: SimpleATO,
        runId: TimetableServiceRunId,
        plan: MovementPlan
    ): MissionSubmission {
        val revision = nextMissionRevision()
        val request = AtoMissionRequest(
            id = runId.toString(),
            revision = revision,
            origin = plan.origin,
            points = plan.points,
            requiredStartDirection = plan.requiredStartDirection
        )
        val resolved = when (val result = AtoMissionResolver(world).resolve(request)) {
            is AtoMissionResolutionResult.Success -> result.mission
            is AtoMissionResolutionResult.TargetUnresolved -> return MissionSubmission(
                null,
                "mission target not found: ${result.target.id}"
            )
            is AtoMissionResolutionResult.RouteUnavailable -> return MissionSubmission(
                null,
                "route unavailable ${result.from.id} -> ${result.to.id}: ${result.reason}"
            )
        }
        val layout = MissionRouteLayout(world, resolved)
        val currentFacing =
            (ato.train.headLocation as work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
                .facing
        val currentSnapshot = TrainSnapshot.capture(ato.train, dispatcherTick, currentFacing)
        if (layout.project(currentSnapshot.head) == null) {
            return MissionSubmission(
                null,
                "train ${ato.train.id} is not facing the planned route from ${plan.origin.id}"
            )
        }
        return when (val result = ato.submitMission(resolved)) {
            is AtoMissionSubmissionResult.Accepted -> MissionSubmission(
                PreparedMission(result.mission, MissionRouteLayout(world, result.mission)),
                null
            )
            is AtoMissionSubmissionResult.StaleRevision -> MissionSubmission(
                null,
                "mission revision ${result.submittedRevision} is stale; latest is ${result.latestAcceptedRevision}"
            )
            is AtoMissionSubmissionResult.TargetUnresolved -> MissionSubmission(
                null,
                "mission target not found: ${result.target.id}"
            )
            is AtoMissionSubmissionResult.RouteUnavailable -> MissionSubmission(
                null,
                "route unavailable ${result.from.id} -> ${result.to.id}: ${result.reason}"
            )
        }
    }

    private fun validateAtWayPoint(world: World, train: CoreTrain, wayPointId: String): String? {
        val wayPoint = world.basicWorldData.wayPoints[wayPointId]
            ?: return "waypoint not found: $wayPointId"
        val trainArc = train.headLocation.arcLengthIn(ViewDirection.AB)
        val destination = world.basicWorldData.destinations[wayPointId]
        val facingMismatch = destination?.approachDirection?.let { requiredDirection ->
            (train.headLocation as? work.art1st.javarailways.core.physics.rail.RailLocationWithFacing)
                ?.facing != requiredDirection
        } ?: false
        return if (train.headLocation.rail.id != wayPoint.railId ||
            abs(trainArc - wayPoint.arcLength) > ORIGIN_TOLERANCE_METERS ||
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
        val elapsed = timetableElapsedTicks(run)
        run.lastScheduledActionTick = scheduledTick
        run.lastLatenessTicks = (elapsed - scheduledTick).coerceAtLeast(0L)
    }

    private fun timetableElapsedTicks(run: ActiveRun): Long =
        (dispatcherTick - run.cycleStartDispatcherTick).coerceAtLeast(0L)

    private fun absoluteTimetableTick(run: ActiveRun, relativeTick: Long): Long =
        Math.addExact(run.cycleStartDispatcherTick, relativeTick)

    private fun applyTimetableLineSpeedLimit(
        ato: SimpleATO,
        timetable: Timetable,
        service: TimetableService,
        runId: TimetableServiceRunId
    ) {
        ato.setLineMaximumSpeed(
            timetableLineSpeedKey(runId),
            LineMaximumSpeedResolver(timetable),
            service
        )
        // TODO: through-running must update an explicit currentLineId at the infrastructure line
        // boundary and re-resolve this cap; never retain the origin line's speed on another line.
    }

    private fun timetableLineSpeedKey(runId: TimetableServiceRunId): String =
        "timetable-line/${runId.timetableId}/${runId.serviceId}"

    private fun stopKey(run: ActiveRun): String = "dispatcher:stop/${run.instanceId}"

    private fun stationPendingHoldKey(run: ActiveRun): String =
        "dispatcher:station-pending/${run.instanceId}"

    private fun scheduleHoldKey(run: ActiveRun): String =
        "dispatcher:timetable-time/${run.instanceId}"

    private companion object {
        const val ORIGIN_TOLERANCE_METERS = 1.0
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

    private data class StationCallSpec(
        val action: TimetableAction.CallStation,
        val stepIndex: Int
    )

    private data class SelectedServiceTrain(
        val trainId: String,
        val originWayPointId: String?,
        val depotAllocation: DepotTrainAllocation?,
        val dynamicAcquisition: Boolean,
        val depotReservationPending: Boolean
    )

    private data class MovementPlan(
        val origin: WayPointRef,
        val points: List<AtoMissionPoint>,
        val target: WayPointRef,
        val priorityTick: Long,
        val actionTick: Long,
        val nextStepIndex: Int,
        val stationCallSpec: StationCallSpec?,
        val destinationOptions: TimetableAction.DestinationOptions?,
        val requiredStartDirection: ViewDirection? = null
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
        var headProgressMeters: Double = 0.0,
        var tailProgressMeters: Double? = null,
        var publishedAuthorityMeters: Double = -1.0,
        var lastOdometerMeters: Double,
        var reportedPointCount: Int = 0
    )

    private data class AdmissionRoute(
        val route: FrozenMissionRoute,
        val missionRevision: Long
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
        val releaseDetector: RailIntervalReleaseDetector
    )

    private data class DispatcherReverseOperation(
        val preparationRevision: Long,
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
        val startedDispatcherTick: Long
    )

    private data class DispatcherDestinationStop(
        val options: TimetableAction.DestinationOptions,
        val missionRuntime: MissionRuntime,
        var started: Boolean = false,
        var operationTicket: TrainOperationTicket? = null,
        var operationTicketId: String? = null,
        var operationStartedDispatcherTick: Long = Long.MAX_VALUE
    )

    private class ActiveRun(
        val id: TimetableServiceRunId,
        val service: TimetableService,
        val train: CoreTrain,
        var ato: SimpleATO,
        var controllerOwned: Boolean,
        var missionRevision: Long,
        var nextStepIndex: Int,
        var currentWayPointId: String,
        currentRuntime: MissionRuntime?,
        var state: TimetableServiceRunState,
        val cycleIndex: Long,
        val cycleStartDispatcherTick: Long,
        admissionOwnerSequence: Long,
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
        var pendingStationCallSpec: StationCallSpec? = null,
        var deferredStationCallSpec: StationCallSpec? = null,
        var haltOperation: DispatcherHaltOperation? = null,
        var reverseOperation: DispatcherReverseOperation? = null,
        var extensionAction: DispatcherExtensionAction? = null,
        val extensionActionQueue: ArrayDeque<TimetableAction> = ArrayDeque(),
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
        var storageCompleted: Boolean = false
    ) {
        var currentRuntimeOrNull: MissionRuntime? = currentRuntime
        var currentRuntime: MissionRuntime
            get() = checkNotNull(currentRuntimeOrNull) { "service $id has not submitted its first Mission" }
            set(value) { currentRuntimeOrNull = value }

        val instanceId: String = "$id@cycle-$cycleIndex/start-$cycleStartDispatcherTick"
        val admissionOwner = InfrastructureOwner("simpleato:dispatcher/run-$admissionOwnerSequence", 0L)
        var admissionClient: RouteAuthorityAdmissionClient? = null
        var admittedRoute: AdmissionRoute? = null
        var admissionCandidate: AdmissionRoute? = null
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
                currentWayPointId = currentWayPointId,
                nextStepIndex = nextStepIndex,
                nextScheduledTick = waitingUntilTimetableTick ?: service.steps.asSequence()
                    .drop(nextStepIndex)
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
                operationTicketId = operation?.ticketId
                    ?: stationCall?.operationTicketId
                    ?: destinationStop?.operationTicketId
                    ?: haltOperation?.ticketId,
                operationTicketState = operation?.state,
                operationTicketError = operationError,
                controlTransferOwner = controlTransferOwner,
                controlMode = controlMode,
                message = message
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
