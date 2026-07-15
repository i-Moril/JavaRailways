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
import work.art1st.mineRails.basic.api.ato.constraint.FollowConstraint
import work.art1st.mineRails.basic.api.ato.constraint.RouteSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.constraint.StopConstraint
import work.art1st.mineRails.basic.api.ato.mission.AtoMission
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
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.api.physics.rail.RailObjectSnapshot
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailSegment
import work.art1st.mineRails.core.physics.train.TrainEnd

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
    }

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
    private val missionResolver = AtoMissionResolver(world)
    private val constraintManager = ConstraintManager()
    private val speedLimits = LinkedHashMap<String, Double>()
    private val stopTargets = LinkedHashMap<String, StopTarget>()
    private var previousObserved: Map<RailObjectRef, Observed> = emptyMap()
    private var currentSegments: List<RailSegment> = emptyList()
    private var activeProvider: AtoProvider? = null
    private var routeRevision = 0L
    private var previousRouteKey: List<Pair<java.util.UUID, work.art1st.mineRails.core.api.physics.rail.ViewDirection>> = emptyList()
    private var perceptionEpoch = 0L

    @Volatile
    var currentMission: AtoMission? = null
        private set

    init {
        atoCoordinator.initialTrain(train.id)?.let(worldData.dispatcher::recordInitialTrain)
    }

    /** Resolve and queue a Mission from the main thread; activation occurs at a physics Tick boundary. */
    @MainThread
    fun submitMission(request: AtoMissionRequest): AtoMissionSubmissionResult {
        val latestRevision = missionCommands.latestAcceptedRevision
        if (request.revision <= latestRevision) {
            return AtoMissionSubmissionResult.StaleRevision(request.revision, latestRevision)
        }
        val mission = when (val result = missionResolver.resolve(request)) {
            is AtoMissionResolutionResult.Success -> result.mission
            is AtoMissionResolutionResult.TargetUnresolved -> {
                return AtoMissionSubmissionResult.TargetUnresolved(result.target)
            }
            is AtoMissionResolutionResult.RouteUnavailable -> {
                return AtoMissionSubmissionResult.RouteUnavailable(result.from, result.to, result.reason)
            }
        }
        return when (missionCommands.offer(mission.revision, mission)) {
            CommandOfferResult.ACCEPTED -> AtoMissionSubmissionResult.Accepted(mission)
            CommandOfferResult.STALE -> AtoMissionSubmissionResult.StaleRevision(
                mission.revision,
                missionCommands.latestAcceptedRevision
            )
        }
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
        commands.drain().forEach { envelope ->
            when (val command = envelope.command) {
                is Command.SetSpeedLimit -> speedLimits[command.key] = command.speed
                is Command.StopAt -> stopTargets[command.key] = StopTarget(command.target, command.offsetMeters)
                is Command.Remove -> {
                    speedLimits.remove(command.key)
                    stopTargets.remove(command.key)
                }
                is Command.SetProvider -> switchProvider(command.providerId)
            }
        }
        missionCommands.drain().lastOrNull()?.command?.let { mission ->
            constraintManager.remove(MISSION_OWNER)
            currentMission = mission
        }
    }

    override fun plan(deltaTimeSeconds: Double): ControlDemand {
        perceptionEpoch++
        val scan = scanRouteAndObjects()
        latestRouteSnapshot = scan.route
        latestPerceptionSnapshot = scan.perception
        currentSegments = scan.segments

        processProviderBoundaries(scan)
        updateBuiltInConstraints(scan)
        updateProviderConstraints(scan)

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
            val transition = if (observed.distanceMeters <= lastTravelDistanceMeters + safetyProfile.stopToleranceMeters) {
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
                    snapshot = RailObjectSnapshot.capture(railObject, view.viewDirection, perceptionEpoch),
                    distanceMeters = distance,
                    relativeSpeedMetersPerSecond = otherTrain?.let {
                        val speedAlongRoute = if (trainEnd.location.facing == view.viewDirection) it.speed else 0.0
                        speedAlongRoute - activeSpeed
                    }
                )
                val existing = result[railObject.ref]
                if (existing == null || observed.distanceMeters < existing.distanceMeters) {
                    result[railObject.ref] = observed
                }
            }
            distanceToSegment += segment.totalArcLength
        }
        return result
    }

    private fun brakingDistance(speed: Double, deceleration: Double): Double =
        speed * speed / (2.0 * deceleration)

    private companion object {
        val LINE_SPEED_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:line-speed")
        val ROUTE_END_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:route-end")
        val FOLLOW_OWNER = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "basic:following")
        val EXTERNAL_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:simple-api")
        val PROVIDER_OWNER = ConstraintOwner(ConstraintOwnerType.SIGNALLING, "basic:active-provider")
        val MISSION_OWNER = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "basic:mission")
    }
}
