package work.art1st.mineRails.basic.timetable

import work.art1st.mineRails.basic.api.ato.mission.AtoMission
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolutionResult
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolver
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
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

enum class TimetableServiceRunState {
    WAITING_MISSION,
    WAITING_ROUTE,
    RUNNING,
    WAITING_BERTH,
    APPROACHING,
    BERTHED,
    DWELLING,
    READY_TO_DEPART,
    DEPARTING,
    DEPARTED,
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
    val headProgressMeters: Double? = null,
    val tailProgressMeters: Double? = null,
    val stationCallId: String? = null,
    val berthRef: StationBerthRef? = null,
    val berthHeld: Boolean = false,
    val message: String? = null
)

sealed interface TimetableServiceStartResult {
    data class Started(val run: TimetableServiceRunSnapshot) : TimetableServiceStartResult
    data class Failure(val reason: String) : TimetableServiceStartResult
}

sealed interface TimetableStationCallScheduleResult {
    data class Scheduled(val run: TimetableServiceRunSnapshot) : TimetableStationCallScheduleResult
    data class Failure(val reason: String) : TimetableStationCallScheduleResult
}

/** Main-thread runtime for explicit-train timetable services. */
class TimetableDispatcher(
    private val allocationTiming: RouteAllocationTiming = RouteAllocationTiming(
        switchSetupSeconds = 0.25,
        reactionTimeSeconds = 0.25,
        safetyMarginMeters = 2.0
    ),
    private val atoFactory: TimetableAtoFactory? = null
) {
    private val initialTrains = ConcurrentHashMap<String, TrainSnapshot>()
    private val missionRevision = AtomicLong(0L)
    private val runs = LinkedHashMap<TimetableServiceRunId, ActiveRun>()
    private val berthAllocator = StationBerthAllocator()
    private var dispatcherTick = 0L
    private var allocatorWorld: World? = null
    private var allocator: RouteSectionAllocator? = null

    fun recordInitialTrain(snapshot: TrainSnapshot) {
        initialTrains[snapshot.trainId] = snapshot
    }

    fun initialTrain(trainId: String): TrainSnapshot? = initialTrains[trainId]

    fun initialTrains(): Map<String, TrainSnapshot> = initialTrains.toMap()

    @MainThread
    fun startService(
        world: World,
        timetableId: String,
        serviceId: String
    ): TimetableServiceStartResult {
        val runId = TimetableServiceRunId(timetableId, serviceId)
        runs[runId]?.takeUnless(ActiveRun::canBeReplaced)?.let {
            return TimetableServiceStartResult.Failure("service is already active or still holds route: $runId")
        }

        val timetable = world.basicWorldData.timetables[timetableId]
            ?: return TimetableServiceStartResult.Failure("timetable not found: $timetableId")
        val service = timetable.services.firstOrNull { it.id == serviceId }
            ?: return TimetableServiceStartResult.Failure("service not found: $runId")
        val validationErrors = TimetableValidator(world).validate(
            timetable.copy(services = listOf(service))
        ).errors
        if (validationErrors.isNotEmpty()) {
            return TimetableServiceStartResult.Failure(validationErrors.joinToString("; "))
        }
        val trainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId
            ?: return TimetableServiceStartResult.Failure(
                "only TrainSelector.TrainId is executable in C1: $runId"
            )
        if (runs.values.any { it.train.id == trainId && !it.canBeReplaced() }) {
            return TimetableServiceStartResult.Failure("train is already assigned to a service: $trainId")
        }
        val train = world.getTrain(trainId) as? CoreTrain
            ?: return TimetableServiceStartResult.Failure("train not found: $trainId")
        if (train.isDerailed) return TimetableServiceStartResult.Failure("train is derailed: $trainId")

        val existingController = train.controller
        if (existingController != null && existingController !is SimpleATO) {
            return TimetableServiceStartResult.Failure("train already has another controller: $trainId")
        }
        val replaceableTrainRuns = runs.values.filter {
            it.train.id == trainId && it.canBeReplaced()
        }
        if (existingController is SimpleATO) {
            replaceableTrainRuns.forEach {
                existingController.removeSpeedLimit(stopKey(it.id))
                existingController.removeSpeedLimit(stationPendingHoldKey(it.id))
            }
        }
        val first = firstMovement(runId, service)
            ?: return TimetableServiceStartResult.Failure(
                "service requires an initial Check followed by GoTo/GoVia before any Stop: $runId"
            )
        val originFailure = validateAtWayPoint(world, train, first.origin.id)
        if (originFailure != null) return TimetableServiceStartResult.Failure(originFailure)

        val ato = existingController ?: atoFactory?.create(train, world)
            ?: return TimetableServiceStartResult.Failure("timetable ATO factory is not configured")
        val submission = submitMission(world, ato, runId, first)
        val accepted = submission.accepted
            ?: return TimetableServiceStartResult.Failure(submission.failure!!)
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
            state = TimetableServiceRunState.WAITING_MISSION
        )
        run.runtimes += runtime
        replaceableTrainRuns.forEach { runs.remove(it.id) }
        runs[runId] = run
        first.stationCallSpec?.let { spec ->
            scheduleStationCallSpec(world, run, spec)?.let { reason -> fail(run, reason) }
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
        val run = runs[id]
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
            serviceRunId = id.toString(),
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
        run.ato.setSpeedLimit(stationPendingHoldKey(run.id), 0.0)
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
        val dwellPlan = DwellPlanResolver.resolve(
            station = station,
            berth = berth,
            minimumDwellTicksOverride = spec.action.minimumDwellTicks,
            plannedDepartureTick = spec.action.plannedDepartureTick
        )
        val call = StationCall(
            callId = "${run.id}/station/${spec.stepIndex}",
            missionRevision = run.missionRevision,
            stationId = berthRef.stationId,
            berthRef = berthRef,
            plannedArrivalTick = spec.plannedArrivalTick,
            dwellPlan = dwellPlan,
            stoppingOffsetMetersBeforeOverride = spec.action.stoppingOffsetMetersBefore,
            doorSideOverride = spec.action.doorSideOverride
        )
        return when (val result = scheduleStationCall(world, run.id, call)) {
            is TimetableStationCallScheduleResult.Scheduled -> null
            is TimetableStationCallScheduleResult.Failure -> result.reason
        }
    }

    /** Called on the main thread after the current physics epoch has published. */
    @MainThread
    fun tick(world: World) {
        dispatcherTick++
        if (world.basicWorldData.atoCoordinator.isStalled) return
        val sectionAllocator = allocator(world)
        val candidates = ArrayList<AllocationCandidate>()

        runs.values.toList().forEach { run ->
            val snapshot = run.ato.currentTrainSnapshot
            processStationNotifications(run)
            updateTailProgress(run, snapshot)
            updateStationTailProgress(run, snapshot)
            releasePassedSections(run, sectionAllocator)
            releasePassedBerth(run)

            if (run.state.isTerminal()) {
                finishControllerIfReleased(run, snapshot)
                return@forEach
            }
            if (run.train.isDerailed) {
                fail(run, "train derailed")
                return@forEach
            }
            if (run.train.controller !== run.ato) {
                fail(run, "train controller was replaced")
                return@forEach
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
            if (run.state == TimetableServiceRunState.BERTHED &&
                stationCall != null && dispatcherTick > stationCall.stateEnteredDispatcherTick
            ) {
                run.state = TimetableServiceRunState.DWELLING
                run.message = "dwelling at ${stationCall.resolved.berthRef}"
            }
            if (run.state == TimetableServiceRunState.DWELLING &&
                stationCall != null && !stationCall.departed
            ) {
                if (dispatcherTick >= stationCall.earliestDepartureDispatcherTick) {
                    if (hasAuthorityBeyondStation(run, stationCall)) {
                        run.state = TimetableServiceRunState.READY_TO_DEPART
                        run.message = "ready to depart ${stationCall.resolved.berthRef}"
                        stationCall.stateEnteredDispatcherTick = dispatcherTick
                        return@forEach
                    }
                    run.message = "dwell complete; waiting for route beyond ${stationCall.resolved.berthRef}"
                }
            } else if (run.state == TimetableServiceRunState.DWELLING) {
                if (run.dwellTicksRemaining > 0L) run.dwellTicksRemaining--
                if (run.dwellTicksRemaining == 0L) advanceService(world, run)
                return@forEach
            }
            if (run.state == TimetableServiceRunState.READY_TO_DEPART && stationCall != null) {
                if (hasAuthorityBeyondStation(run, stationCall)) {
                    run.ato.grantStationDeparture(
                        stationCall.resolved.call.callId,
                        stationCall.resolved.call.missionRevision
                    )
                    stationCall.departureGranted = true
                    stationCall.stateEnteredDispatcherTick = dispatcherTick
                    run.state = TimetableServiceRunState.DEPARTING
                    run.message = "departing ${stationCall.resolved.berthRef}"
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
                { it.runtime.priorityTick },
                { it.run.id.timetableId },
                { it.run.id.serviceId }
            )
        ).forEach { candidate ->
            if (candidate.run.state.isTerminal() || candidate.section.status != SectionStatus.PENDING) {
                return@forEach
            }
            allocate(candidate, sectionAllocator)
        }
    }

    @MainThread
    fun stopService(world: World, id: TimetableServiceRunId): Boolean {
        allocator(world)
        val run = runs[id] ?: return false
        if (run.canBeReplaced() || run.state == TimetableServiceRunState.STOPPED) return true
        run.ato.setSpeedLimit(stopKey(run.id), 0.0)
        run.state = TimetableServiceRunState.STOPPING
        run.message = "operator stop requested; route remains held until the tail clears"
        return true
    }

    @MainThread
    fun status(id: TimetableServiceRunId): TimetableServiceRunSnapshot? = runs[id]?.snapshot()

    @MainThread
    fun statuses(): List<TimetableServiceRunSnapshot> = runs.values.map(ActiveRun::snapshot)

    private fun tryAllocateStationCall(run: ActiveRun): String? {
        val stationCall = run.stationCall ?: return null
        if (stationCall.submittedToAto) return null
        val call = stationCall.resolved.call
        val key = stationCall.allocationKey ?: StationBerthAllocationKey(
            owner = StationBerthOwner(run.id.toString(), run.train.id, call.callId),
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
                        run.ato.removeSpeedLimit(stationPendingHoldKey(run.id))
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
                    stationCall.earliestDepartureDispatcherTick =
                        stationCall.resolved.call.dwellPlan.earliestDepartureTick(dispatcherTick)
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
        if (stationCall.departed && run.state != TimetableServiceRunState.DEPARTED) {
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
                run.ato.confirmRouteWindow(result.window)
                publishMovementAuthority(run, runtime)
                setRouteState(run, TimetableServiceRunState.RUNNING, null)
            }
            is RouteSectionAllocationResult.Conflict -> {
                setRouteState(
                    run,
                    TimetableServiceRunState.WAITING_ROUTE,
                    "route conflict at ${result.resource}, held by ${result.heldBy.owner}"
                )
            }
            is RouteSectionAllocationResult.Invalid -> fail(run, result.reason)
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
                    is TimetableAction.Stop -> {
                        run.nextStepIndex++
                        run.dwellTicksRemaining = action.ticks
                        run.state = TimetableServiceRunState.DWELLING
                        run.message = "dwell ${action.ticks} ticks"
                        return
                    }
                    is TimetableAction.CallStation -> {
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
                        val submission = submitMission(world, run.ato, run.id, plan)
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
        run.ato.setSpeedLimit(stopKey(run.id), 0.0)
        run.state = TimetableServiceRunState.FAILED
        run.message = reason
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

    private fun firstMovement(
        runId: TimetableServiceRunId,
        service: TimetableService
    ): MovementPlan? {
        var origin: WayPointRef? = null
        var stationCallSpec: StationCallSpec? = null
        service.steps.forEachIndexed { index, step ->
            when (step) {
                is TimetableStep.Check -> origin = WayPointRef(step.wayPointId)
                is TimetableStep.At -> when (val action = step.action) {
                    is TimetableAction.CallStation -> {
                        if (stationCallSpec != null) return null
                        stationCallSpec = StationCallSpec(action, step.tick, index)
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
            is TimetableAction.Stop,
            is TimetableAction.CallStation -> error("action is not a movement action")
        }
        require(points.isNotEmpty()) { "movement action must contain a target: $runId" }
        return MovementPlan(
            origin = origin,
            points = points,
            target = points.last().target,
            priorityTick = stationCallSpec?.plannedArrivalTick ?: step.tick,
            nextStepIndex = nextStepIndex,
            stationCallSpec = stationCallSpec
        )
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
        return if (train.headLocation.rail.id != wayPoint.railId ||
            abs(trainArc - wayPoint.arcLength) > ORIGIN_TOLERANCE_METERS
        ) {
            "train ${train.id} is not at waypoint $wayPointId"
        } else {
            null
        }
    }

    private fun stopKey(id: TimetableServiceRunId): String = "dispatcher:stop/$id"

    private fun stationPendingHoldKey(id: TimetableServiceRunId): String =
        "dispatcher:station-pending/$id"

    private companion object {
        const val ORIGIN_TOLERANCE_METERS = 1.0
        const val POSITION_TOLERANCE_METERS = 0.5
        const val RELEASE_EPSILON_METERS = 1.0e-6
    }

    private data class StationCallSpec(
        val action: TimetableAction.CallStation,
        val plannedArrivalTick: Long,
        val stepIndex: Int
    )

    private data class MovementPlan(
        val origin: WayPointRef,
        val points: List<AtoMissionPoint>,
        val target: WayPointRef,
        val priorityTick: Long,
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
        var lastNotificationEpoch: Long = -1L,
        var tailMissionProgressMeters: Double? = null
    )

    private data class ActiveRun(
        val id: TimetableServiceRunId,
        val service: TimetableService,
        val train: CoreTrain,
        val ato: SimpleATO,
        val controllerOwned: Boolean,
        var missionRevision: Long,
        var nextStepIndex: Int,
        var currentWayPointId: String,
        var currentRuntime: MissionRuntime,
        var state: TimetableServiceRunState,
        var dwellTicksRemaining: Long = 0L,
        var message: String? = null,
        var stationCall: DispatcherStationCall? = null,
        var pendingStationCallSpec: StationCallSpec? = null,
        val runtimes: MutableList<MissionRuntime> = ArrayList(),
        val allocations: MutableMap<RouteSectionAllocationKey, AllocatedSection> = LinkedHashMap()
    ) {
        val owner = RouteSectionOwner(id.toString(), train.id)

        fun canBeReplaced(): Boolean =
            state.isTerminal() && allocations.isEmpty() &&
                stationCall?.allocationKey == null &&
                ato.isStopped(ato.currentTrainSnapshot.speedMetersPerSecond)

        fun snapshot(): TimetableServiceRunSnapshot = TimetableServiceRunSnapshot(
            id = id,
            trainId = train.id,
            missionRevision = missionRevision,
            state = state,
            confirmedSectionCount = allocations.size,
            headProgressMeters = currentRuntime.headProgressMeters,
            tailProgressMeters = currentRuntime.tailProgressMeters,
            stationCallId = stationCall?.resolved?.call?.callId,
            berthRef = stationCall?.resolved?.berthRef,
            berthHeld = stationCall?.allocationKey != null,
            message = message
        )
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
        this == TimetableServiceRunState.DWELLING ||
        this == TimetableServiceRunState.READY_TO_DEPART ||
        this == TimetableServiceRunState.DEPARTING ||
        this == TimetableServiceRunState.DEPARTED
