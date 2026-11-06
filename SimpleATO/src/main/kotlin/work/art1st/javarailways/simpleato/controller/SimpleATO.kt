package work.art1st.javarailways.simpleato.controller

import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.api.ato.AbstractATO
import work.art1st.javarailways.simpleato.api.ato.AtoPlanningContext
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.simpleato.control.*
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.simpleato.api.ato.MinimumConstraintPlanner
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.basic.control.DynamicsProfileSource
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.basic.api.ato.command.CommandOfferResult
import work.art1st.javarailways.basic.api.ato.command.RevisionedCommandQueue
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.ConstraintKey
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwnerType
import work.art1st.javarailways.simpleato.api.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.DepartureHoldConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.simpleato.api.constraint.FollowConstraint
import work.art1st.javarailways.simpleato.api.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.StopConstraint
import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver
import work.art1st.javarailways.simpleato.api.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.basic.api.ato.snapshot.ObservationTransition
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RailObservationSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.station.ResolvedStationCall
import work.art1st.javarailways.simpleato.api.station.StationBerthDetector
import work.art1st.javarailways.simpleato.api.station.StationCallNotification
import work.art1st.javarailways.simpleato.api.station.StationCallNotificationType
import work.art1st.javarailways.simpleato.api.station.StationCallRequest
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionFailure
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolver
import work.art1st.javarailways.simpleato.api.station.StationCallSubmissionResult
import work.art1st.javarailways.simpleato.timetable.LineMaximumSpeedResolver
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.simpleato.routing.MissionRouteLayout
import work.art1st.javarailways.simpleato.routing.RouteAllocationTiming
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.train.TrainEnd
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionConstraintAdapter
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.inspection.entryClearanceState
import work.art1st.javarailways.simpleato.api.inspection.InspectionClearanceSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearanceState
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
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
    val perception: PerceptionSnapshot?,
    val perceptionHorizonMeters: Double,
    val preparedReverseRevision: Long,
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics,
    val currentMission: AtoMission?,
    val currentStationCall: ResolvedStationCall?,
    val inspectedRoute: FrozenRouteBinding?
)

private data class SimpleAtoControllerPublishedState(
    val perception: PerceptionSnapshot?,
    val perceptionHorizonMeters: Double,
    val preparedReverseRevision: Long,
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics,
    val currentMission: AtoMission?,
    val currentStationCall: ResolvedStationCall?,
    val inspectedRoute: FrozenRouteBinding?
)

/** Automatic controller with line-speed, fixed-stop, following, and external restrictive signal inputs. */
class SimpleATO(
    train: Train,
    world: World,
    actuator: TrainActuator,
    safetyProfile: AtoSafetyProfile = AtoSafetyProfile(),
    private val inspectionMaximumLeadTimeSeconds: (String) -> Double
) : AbstractATO(train, world, actuator, safetyProfile, world.simpleAtoWorldData.atoCoordinator), DynamicsProfileSource {
    override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? =
        (actuator as? DynamicsProfileSource)?.dynamicsProfileFor(car)
    override fun dynamicsDeviceStateFor(car: Car): TrainDynamics.CarDeviceState? =
        (actuator as? DynamicsProfileSource)?.dynamicsDeviceStateFor(car)

    private sealed interface Command {
        data class SetSpeedLimit(val key: String, val speed: Double) : Command
        data class StopAt(val key: String, val target: RailObjectRef, val offsetMeters: Double) : Command
        data class Remove(val key: String) : Command
        data class SetMovementAuthority(val missionRevision: Long, val limitDistanceMeters: Double) : Command
        data class HoldForMovementAuthority(val missionRevision: Long) : Command
        data class GrantStationDeparture(val callId: String, val missionRevision: Long) : Command
        data class RetireFormationMission(val throughMissionRevision: Long) : Command
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

    private data class RestrictionRoute(
        val observationStamp: RailwayStateStamp,
        val binding: FrozenRouteBinding,
        val frozenRoute: FrozenMissionRoute,
        val missionRevision: Long,
        val headDistanceFromRouteStartMeters: Double,
        val missionHeadProgressAtSelectionMeters: Double,
        val signalSnapshots: TrainRestrictionSnapshotProvider,
        val inspectionBinding: InspectionRuntime.TrainBinding
    )

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
        val perceptionHorizonMeters: Double,
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
    private val trainRestrictionAdapter = TrainRestrictionConstraintAdapter(train.id, constraintManager)
    private var trainInspection: InspectionRuntime.TrainInspection? = null
    private var inspectedRoute: FrozenRouteBinding? = null
    private val speedLimits = LinkedHashMap<String, Double>()
    private val stopTargets = LinkedHashMap<String, StopTarget>()
    private var activeInfrastructureLimitMetersPerSecond: Double? = null
    private var infrastructureLimitInitialized = false
    private var infrastructureLimitRailId: java.util.UUID? = null
    private var infrastructureLimitDirection: work.art1st.javarailways.core.api.physics.rail.ViewDirection? = null
    private var previousObserved: Map<RailObjectRef, Observed> = emptyMap()
    private var currentSegments: List<RailSegment> = emptyList()
    private var routeRevision = 0L
    private var previousRouteKey: List<Pair<java.util.UUID, work.art1st.javarailways.core.api.physics.rail.ViewDirection>> = emptyList()
    private var perceptionEpoch = 0L
    private var activePerceptionHorizonMeters = 0.0
    private var activeBrakingEnvelope: GradeAwareBrakingEnvelope? = null
    private var missionProgressMeters = 0.0
    private var missionRouteLayout: MissionRouteLayout? = null
    private var missionProjectedProgressMeters: Double? = null

    private var activePreparedReverseRevision: Long = 0L
    private var movementAuthorityRevision = -1L
    private var movementAuthorityLimitMeters = 0.0
    private var hasMovementAuthority = false
    private var activeStationCall: ActiveStationCall? = null
    private var stationPositionErrorMeters: Double? = null

    private var activeRuntimeDiagnostics = INITIAL_RUNTIME_DIAGNOSTICS
        private set

    private var activeMission: AtoMission? = null
    private var activeCurrentStationCall: ResolvedStationCall? = null

    @Volatile
    private var restrictionRoute: RestrictionRoute? = null
    private var activeContactAuthority: work.art1st.javarailways.basic.api.route.CouplingContactAuthority? = null

    val publishedState: SimpleAtoPublishedState
        get() {
            val state = publishedAtoState()
            val controller = state.controllerState as? SimpleAtoControllerPublishedState
                ?: INITIAL_CONTROLLER_STATE
            return SimpleAtoPublishedState(
                trainSnapshot = state.trainSnapshot,
                lifetimeTravelDistanceMeters = state.lifetimeTravelDistanceMeters,
                perception = controller.perception,
                perceptionHorizonMeters = controller.perceptionHorizonMeters,
                preparedReverseRevision = controller.preparedReverseRevision,
                runtimeDiagnostics = controller.runtimeDiagnostics,
                currentMission = controller.currentMission,
                currentStationCall = controller.currentStationCall,
                inspectedRoute = controller.inspectedRoute
            )
        }

    val preparedReverseRevision: Long get() = publishedState.preparedReverseRevision
    val runtimeDiagnostics: SimpleAtoRuntimeDiagnostics get() = publishedState.runtimeDiagnostics
    val currentMission: AtoMission? get() = publishedState.currentMission
    val currentStationCall: ResolvedStationCall? get() = publishedState.currentStationCall

    /** Publisher used by the one resource-admission client attached to this controller. */
    internal val trainRestrictionPublisher: TrainRestrictionPublisher =
        TrainRestrictionPublisher(trainRestrictionAdapter::publish)

    /** Installs the Dispatcher-owned mapping from Mission progress to one frozen admission route. */
    @MainThread
    internal fun bindTrainRestrictionRoute(
        observationStamp: RailwayStateStamp,
        route: FrozenMissionRoute,
        missionRevision: Long,
        signalSnapshots: TrainRestrictionSnapshotProvider,
        inspectionBinding: InspectionRuntime.TrainBinding = world.simpleAtoWorldData.inspectionRuntime.bindingFor(train.id)
    ) {
        restrictionRoute = RestrictionRoute(
            observationStamp = observationStamp,
            binding = FrozenRouteBinding.from(route.selection.route),
            frozenRoute = route,
            missionRevision = missionRevision,
            headDistanceFromRouteStartMeters = route.headDistanceFromRouteStartMeters,
            missionHeadProgressAtSelectionMeters = route.missionHeadProgressAtSelectionMeters,
            signalSnapshots = signalSnapshots,
            inspectionBinding = inspectionBinding
        )
    }

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
    fun setMovementAuthority(missionRevision: Long, limitDistanceMeters: Double): Long {
        require(missionRevision >= 0L)
        require(limitDistanceMeters.isFinite() && limitDistanceMeters >= 0.0)
        return commands.enqueue(
            Command.SetMovementAuthority(missionRevision, limitDistanceMeters)
        ).revision
    }

    /** Hold for permission without inventing a physical endpoint at the mission origin. */
    @MainThread
    fun holdForMovementAuthority(missionRevision: Long): Long {
        require(missionRevision >= 0L)
        return commands.enqueue(Command.HoldForMovementAuthority(missionRevision)).revision
    }

    fun serviceBrakingDistanceMeters(speedMetersPerSecond: Double): Double {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        return brakingDistance(
            speedMetersPerSecond,
            safetyProfile.serviceDecelerationMetersPerSecondSquared
        )
    }

    /** Host-owned current-speed stopping lookahead used by inspection activation. */
    internal fun inspectionDynamicLookaheadMeters(speedMetersPerSecond: Double): Double {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        return serviceBrakingDistanceMeters(speedMetersPerSecond) +
            speedMetersPerSecond * safetyProfile.reactionTimeSeconds +
            safetyProfile.safetyDistanceMeters
    }

    /**
     * Stable low-water mark for rolling route admission.
     *
     * This deliberately uses the train's maximum speed rather than its current speed. A current-
     * speed threshold retreats while the authority constraint brakes the train, which can leave a
     * slow or line-limited train stopped just short of the committed boundary without requesting
     * the next window. The startup reserve also keeps a very slow train eligible when it stops
     * slightly before the nominal safety margin rather than precisely on that mathematical point.
     */
    internal fun routeAllocationTriggerDistanceMeters(
        timing: RouteAllocationTiming
    ): Double = maxOf(
        timing.triggerDistanceMeters(actuator.maximumSpeedMetersPerSecond,
            safetyProfile.serviceDecelerationMetersPerSecondSquared),
        inspectionDynamicLookaheadMeters(actuator.maximumSpeedMetersPerSecond),
        safetyProfile.safetyDistanceMeters + ROUTE_ALLOCATION_RESERVE_METERS
    )

    /**
     * Resource-request window, distinct from the longer shared perception scan.
     *
     * Use speed from the Dispatcher's captured publication, not mutable physics state. The
     * reserve permits starting and continuing from a stop at the global authority margin. The
     * planner batches small advances and never grows this window from the committed frontier,
     * so a stationary train does not progressively reserve its entire route.
     */
    internal fun routeAllocationRequestHorizonMeters(
        timing: RouteAllocationTiming,
        speedMetersPerSecond: Double
    ): Double = minOf(
        maxOf(
            timing.triggerDistanceMeters(speedMetersPerSecond,
                safetyProfile.serviceDecelerationMetersPerSecondSquared),
            inspectionDynamicLookaheadMeters(speedMetersPerSecond)
        ) + ROUTE_ALLOCATION_RESERVE_METERS,
        routePerceptionHorizonMeters()
    )

    /**
     * Distance covered by the controller's one shared forward perception.
     *
     * Resource admission has a shorter current-motion window within this scan; neither its
     * window calculation nor the rolling planner performs another route scan.
     */
    internal fun routePerceptionHorizonMeters(
        maximumDeclaredInspectionLeadTimeSeconds: Double =
            inspectionMaximumLeadTimeSeconds(train.id)
    ): Double {
        val controllerHorizon = brakingDistance(
            actuator.maximumSpeedMetersPerSecond,
            safetyProfile.serviceDecelerationMetersPerSecondSquared
        ) + actuator.maximumSpeedMetersPerSecond * safetyProfile.reactionTimeSeconds +
            safetyProfile.safetyDistanceMeters + ROUTE_PERCEPTION_MARGIN_METERS
        return maxOf(
            controllerHorizon,
            inspectionLeadHorizonMeters(maximumDeclaredInspectionLeadTimeSeconds)
        )
    }

    internal fun inspectionLeadHorizonMeters(leadTimeSeconds: Double): Double {
        require(leadTimeSeconds.isFinite() && leadTimeSeconds >= 0.0) {
            "leadTimeSeconds must be finite and non-negative"
        }
        return (actuator.maximumSpeedMetersPerSecond * leadTimeSeconds).also { distance ->
            require(distance.isFinite()) { "inspection lead-time horizon must be finite" }
        }
    }

    internal val inspectionMaximumSpeedMetersPerSecond: Double
        get() = actuator.maximumSpeedMetersPerSecond

    internal val inspectionAccelerationMetersPerSecondSquared: Double
        get() = safetyProfile.accelerationMetersPerSecondSquared

    fun isStopped(speedMetersPerSecond: Double): Boolean {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        return speedMetersPerSecond == 0.0
    }

    val missionStopToleranceMeters: Double
        get() = safetyProfile.stopToleranceMeters

    internal val authorityStoppingMarginMeters: Double get() = safetyProfile.safetyDistanceMeters

    fun terminalMovementAuthorityLimitMeters(routeEndDistanceMeters: Double, stationTerminal: Boolean = false): Double {
        require(routeEndDistanceMeters.isFinite() && routeEndDistanceMeters >= 0.0)
        return routeEndDistanceMeters + if (stationTerminal) AtoSafetyProfile.STATION_AUTHORITY_MARGIN_METERS else safetyProfile.safetyDistanceMeters
    }

    private fun terminalAuthorityMarginMeters(routeEndDistanceMeters: Double): Double {
        val mission = activeMission ?: return safetyProfile.safetyDistanceMeters
        val stop = mission.legs.lastOrNull()?.point as? AtoMissionPoint.Stop ?: return safetyProfile.safetyDistanceMeters
        return if (stop.stationStop || activeStationCall?.let { call ->
            call.resolved.call.missionRevision == mission.revision &&
                kotlin.math.abs(call.missionTargetProgressMeters - call.resolved.stoppingOffsetMetersBefore -
                    (routeEndDistanceMeters - stop.offsetMetersBefore)) <= 1.0e-6
        } == true) AtoSafetyProfile.STATION_AUTHORITY_MARGIN_METERS else safetyProfile.safetyDistanceMeters
    }

    /** Retire consumed pre-formation movement without replacing the controller or its restrictions. */
    fun retireMissionForFormation(): Long {
        val physical = train as? work.art1st.javarailways.core.physics.train.CoreTrain
            ?: error("formation retirement requires a Core train")
        physical.world.physicsCoordinator.ensureBetweenFrames("SimpleATO.retireMissionForFormation")
        check(physical.world.getTrain(train.id) === physical && physical.controller === this &&
            physical.hasFormationMovementHold) { "formation retirement requires the current structurally held controller" }
        val frame = physical.publishedFrame
        check(!frame.derailed && frame.speedMetersPerSecond == 0.0 &&
            (frame.headTraversal + frame.tailTraversal).none { it.startArcLengthInAbMeters != it.endArcLengthInAbMeters } &&
            frame.cars.all { car -> (car.bogies + car.state).all {
                it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
            } }) { "formation retirement requires stopped physical publication" }
        check(activeStationCall == null && stationCallCommands.pendingCount == 0) {
            "formation retirement cannot discard an active or queued StationCall"
        }
        return commands.enqueue(Command.RetireFormationMission(missionCommands.latestAcceptedRevision)).revision
    }

    fun prepareForReverse(callId: String?, missionRevision: Long?): Long {
        require((callId == null) == (missionRevision == null))
        return commands.enqueue(Command.PrepareReverse(callId, missionRevision)).revision
    }

    fun setLineMaximumSpeed(
        key: String,
        resolver: LineMaximumSpeedResolver,
        service: TimetableService
    ): Long = resolver.resolve(service)?.let { setSpeedLimit(key, it) } ?: removeSpeedLimit(key)

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

    override fun processPendingCommands() {
        missionCommands.drain().lastOrNull()?.command?.let { mission ->
            (mission.origin as? work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin.Physical)?.let { origin ->
                check(train.publishedFrame?.head == origin.position) { "formation Mission origin changed before activation" }
            }
            constraintManager.remove(MISSION_OWNER)
            missionProgressMeters = 0.0
            missionRouteLayout = MissionRouteLayout(world, mission)
            missionProjectedProgressMeters = null
            movementAuthorityRevision = mission.revision
            movementAuthorityLimitMeters = 0.0
            hasMovementAuthority = false
            activeMission = mission
        }
        stationCallCommands.drain().lastOrNull()?.command?.let { resolved ->
            check(activeMission?.revision == resolved.call.missionRevision) {
                "StationCall revision ${resolved.call.missionRevision} does not match active Mission " +
                    "${activeMission?.revision}"
            }
            constraintManager.remove(STATION_OWNER)
            val berth = requireNotNull(world.simpleAtoWorldData.stations.berth(resolved.berthRef)) {
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
                        work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
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
                    TrainSnapshot.capture(train, perceptionEpoch).head
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
                is Command.HoldForMovementAuthority -> {
                    val activeRevision = activeMission?.revision ?: -1L
                    when {
                        command.missionRevision == activeRevision -> {
                            movementAuthorityRevision = command.missionRevision
                            hasMovementAuthority = false
                        }
                        command.missionRevision > activeRevision -> error(
                            "movement authority revision ${command.missionRevision} is newer than active Mission $activeRevision"
                        )
                    }
                }
                is Command.SetMovementAuthority -> {
                    val activeRevision = activeMission?.revision ?: -1L
                    when {
                        command.missionRevision == activeRevision -> {
                            movementAuthorityRevision = command.missionRevision
                            movementAuthorityLimitMeters = command.limitDistanceMeters
                            hasMovementAuthority = true
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
                is Command.RetireFormationMission -> {
                    // Mission submissions drain before generic commands. A later output mission
                    // must survive a queued retirement of its consumed predecessor.
                    if ((activeMission?.revision ?: -1L) <= command.throughMissionRevision) {
                        check(activeStationCall == null) { "formation retirement cannot discard an active StationCall" }
                        activeMission = null
                        missionRouteLayout = null
                        missionProjectedProgressMeters = null
                        missionProgressMeters = 0.0
                        movementAuthorityRevision = -1L
                        movementAuthorityLimitMeters = 0.0
                        constraintManager.remove(MISSION_OWNER)
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
        activeContactAuthority = null
        perceptionEpoch++
        missionProgressMeters += lastTravelDistanceMeters
        val restriction = restrictionRoute
        val hasClearanceRequirements = restriction?.inspectionBinding?.entryClearances?.sourceIds?.isNotEmpty() == true
        val frame = if (hasClearanceRequirements) train.publishedFrame else null
        missionRouteLayout?.let { layout -> frame?.let {
            missionProjectedProgressMeters = projectMissionProgress(layout, it.head)
        } }
        val progress = if (hasClearanceRequirements) restriction.let(::restrictionRouteProgress) else null
        val nominalHorizon = routePerceptionHorizonMeters(
            restriction?.inspectionBinding?.maximumDeclaredLeadTimeSeconds
                ?: inspectionMaximumLeadTimeSeconds(train.id)
        )
        val frameLength = if (hasClearanceRequirements) restriction.let { it.inspectionBinding.currentTrainLength(
            frame, train.id, restrictionStamp(it).physicsEpoch,
            RailPositionSnapshot.capture(train.headLocation, (train.headLocation as RailLocationWithFacing).facing)) }
        else null
        val clearances = if (restriction != null && progress != null)
            restriction.inspectionBinding.entryClearances.between(progress, progress + nominalHorizon,
                frameLength, safetyProfile.safetyDistanceMeters)
        else emptyList()
        val horizon = clearances.maxOfOrNull { clearance ->
            val end = clearance.requirement.tailClearRouteDistanceMeters + (frameLength ?: 0.0) +
                safetyProfile.stopToleranceMeters + ROUTE_PERCEPTION_MARGIN_METERS
            if (end.isFinite()) maxOf(nominalHorizon, end - checkNotNull(progress)) else nominalHorizon
        } ?: nominalHorizon
        val scan = scanRouteAndObjects(horizon)
        missionRouteLayout?.let { layout ->
            missionProjectedProgressMeters = projectMissionProgress(layout, scan.perception.train.head)
        }
        latestRouteSnapshot = scan.route
        latestPerceptionSnapshot = scan.perception
        activePerceptionHorizonMeters = scan.perceptionHorizonMeters
        currentSegments = scan.segments
        val gradeSegments = buildBrakingGradeSegments(scan.segments)
        activeBrakingEnvelope = if (gradeSegments.isEmpty()) null else actuator.brakingEnvelope(gradeSegments, deltaTimeSeconds, world.maximumPhysicsDeltaSeconds)

        val geometryLength = frameLength?.takeIf { frame?.head == scan.perception.train.head }
        updateTrainRestrictions(scan.perception, restriction, if (clearances.isNotEmpty()) horizon else null)
        synchronizeInfrastructureLimitWithHead()
        processSpeedLimitTransitions(scan)
        updateBuiltInConstraints(scan)
        updateMissionConstraints()
        updateStationCallConstraints(scan)
        constraintManager.remove(ENTRY_CLEARANCE_OWNER)
        if (restriction != null && hasClearanceRequirements) {
            val routeProgress = restrictionRouteProgress(restriction)
            val base = constraintManager.snapshot().values.flatten()
            val observedDistance = scan.segments.sumOf { it.totalArcLength }
            val holds = mutableListOf<AtoConstraint>()
            val statesBySource = linkedMapOf<String, MutableList<InspectionEntryClearanceState>>()
            clearances.asReversed().forEach { bound ->
                val state = entryClearanceState(bound.requirement, geometryLength, routeProgress,
                    restriction.binding.totalLengthMeters, observedDistance,
                    safetyProfile, base + holds)
                statesBySource.getOrPut(bound.sourceId, ::mutableListOf) += state
                if (!state.movementReady) holds += RouteSpeedLimitConstraint(
                    ConstraintKey("simpleato:entry-clearance", "${bound.sourceId}/${bound.requirement.declarationId}/${bound.requirement.entryRouteDistanceMeters}"),
                    (bound.requirement.entryRouteDistanceMeters - routeProgress).coerceAtLeast(0.0), 0.0)
            }
            constraintManager.replace(ENTRY_CLEARANCE_OWNER, holds)
            restriction.inspectionBinding.entryClearances.sourceIds.forEach { source ->
                restriction.inspectionBinding.runtime.publishClearance(InspectionClearanceSnapshot(
                    source, train.id, restrictionStamp(restriction), restriction.binding,
                    statesBySource[source].orEmpty().asReversed()))
            }
        }
        publishRuntimeDiagnostics()

        // Continue perception and invariant validation without a Mission, but never turn line-speed
        // limits into movement authority. Infrastructure constraints are upper bounds only.
        if (activeMission == null) return ControlDemand(0.0)

        return MinimumConstraintPlanner.plan(
            AtoPlanningContext(
                currentSpeedMetersPerSecond = activeSpeed,
                maximumSpeedMetersPerSecond = actuator.maximumSpeedMetersPerSecond,
                profile = safetyProfile,
                constraints = constraintManager,
                brakingEnvelope = activeBrakingEnvelope,
                brakingGradeSegments = gradeSegments,
                maximumControlDeltaSeconds = world.maximumPhysicsDeltaSeconds
            )
        )
    }

    override fun controlMissionRevision(): Long? = activeMission?.revision

    override fun motionControlFrame(demand: ControlDemand): TrainControlFrame {
        val grade = RouteGradeBounds.mostAdverseRouteGrade(currentSegments,
            actuator.controlCapabilities(activeSpeed).worstCurrentGrade)
        val snapshot = constraintManager.snapshot()
        val frame = controlFrame(snapshot, safetyProfile, grade)
        val limits = mutableListOf(MotionReferenceConstraint("vehicle/maximum", 0.0, actuator.maximumSpeedMetersPerSecond))
        val protection = mutableListOf(work.art1st.javarailways.simpleato.api.atp.AtpTarget(
            "vehicle/maximum", 0.0, actuator.maximumSpeedMetersPerSecond))
        if (activeMission == null) {
            limits += MotionReferenceConstraint("mission/absent", 0.0, 0.0)
        }
        railwayControlRuntime.registerProtectionSource(train) { delta -> refreshTransferredProtection(delta) }
        return frame.copy(referenceTargets = java.util.List.copyOf(frame.referenceTargets + limits),
            protectionTargets = java.util.List.copyOf(frame.protectionTargets + protection))
    }

    /**
     * Pulls signal values and recompiles every external restriction before this Tick plans motion.
     * The previous completed signal epoch is therefore consumed without waiting for Bukkit.
     */
    private fun restrictionStamp(route: RestrictionRoute): RailwayStateStamp =
        route.signalSnapshots.completedObservationStamp()
            ?.takeIf { it.physicsEpoch >= route.observationStamp.physicsEpoch } ?: route.observationStamp

    private fun projectMissionProgress(layout: MissionRouteLayout, head: RailPositionSnapshot): Double {
        layout.project(head, missionProjectedProgressMeters)?.let { return it }
        val selected = restrictionRoute?.takeIf { it.missionRevision == layout.mission.revision }
        val progress = selected?.frozenRoute?.missionProgressFromPhysicalHead(world.topologyState, head,
            missionProjectedProgressMeters ?: missionProgressMeters)
        check(progress != null) {
            "ATO train head $head left active Mission ${layout.mission.id}@${layout.mission.revision} " +
                "and its frozen physical route; previous progress=$missionProjectedProgressMeters"
        }
        // An operating stop is a target, not a physical graph boundary. Keep the measured error
        // when still on its real continuation; existing constraints and Basic grants remain limits.
        return progress
    }

    private fun restrictionRouteProgress(route: RestrictionRoute): Double =
        if (activeMission?.revision == route.missionRevision) {
            val missionProgress = requireNotNull(missionProjectedProgressMeters) {
                "active Mission has no projected progress for its frozen restriction route"
            }
            route.headDistanceFromRouteStartMeters + missionProgress - route.missionHeadProgressAtSelectionMeters
        } else route.binding.totalLengthMeters

    private fun updateTrainRestrictions(perception: PerceptionSnapshot, route: RestrictionRoute?, clearanceHorizon: Double? = null) {
        if (route == null) return
        val currentStamp = restrictionStamp(route)
        val routeProgress = restrictionRouteProgress(route)
        val inspection = trainInspection?.takeIf { it.runtime === route.inspectionBinding.runtime }
            ?: route.inspectionBinding.runtime.createTrainInspection(train.id).also { trainInspection = it }
        inspection.update(
            currentStamp, route.binding, routeProgress,
            maxOf(inspectionDynamicLookaheadMeters(perception.train.speedMetersPerSecond), clearanceHorizon ?: 0.0),
            inspectionMaximumSpeedMetersPerSecond, inspectionAccelerationMetersPerSecondSquared,
            perception, route.inspectionBinding
        )
        inspectedRoute = route.binding
        route.signalSnapshots.snapshotsFor(train.id).forEach { snapshot ->
            val result = trainRestrictionAdapter.publish(snapshot)
            check(result.acceptedRevision == snapshot.revision) {
                "signal restriction ${snapshot.sourceId}/${snapshot.revision} for " +
                    "${snapshot.trainId} was ${result.status}"
            }
        }
        trainRestrictionAdapter.apply(currentStamp, route.binding, routeProgress) { contact ->
            val mission = activeMission
            val authorityEnd = route.headDistanceFromRouteStartMeters + movementAuthorityLimitMeters -
                route.missionHeadProgressAtSelectionMeters
            mission != null && mission.revision == route.missionRevision &&
                movementAuthorityRevision == mission.revision &&
                contact.proof.request.approachingTrainId == train.id &&
                kotlin.math.abs(authorityEnd - contact.contactBoundary.distanceFromRouteStartMeters) <= 1.0e-6 &&
                route.frozenRoute.physicalHeadProgressMeters(world.topologyState,
                    contact.proof.contactHead, contact.contactBoundary.distanceFromRouteStartMeters)?.let {
                    kotlin.math.abs(it - contact.contactBoundary.distanceFromRouteStartMeters) <= 1.0e-6
                } == true && world.validatePublishedCouplingApproach(contact.proof)
        }
        activeContactAuthority = trainRestrictionAdapter.appliedContactAuthority
    }

    override fun validateBeforeMovement() {
        currentSegments.zipWithNext().forEach { (current, next) ->
            check(current.railView.activeRail === next.railView) {
                "turnout changed while ATO was planning at rail ${current.railView.rail.id}"
            }
        }
    }

    override fun constrainMovementDistance(requestedDistanceMeters: Double): Double =
        activeContactAuthority?.let { contact ->
            world.clampCouplingApproachDistance(contact.proof, requestedDistanceMeters)
        } ?: requestedDistanceMeters

    override fun constraintSnapshot(): Map<ConstraintOwner, List<AtoConstraint>> = constraintManager.snapshot()

    private fun updateBuiltInConstraints(scan: ScanResult) {
        val infrastructureLimits = buildList {
            activeInfrastructureLimitMetersPerSecond?.let { limit ->
                add(CurrentSpeedLimitConstraint(
                    key = ConstraintKey("basic", "infrastructure-current"),
                    maximumSpeedMetersPerSecond = minOf(limit, actuator.maximumSpeedMetersPerSecond)
                ))
            }
            scan.currentObservedByRef.values
                .sortedBy(Observed::distanceMeters)
                .forEach { observed ->
                    val signal = observed.railObject as? OperationalSpeedLimitSignal ?: return@forEach
                    val limit = signal.speedLimitMetersPerSecond ?: return@forEach
                    add(RouteSpeedLimitConstraint(
                        key = ConstraintKey("basic", "infrastructure-signal/${signal.ref.id}"),
                        distanceToStartMeters = observed.distanceMeters.coerceAtLeast(0.0),
                        maximumSpeedMetersPerSecond = minOf(limit, actuator.maximumSpeedMetersPerSecond)
                    ))
                }
        }
        constraintManager.replace(LINE_SPEED_OWNER, infrastructureLimits)

        val lastSegment = scan.segments.last()
        val observedRouteDistance = scan.segments.sumOf { it.totalArcLength }
        val reachesPhysicalRouteEnd = lastSegment.railView.activeRail == null &&
            lastSegment.endArcLength >=
            lastSegment.railView.curve.totalArcLength - safetyProfile.stopToleranceMeters
        val routeEnd = if (reachesPhysicalRouteEnd) {
            listOf(
                StopConstraint(
                    ConstraintKey("basic", "route-end/${lastSegment.railView.rail.id}"),
                    observedRouteDistance
                )
            )
        } else if (activeBrakingEnvelope != null) {
            // The nominal horizon is derived from flat-track braking. Treat its far edge as a
            // stop boundary so a known downhill cannot require more route than was inspected.
            listOf(
                StopConstraint(
                    ConstraintKey("basic", "perception-boundary"),
                    observedRouteDistance
                )
            )
        } else {
            emptyList()
        }
        constraintManager.replace(ROUTE_END_OWNER, routeEnd)

        val nearestTrain = scan.currentObservedByRef.values
            .asSequence()
            .filter { it.railObject is TrainEnd }
            .filterNot { observed ->
                val contact = activeContactAuthority ?: return@filterNot false
                val end = observed.railObject as TrainEnd
                val proof = contact.proof
                val targetIsHead = (proof.request.stationaryEnd ==
                    work.art1st.javarailways.core.api.physics.train.TrainEndSide.A) != proof.stationaryFrame.reversed
                end.trainOrNull?.id == proof.request.stationaryTrainId &&
                    end.type == if (targetIsHead) TrainEnd.Type.HEAD else TrainEnd.Type.TAIL
            }
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
                        observed.distanceMeters - target.offsetMeters
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
            val authorityKey = ConstraintKey("mission", "authority/${mission.revision}")
            val authorityRemaining = authorityLimit - controlProgress
            val contact = activeContactAuthority
            add(if (!hasMovementAuthority)
                CurrentSpeedLimitConstraint(authorityKey, 0.0)
            else if (contact != null)
                RouteSpeedLimitConstraint(authorityKey, authorityRemaining.coerceAtLeast(0.0), contact.maximumApproachSpeedMetersPerSecond)
            else MovementAuthorityConstraint(authorityKey, authorityRemaining,
                terminalAuthorityMarginMeters(mission.legs.sumOf { it.plannedRoute.expandedRoute.totalLength })))
            mission.legs.forEach { leg ->
                targetDistance += leg.plannedRoute.expandedRoute.totalLength
                val point = leg.point
                if (point is AtoMissionPoint.Stop) {
                    val stopProgress = controlProgress
                    val stopKey = ConstraintKey("mission", "stop/${mission.revision}/${leg.index}")
                    val stopTarget = targetDistance - point.offsetMetersBefore
                    val remaining = (stopTarget - stopProgress).coerceAtLeast(0.0)
                    // A validated contact ends through Core's physical contact clamp, at the
                    // admitted coupling speed. All other mission stops retain zero-speed targets.
                    add(if (contact != null && leg === mission.legs.last() &&
                        kotlin.math.abs(stopTarget - authorityLimit) <= 1.0e-6)
                        RouteSpeedLimitConstraint(stopKey, remaining, contact.maximumApproachSpeedMetersPerSecond)
                    else StopConstraint(stopKey, stopTarget - stopProgress, stoppingMarginMeters = 0.0, operatingTarget = true))
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
        val berth = world.simpleAtoWorldData.stations.berth(resolved.berthRef)
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
                speedMetersPerSecond = activeSpeed
            )
        ) {
            val holdingBrakeEstablished = serviceHoldingBrakeEstablished(
                actuator.controlState(activeSpeed),
                actuator.controlCapabilities(activeSpeed)
            )
            if (holdingBrakeEstablished) {
                active.berthed = true
                reportStationCall(active, StationCallNotificationType.BERTHED, positionError)
            }
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
                    positionError,
                    stoppingMarginMeters = 0.0,
                    operatingTarget = true
                )
            )
        }
        constraintManager.replace(STATION_OWNER, constraints)
    }

    private fun publishRuntimeDiagnostics() {
        val permittedSpeed = constraintManager.permittedSpeedMetersPerSecond(
            currentSpeedMetersPerSecond = activeSpeed,
            maximumSpeedMetersPerSecond = actuator.maximumSpeedMetersPerSecond,
            profile = safetyProfile,
            brakingEnvelope = activeBrakingEnvelope
        )
        val descriptions = constraintManager.snapshot().flatMap { (owner, constraints) ->
            constraints.map { constraint ->
                val limit = constraint.permittedSpeedMetersPerSecond(
                    activeSpeed,
                    safetyProfile,
                    activeBrakingEnvelope
                )
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

    private fun synchronizeInfrastructureLimitWithHead() {
        val head = train.headLocation as? RailLocationWithFacing
            ?: error("SimpleATO requires a facing-aware train head location")
        val railChanged = infrastructureLimitRailId != head.rail.id
        val directionChanged = infrastructureLimitDirection != head.facing
        if (!infrastructureLimitInitialized || railChanged || directionChanged) {
            activeInfrastructureLimitMetersPerSecond =
                head.rail.infrastructureMetadata.speedLimits.effectiveLimitMetersPerSecond()
            infrastructureLimitInitialized = true
            infrastructureLimitRailId = head.rail.id
            infrastructureLimitDirection = head.facing
        }
    }

    private fun processSpeedLimitTransitions(scan: ScanResult) {
        scan.perception.observations
            .filter { it.transition == ObservationTransition.PASS }
            .sortedBy(RailObservationSnapshot::routeDistanceMeters)
            .forEach { observation ->
                val signal = scan.eventObjectsByRef[observation.subject.ref]?.railObject
                    as? OperationalSpeedLimitSignal ?: return@forEach
                if (!signal.appliesTo(observation.subject.position.facing)) return@forEach
                activeInfrastructureLimitMetersPerSecond = signal.speedLimitMetersPerSecond
            }
    }

    private fun scanRouteAndObjects(horizon: Double): ScanResult {
        val topology = world.topologyState
        val head = train.headLocation as? RailLocationWithFacing
            ?: error("SimpleATO requires a facing-aware train head location")
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
            topologyRevision = topology.revision,
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

        val trainSnapshot = TrainSnapshot.capture(train, perceptionEpoch)
        val perception = PerceptionSnapshot(perceptionEpoch, trainSnapshot, route, observations)
        val allObserved = previousObserved + current
        previousObserved = current
        return ScanResult(route, segments, perception, horizon, current, allObserved)
    }

    private fun collectObserved(segments: List<RailSegment>): Map<RailObjectRef, Observed> {
        val result = LinkedHashMap<RailObjectRef, Observed>()
        var distanceToSegment = 0.0
        segments.forEach { segment ->
            val view = segment.railView
            val objects = buildList<RailObject> {
                addAll(view.staticRailObjects)
                addAll(view.dynamicRailObjects)
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

    private fun buildBrakingGradeSegments(segments: List<RailSegment>): List<BrakingGradeSegment> {
        val result = ArrayList<BrakingGradeSegment>()
        segments.forEachIndexed { segmentIndex, segment ->
            var start = segment.startArcLength
            while (start < segment.endArcLength - GRADE_ARC_EPSILON_METERS) {
                if (result.size >= MAX_BRAKING_GRADE_SEGMENTS - 1) {
                    var remainingLength = 0.0
                    var worstGradeValue = 1.0
                    segments.drop(segmentIndex).forEachIndexed { remainingIndex, remaining ->
                        val remainingStart = if (remainingIndex == 0) start else remaining.startArcLength
                        if (remaining.endArcLength > remainingStart) {
                            remainingLength += remaining.endArcLength - remainingStart
                            worstGradeValue = minOf(
                                worstGradeValue,
                                worstGrade(remaining, remainingStart, remaining.endArcLength)
                            )
                        }
                    }
                    if (remainingLength > 0.0) {
                        result += BrakingGradeSegment(remainingLength, worstGradeValue)
                    }
                    return result
                }
                val end = minOf(start + BRAKING_GRADE_SAMPLE_SPACING_METERS, segment.endArcLength)
                result += BrakingGradeSegment(end - start, worstGrade(segment, start, end))
                start = end
            }
        }
        return result
    }

    private fun worstGrade(segment: RailSegment, startArc: Double, endArc: Double): Double {
        val curve = segment.railView.curve
        val middleArc = (startArc + endArc) * 0.5
        return minOf(
            curve.getPointAt(startArc).tangent.y(),
            curve.getPointAt(middleArc).tangent.y(),
            curve.getPointAt(endArc).tangent.y()
        )
    }

    override fun capturePublishedControllerState(): Any = SimpleAtoControllerPublishedState(
        perception = latestPerceptionSnapshot,
        perceptionHorizonMeters = activePerceptionHorizonMeters,
        preparedReverseRevision = activePreparedReverseRevision,
        runtimeDiagnostics = activeRuntimeDiagnostics.copy(
            constraints = java.util.List.copyOf(activeRuntimeDiagnostics.constraints)
        ),
        currentMission = activeMission,
        currentStationCall = activeCurrentStationCall,
        inspectedRoute = inspectedRoute
    )

    override fun afterPhysicsStatePublished() {
        if (pendingStationNotifications.isEmpty()) return
        publishedStationNotifications.addAll(pendingStationNotifications)
        pendingStationNotifications.clear()
    }

    private fun brakingDistance(speed: Double, deceleration: Double): Double =
        speed * speed / (2.0 * deceleration)

    private companion object {
        const val ROUTE_ALLOCATION_RESERVE_METERS = 20.0
        const val ROUTE_PERCEPTION_MARGIN_METERS = 10.0
        const val BRAKING_GRADE_SAMPLE_SPACING_METERS = 10.0
        const val MAX_BRAKING_GRADE_SEGMENTS = 256
        const val GRADE_ARC_EPSILON_METERS = 1.0e-9
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
            perception = null,
            perceptionHorizonMeters = 0.0,
            preparedReverseRevision = 0L,
            runtimeDiagnostics = INITIAL_RUNTIME_DIAGNOSTICS,
            currentMission = null,
            currentStationCall = null,
            inspectedRoute = null
        )
        val LINE_SPEED_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:line-speed")
        val ROUTE_END_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:route-end")
        val FOLLOW_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:following")
        val EXTERNAL_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:simple-api")
        val MISSION_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:mission")
        val STATION_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:station-call")
        val ENTRY_CLEARANCE_OWNER = ConstraintOwner(ConstraintOwnerType.SIGNALLING, "simpleato:entry-clearance")
        const val POSITION_EPSILON_METERS = 1.0e-3
    }
}
