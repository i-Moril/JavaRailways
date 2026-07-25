package work.art1st.mineRails.basic.controller

import work.art1st.mineRails.basic.api.ato.AbstractATO
import work.art1st.mineRails.basic.api.ato.AtoPlanningContext
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.ControlDemand
import work.art1st.mineRails.basic.api.ato.MinimumConstraintPlanner
import work.art1st.mineRails.basic.api.ato.TrainActuator
import work.art1st.mineRails.basic.api.ato.command.CommandOfferResult
import work.art1st.mineRails.basic.api.ato.command.RevisionedCommandQueue
import work.art1st.mineRails.basic.api.ato.constraint.AtoConstraint
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintKey
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintManager
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintOwner
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintOwnerType
import work.art1st.mineRails.basic.api.ato.constraint.CurrentSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.constraint.DepartureHoldConstraint
import work.art1st.mineRails.basic.api.ato.constraint.MovementAuthorityConstraint
import work.art1st.mineRails.basic.api.ato.constraint.FollowConstraint
import work.art1st.mineRails.basic.api.ato.constraint.RouteSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.constraint.StopConstraint
import work.art1st.mineRails.basic.api.ato.mission.AtoMission
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolutionResult
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolver
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.provider.AtoProvider
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderBoundary
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderContext
import work.art1st.mineRails.basic.api.ato.provider.ProviderBoundaryAction
import work.art1st.mineRails.basic.api.ato.snapshot.ObservationTransition
import work.art1st.mineRails.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.mineRails.basic.api.ato.snapshot.RailObservationSnapshot
import work.art1st.mineRails.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.mineRails.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.mineRails.basic.api.station.ResolvedStationCall
import work.art1st.mineRails.basic.api.station.StationBerthDetector
import work.art1st.mineRails.basic.api.station.StationCallNotification
import work.art1st.mineRails.basic.api.station.StationCallNotificationType
import work.art1st.mineRails.basic.api.station.StationCallRequest
import work.art1st.mineRails.basic.api.station.StationCallResolutionFailure
import work.art1st.mineRails.basic.api.station.StationCallResolutionResult
import work.art1st.mineRails.basic.api.station.StationCallResolver
import work.art1st.mineRails.basic.api.station.StationCallSubmissionResult
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.ConfirmedRouteWindow
import work.art1st.mineRails.basic.route.MissionRouteLayout
import work.art1st.mineRails.basic.route.RouteAllocationTiming
import work.art1st.mineRails.basic.route.RouteSectionAllocationKey
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.api.physics.rail.RailObjectSnapshot
import work.art1st.mineRails.core.api.physics.rail.RailPositionSnapshot
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailSegment
import work.art1st.mineRails.core.physics.train.TrainEnd
import java.util.concurrent.ConcurrentLinkedQueue

data class SimpleAtoRuntimeDiagnostics(
    val missionRevision: Long?,
    val odometerProgressMeters: Double,
    val projectedProgressMeters: Double?,
    val movementAuthorityRevision: Long,
    val movementAuthorityLimitMeters: Double,
    val activeStationCallId: String?,
    val stationTargetProgressMeters: Double?,
    val stationPositionErrorMeters: Double?,
    val permittedSpeedMetersPerSecond: Double,
    val constraints: List<String>
)

data class SimpleAtoPublishedState(
    val trainSnapshot: TrainSnapshot,
    val lifetimeTravelDistanceMeters: Double,
    val preparedReverseRevision: Long,
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics,
    val currentMission: AtoMission?,
    val currentConfirmedRouteWindows: List<ConfirmedRouteWindow>,
    val currentStationCall: ResolvedStationCall?
)

private data class SimpleAtoControllerPublishedState(
    val preparedReverseRevision: Long,
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics,
    val currentMission: AtoMission?,
    val currentConfirmedRouteWindows: List<ConfirmedRouteWindow>,
    val currentStationCall: ResolvedStationCall?
)

/** Basic ATO with line-speed, fixed-stop, following, and one optional signalling provider. */
class SimpleATO(
    train: Train,
    world: World,
    actuator: TrainActuator,
    safetyProfile: AtoSafetyProfile = AtoSafetyProfile()
) : AbstractATO(train, world, actuator, safetyProfile, world.basicWorldData.atoCoordinator) {
    private sealed interface Command {
        data class SetSpeedLimit(val key: String, val speed: Double) : Command
        data class StopAt(val key: String, val target: RailObjectRef, val offsetMeters: Double) : Command
        data class Remove(val key: String) : Command
        data class SetProvider(val providerId: String?) : Command
        data class ConfirmRouteWindow(val window: ConfirmedRouteWindow) : Command
        data class ReleaseRouteWindow(val key: RouteSectionAllocationKey) : Command
        data class SetMovementAuthority(val missionRevision: Long, val limitDistanceMeters: Double) : Command
        data class GrantStationDeparture(val callId: String, val missionRevision: Long) : Command
        data class PrepareReverse(
            val callId: String?,
            val missionRevision: Long?
        ) : Command
    }

    private data class ActiveStationCall(
        val resolved: ResolvedStationCall,
        val missionLayout: MissionRouteLayout,
        val missionTargetProgressMeters: Double,
        var projectedProgressMeters: Double? = null,
        var approachingReported: Boolean = false,
        var berthed: Boolean = false,
        var departureGranted: Boolean = false,
        var departed: Boolean = false
    )

    private data class StopTarget(val ref: RailObjectRef, val offsetMeters: Double)

    private data class Observed(
        val railObject: RailObject,
        val snapshot: RailObjectSnapshot,
        val distanceMeters: Double,
        val relativeSpeedMetersPerSecond: Double?
    )

    private data class ScanResult(
        val route: RouteSnapshot,
        val segments: List<RailSegment>,
        val perception: PerceptionSnapshot,
        val currentObservedByRef: Map<RailObjectRef, Observed>,
        val eventObjectsByRef: Map<RailObjectRef, Observed>
    )

    private val worldData = world.basicWorldData
    private val commands = RevisionedCommandQueue<Command>()
    private val missionCommands = RevisionedCommandQueue<AtoMission>()
    private val stationCallCommands = RevisionedCommandQueue<ResolvedStationCall>()
    private val missionResolver = AtoMissionResolver(world)
    private val stationCallResolver = StationCallResolver(world)
    private val publishedStationNotifications = ConcurrentLinkedQueue<StationCallNotification>()
    private val pendingStationNotifications = ArrayList<StationCallNotification>()
    private val constraintManager = ConstraintManager()
    private val speedLimits = LinkedHashMap<String, Double>()
    private val stopTargets = LinkedHashMap<String, StopTarget>()
    private var previousObserved: Map<RailObjectRef, Observed> = emptyMap()
    private var currentSegments: List<RailSegment> = emptyList()
    private var activeProvider: AtoProvider? = null
    private val confirmedRouteWindows = LinkedHashMap<RouteSectionAllocationKey, ConfirmedRouteWindow>()
    private var routeRevision = 0L
    private var previousRouteKey: List<Pair<java.util.UUID, work.art1st.mineRails.core.api.physics.rail.ViewDirection>> = emptyList()
    private var perceptionEpoch = 0L
    private var missionProgressMeters = 0.0
    private var missionRouteLayout: MissionRouteLayout? = null
    private var missionProjectedProgressMeters: Double? = null

    private var activePreparedReverseRevision: Long = 0L
    private var movementAuthorityRevision = -1L
    private var movementAuthorityLimitMeters = 0.0
    private var activeStationCall: ActiveStationCall? = null
    private var stationPositionErrorMeters: Double? = null

    private var activeRuntimeDiagnostics = INITIAL_RUNTIME_DIAGNOSTICS
        private set

    private var activeMission: AtoMission? = null
    private var activeConfirmedRouteWindows: List<ConfirmedRouteWindow> = emptyList()
    private var activeCurrentStationCall: ResolvedStationCall? = null

    val publishedState: SimpleAtoPublishedState
        get() {
            val state = publishedAtoState()
            val controller = state.controllerState as? SimpleAtoControllerPublishedState
                ?: INITIAL_CONTROLLER_STATE
            return SimpleAtoPublishedState(
                trainSnapshot = state.trainSnapshot,
                lifetimeTravelDistanceMeters = state.lifetimeTravelDistanceMeters,
                preparedReverseRevision = controller.preparedReverseRevision,
                runtimeDiagnostics = controller.runtimeDiagnostics,
                currentMission = controller.currentMission,
                currentConfirmedRouteWindows = controller.currentConfirmedRouteWindows,
                currentStationCall = controller.currentStationCall
            )
        }

    val preparedReverseRevision: Long get() = publishedState.preparedReverseRevision
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics get() = publishedState.runtimeDiagnostics
    val currentMission: AtoMission? get() = publishedState.currentMission
    val currentConfirmedRouteWindows: List<ConfirmedRouteWindow>
        get() = publishedState.currentConfirmedRouteWindows
    val currentStationCall: ResolvedStationCall? get() = publishedState.currentStationCall

    /** Resolve and queue a Mission from the main thread; activation occurs at a physics Tick boundary. */
    @MainThread
    fun submitMission(request: AtoMissionRequest): AtoMissionSubmissionResult {
        val mission = when (val result = missionResolver.resolve(request)) {
            is AtoMissionResolutionResult.Success -> result.mission
            is AtoMissionResolutionResult.TargetUnresolved -> {
                return AtoMissionSubmissionResult.TargetUnresolved(result.target)
            }
            is AtoMissionResolutionResult.RouteUnavailable -> {
                return AtoMissionSubmissionResult.RouteUnavailable(result.from, result.to, result.reason)
            }
        }
        return submitMission(mission)
    }

    /** Queue the exact Mission already resolved and verified by the Dispatcher. */
    @MainThread
    fun submitMission(mission: AtoMission): AtoMissionSubmissionResult {
        val latestRevision = missionCommands.latestAcceptedRevision
        if (mission.revision <= latestRevision) {
            return AtoMissionSubmissionResult.StaleRevision(mission.revision, latestRevision)
        }
        return when (missionCommands.offer(mission.revision, mission)) {
            CommandOfferResult.ACCEPTED -> AtoMissionSubmissionResult.Accepted(mission)
            CommandOfferResult.STALE -> AtoMissionSubmissionResult.StaleRevision(
                mission.revision,
                missionCommands.latestAcceptedRevision
            )
        }
    }

    /** Resolve and queue one StationCall; activation occurs at a physics Tick boundary. */
    @MainThread
    fun submitStationCall(request: StationCallRequest): StationCallSubmissionResult {
        val resolved = when (val result = stationCallResolver.resolve(
            request,
            trainLengthMeters = train.cars.sumOf { it.length }
        )) {
            is StationCallResolutionResult.Success -> result.resolved
            is StationCallResolutionResult.Failure -> return StationCallSubmissionResult.ResolutionFailure(
                result.reason,
                result.message
            )
        }
        return submitStationCall(resolved)
    }

    /** Queue the exact route already resolved and verified by the Dispatcher. */
    @MainThread
    fun submitStationCall(resolved: ResolvedStationCall): StationCallSubmissionResult {
        val revision = resolved.call.missionRevision
        val latestMissionRevision = missionCommands.latestAcceptedRevision
        if (latestMissionRevision <= 0L || revision != latestMissionRevision) {
            return StationCallSubmissionResult.ResolutionFailure(
                StationCallResolutionFailure.MISSION_REVISION_MISMATCH,
                "StationCall revision $revision does not match latest Mission $latestMissionRevision"
            )
        }
        val latestRevision = stationCallCommands.latestAcceptedRevision
        if (revision <= latestRevision) {
            return StationCallSubmissionResult.StaleRevision(revision, latestRevision)
        }
        return when (stationCallCommands.offer(revision, resolved)) {
            CommandOfferResult.ACCEPTED -> StationCallSubmissionResult.Accepted(resolved)
            CommandOfferResult.STALE -> StationCallSubmissionResult.StaleRevision(
                revision,
                stationCallCommands.latestAcceptedRevision
            )
        }
    }

    @MainThread
    fun grantStationDeparture(callId: String, missionRevision: Long): Long {
        require(callId.isNotBlank())
        require(missionRevision >= 0L)
        return commands.enqueue(Command.GrantStationDeparture(callId, missionRevision)).revision
    }

    @MainThread
    fun drainStationCallNotifications(): List<StationCallNotification> = buildList {
        while (true) {
            val notification = publishedStationNotifications.poll() ?: break
            add(notification)
        }
    }

    @MainThread
    fun confirmRouteWindow(window: ConfirmedRouteWindow): Long =
        commands.enqueue(Command.ConfirmRouteWindow(window)).revision

    @MainThread
    fun releaseRouteWindow(key: RouteSectionAllocationKey): Long =
        commands.enqueue(Command.ReleaseRouteWindow(key)).revision

    @MainThread
    fun setMovementAuthority(missionRevision: Long, limitDistanceMeters: Double): Long {
        require(missionRevision >= 0L)
        require(limitDistanceMeters.isFinite() && limitDistanceMeters >= 0.0)
        return commands.enqueue(
            Command.SetMovementAuthority(missionRevision, limitDistanceMeters)
        ).revision
    }

    fun serviceBrakingDistanceMeters(speedMetersPerSecond: Double): Double {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        return brakingDistance(
            speedMetersPerSecond,
            safetyProfile.serviceDecelerationMetersPerSecondSquared
        )
    }

    fun routeAllocationTriggerDistanceMeters(
        approachSpeedMetersPerSecond: Double,
        timing: RouteAllocationTiming
    ): Double = timing.triggerDistanceMeters(
        approachSpeedMetersPerSecond,
        safetyProfile.serviceDecelerationMetersPerSecondSquared
    )

    fun isStopped(speedMetersPerSecond: Double): Boolean =
        speedMetersPerSecond <= safetyProfile.stoppedSpeedThresholdMetersPerSecond

    val missionStopToleranceMeters: Double
        get() = safetyProfile.stopToleranceMeters

    fun terminalMovementAuthorityLimitMeters(routeEndDistanceMeters: Double): Double {
        require(routeEndDistanceMeters.isFinite() && routeEndDistanceMeters >= 0.0)
        return routeEndDistanceMeters + safetyProfile.safetyDistanceMeters
    }

    fun prepareForReverse(callId: String?, missionRevision: Long?): Long {
        require((callId == null) == (missionRevision == null))
        return commands.enqueue(Command.PrepareReverse(callId, missionRevision)).revision
    }

    fun setSpeedLimit(key: String, speedLimitMetersPerSecond: Double): Long {
        require(key.isNotBlank())
        require(speedLimitMetersPerSecond >= 0.0 && speedLimitMetersPerSecond.isFinite())
        return commands.enqueue(Command.SetSpeedLimit(key, speedLimitMetersPerSecond)).revision
    }

    fun stopAt(key: String, railObject: RailObject, offsetMetersBefore: Double = 0.0): Long =
        stopAt(key, railObject.ref, offsetMetersBefore)

    fun stopAt(key: String, target: RailObjectRef, offsetMetersBefore: Double = 0.0): Long {
        require(key.isNotBlank())
        require(offsetMetersBefore >= 0.0 && offsetMetersBefore.isFinite())
        return commands.enqueue(Command.StopAt(key, target, offsetMetersBefore)).revision
    }

    fun removeSpeedLimit(key: String): Long {
        require(key.isNotBlank())
        return commands.enqueue(Command.Remove(key)).revision
    }

    fun setProvider(providerId: String?): Long {
        require(providerId == null || providerId.isNotBlank())
        return commands.enqueue(Command.SetProvider(providerId)).revision
    }

    override fun processPendingCommands() {
        missionCommands.drain().lastOrNull()?.command?.let { mission ->
            constraintManager.remove(MISSION_OWNER)
            missionProgressMeters = 0.0
            missionRouteLayout = MissionRouteLayout(world, mission)
            missionProjectedProgressMeters = null
            movementAuthorityRevision = mission.revision
            movementAuthorityLimitMeters = 0.0
            activeMission = mission
        }
        stationCallCommands.drain().lastOrNull()?.command?.let { resolved ->
            check(activeMission?.revision == resolved.call.missionRevision) {
                "StationCall revision ${resolved.call.missionRevision} does not match active Mission " +
                    "${activeMission?.revision}"
            }
            constraintManager.remove(STATION_OWNER)
            val berth = requireNotNull(worldData.stations.berth(resolved.berthRef)) {
                "active StationBerth disappeared: ${resolved.berthRef}"
            }
            val arrivalDirection = resolved.plannedRoute.expandedRoute.segments.last()
                .railViewRef.direction
            val stationMissionLayout = requireNotNull(missionRouteLayout)
            val targetProgress = requireNotNull(stationMissionLayout.project(
                RailPositionSnapshot(
                    railId = berth.location.rail.id,
                    facing = arrivalDirection,
                    arcLengthInAbMeters = berth.location.arcLengthIn(
                        work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB
                    )
                ),
                resolved.plannedRoute.expandedRoute.totalLength
            )) {
                "StationBerth ${resolved.berthRef} is not on the active Mission route"
            }
            activeStationCall = ActiveStationCall(
                resolved = resolved,
                missionLayout = stationMissionLayout,
                missionTargetProgressMeters = targetProgress,
                projectedProgressMeters = stationMissionLayout.project(
                    TrainSnapshot.capture(
                        train,
                        perceptionEpoch,
                        (train.headLocation as RailLocationWithFacing).facing
                    ).head
                )
            )
            activeCurrentStationCall = resolved
        }
        commands.drain().forEach { envelope ->
            when (val command = envelope.command) {
                is Command.SetSpeedLimit -> speedLimits[command.key] = command.speed
                is Command.StopAt -> stopTargets[command.key] = StopTarget(command.target, command.offsetMeters)
                is Command.Remove -> {
                    speedLimits.remove(command.key)
                    stopTargets.remove(command.key)
                }
                is Command.SetProvider -> switchProvider(command.providerId)
                is Command.ConfirmRouteWindow -> {
                    check(activeMission?.legs?.any { leg ->
                        leg.plannedRoute.routeId == command.window.key.routeId &&
                            leg.plannedRoute.revision == command.window.key.routeRevision
                    } == true) {
                        "confirmed RouteSection does not belong to the active Mission: ${command.window.key}"
                    }
                    confirmedRouteWindows[command.window.key] = command.window
                    publishConfirmedRouteWindows()
                }
                is Command.ReleaseRouteWindow -> {
                    confirmedRouteWindows.remove(command.key)
                    publishConfirmedRouteWindows()
                }
                is Command.SetMovementAuthority -> {
                    val activeRevision = activeMission?.revision ?: -1L
                    when {
                        command.missionRevision == activeRevision -> {
                            movementAuthorityRevision = command.missionRevision
                            movementAuthorityLimitMeters = command.limitDistanceMeters
                        }
                        command.missionRevision > activeRevision -> error(
                            "movement authority revision ${command.missionRevision} is newer than active Mission $activeRevision"
                        )
                    }
                }
                is Command.GrantStationDeparture -> {
                    val active = activeStationCall
                    val activeRevision = active?.resolved?.call?.missionRevision ?: -1L
                    when {
                        command.missionRevision < activeRevision -> Unit
                        command.missionRevision > activeRevision -> error(
                            "departure grant revision ${command.missionRevision} is newer than active StationCall $activeRevision"
                        )
                        else -> {
                            check(active != null && active.resolved.call.callId == command.callId) {
                                "departure grant does not match the active StationCall"
                            }
                            check(active.berthed) { "departure cannot be granted before BERTHED" }
                            active.departureGranted = true
                        }
                    }
                }
                is Command.PrepareReverse -> {
                    val active = activeStationCall
                    if (command.callId == null) {
                        check(active == null) {
                            "reverse without StationCall identity cannot retire an active StationCall"
                        }
                    } else {
                        check(active != null &&
                            active.resolved.call.callId == command.callId &&
                            active.resolved.call.missionRevision == command.missionRevision
                        ) { "reverse preparation does not match the active StationCall" }
                        check(active.berthed) {
                            "reverse cannot retire a StationCall before BERTHED"
                        }
                        active.departureGranted = true
                        active.departed = true
                        reportStationCall(active, StationCallNotificationType.REVERSE_READY, 0.0)
                    }
                    activeStationCall = null
                    activeCurrentStationCall = null
                    stationPositionErrorMeters = null
                    constraintManager.remove(STATION_OWNER)
                    activeMission = null
                    missionRouteLayout = null
                    missionProjectedProgressMeters = null
                    missionProgressMeters = 0.0
                    movementAuthorityRevision = -1L
                    movementAuthorityLimitMeters = 0.0
                    constraintManager.remove(MISSION_OWNER)
                    activePreparedReverseRevision = envelope.revision
                }
            }
        }
    }

    override fun plan(deltaTimeSeconds: Double): ControlDemand {
        perceptionEpoch++
        missionProgressMeters += lastTravelDistanceMeters
        val scan = scanRouteAndObjects()
        missionRouteLayout?.let { layout ->
            missionProjectedProgressMeters = requireNotNull(
                layout.project(scan.perception.train.head, missionProjectedProgressMeters)
            ) { "ATO train head left the active Mission route" }
        }
        latestRouteSnapshot = scan.route
        latestPerceptionSnapshot = scan.perception
        currentSegments = scan.segments

        processProviderBoundaries(scan)
        updateBuiltInConstraints(scan)
        updateMissionConstraints()
        updateStationCallConstraints(scan)
        updateProviderConstraints(scan)
        publishRuntimeDiagnostics()

        // Continue perception and invariant validation without a Mission, but never turn line-speed
        // limits into movement authority. Infrastructure constraints are upper bounds only.
        if (activeMission == null) return ControlDemand(0.0)

        return MinimumConstraintPlanner.plan(
            AtoPlanningContext(
                currentSpeedMetersPerSecond = activeSpeed,
                maximumSpeedMetersPerSecond = actuator.maximumSpeedMetersPerSecond,
                profile = safetyProfile,
                constraints = constraintManager
            )
        )
    }

    override fun validateBeforeMovement() {
        currentSegments.zipWithNext().forEach { (current, next) ->
            check(current.railView.activeRail === next.railView) {
                "turnout changed while ATO was planning at rail ${current.railView.rail.id}"
            }
        }
        confirmedRouteWindows.values.forEach { window ->
            window.section.turnoutRequirements.forEach { requirement ->
                val from = world.getRail(requirement.from.railId)?.getView(requirement.from.direction)
                    ?: error("confirmed turnout no longer exists: ${requirement.from}")
                val selected = world.getRail(requirement.selectedNext.railId)
                    ?.getView(requirement.selectedNext.direction)
                    ?: error("confirmed branch no longer exists: ${requirement.selectedNext}")
                check(from.nextActiveRail === selected) {
                    "confirmed turnout mismatch at ${requirement.from}: " +
                        "expected ${requirement.selectedNext}, actual ${from.nextActiveRail?.rail?.id}"
                }
            }
        }
    }

    override fun activeProviderId(): String? = activeProvider?.id

    override fun constraintSnapshot(): Map<ConstraintOwner, List<AtoConstraint>> = constraintManager.snapshot()

    private fun updateBuiltInConstraints(scan: ScanResult) {
        var distanceToSegment = 0.0
        val routeLimits = scan.segments.mapIndexed { index, segment ->
            val limit = segment.railView.rail.metadata.speedLimitMetersPerSecond
                ?: actuator.maximumSpeedMetersPerSecond
            RouteSpeedLimitConstraint(
                key = ConstraintKey("basic", "route-speed/$index/${segment.railView.rail.id}"),
                distanceToStartMeters = distanceToSegment,
                maximumSpeedMetersPerSecond = minOf(limit, actuator.maximumSpeedMetersPerSecond)
            ).also { distanceToSegment += segment.totalArcLength }
        }
        constraintManager.replace(LINE_SPEED_OWNER, routeLimits)

        val lastSegment = scan.segments.last()
        val routeEnd = if (
            lastSegment.railView.activeRail == null &&
            lastSegment.endArcLength >= lastSegment.railView.curve.totalArcLength - safetyProfile.stopToleranceMeters
        ) {
            listOf(
                StopConstraint(
                    ConstraintKey("basic", "route-end/${lastSegment.railView.rail.id}"),
                    scan.segments.sumOf { it.totalArcLength }
                )
            )
        } else {
            emptyList()
        }
        constraintManager.replace(ROUTE_END_OWNER, routeEnd)

        val nearestTrain = scan.currentObservedByRef.values
            .asSequence()
            .filter { it.railObject is TrainEnd }
            .minByOrNull { it.distanceMeters }
        val following = nearestTrain?.let { observed ->
            val leaderSpeed = (activeSpeed + (observed.relativeSpeedMetersPerSecond ?: 0.0)).coerceAtLeast(0.0)
            listOf(
                FollowConstraint(
                    ConstraintKey("basic", "follow/${observed.snapshot.ref}"),
                    observed.distanceMeters,
                    leaderSpeed
                )
            )
        }.orEmpty()
        constraintManager.replace(FOLLOW_OWNER, following)

        val external = buildList {
            speedLimits.forEach { (key, limit) ->
                add(CurrentSpeedLimitConstraint(ConstraintKey("external", key), limit))
            }
            stopTargets.forEach { (key, target) ->
                val transition = scan.perception.observations
                    .firstOrNull { it.subject.ref == target.ref }
                    ?.transition
                check(transition != ObservationTransition.PASS) {
                    "ATO passed stop target ${target.ref}"
                }
                check(transition != ObservationTransition.EXIT) {
                    "observed stop target ${target.ref} disappeared from the active route"
                }
                val observed = scan.currentObservedByRef[target.ref] ?: return@forEach
                add(
                    StopConstraint(
                        ConstraintKey("external", key),
                        (observed.distanceMeters - target.offsetMeters).coerceAtLeast(0.0)
                    )
                )
            }
        }
        constraintManager.replace(EXTERNAL_OWNER, external)
    }

    private fun updateMissionConstraints() {
        val mission = activeMission
        if (mission == null) {
            constraintManager.remove(MISSION_OWNER)
            return
        }
        var targetDistance = 0.0
        val controlProgress = missionProjectedProgressMeters ?: missionProgressMeters
        val constraints = buildList {
            val authorityLimit = movementAuthorityLimitMeters.takeIf {
                movementAuthorityRevision == mission.revision
            } ?: 0.0
            add(
                MovementAuthorityConstraint(
                    ConstraintKey("mission", "authority/${mission.revision}"),
                    (authorityLimit - controlProgress).coerceAtLeast(0.0)
                )
            )
            mission.legs.forEach { leg ->
                targetDistance += leg.plannedRoute.expandedRoute.totalLength
                val point = leg.point
                if (point is AtoMissionPoint.Stop) {
                    val stationMargin = activeStationCall?.takeIf { stationCall ->
                        stationCall.resolved.call.missionRevision == mission.revision &&
                            kotlin.math.abs(
                                stationCall.missionTargetProgressMeters - targetDistance
                            ) <= safetyProfile.stopToleranceMeters
                    }?.let { stationCall ->
                        2.0 * minOf(
                            stationCall.resolved.positionToleranceMeters,
                            safetyProfile.stopToleranceMeters
                        )
                    }
                    val stopProgress = controlProgress
                    add(
                        StopConstraint(
                            ConstraintKey("mission", "stop/${mission.revision}/${leg.index}"),
                            (targetDistance - stopProgress - point.offsetMetersBefore)
                                .coerceAtLeast(0.0),
                            stoppingMarginMeters = stationMargin
                        )
                    )
                }
            }
        }
        constraintManager.replace(MISSION_OWNER, constraints)
    }

    private fun updateStationCallConstraints(scan: ScanResult) {
        val active = activeStationCall
        if (active == null || active.departed) {
            stationPositionErrorMeters = null
            constraintManager.remove(STATION_OWNER)
            return
        }
        val resolved = active.resolved
        check(activeMission == null || activeMission?.revision == resolved.call.missionRevision ||
            active.departureGranted
        ) {
            "active StationCall revision does not match the active Mission before departure"
        }
        val berth = worldData.stations.berth(resolved.berthRef)
        check(berth != null && berth.lifecycleState == RailObjectLifecycleState.ACTIVE &&
            worldData.railObjects.get(berth.ref) === berth
        ) {
            "active StationBerth disappeared: ${resolved.berthRef}"
        }

        val directProjection = active.missionLayout.project(
            scan.perception.train.head,
            active.projectedProgressMeters
        )
        active.projectedProgressMeters = when {
            directProjection != null -> directProjection
            active.projectedProgressMeters != null &&
                active.projectedProgressMeters!! >=
                active.missionTargetProgressMeters -
                    (resolved.positionToleranceMeters + POSITION_EPSILON_METERS) ->
                active.projectedProgressMeters!! + lastTravelDistanceMeters
            else -> error("train head left the StationCall Mission route before its berth: ${resolved.berthRef}")
        }
        val positionError = active.missionTargetProgressMeters -
            requireNotNull(active.projectedProgressMeters) - resolved.stoppingOffsetMetersBefore
        stationPositionErrorMeters = positionError
        if (!active.approachingReported) {
            active.approachingReported = true
            reportStationCall(active, StationCallNotificationType.APPROACHING, positionError)
        }
        val effectivePositionTolerance = resolved.positionToleranceMeters + POSITION_EPSILON_METERS
        check(active.berthed || positionError >= -effectivePositionTolerance) {
            "train passed StationBerth stopping position before BERTHED: ${resolved.berthRef}; " +
                "error=$positionError"
        }
        if (!active.berthed && StationBerthDetector.isBerthed(
                headPositionErrorMeters = positionError,
                speedMetersPerSecond = activeSpeed,
                positionToleranceMeters = effectivePositionTolerance,
                stoppedSpeedThresholdMetersPerSecond = safetyProfile.stoppedSpeedThresholdMetersPerSecond
            )
        ) {
            active.berthed = true
            reportStationCall(active, StationCallNotificationType.BERTHED, positionError)
        }
        if (active.departureGranted && positionError < -effectivePositionTolerance) {
            active.departed = true
            reportStationCall(active, StationCallNotificationType.DEPARTED, positionError)
            constraintManager.remove(STATION_OWNER)
            activeStationCall = null
            activeCurrentStationCall = null
            return
        }
        val constraints = when {
            active.berthed && !active.departureGranted -> listOf(
                DepartureHoldConstraint(
                    ConstraintKey("station", "departure-hold/${resolved.call.callId}")
                )
            )
            active.departureGranted -> emptyList()
            else -> listOf(
                StopConstraint(
                    ConstraintKey("station", "stop/${resolved.call.callId}"),
                    positionError.coerceAtLeast(0.0),
                    stoppingMarginMeters = 2.0 * minOf(
                        resolved.positionToleranceMeters,
                        safetyProfile.stopToleranceMeters
                    )
                )
            )
        }
        constraintManager.replace(STATION_OWNER, constraints)
    }

    private fun publishRuntimeDiagnostics() {
        val permittedSpeed = constraintManager.permittedSpeedMetersPerSecond(
            currentSpeedMetersPerSecond = activeSpeed,
            maximumSpeedMetersPerSecond = actuator.maximumSpeedMetersPerSecond,
            profile = safetyProfile
        )
        val descriptions = constraintManager.snapshot().flatMap { (owner, constraints) ->
            constraints.map { constraint ->
                val limit = constraint.permittedSpeedMetersPerSecond(activeSpeed, safetyProfile)
                "${owner.type}/${owner.id}:${constraint.key.namespace}/${constraint.key.value}" +
                    "=${constraint}; permitted=$limit"
            }
        }
        val station = activeStationCall
        activeRuntimeDiagnostics = SimpleAtoRuntimeDiagnostics(
            missionRevision = activeMission?.revision,
            odometerProgressMeters = missionProgressMeters,
            projectedProgressMeters = missionProjectedProgressMeters,
            movementAuthorityRevision = movementAuthorityRevision,
            movementAuthorityLimitMeters = movementAuthorityLimitMeters,
            activeStationCallId = station?.resolved?.call?.callId,
            stationTargetProgressMeters = station?.missionTargetProgressMeters,
            stationPositionErrorMeters = stationPositionErrorMeters,
            permittedSpeedMetersPerSecond = permittedSpeed,
            constraints = descriptions
        )
    }

    private fun reportStationCall(
        active: ActiveStationCall,
        type: StationCallNotificationType,
        positionErrorMeters: Double
    ) {
        val resolved = active.resolved
        pendingStationNotifications.add(
            StationCallNotification(
                type = type,
                callId = resolved.call.callId,
                missionRevision = resolved.call.missionRevision,
                trainId = train.id,
                stationId = resolved.call.stationId,
                berthRef = resolved.berthRef,
                physicsEpoch = perceptionEpoch,
                speedMetersPerSecond = activeSpeed,
                headPositionErrorMeters = positionErrorMeters
            )
        )
    }

    private fun updateProviderConstraints(scan: ScanResult) {
        val provider = activeProvider
        if (provider == null) {
            constraintManager.remove(PROVIDER_OWNER)
            return
        }
        val trainSnapshot = scan.perception.train
        val values = provider.constraints(AtoProviderContext(trainSnapshot, scan.route, scan.perception))
        constraintManager.replace(PROVIDER_OWNER, values)
    }

    private fun processProviderBoundaries(scan: ScanResult) {
        scan.perception.observations.forEach { observation ->
            if (observation.transition != ObservationTransition.PASS) return@forEach
            val boundary = scan.eventObjectsByRef[observation.subject.ref]?.railObject as? AtoProviderBoundary
                ?: return@forEach
            if (boundary.direction != null && boundary.direction != observation.subject.position.facing) return@forEach
            when (boundary.action) {
                ProviderBoundaryAction.ACTIVATE -> switchProvider(boundary.providerId)
                ProviderBoundaryAction.DEACTIVATE -> if (activeProvider?.id == boundary.providerId) switchProvider(null)
            }
        }
    }

    private fun switchProvider(providerId: String?) {
        constraintManager.remove(PROVIDER_OWNER)
        activeProvider = providerId?.let { requestedId ->
            worldData.atoProviders.create(requestedId, train).also { provider ->
                check(provider.id == requestedId) {
                    "ATO provider factory '$requestedId' returned '${provider.id}'"
                }
            }
        }
    }

    private fun scanRouteAndObjects(): ScanResult {
        val head = train.headLocation as? RailLocationWithFacing
            ?: error("SimpleATO requires a facing-aware train head location")
        val horizon = brakingDistance(
            actuator.maximumSpeedMetersPerSecond,
            safetyProfile.serviceDecelerationMetersPerSecondSquared
        ) + actuator.maximumSpeedMetersPerSecond * safetyProfile.reactionTimeSeconds +
            safetyProfile.safetyDistanceMeters + 10.0
        val segments = head.peekCurve(horizon).toList()
        check(segments.isNotEmpty()) { "SimpleATO cannot build a route from the train head" }

        val routeKey = segments.map { it.railView.rail.id to it.railView.viewDirection }
        if (routeKey != previousRouteKey) {
            routeRevision++
            previousRouteKey = routeKey
        }
        val route = RouteSnapshot(
            routeId = "simple-ato/${train.id}",
            revision = routeRevision,
            segments = segments.map { segment ->
                RouteSegmentSnapshot(
                    segment.railView.rail.id,
                    segment.railView.viewDirection,
                    segment.startArcLength,
                    segment.endArcLength
                )
            }
        )

        val current = collectObserved(segments)
        current.forEach { (ref, observed) ->
            val previous = previousObserved[ref] ?: return@forEach
            check(previous.railObject === observed.railObject ||
                previous.railObject.lifecycleState != RailObjectLifecycleState.ACTIVE
            ) {
                "duplicate RailObjectRef $ref refers to distinct live objects across perception epochs"
            }
        }
        val observations = ArrayList<RailObservationSnapshot>(current.size + previousObserved.size)
        current.values.forEach { observed ->
            observations += RailObservationSnapshot(
                subject = observed.snapshot,
                transition = if (observed.snapshot.ref in previousObserved) {
                    ObservationTransition.UPDATE
                } else {
                    ObservationTransition.ENTER
                },
                routeDistanceMeters = observed.distanceMeters,
                relativeSpeedMetersPerSecond = observed.relativeSpeedMetersPerSecond
            )
        }
        previousObserved.forEach { (ref, observed) ->
            if (ref in current) return@forEach
            val transition = if (observed.distanceMeters <= lastTravelDistanceMeters + 1.0e-9) {
                ObservationTransition.PASS
            } else {
                ObservationTransition.EXIT
            }
            observations += RailObservationSnapshot(
                observed.snapshot,
                transition,
                observed.distanceMeters,
                observed.relativeSpeedMetersPerSecond
            )
        }
        observations.sortBy { it.routeDistanceMeters }

        val trainSnapshot = TrainSnapshot.capture(train, perceptionEpoch, head.facing)
        val perception = PerceptionSnapshot(perceptionEpoch, trainSnapshot, route, observations)
        val allObserved = previousObserved + current
        previousObserved = current
        return ScanResult(route, segments, perception, current, allObserved)
    }

    private fun collectObserved(segments: List<RailSegment>): Map<RailObjectRef, Observed> {
        val result = LinkedHashMap<RailObjectRef, Observed>()
        var distanceToSegment = 0.0
        segments.forEach { segment ->
            val view = segment.railView
            val objects = buildList<RailObject> {
                addAll(view.staticRailObjects)
                addAll(view.dynamicRailObjects)
                addAll(worldData.railMap.destinationsOn(view))
                addAll(worldData.railObjects.objectsOnRail(view.rail.id))
            }
            objects.forEach objectLoop@{ railObject ->
                if (railObject.lifecycleState != RailObjectLifecycleState.ACTIVE) return@objectLoop
                val trainEnd = railObject as? TrainEnd
                val otherTrain = trainEnd?.trainOrNull
                if (otherTrain?.id == train.id) return@objectLoop
                val objectArc = railObject.location.arcLengthIn(view.viewDirection)
                if (objectArc + safetyProfile.stopToleranceMeters < segment.startArcLength ||
                    objectArc > segment.endArcLength
                ) return@objectLoop
                val distance = distanceToSegment + (objectArc - segment.startArcLength).coerceAtLeast(0.0)
                val observed = Observed(
                    railObject = railObject,
                    snapshot = RailObjectSnapshot.capture(railObject, view.viewDirection),
                    distanceMeters = distance,
                    relativeSpeedMetersPerSecond = otherTrain?.let {
                        val speedAlongRoute = if (trainEnd.location.facing == view.viewDirection) it.speed else 0.0
                        speedAlongRoute - activeSpeed
                    }
                )
                val existing = result[railObject.ref]
                check(existing == null || existing.railObject === railObject) {
                    "duplicate RailObjectRef ${railObject.ref} refers to distinct live objects"
                }
                if (existing == null || observed.distanceMeters < existing.distanceMeters) {
                    result[railObject.ref] = observed
                }
            }
            distanceToSegment += segment.totalArcLength
        }
        return result
    }

    override fun capturePublishedControllerState(): Any = SimpleAtoControllerPublishedState(
        preparedReverseRevision = activePreparedReverseRevision,
        runtimeDiagnostics = activeRuntimeDiagnostics.copy(
            constraints = java.util.List.copyOf(activeRuntimeDiagnostics.constraints)
        ),
        currentMission = activeMission,
        currentConfirmedRouteWindows = activeConfirmedRouteWindows,
        currentStationCall = activeCurrentStationCall
    )

    override fun afterPhysicsStatePublished() {
        if (pendingStationNotifications.isEmpty()) return
        publishedStationNotifications.addAll(pendingStationNotifications)
        pendingStationNotifications.clear()
    }

    private fun publishConfirmedRouteWindows() {
        activeConfirmedRouteWindows = java.util.Collections.unmodifiableList(
            ArrayList(confirmedRouteWindows.values)
        )
    }

    private fun brakingDistance(speed: Double, deceleration: Double): Double =
        speed * speed / (2.0 * deceleration)

    private companion object {
        val INITIAL_RUNTIME_DIAGNOSTICS = SimpleAtoRuntimeDiagnostics(
            missionRevision = null,
            odometerProgressMeters = 0.0,
            projectedProgressMeters = null,
            movementAuthorityRevision = -1L,
            movementAuthorityLimitMeters = 0.0,
            activeStationCallId = null,
            stationTargetProgressMeters = null,
            stationPositionErrorMeters = null,
            permittedSpeedMetersPerSecond = 0.0,
            constraints = emptyList()
        )
        val INITIAL_CONTROLLER_STATE = SimpleAtoControllerPublishedState(
            preparedReverseRevision = 0L,
            runtimeDiagnostics = INITIAL_RUNTIME_DIAGNOSTICS,
            currentMission = null,
            currentConfirmedRouteWindows = emptyList(),
            currentStationCall = null
        )
        val LINE_SPEED_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:line-speed")
        val ROUTE_END_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:route-end")
        val FOLLOW_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:following")
        val EXTERNAL_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:simple-api")
        val PROVIDER_OWNER = ConstraintOwner(ConstraintOwnerType.SIGNALLING, "basic:active-provider")
        val MISSION_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:mission")
        val STATION_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:station-call")
        const val POSITION_EPSILON_METERS = 1.0e-3
    }
}
