package work.art1st.mineRails.basic.timetable

import work.art1st.mineRails.basic.api.ato.AtoStallReport
import work.art1st.mineRails.basic.api.ato.mission.AtoMission
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolutionResult
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolver
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.api.depot.DepotTrainAllocation
import work.art1st.mineRails.basic.api.depot.DepotStorageReservation
import work.art1st.mineRails.basic.api.depot.DepotStorageReservationResult
import work.art1st.mineRails.basic.api.depot.DepotTrainAllocationResult
import work.art1st.mineRails.basic.api.operation.RejectingTrainOperationGateway
import work.art1st.mineRails.basic.api.operation.TrainOperation
import work.art1st.mineRails.basic.api.operation.TrainOperationExecutionResult
import work.art1st.mineRails.basic.api.operation.TrainOperationGateway
import work.art1st.mineRails.basic.api.operation.TrainOperationRequest
import work.art1st.mineRails.basic.api.operation.TrainOperationTicket
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketState
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.DwellPlanResolver
import work.art1st.mineRails.basic.api.station.ResolvedStationCall
import work.art1st.mineRails.basic.api.station.StationBerthAllocationKey
import work.art1st.mineRails.basic.api.station.StationBerthAllocationResult
import work.art1st.mineRails.basic.api.station.StationBerthAllocator
import work.art1st.mineRails.basic.api.station.StationBerthOwner
import work.art1st.mineRails.basic.api.station.StationBerthRef
import work.art1st.mineRails.basic.api.station.StationCall
import work.art1st.mineRails.basic.api.station.StationCallNotificationType
import work.art1st.mineRails.basic.api.station.StationCallRequest
import work.art1st.mineRails.basic.api.station.StationCallResolutionResult
import work.art1st.mineRails.basic.api.station.StationCallResolver
import work.art1st.mineRails.basic.api.station.StationCallSubmissionResult
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.MissionRouteLayout
import work.art1st.mineRails.basic.route.MissionRouteSection
import work.art1st.mineRails.basic.route.RouteAllocationTiming
import work.art1st.mineRails.basic.route.RouteSectionAllocationKey
import work.art1st.mineRails.basic.route.RouteSectionAllocationResult
import work.art1st.mineRails.basic.route.RouteSectionAllocator
import work.art1st.mineRails.basic.route.RouteSectionOwner
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailPositionSnapshot
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.train.CoreTrain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

fun interface TimetableAtoFactory {
    fun create(train: Train, world: World): SimpleATO
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
    MANUAL_INTERVENTION,
    SUSPENDED,
    REPLAN_REQUIRED,
    STOPPING,
    STOPPED,
    COMPLETED,
    FAILED
}

data class TimetableServiceRunSnapshot(
    val id: TimetableServiceRunId,
    val trainId: String,
    val missionRevision: Long,
    val state: TimetableServiceRunState,
    val confirmedSectionCount: Int,
    val cycleIndex: Long = 0L,
    val timetableElapsedTicks: Long = 0L,
    val scheduledActionTick: Long? = null,
    val lastLatenessTicks: Long = 0L,
    val headProgressMeters: Double? = null,
    val tailProgressMeters: Double? = null,
    val stationCallId: String? = null,
    val berthRef: StationBerthRef? = null,
    val berthHeld: Boolean = false,
    val operationTicketId: String? = null,
    val operationTicketState: TrainOperationTicketState? = null,
    val controlTransferOwner: String? = null,
    val controlMode: TimetableControlMode = TimetableControlMode.AUTOMATIC,
    val message: String? = null
)

sealed interface TimetableServiceStartResult {
    data class Started(val run: TimetableServiceRunSnapshot) : TimetableServiceStartResult
    data class Failure(val reason: String) : TimetableServiceStartResult
}

data class TimetableDepotResetSummary(
    val removedRunCount: Int,
    val releasedRouteSectionCount: Int,
    val releasedBerthCount: Int,
    val stoppedTimetableIds: Set<String>
)

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
    private val allocationTiming: RouteAllocationTiming = RouteAllocationTiming(
        switchSetupSeconds = 0.25,
        reactionTimeSeconds = 0.25,
        safetyMarginMeters = 2.0
    ),
    private val operationGateway: TrainOperationGateway = RejectingTrainOperationGateway,
    private val operationTimeoutTicks: Long = DEFAULT_OPERATION_TIMEOUT_TICKS,
    private val atoFactory: TimetableAtoFactory? = null
) {
    init {
        require(operationTimeoutTicks > 0L)
    }
    private val initialTrains = ConcurrentHashMap<String, TrainSnapshot>()
    private val missionRevision = AtomicLong(0L)
    private val runs = LinkedHashMap<TimetableServiceRunKey, ActiveRun>()
    private val timetableClocks = LinkedHashMap<String, TimetableClock>()
    private val berthAllocator = StationBerthAllocator()
    private var dispatcherTick = 0L
    private var allocatorWorld: World? = null
    private var allocator: RouteSectionAllocator? = null

    fun recordInitialTrain(snapshot: TrainSnapshot) {
        initialTrains[snapshot.trainId] = snapshot
    }

    fun initialTrain(trainId: String): TrainSnapshot? = initialTrains[trainId]

    fun initialTrains(): Map<String, TrainSnapshot> = initialTrains.toMap()

    /** Establish one shared cycle-zero epoch before an administrator starts timetable services. */
    @MainThread
    fun beginTimetable(
        world: World,
        timetableId: String
    ): TimetableEpochStartResult {
        allocator(world)
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
        val selected = selectTrainForService(world, timetable, cycleIndex, runId, service)
            ?: return TimetableServiceStartResult.Failure(
                if (world.basicWorldData.atoCoordinator.isStalled) {
                    "world stalled because Depot allocation failed: $runId"
                } else {
                    "service requires TrainSelector.TrainId or a first takeFromDepot action: $runId"
                }
            )
        val trainId = selected.trainId
        fun startupFailure(reason: String): TimetableServiceStartResult.Failure {
            selected.depotAllocation?.let { allocation ->
                world.basicWorldData.depots.cancelReservation(allocation)
                stallDepotAllocation(world, runId, reason)
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
            }
        }
        val first = firstMovement(runId, service, selected.originWayPointId)
            ?: return startupFailure(
                "service requires an initial Check/takeFromDepot followed by GoTo/GoVia before any Stop: $runId"
            )
        val originFailure = validateAtWayPoint(world, train, first.origin.id)
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
        val submission = try {
            submitMission(world, ato, runId, first)
        } catch (failure: Exception) {
            return startupFailure(
                "failed to submit initial Mission: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        }
        val accepted = submission.accepted
            ?: return startupFailure(submission.failure!!)
        selected.depotAllocation?.let { allocation ->
            check(world.basicWorldData.depots.confirmInService(allocation)) {
                "Depot allocation disappeared before service confirmation: $allocation"
            }
        }
        val controllerOwned = existingController == null || replaceableTrainRuns.any {
            it.ato === ato && it.controllerOwned
        }
        if (existingController == null) train.setController(ato)

        val runtime = MissionRuntime(
            mission = accepted.mission,
            layout = accepted.layout,
            priorityTick = first.priorityTick,
            targetWayPointId = first.target.id,
            lastOdometerMeters = ato.lifetimeTravelDistanceMeters
        )

        val run = ActiveRun(
            id = runId,
            service = service,
            train = train,
            ato = ato,
            controllerOwned = controllerOwned,
            missionRevision = accepted.mission.revision,
            nextStepIndex = first.nextStepIndex,
            currentWayPointId = first.origin.id,
            currentRuntime = runtime,
            state = TimetableServiceRunState.WAITING_MISSION,
            cycleIndex = cycleIndex,
            cycleStartDispatcherTick = cycleStartDispatcherTick,
            lastObservedDispatcherTick = dispatcherTick,
            depotAllocation = selected.depotAllocation,
            depotDepartureRuntime = runtime.takeIf { selected.depotAllocation != null }
        )
        replaceableTrainRuns.filter(ActiveRun::isStoredTerminal).forEach { previous ->
            check(previous.stationCall?.allocationKey == null) {
                "stored run still owns a StationBerth: ${previous.id}"
            }
            run.runtimes += previous.runtimes
            run.allocations.putAll(previous.allocations)
        }
        run.runtimes += runtime
        replaceableTrainRuns.forEach { previous ->
            runs.entries.removeIf { it.value === previous }
        }
        runs[runKey] = run
        first.stationCallSpec?.let { spec ->
            scheduleStationCallSpec(world, run, spec)?.let { reason -> fail(run, reason) }
        }
        if (first.stationCallSpec == null) {
            if (timetableElapsedTicks(run) < first.actionTick) {
                waitForTimetableTick(run, first.actionTick, pendingStep = false)
                run.ato.setSpeedLimit(scheduleHoldKey(run), 0.0)
            } else {
                recordScheduledAction(run, first.actionTick)
            }
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
        if (run.currentRuntime.headProgressMeters > ORIGIN_TOLERANCE_METERS ||
            !run.ato.isStopped(run.ato.currentTrainSnapshot.speedMetersPerSecond)
        ) {
            return TimetableStationCallScheduleResult.Failure(
                "StationCall must be scheduled before the train departs its Mission origin"
            )
        }
        val request = StationCallRequest(
            serviceRunId = run.instanceId,
            origin = WayPointRef(run.currentWayPointId),
            call = call
        )
        val resolved = when (val result = StationCallResolver(world).resolve(
            request,
            trainLengthMeters = run.train.cars.sumOf { it.length }
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
        if (run.waitingUntilTimetableTick != null && !run.waitingStepPending) {
            run.waitingUntilTimetableTick = null
            run.ato.removeSpeedLimit(scheduleHoldKey(run))
        }
        run.ato.setSpeedLimit(stationPendingHoldKey(run), 0.0)
        run.stationCall = DispatcherStationCall(
            resolved = resolved,
            missionRuntime = run.currentRuntime,
            missionDistanceMeters = missionDistance,
            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
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
        val plannedDepartureRelativeTick = spec.action.plannedDepartureTick
            ?: spec.pairedDepartureTick
        val dwellPlan = DwellPlanResolver.resolve(
            station = station,
            berth = berth,
            minimumDwellTicksOverride = spec.action.minimumDwellTicks,
            plannedDepartureTick = absoluteTimetableTick(run, plannedDepartureRelativeTick)
        )
        val call = StationCall(
            callId = "${run.instanceId}/station/${spec.stepIndex}",
            missionRevision = run.missionRevision,
            stationId = berthRef.stationId,
            berthRef = berthRef,
            plannedArrivalTick = absoluteTimetableTick(run, spec.plannedArrivalTick),
            dwellPlan = dwellPlan,
            stoppingOffsetMetersBeforeOverride = spec.action.stoppingOffsetMetersBefore,
            doorSideOverride = spec.action.doorSideOverride
        )
        return when (val result = scheduleStationCall(world, run.id, call)) {
            is TimetableStationCallScheduleResult.Scheduled -> {
                run.stationCall?.plannedDepartureTimetableTick = plannedDepartureRelativeTick
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
        allocator(world)
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
        if (run.controlMode == TimetableControlMode.FULL_MANUAL ||
            run.state == TimetableServiceRunState.REPLAN_REQUIRED
        ) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is suspended and requires explicit replan"
            )
        }
        cancelPendingOperation(run)
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
        cancelPendingOperation(run)
        cancelStationCallForControlTransfer(run)
        run.controlMode = TimetableControlMode.FULL_MANUAL
        run.manualController = null
        run.state = TimetableServiceRunState.SUSPENDED
        run.message = "FULL_MANUAL by $transferOwner; no new RouteSection will be requested"
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
        allocator(world)
        val run = runs.values.firstOrNull { it.train.id == trainId && !it.state.isTerminal() }
            ?: return TimetableControlTransferResult.NotAssigned
        if (run.state == TimetableServiceRunState.SUSPENDED &&
            run.controlTransferOwner == transferOwner
        ) {
            return TimetableControlTransferResult.Suspended(run.snapshot())
        }
        if (run.state == TimetableServiceRunState.SUSPENDED ||
            run.state == TimetableServiceRunState.REPLAN_REQUIRED
        ) {
            return TimetableControlTransferResult.Failure(
                "service ${run.id} is already suspended by ${run.controlTransferOwner ?: "released transfer"}"
            )
        }
        cancelPendingOperation(run)
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
        allocator(world)
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
        if (run.allocations.isNotEmpty()) {
            return TimetableReplanResult.Failure(
                "old RouteSection resources are still held by the train tail"
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
        if (run.train.speed > REPLAN_STOPPED_SPEED_METERS_PER_SECOND) {
            return TimetableReplanResult.Failure(
                "train must be stopped before replanning: speed=${run.train.speed}"
            )
        }
        validateAtWayPoint(world, run.train, originWayPointId)?.let {
            return TimetableReplanResult.Failure(it)
        }
        val factory = atoFactory
            ?: return TimetableReplanResult.Failure("timetable ATO factory is not configured")
        val replacementAto = try {
            factory.create(run.train, world)
        } catch (failure: Exception) {
            return TimetableReplanResult.Failure(
                "failed to create replacement ATO: ${failure.message ?: failure::class.java.simpleName}"
            )
        }
        val target = WayPointRef(run.currentRuntime.targetWayPointId)
        if (target.id == originWayPointId) {
            run.ato = replacementAto
            run.controllerOwned = true
            run.runtimes.clear()
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
            stationCallSpec = null
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
        run.runtimes.clear()
        run.pendingStationCallSpec = null
        run.currentWayPointId = originWayPointId
        run.runtimes += runtime
        run.currentRuntime = runtime
        run.missionRevision = accepted.mission.revision
        run.controlMode = TimetableControlMode.AUTOMATIC
        run.manualController = null
        run.state = TimetableServiceRunState.WAITING_MISSION
        run.message = "replanned from $originWayPointId to ${target.id}"
        run.train.setController(replacementAto)
        return TimetableReplanResult.Resumed(run.snapshot())
    }

    /** Called on the main thread after the current physics epoch has published. */
    @MainThread
    fun tick(world: World) {
        dispatcherTick++
        if (world.basicWorldData.atoCoordinator.isStalled) return
        val sectionAllocator = allocator(world)
        val candidates = ArrayList<AllocationCandidate>()

        runs.values.toList().forEach { run ->
            run.lastObservedDispatcherTick = dispatcherTick
            val manualRouted = run.controlMode == TimetableControlMode.MANUAL_ROUTED
            val fullManual = run.controlMode == TimetableControlMode.FULL_MANUAL ||
                run.state == TimetableServiceRunState.SUSPENDED ||
                run.state == TimetableServiceRunState.REPLAN_REQUIRED
            val controlTransferred = manualRouted || fullManual
            val snapshot = if (controlTransferred) {
                capturePublishedTrainSnapshot(run)
            } else {
                run.ato.currentTrainSnapshot
            }
            if (!controlTransferred) processStationNotifications(run)
            updateTailProgress(run, snapshot)
            updateStationTailProgress(run, snapshot)
            if (manualRouted && !validateManualConfirmedTurnouts(world, run, snapshot)) {
                return@forEach
            }
            releaseDepotDeparture(world, run)
            releasePassedSections(run, sectionAllocator)
            releasePassedBerth(run)

            if (run.state.isTerminal()) {
                finishControllerIfReleased(run, snapshot)
                return@forEach
            }
            if (run.train.isDerailed) {
                if (manualRouted) {
                    stallManualRouted(world, run, snapshot, "MANUAL_ROUTED train derailed")
                } else {
                    fail(run, "train derailed")
                }
                return@forEach
            }
            if (fullManual) return@forEach
            if (manualRouted) {
                processManualRouted(world, run, snapshot, candidates)
                return@forEach
            }
            if (run.train.controller !== run.ato) {
                fail(run, "train controller was replaced")
                return@forEach
            }
            if (run.state == TimetableServiceRunState.WAITING_TIMETABLE_TIME) {
                val scheduledTick = run.waitingUntilTimetableTick
                    ?: error("WAITING_TIMETABLE_TIME run has no scheduled Tick: ${run.id}")
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
            if (run.state == TimetableServiceRunState.WAITING_BERTH &&
                stationCall != null && !stationCall.submittedToAto
            ) {
                tryAllocateStationCall(run)
                return@forEach
            }
            if (stationCall != null &&
                (run.state == TimetableServiceRunState.DOORS_OPENING ||
                    run.state == TimetableServiceRunState.DOORS_CLOSING)
            ) {
                if (processStationOperation(run, stationCall)) return@forEach
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
                    return@forEach
                }
            } else if (run.state == TimetableServiceRunState.DWELLING) {
                if (run.dwellTicksRemaining > 0L) run.dwellTicksRemaining--
                if (run.dwellTicksRemaining == 0L) advanceService(world, run)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.READY_TO_DEPART && stationCall != null) {
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

            val mission = run.ato.currentMission
            if (mission?.revision != run.missionRevision) {
                setRouteState(run, TimetableServiceRunState.WAITING_MISSION, null)
                return@forEach
            }
            val runtime = run.currentRuntime
            val headProgress = runtime.layout.project(snapshot.head, runtime.headProgressMeters)
            if (headProgress == null) {
                fail(run, "train head is outside planned Mission route")
                return@forEach
            }
            runtime.headProgressMeters = headProgress
            runtime.tailProgressMeters = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
                ?: runtime.tailProgressMeters

            releasePassedSections(run, sectionAllocator)
            if (missionCompleted(run, runtime, snapshot) &&
                (run.stationCall == null || run.stationCall?.departed == true)
            ) {
                if (runtime === run.storageRuntime) {
                    if (!tryCompleteStorage(world, run, runtime, sectionAllocator)) {
                        return@forEach
                    }
                }
                run.currentWayPointId = runtime.targetWayPointId
                advanceService(world, run)
                return@forEach
            }

            val nextSection = runtime.sections.firstOrNull { it.status == SectionStatus.PENDING }
            if (nextSection == null) {
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
                return@forEach
            }
            val previousReady = runtime.sections
                .take(nextSection.routeSection.ordinal)
                .all { it.status == SectionStatus.CONFIRMED || it.status == SectionStatus.RELEASED }
            if (!previousReady) {
                setRouteState(run, TimetableServiceRunState.WAITING_ROUTE, null)
                return@forEach
            }
            val triggerDistance = run.ato.routeAllocationTriggerDistanceMeters(
                snapshot.speedMetersPerSecond,
                allocationTiming
            )
            val distanceToProtection = nextSection.routeSection.entryDistanceMeters - headProgress
            if (distanceToProtection <= triggerDistance) {
                candidates += AllocationCandidate(run, runtime, nextSection)
                setRouteState(run, TimetableServiceRunState.WAITING_ROUTE, null)
            } else {
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
            }
        }

        candidates.sortedWith(
            compareBy<AllocationCandidate>(
                { absoluteTimetableTick(it.run, it.runtime.priorityTick) },
                { it.run.id.timetableId },
                { it.run.id.serviceId },
                { it.run.cycleIndex }
            )
        ).forEach { candidate ->
            if (candidate.run.state.isTerminal() || candidate.section.status != SectionStatus.PENDING) {
                return@forEach
            }
            allocate(candidate, sectionAllocator)
        }
        startDueCycles(world)
    }

    private fun startDueCycles(world: World) {
        for ((timetableId, clock) in timetableClocks.toList().sortedBy { it.first }) {
            val elapsed = (dispatcherTick - clock.epochDispatcherTick).coerceAtLeast(0L)
            val currentCycleIndex = elapsed / clock.periodTicks
            while (clock.nextCycleIndex <= currentCycleIndex) {
                val cycleIndex = clock.nextCycleIndex
                val timetable = world.basicWorldData.timetables[timetableId]
                if (timetable == null) {
                    stallTimetableRuntime(
                        world,
                        TimetableServiceRunId(timetableId, "<cycle-$cycleIndex>"),
                        "TimetableDisappeared",
                        "active timetable disappeared before cycle $cycleIndex: $timetableId"
                    )
                    return
                }
                var complete = true
                for (service in timetable.services) {
                    if (service.id in clock.disabledServiceIds) continue
                    val runId = TimetableServiceRunId(timetableId, service.id)
                    val runKey = TimetableServiceRunKey(
                        runId,
                        clock.epochDispatcherTick,
                        cycleIndex
                    )
                    if (runs.containsKey(runKey)) continue
                    when (val result = startServiceForCycle(
                        world,
                        timetableId,
                        service.id,
                        cycleIndex
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
                clock.lastCycleStartFailure = null
                clock.nextCycleIndex = Math.incrementExact(cycleIndex)
            }
        }
    }

    @MainThread
    fun stopService(
        world: World,
        id: TimetableServiceRunId,
        cycleIndex: Long? = null
    ): Boolean {
        allocator(world)
        val run = findRun(id, cycleIndex) ?: return false
        if (run.canBeReplaced() || run.state == TimetableServiceRunState.STOPPED ||
            run.isStoredTerminal()
        ) return true
        cancelPendingOperation(run)
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

    /** Preflight forced resource cleanup used only by an administrator-confirmed Depot Reset. */
    @MainThread
    fun validateDepotReset(world: World, trainIds: Set<String>): String? {
        val sectionAllocator = allocator(world)
        val selectedRuns = runs.values.filter { it.train.id in trainIds }
        val confirmedKeys = sectionAllocator.confirmedWindows().map { it.key }.toSet()
        val missingRoute = selectedRuns.asSequence()
            .flatMap { it.allocations.keys.asSequence() }
            .firstOrNull { it !in confirmedKeys }
        if (missingRoute != null) return "RouteSection allocation disappeared: $missingRoute"
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
    fun applyDepotReset(world: World, trainIds: Set<String>): TimetableDepotResetSummary {
        check(world.basicWorldData.atoCoordinator.isStalled) {
            "Depot Reset is only legal while the World ATO is stalled"
        }
        validateDepotReset(world, trainIds)?.let { error(it) }
        val sectionAllocator = allocator(world)
        val selectedRuns = runs.values.filter { it.train.id in trainIds }
        selectedRuns.forEach(::cancelPendingOperation)
        val routeKeys = sectionAllocator.confirmedWindows()
            .map { it.key }
            .filter { it.owner.trainId in trainIds }
        routeKeys.forEach { key ->
            check(sectionAllocator.release(key)) { "RouteSection disappeared during reset: $key" }
            selectedRuns.forEach { run ->
                run.allocations.remove(key)?.let { allocation ->
                    allocation.section.status = SectionStatus.RELEASED
                    run.ato.releaseRouteWindow(key)
                }
            }
        }
        val berthKeys = berthAllocator.allocations()
            .filter { it.owner.trainId in trainIds }
        berthKeys.forEach { key ->
            check(berthAllocator.release(key)) { "StationBerth disappeared during reset: $key" }
            selectedRuns.forEach { run ->
                if (run.stationCall?.allocationKey == key) run.stationCall?.allocationKey = null
            }
        }
        selectedRuns.forEach { run ->
            run.ato.removeSpeedLimit(stopKey(run))
            run.ato.removeSpeedLimit(stationPendingHoldKey(run))
            run.ato.removeSpeedLimit(scheduleHoldKey(run))
            if (run.train.controller === run.ato) run.train.setController(null)
        }
        val stoppedTimetables = selectedRuns.mapTo(LinkedHashSet()) { it.id.timetableId }
        stoppedTimetables.forEach(timetableClocks::remove)
        runs.entries.removeIf { it.value.train.id in trainIds }
        return TimetableDepotResetSummary(
            removedRunCount = selectedRuns.size,
            releasedRouteSectionCount = routeKeys.size,
            releasedBerthCount = berthKeys.size,
            stoppedTimetableIds = stoppedTimetables
        )
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
                when (val submission = run.ato.submitStationCall(stationCall.resolved.request)) {
                    is StationCallSubmissionResult.Accepted -> {
                        stationCall.resolved = submission.resolved
                        stationCall.submittedToAto = true
                        run.ato.removeSpeedLimit(stationPendingHoldKey(run))
                        run.message = "StationCall ${call.callId} awaiting physics activation"
                        null
                    }
                    is StationCallSubmissionResult.StaleRevision -> {
                        berthAllocator.release(result.key)
                        stationCall.allocationKey = null
                        val reason = "StationCall revision ${submission.submittedRevision} is stale"
                        fail(run, reason)
                        reason
                    }
                    is StationCallSubmissionResult.ResolutionFailure -> {
                        berthAllocator.release(result.key)
                        stationCall.allocationKey = null
                        fail(run, submission.message)
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
            is TrainOperation.Announce -> "announce"
        }
        stationCall.operationTicket?.let { previous ->
            val pending = runCatching {
                previous.snapshot().state == TrainOperationTicketState.PENDING
            }.getOrDefault(true)
            if (pending) runCatching { previous.cancel() }
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
                    runCatching { result.ticket.cancel() }
                    enterManualIntervention(run, stationCall, "$phase returned a ticket for another request")
                    return
                }
                val initialSnapshot = runCatching { result.ticket.snapshot() }.getOrElse { failure ->
                    runCatching { result.ticket.cancel() }
                    enterManualIntervention(
                        run,
                        stationCall,
                        "$phase ticket exception: ${failure.message ?: failure::class.java.simpleName}"
                    )
                    return
                }
                if (initialSnapshot.requestId != request.requestId) {
                    runCatching { result.ticket.cancel() }
                    enterManualIntervention(run, stationCall, "$phase ticket request identity mismatch")
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
            runCatching { ticket.cancel() }
            enterManualIntervention(
                run,
                stationCall,
                "operation ticket exception: ${failure.message ?: failure::class.java.simpleName}"
            )
            return true
        }
        if (snapshot.requestId != ticket.request.requestId ||
            snapshot.ticketId != stationCall.operationTicketId
        ) {
            runCatching { ticket.cancel() }
            enterManualIntervention(run, stationCall, "operation ticket identity changed")
            return true
        }
        when (snapshot.state) {
            TrainOperationTicketState.PENDING -> {
                if (dispatcherTick - stationCall.operationStartedDispatcherTick >= operationTimeoutTicks) {
                    runCatching { ticket.cancel() }
                    enterManualIntervention(
                        run,
                        stationCall,
                        "operation ${snapshot.ticketId} timed out after $operationTimeoutTicks ticks"
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

    private fun enterManualIntervention(
        run: ActiveRun,
        stationCall: DispatcherStationCall,
        reason: String
    ) {
        stationCall.stateEnteredDispatcherTick = dispatcherTick
        run.state = TimetableServiceRunState.MANUAL_INTERVENTION
        run.message = "$reason; DepartureHold remains applied"
    }

    private fun processStationNotifications(run: ActiveRun) {
        run.ato.drainStationCallNotifications().forEach { notification ->
            val stationCall = run.stationCall ?: return@forEach
            val activeRevision = stationCall.resolved.call.missionRevision
            when {
                notification.missionRevision < activeRevision -> return@forEach
                notification.missionRevision > activeRevision -> {
                    fail(run, "StationCall notification revision is newer than active call")
                    return@forEach
                }
            }
            if (notification.callId != stationCall.resolved.call.callId) {
                fail(run, "StationCall notification ID does not match active call")
                return@forEach
            }
            if (notification.physicsEpoch < stationCall.lastNotificationEpoch) return@forEach
            stationCall.lastNotificationEpoch = notification.physicsEpoch
            when (notification.type) {
                StationCallNotificationType.APPROACHING -> {
                    if (!stationCall.approaching) {
                        stationCall.approaching = true
                        stationCall.stateEnteredDispatcherTick = dispatcherTick
                        run.state = TimetableServiceRunState.APPROACHING
                        run.message = "approaching ${notification.berthRef}"
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
            }
        }
    }

    private fun capturePublishedTrainSnapshot(run: ActiveRun): TrainSnapshot {
        val facing = (run.train.headLocation as? work.art1st.mineRails.core.physics.rail.RailLocationWithFacing)
            ?.facing ?: ViewDirection.AB
        return TrainSnapshot.capture(run.train, dispatcherTick, facing)
    }

    private fun updateStationTailProgress(run: ActiveRun, snapshot: TrainSnapshot) {
        val stationCall = run.stationCall ?: return
        val odometerDelta = (run.ato.lifetimeTravelDistanceMeters - stationCall.lastOdometerMeters)
            .coerceAtLeast(0.0)
        stationCall.lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
        val projected = stationCall.missionRuntime.layout.project(
            snapshot.tail,
            stationCall.tailMissionProgressMeters
        )
        stationCall.tailMissionProgressMeters = when {
            projected != null -> projected
            stationCall.tailMissionProgressMeters != null &&
                stationCall.tailMissionProgressMeters!! >=
                stationCall.missionRuntime.layout.totalLengthMeters - POSITION_TOLERANCE_METERS ->
                stationCall.tailMissionProgressMeters!! + odometerDelta
            else -> stationCall.tailMissionProgressMeters
        }
    }

    private fun releasePassedBerth(run: ActiveRun) {
        val stationCall = run.stationCall ?: return
        val key = stationCall.allocationKey ?: return
        val tailProgress = stationCall.tailMissionProgressMeters ?: return
        val releaseDistance = stationCall.missionDistanceMeters +
            stationCall.resolved.releaseDistanceMetersAfter
        if (tailProgress + RELEASE_EPSILON_METERS < releaseDistance) return
        check(berthAllocator.release(key)) { "Station berth allocation disappeared: $key" }
        stationCall.allocationKey = null
        if (stationCall.cancelledForControlTransfer ||
            stationCall.departed && run.state != TimetableServiceRunState.DEPARTED
        ) {
            run.stationCall = null
        }
    }

    private fun hasAuthorityBeyondStation(
        run: ActiveRun,
        stationCall: DispatcherStationCall
    ): Boolean {
        val tolerance = run.ato.missionStopToleranceMeters
        if (stationCall.missionRuntime.publishedAuthorityMeters <=
            stationCall.missionDistanceMeters + tolerance
        ) return false
        val nextSection = stationCall.missionRuntime.sections.firstOrNull { section ->
            section.routeSection.entryDistanceMeters >= stationCall.missionDistanceMeters - tolerance &&
                section.routeSection.releaseDistanceMeters > stationCall.missionDistanceMeters + tolerance
        }
        return nextSection == null ||
            nextSection.status == SectionStatus.CONFIRMED ||
            nextSection.status == SectionStatus.RELEASED
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

    private fun processManualRouted(
        world: World,
        run: ActiveRun,
        snapshot: TrainSnapshot,
        candidates: MutableList<AllocationCandidate>,
        sectionAllocator: RouteSectionAllocator = allocator(world)
    ) {
        if (run.controlTransferOwner == null || run.manualController == null ||
            run.train.controller !== run.manualController
        ) {
            stallManualRouted(world, run, snapshot, "MANUAL_ROUTED controller identity mismatch")
            return
        }
        val runtime = run.currentRuntime
        val headProgress = runtime.layout.project(snapshot.head, runtime.headProgressMeters)
        if (headProgress == null) {
            stallManualRouted(world, run, snapshot, "MANUAL_ROUTED train head left PlannedRoute")
            return
        }
        runtime.headProgressMeters = headProgress
        runtime.tailProgressMeters = runtime.layout.project(snapshot.tail, runtime.tailProgressMeters)
            ?: runtime.tailProgressMeters
        if (run.state == TimetableServiceRunState.WAITING_TIMETABLE_TIME) {
            val scheduledTick = run.waitingUntilTimetableTick
                ?: error("WAITING_TIMETABLE_TIME run has no scheduled Tick: ${run.id}")
            if (timetableElapsedTicks(run) < scheduledTick) {
                enforceManualProtection(world, run, runtime, snapshot)
                return
            }
            run.waitingUntilTimetableTick = null
            run.state = TimetableServiceRunState.WAITING_MISSION
            run.message = null
            if (run.waitingStepPending) {
                run.waitingStepPending = false
                advanceService(world, run)
                return
            }
            recordScheduledAction(run, scheduledTick)
        }

        releasePassedSections(run, sectionAllocator)
        if (missionCompleted(run, runtime, snapshot) &&
            (run.stationCall == null || run.stationCall?.cancelledForControlTransfer == true)
        ) {
            if (runtime === run.storageRuntime &&
                !tryCompleteStorage(world, run, runtime, sectionAllocator)
            ) return
            run.currentWayPointId = runtime.targetWayPointId
            advanceService(world, run)
            return
        }

        val nextSection = runtime.sections.firstOrNull { it.status == SectionStatus.PENDING }
        if (nextSection == null) {
            setRouteState(run, TimetableServiceRunState.RUNNING, "MANUAL_ROUTED")
            return
        }
        if (headProgress > nextSection.routeSection.entryDistanceMeters +
            MANUAL_PROTECTION_TOLERANCE_METERS
        ) {
            stallManualRouted(
                world,
                run,
                snapshot,
                "MANUAL_ROUTED crossed unconfirmed protection point for section " +
                    nextSection.routeSection.ordinal
            )
            return
        }
        val previousReady = runtime.sections
            .take(nextSection.routeSection.ordinal)
            .all { it.status == SectionStatus.CONFIRMED || it.status == SectionStatus.RELEASED }
        if (!previousReady) {
            setRouteState(run, TimetableServiceRunState.WAITING_ROUTE, "MANUAL_ROUTED")
            return
        }
        val triggerDistance = run.ato.routeAllocationTriggerDistanceMeters(
            snapshot.speedMetersPerSecond,
            allocationTiming
        )
        if (nextSection.routeSection.entryDistanceMeters - headProgress <= triggerDistance) {
            candidates += AllocationCandidate(run, runtime, nextSection)
            setRouteState(run, TimetableServiceRunState.WAITING_ROUTE, "MANUAL_ROUTED")
        } else {
            setRouteState(run, TimetableServiceRunState.RUNNING, "MANUAL_ROUTED")
        }
    }

    private fun validateManualConfirmedTurnouts(
        world: World,
        run: ActiveRun,
        snapshot: TrainSnapshot
    ): Boolean {
        for (allocation in run.allocations.values) {
            for (requirement in allocation.section.routeSection.section.turnoutRequirements) {
                val from = world.getRail(requirement.from.railId)
                    ?.getView(requirement.from.direction)
                val selected = world.getRail(requirement.selectedNext.railId)
                    ?.getView(requirement.selectedNext.direction)
                if (from == null || selected == null || from.nextActiveRail !== selected) {
                    stallManualRouted(
                        world,
                        run,
                        snapshot,
                        "MANUAL_ROUTED confirmed turnout mismatch at ${requirement.from}"
                    )
                    return false
                }
            }
        }
        return true
    }

    private fun enforceManualProtection(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        snapshot: TrainSnapshot
    ) {
        val pending = runtime.sections.firstOrNull { it.status == SectionStatus.PENDING } ?: return
        if (runtime.headProgressMeters > pending.routeSection.entryDistanceMeters +
            MANUAL_PROTECTION_TOLERANCE_METERS
        ) {
            stallManualRouted(
                world,
                run,
                snapshot,
                "MANUAL_ROUTED moved before timetable authority became eligible"
            )
        }
    }

    private fun stallManualRouted(
        world: World,
        run: ActiveRun,
        snapshot: TrainSnapshot,
        reason: String
    ) {
        val failure = IllegalStateException(reason)
        world.basicWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = world.basicWorldData.atoCoordinator.worldId,
                trainId = run.train.id,
                tick = dispatcherTick,
                errorType = "ManualRoutedSafetyViolation",
                message = reason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = initialTrain(run.train.id),
                currentTrain = snapshot,
                route = null,
                perception = null,
                activeProviderId = null,
                constraints = emptyMap()
            )
        )
        run.state = TimetableServiceRunState.FAILED
        run.message = reason
    }

    private fun allocate(candidate: AllocationCandidate, allocator: RouteSectionAllocator) {
        val run = candidate.run
        val runtime = candidate.runtime
        val section = candidate.section
        when (val result = allocator.allocate(
            run.owner,
            section.routeSection.plannedRoute,
            section.routeSection.section.index
        )) {
            is RouteSectionAllocationResult.Confirmed -> {
                section.status = SectionStatus.CONFIRMED
                run.allocations[result.window.key] = AllocatedSection(runtime, section)
                if (run.controlMode == TimetableControlMode.AUTOMATIC) {
                    run.ato.confirmRouteWindow(result.window)
                    publishMovementAuthority(run, runtime)
                }
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
            }
            is RouteSectionAllocationResult.Conflict -> {
                setRouteState(
                    run,
                    TimetableServiceRunState.WAITING_ROUTE,
                    "route conflict at ${result.resource}, held by ${result.heldBy.owner}"
                )
            }
            is RouteSectionAllocationResult.Invalid -> {
                if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                    val world = requireNotNull(allocatorWorld)
                    stallManualRouted(
                        world,
                        run,
                        capturePublishedTrainSnapshot(run),
                        "MANUAL_ROUTED route allocation invalid: ${result.reason}"
                    )
                } else {
                    fail(run, result.reason)
                }
            }
        }
    }

    private fun publishMovementAuthority(run: ActiveRun, runtime: MissionRuntime) {
        var authority = 0.0
        for (section in runtime.sections) {
            if (section.status != SectionStatus.CONFIRMED && section.status != SectionStatus.RELEASED) break
            authority = section.routeSection.releaseDistanceMeters
        }
        if (runtime.sections.all {
                it.status == SectionStatus.CONFIRMED || it.status == SectionStatus.RELEASED
            }
        ) {
            authority = run.ato.terminalMovementAuthorityLimitMeters(runtime.layout.totalLengthMeters)
        }
        if (authority != runtime.publishedAuthorityMeters) {
            runtime.publishedAuthorityMeters = authority
            run.ato.setMovementAuthority(runtime.mission.revision, authority)
        }
    }

    private fun updateTailProgress(run: ActiveRun, snapshot: TrainSnapshot) {
        run.runtimes.forEach { runtime ->
            val odometerDelta = (run.ato.lifetimeTravelDistanceMeters - runtime.lastOdometerMeters)
                .coerceAtLeast(0.0)
            runtime.lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
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
        val runtime = run.depotDepartureRuntime ?: error("Depot departure has no Mission runtime")
        val tailProgress = runtime.tailProgressMeters ?: return
        if (tailProgress < DEPOT_DEPARTURE_RELEASE_EPSILON_METERS) return
        check(world.basicWorldData.depots.releaseDeparture(allocation)) {
            "Depot departure occupancy disappeared before tail release: $allocation"
        }
        run.depotDepartureReleased = true
    }

    private fun tryCompleteStorage(
        world: World,
        run: ActiveRun,
        runtime: MissionRuntime,
        allocator: RouteSectionAllocator
    ): Boolean {
        val reservation = run.storageReservation
            ?: error("storage Mission has no StablingPoint reservation: ${run.id}")
        val point = world.basicWorldData.depots.stablingPoint(reservation.stablingPointRef)
            ?: error("reserved StablingPoint disappeared: ${reservation.stablingPointRef}")
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
        if (run.allocations.values.any { it.runtime !== runtime }) {
            run.state = TimetableServiceRunState.STORING
            run.message = "waiting for old RouteSection tail release before storage"
            return false
        }
        val storageKeys = run.allocations.filterValues { it.runtime === runtime }.keys
        val confirmedKeys = allocator.confirmedWindows().map { it.key }.toSet()
        check(storageKeys.all { it in confirmedKeys }) {
            "storage RouteSection allocation disappeared before occupancy handoff"
        }
        check(world.basicWorldData.depots.completeStorage(reservation)) {
            "StablingPoint reservation changed before storage completion: $reservation"
        }
        run.storageReservation = null
        run.storageCompleted = true
        run.message = "stored at ${reservation.stablingPointRef}; terminal route remains tail-held"
        return true
    }

    private fun releasePassedSections(run: ActiveRun, allocator: RouteSectionAllocator) {
        val iterator = run.allocations.entries.iterator()
        while (iterator.hasNext()) {
            val (key, allocation) = iterator.next()
            val tailProgress = allocation.runtime.tailProgressMeters ?: continue
            if (tailProgress + RELEASE_EPSILON_METERS < allocation.section.routeSection.releaseDistanceMeters) {
                continue
            }
            check(allocator.release(key)) { "confirmed RouteSection allocation disappeared before release: $key" }
            run.ato.releaseRouteWindow(key)
            allocation.section.status = SectionStatus.RELEASED
            iterator.remove()
        }
        run.runtimes.removeIf { runtime ->
            runtime !== run.currentRuntime && run.allocations.values.none { it.runtime === runtime }
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
                        fail(run, failure)
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
                        if (allocation == null || !run.depotDepartureReleased) {
                            fail(run, "storeAtDepot requires a tail-released takeFromDepot assignment")
                            return
                        }
                        val reservation = when (val result = world.basicWorldData.depots.reserveStorage(
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
                                stallDepotAllocation(world, run.id, reason)
                                fail(run, reason)
                                return
                            }
                            is DepotStorageReservationResult.RollingStockUnavailable -> {
                                val reason = "RollingStock ${result.trainId} cannot be stored: ${result.reason}"
                                stallDepotAllocation(world, run.id, reason)
                                fail(run, reason)
                                return
                            }
                        }
                        val point = world.basicWorldData.depots
                            .stablingPoint(reservation.stablingPointRef)
                        val target = point?.point?.destinationId
                        if (target == null) {
                            world.basicWorldData.depots.cancelStorage(reservation)
                            fail(run, "reserved StablingPoint disappeared: ${reservation.stablingPointRef}")
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
                            check(world.basicWorldData.depots.cancelStorage(reservation)) {
                                "failed storage Mission lost its reservation: $reservation"
                            }
                            fail(run, submission.failure!!)
                            return
                        }
                        val runtime = MissionRuntime(
                            mission = accepted.mission,
                            layout = accepted.layout,
                            priorityTick = plan.priorityTick,
                            targetWayPointId = plan.target.id,
                            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
                        )
                        run.runtimes += runtime
                        run.currentRuntime = runtime
                        run.missionRevision = accepted.mission.revision
                        run.nextStepIndex = plan.nextStepIndex
                        run.storageReservation = reservation
                        run.storageRuntime = runtime
                        run.state = TimetableServiceRunState.STORING
                        run.message = "storing at ${reservation.stablingPointRef}"
                        return
                    }
                    is TimetableAction.TakeFromDepot -> {
                        fail(run, "takeFromDepot is only valid as the first service step")
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
                    is TimetableAction.CallStation -> {
                        if (run.controlMode == TimetableControlMode.MANUAL_ROUTED) {
                            run.pendingStationCallSpec = null
                            run.nextStepIndex++
                            run.message = "MANUAL_ROUTED skipped automatic station operation"
                            continue
                        }
                        run.pendingStationCallSpec = StationCallSpec(
                            action = action,
                            plannedArrivalTick = step.tick,
                            stepIndex = run.nextStepIndex
                        )
                        run.nextStepIndex++
                    }
                    is TimetableAction.GoTo,
                    is TimetableAction.GoVia -> {
                        val plan = movement(
                            run.id,
                            WayPointRef(run.currentWayPointId),
                            step,
                            run.nextStepIndex + 1,
                            run.pendingStationCallSpec
                        )
                        if (plan.stationCallSpec == null &&
                            timetableElapsedTicks(run) < plan.actionTick
                        ) {
                            waitForTimetableTick(run, plan.actionTick, pendingStep = true)
                            return
                        }
                        if (plan.stationCallSpec == null) {
                            recordScheduledAction(run, plan.actionTick)
                        }
                        val submission = submitMissionForRun(world, run, plan)
                        val accepted = submission.accepted
                        if (accepted == null) {
                            fail(run, submission.failure!!)
                            return
                        }
                        val runtime = MissionRuntime(
                            mission = accepted.mission,
                            layout = accepted.layout,
                            priorityTick = plan.priorityTick,
                            targetWayPointId = plan.target.id,
                            lastOdometerMeters = run.ato.lifetimeTravelDistanceMeters
                        )
                        run.runtimes += runtime
                        run.currentRuntime = runtime
                        run.missionRevision = accepted.mission.revision
                        run.nextStepIndex = plan.nextStepIndex
                        run.state = TimetableServiceRunState.WAITING_MISSION
                        run.message = null
                        run.pendingStationCallSpec = null
                        plan.stationCallSpec?.let { spec ->
                            scheduleStationCallSpec(world, run, spec)?.let { reason -> fail(run, reason) }
                        }
                        return
                    }
                }
            }
        }
        run.state = TimetableServiceRunState.COMPLETED
        run.message = "service completed; route remains held until the tail clears"
    }

    private fun fail(run: ActiveRun, reason: String) {
        cancelPendingOperation(run)
        run.ato.setSpeedLimit(stopKey(run), 0.0)
        run.state = TimetableServiceRunState.FAILED
        run.message = reason
    }

    private fun cancelPendingOperation(run: ActiveRun) {
        val ticket = run.stationCall?.operationTicket ?: return
        val pending = runCatching { ticket.snapshot().state == TrainOperationTicketState.PENDING }
            .getOrDefault(true)
        if (pending) runCatching { ticket.cancel() }
    }

    private fun finishControllerIfReleased(run: ActiveRun, snapshot: TrainSnapshot) {
        if (run.allocations.isNotEmpty() || run.stationCall?.allocationKey != null ||
            !run.ato.isStopped(snapshot.speedMetersPerSecond)
        ) return
        if (run.controllerOwned && run.train.controller === run.ato) {
            run.train.setController(null)
        }
    }

    private fun allocator(world: World): RouteSectionAllocator {
        val currentWorld = allocatorWorld
        check(currentWorld == null || currentWorld === world) {
            "TimetableDispatcher cannot be shared between worlds"
        }
        if (currentWorld == null) {
            allocatorWorld = world
            allocator = RouteSectionAllocator(world)
        }
        return allocator!!
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
        val take = service.steps.firstOrNull()
            ?.let { it as? TimetableStep.At }
            ?.action as? TimetableAction.TakeFromDepot
        if (take == null) {
            val trainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId ?: return null
            return SelectedServiceTrain(trainId, null, null)
        }
        val assignmentId = "${runId}@cycle-$cycleIndex"
        return when (val result = world.basicWorldData.depots.allocate(
            selector = take.selector,
            assignmentId = assignmentId,
            stableSeed = "${timetable.id}|$cycleIndex|${service.id}"
        )) {
            is DepotTrainAllocationResult.Allocated -> {
                val point = world.basicWorldData.depots
                    .stablingPoint(result.allocation.stablingPointRef)
                    ?: error("allocated StablingPoint disappeared: ${result.allocation.stablingPointRef}")
                SelectedServiceTrain(
                    trainId = result.allocation.trainId,
                    originWayPointId = point.point.destinationId,
                    depotAllocation = result.allocation
                )
            }
            is DepotTrainAllocationResult.NoMatchingTrain -> {
                stallDepotAllocation(
                    world,
                    runId,
                    "no compatible AVAILABLE train in Depot ${result.depotId} for cycle $cycleIndex; " +
                        "available=${result.availableTrainCount}, selector=${result.selector}"
                )
                null
            }
            is DepotTrainAllocationResult.AssignmentConflict -> {
                stallDepotAllocation(
                    world,
                    runId,
                    "Depot assignment identity conflict: ${result.assignmentId}"
                )
                null
            }
        }
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

    private fun firstMovement(
        runId: TimetableServiceRunId,
        service: TimetableService,
        originWayPointId: String? = null
    ): MovementPlan? {
        var origin: WayPointRef? = originWayPointId?.let(::WayPointRef)
        var stationCallSpec: StationCallSpec? = null
        service.steps.forEachIndexed { index, step ->
            when (step) {
                is TimetableStep.Check -> origin = WayPointRef(step.wayPointId)
                is TimetableStep.At -> when (val action = step.action) {
                    is TimetableAction.TakeFromDepot -> Unit
                    is TimetableAction.StoreAtDepot -> return null
                    is TimetableAction.CallStation -> {
                        if (stationCallSpec != null) return null
                        stationCallSpec = StationCallSpec(
                            action = action,
                            plannedArrivalTick = step.tick,
                            stepIndex = index
                        )
                    }
                    is TimetableAction.GoTo,
                    is TimetableAction.GoVia -> return movement(
                        runId,
                        origin ?: return null,
                        step,
                        index + 1,
                        stationCallSpec
                    )
                    is TimetableAction.Stop -> return null
                }
            }
        }
        return null
    }

    private fun movement(
        runId: TimetableServiceRunId,
        origin: WayPointRef,
        step: TimetableStep.At,
        nextStepIndex: Int,
        stationCallSpec: StationCallSpec? = null
    ): MovementPlan {
        val points = when (val action = step.action) {
            is TimetableAction.GoTo -> listOf(AtoMissionPoint.Stop(WayPointRef(action.wayPointId)))
            is TimetableAction.GoVia -> action.wayPointIds.mapIndexed { index, id ->
                val target = WayPointRef(id)
                if (index == action.wayPointIds.lastIndex) AtoMissionPoint.Stop(target)
                else AtoMissionPoint.Via(target)
            }
            is TimetableAction.TakeFromDepot,
            is TimetableAction.StoreAtDepot,
            is TimetableAction.Stop,
            is TimetableAction.CallStation -> error("action is not a movement action")
        }
        require(points.isNotEmpty()) { "movement action must contain a target: $runId" }
        return MovementPlan(
            origin = origin,
            points = points,
            target = points.last().target,
            priorityTick = stationCallSpec?.plannedArrivalTick ?: step.tick,
            actionTick = step.tick,
            nextStepIndex = nextStepIndex,
            stationCallSpec = stationCallSpec?.copy(pairedDepartureTick = step.tick)
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
            points = plan.points
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
            points = plan.points
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
        if (layout.project(ato.currentTrainSnapshot.head) == null) {
            return MissionSubmission(
                null,
                "train ${ato.train.id} is not facing the planned route from ${plan.origin.id}"
            )
        }
        return when (val result = ato.submitMission(request)) {
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
        val facingMismatch = destination != null &&
            (train.headLocation as? work.art1st.mineRails.core.physics.rail.RailLocationWithFacing)
                ?.facing != destination.approachDirection
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
        const val REPLAN_STOPPED_SPEED_METERS_PER_SECOND = 0.05
        const val MANUAL_PROTECTION_TOLERANCE_METERS = 0.5
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

    private data class TimetableClock(
        val epochDispatcherTick: Long,
        val periodTicks: Long,
        var nextCycleIndex: Long = 1L,
        var lastCycleStartFailure: String? = null,
        val disabledServiceIds: MutableSet<String> = LinkedHashSet()
    )

    private data class StationCallSpec(
        val action: TimetableAction.CallStation,
        val plannedArrivalTick: Long,
        val stepIndex: Int,
        val pairedDepartureTick: Long = Long.MAX_VALUE
    )

    private data class SelectedServiceTrain(
        val trainId: String,
        val originWayPointId: String?,
        val depotAllocation: DepotTrainAllocation?
    )

    private data class MovementPlan(
        val origin: WayPointRef,
        val points: List<AtoMissionPoint>,
        val target: WayPointRef,
        val priorityTick: Long,
        val actionTick: Long,
        val nextStepIndex: Int,
        val stationCallSpec: StationCallSpec?
    )

    private data class PreparedMission(
        val mission: AtoMission,
        val layout: MissionRouteLayout
    )

    private data class MissionSubmission(
        val accepted: PreparedMission?,
        val failure: String?
    )

    private enum class SectionStatus {
        PENDING,
        CONFIRMED,
        RELEASED
    }

    private data class RuntimeSection(
        val routeSection: MissionRouteSection,
        var status: SectionStatus = SectionStatus.PENDING
    )

    private data class MissionRuntime(
        val mission: AtoMission,
        val layout: MissionRouteLayout,
        val priorityTick: Long,
        val targetWayPointId: String,
        var headProgressMeters: Double = 0.0,
        var tailProgressMeters: Double? = null,
        var publishedAuthorityMeters: Double = -1.0,
        var lastOdometerMeters: Double
    ) {
        val sections = layout.sections.map(::RuntimeSection)
    }

    private data class AllocatedSection(
        val runtime: MissionRuntime,
        val section: RuntimeSection
    )

    private data class AllocationCandidate(
        val run: ActiveRun,
        val runtime: MissionRuntime,
        val section: RuntimeSection
    )

    private data class DispatcherStationCall(
        var resolved: ResolvedStationCall,
        val missionRuntime: MissionRuntime,
        val missionDistanceMeters: Double,
        var lastOdometerMeters: Double,
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
        var cancelledForControlTransfer: Boolean = false,
        var lastNotificationEpoch: Long = -1L,
        var tailMissionProgressMeters: Double? = null
    )

    private data class ActiveRun(
        val id: TimetableServiceRunId,
        val service: TimetableService,
        val train: CoreTrain,
        var ato: SimpleATO,
        var controllerOwned: Boolean,
        var missionRevision: Long,
        var nextStepIndex: Int,
        var currentWayPointId: String,
        var currentRuntime: MissionRuntime,
        var state: TimetableServiceRunState,
        val cycleIndex: Long,
        val cycleStartDispatcherTick: Long,
        var lastObservedDispatcherTick: Long = cycleStartDispatcherTick,
        var waitingUntilTimetableTick: Long? = null,
        var waitingStepPending: Boolean = false,
        var lastScheduledActionTick: Long? = null,
        var lastLatenessTicks: Long = 0L,
        var dwellTicksRemaining: Long = 0L,
        var message: String? = null,
        var stationCall: DispatcherStationCall? = null,
        var pendingStationCallSpec: StationCallSpec? = null,
        var controlTransferOwner: String? = null,
        var controlMode: TimetableControlMode = TimetableControlMode.AUTOMATIC,
        var manualController: TrainController? = null,
        val depotAllocation: DepotTrainAllocation? = null,
        val depotDepartureRuntime: MissionRuntime? = null,
        var depotDepartureReleased: Boolean = depotAllocation == null,
        var storageReservation: DepotStorageReservation? = null,
        var storageRuntime: MissionRuntime? = null,
        var storageCompleted: Boolean = false,
        val runtimes: MutableList<MissionRuntime> = ArrayList(),
        val allocations: MutableMap<RouteSectionAllocationKey, AllocatedSection> = LinkedHashMap()
    ) {
        val instanceId: String = "$id@cycle-$cycleIndex/start-$cycleStartDispatcherTick"
        val owner = RouteSectionOwner(instanceId, train.id)

        fun canBeReplaced(): Boolean =
            state.isTerminal() && allocations.isEmpty() &&
                stationCall?.allocationKey == null && storageReservation == null &&
                ato.isStopped(ato.currentTrainSnapshot.speedMetersPerSecond)

        fun isStoredTerminal(): Boolean =
            state == TimetableServiceRunState.COMPLETED && storageCompleted &&
                storageReservation == null && stationCall?.allocationKey == null &&
                ato.isStopped(ato.currentTrainSnapshot.speedMetersPerSecond)

        fun snapshot(): TimetableServiceRunSnapshot {
            val operation = stationCall?.operationTicket?.let { ticket ->
                runCatching { ticket.snapshot() }.getOrNull()
            }
            return TimetableServiceRunSnapshot(
                id = id,
                trainId = train.id,
                missionRevision = missionRevision,
                state = state,
                confirmedSectionCount = allocations.size,
                cycleIndex = cycleIndex,
                timetableElapsedTicks =
                    (lastObservedDispatcherTick - cycleStartDispatcherTick).coerceAtLeast(0L),
                scheduledActionTick = waitingUntilTimetableTick
                    ?: stationCall?.plannedDepartureTimetableTick
                    ?: lastScheduledActionTick,
                lastLatenessTicks = lastLatenessTicks,
                headProgressMeters = currentRuntime.headProgressMeters,
                tailProgressMeters = currentRuntime.tailProgressMeters,
                stationCallId = stationCall?.resolved?.call?.callId,
                berthRef = stationCall?.resolved?.berthRef,
                berthHeld = stationCall?.allocationKey != null,
                operationTicketId = operation?.ticketId ?: stationCall?.operationTicketId,
                operationTicketState = operation?.state,
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
