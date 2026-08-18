package work.art1st.javarailways.basic.rail

import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.basic.pathFinding.basicWorldDataOrNull
import work.art1st.javarailways.basic.route.TurnoutSafetyDefaults
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Basic-layer operational topology rules for automatically joined rail endpoints. */
object RailAutoConnector {
    data class Options(
        val endpointTolerance: Double = Curve.TOLERANCE,
        val maxTangentAngleDegrees: Double = RailConnectionPolicy.DEFAULT_MAX_TANGENT_ANGLE_DEGREES,
        /** Final topology visible at commit; defaults to the world's currently published rails. */
        val topologyRails: Collection<CoreRail>? = null,
        /** Override for tests/tools; null resolves the world's default turnout locking distance. */
        val minimumTurnoutLegLengthMeters: Double? = null
    ) {
        init {
            require(endpointTolerance.isFinite() && endpointTolerance >= 0.0)
            require(maxTangentAngleDegrees.isFinite() && maxTangentAngleDegrees >= 0.0)
            require(minimumTurnoutLegLengthMeters == null ||
                minimumTurnoutLegLengthMeters.isFinite() && minimumTurnoutLegLengthMeters >= 0.0)
        }
    }

    enum class Outcome {
        CONNECTED,
        ALREADY_CONNECTED,
        TOO_FAR,
        ANGLE_TOO_SHARP,
        INVALID_TANGENT,
        TOO_MANY_ENDPOINTS,
        AMBIGUOUS_TURNOUT,
        TURNOUT_ANGLE_TOO_SHARP,
        TURNOUT_INVALID_TANGENT,
        TURNOUT_LOCKING_DISTANCE_UNAVAILABLE,
        TURNOUT_GRADE_DISCONTINUITY
    }

    data class Result(
        val outcome: Outcome,
        val distance: Double? = null,
        val angleDegrees: Double? = null,
        val requiredLengthMeters: Double? = null,
        val availableLengthMeters: Double? = null,
        val railId: UUID? = null,
        val expectedGrade: Double? = null,
        val actualGrade: Double? = null
    )

    private data class Joint(
        val firstViewToJoint: RailView,
        val secondViewToJoint: RailView,
        val distance: Double
    )

    private data class Endpoint(val viewToJoint: RailView) {
        val nextAwayFromJoint: RailView get() = viewToJoint.reversed
        val outwardTangent: Vector3d
            get() = Vector3d(viewToJoint.endPoint.tangent).negate().normalize()
    }

    fun connectIfAllowed(
        first: CoreRail,
        second: CoreRail,
        options: Options = Options()
    ): Result {
        if (!first.world.isRailTopologyTransactionActiveOnCurrentThread()) {
            first.world.awaitCurrentPhysicsEpoch()
        }
        if (first == second) return Result(Outcome.ALREADY_CONNECTED)
        require(first.world === second.world) { "rails must belong to the same world" }

        val joint = closestJoint(first, second)
        if (joint.distance > options.endpointTolerance) {
            return Result(Outcome.TOO_FAR, distance = joint.distance)
        }

        val endpoints = endpointsAt(
            first,
            joint.firstViewToJoint.endPoint.position,
            options.endpointTolerance,
            options.topologyRails ?: first.world.allRails
        )
        val affectedRailIds = endpoints.mapTo(linkedSetOf()) { it.viewToJoint.rail.id }.apply {
            add(first.id)
            add(second.id)
        }
        val occupyingTrainIds = first.world.occupyingTrainIds(affectedRailIds)
        require(occupyingTrainIds.isEmpty()) {
            "Cannot connect occupied endpoint rails: ${occupyingTrainIds.sorted()}"
        }
        require(first.world.basicWorldDataOrNull?.dispatcher?.hasRouteAllocationOn(affectedRailIds) != true) {
            "Cannot connect endpoint rails with active route allocations"
        }
        if (endpoints.size > MAX_ENDPOINTS_PER_ANCHOR) {
            return Result(Outcome.TOO_MANY_ENDPOINTS, distance = joint.distance)
        }
        if (areConnected(first, second)) return Result(Outcome.ALREADY_CONNECTED, joint.distance)
        val endpointTargets = endpoints.mapTo(HashSet()) { it.nextAwayFromJoint }
        val jointAlreadyHasConnection = endpoints.any { endpoint ->
            endpoint.viewToJoint.mutableNextRails.any { it in endpointTargets }
        }
        if (endpoints.size == MAX_ENDPOINTS_PER_ANCHOR && jointAlreadyHasConnection) {
            return configureThreeLegTurnout(endpoints, joint, options)
        }

        val firstNext = joint.secondViewToJoint.reversed
        val secondNext = joint.firstViewToJoint.reversed
        val angle = transitionAngle(joint.firstViewToJoint, firstNext, joint.secondViewToJoint, secondNext)
            ?: return Result(Outcome.INVALID_TANGENT, distance = joint.distance)
        if (!RailConnectionPolicy.canTransition(joint.firstViewToJoint, firstNext, options.maxTangentAngleDegrees) ||
            !RailConnectionPolicy.canTransition(joint.secondViewToJoint, secondNext, options.maxTangentAngleDegrees)
        ) {
            return Result(Outcome.ANGLE_TOO_SHARP, distance = joint.distance, angleDegrees = angle)
        }

        val changedFirst = addNextPreservingActive(joint.firstViewToJoint, firstNext)
        val changedSecond = addNextPreservingActive(joint.secondViewToJoint, secondNext)
        return if (changedFirst || changedSecond) {
            Result(Outcome.CONNECTED, distance = joint.distance, angleDegrees = angle)
        } else {
            Result(Outcome.ALREADY_CONNECTED, distance = joint.distance, angleDegrees = angle)
        }
    }

    fun areConnected(first: CoreRail, second: CoreRail): Boolean =
        first.viewAB.nextRails.any { it.rail == second } ||
            first.viewBA.nextRails.any { it.rail == second } ||
            second.viewAB.nextRails.any { it.rail == first } ||
            second.viewBA.nextRails.any { it.rail == first }

    fun selectSmoothestActiveRail(view: RailView): Boolean {
        val best = view.mutableNextRails
            .mapNotNull { next ->
                RailConnectionPolicy.activeSelectionScoreDegrees(view, next)?.let { angle -> angle to next }
            }
            .minWithOrNull(compareBy<Pair<Double, RailView>> { it.first }.thenBy { it.second.rail.id.toString() })
            ?.second
            ?: return false
        if (view.activeRail == best) return false
        view.activeRail = best
        return true
    }

    private fun configureThreeLegTurnout(
        endpoints: List<Endpoint>,
        joint: Joint,
        options: Options
    ): Result {
        val distance = joint.distance
        val scoredPairs = listOf(
            Triple(0, 1, endpoints[0].outwardTangent.dot(endpoints[1].outwardTangent)),
            Triple(0, 2, endpoints[0].outwardTangent.dot(endpoints[2].outwardTangent)),
            Triple(1, 2, endpoints[1].outwardTangent.dot(endpoints[2].outwardTangent))
        ).sortedByDescending { it.third }
        if (abs(scoredPairs[0].third - scoredPairs[1].third) <= STEM_SCORE_EPSILON) {
            return Result(Outcome.AMBIGUOUS_TURNOUT, distance = distance)
        }

        val branchIndices = setOf(scoredPairs[0].first, scoredPairs[0].second)
        val stemIndex = (endpoints.indices).single { it !in branchIndices }
        val stem = endpoints[stemIndex]
        val branches = branchIndices.map(endpoints::get)
        val world = stem.viewToJoint.rail.world
        val requiredLegLength = options.minimumTurnoutLegLengthMeters
            ?: world.basicWorldDataOrNull?.turnoutSafetyDefaults?.lockingDistanceMeters
            ?: TurnoutSafetyDefaults.DEFAULT_LOCKING_DISTANCE_METERS
        val shortestLeg = endpoints.minBy { it.viewToJoint.curve.totalArcLength }
        val shortestLength = shortestLeg.viewToJoint.curve.totalArcLength
        if (shortestLength + Curve.TOLERANCE < requiredLegLength) {
            return Result(
                outcome = Outcome.TURNOUT_LOCKING_DISTANCE_UNAVAILABLE,
                distance = distance,
                requiredLengthMeters = requiredLegLength,
                availableLengthMeters = shortestLength,
                railId = shortestLeg.viewToJoint.rail.id
            )
        }
        val stemGrade = horizontalGrade(stem.viewToJoint.endPoint.tangent)
            ?: return Result(Outcome.TURNOUT_INVALID_TANGENT, distance = distance)
        for (branch in branches) {
            val branchGrade = horizontalGrade(branch.nextAwayFromJoint.startPoint.tangent)
                ?: return Result(Outcome.TURNOUT_INVALID_TANGENT, distance = distance)
            if (abs(branchGrade - stemGrade) > TURNOUT_GRADE_TOLERANCE) {
                return Result(
                    outcome = Outcome.TURNOUT_GRADE_DISCONTINUITY,
                    distance = distance,
                    railId = branch.viewToJoint.rail.id,
                    expectedGrade = stemGrade,
                    actualGrade = branchGrade
                )
            }
        }
        var maximumAngle = 0.0
        for (branch in branches) {
            val angle = transitionAngle(
                stem.viewToJoint,
                branch.nextAwayFromJoint,
                branch.viewToJoint,
                stem.nextAwayFromJoint
            ) ?: return Result(Outcome.TURNOUT_INVALID_TANGENT, distance = distance)
            maximumAngle = max(maximumAngle, angle)
            if (!RailConnectionPolicy.canTransition(
                    stem.viewToJoint,
                    branch.nextAwayFromJoint,
                    options.maxTangentAngleDegrees
                ) ||
                !RailConnectionPolicy.canTransition(
                    branch.viewToJoint,
                    stem.nextAwayFromJoint,
                    options.maxTangentAngleDegrees
                )
            ) {
                return Result(Outcome.TURNOUT_ANGLE_TOO_SHARP, distance = distance, angleDegrees = angle)
            }
        }

        var changed = false

        fun preferredNext(view: RailView): RailView? = when (view) {
            joint.firstViewToJoint -> joint.secondViewToJoint.reversed
            joint.secondViewToJoint -> joint.firstViewToJoint.reversed
            else -> null
        }
        changed = setAllowedNext(
            stem.viewToJoint,
            branches.map { it.nextAwayFromJoint },
            preferredNext(stem.viewToJoint)
        ) || changed
        branches.forEach { branch ->
            changed = setAllowedNext(
                branch.viewToJoint,
                listOf(stem.nextAwayFromJoint),
                preferredNext(branch.viewToJoint)
            ) || changed
        }
        return Result(
            if (changed) Outcome.CONNECTED else Outcome.ALREADY_CONNECTED,
            distance,
            maximumAngle
        )
    }

    private fun endpointsAt(
        first: CoreRail,
        position: org.joml.Vector3dc,
        tolerance: Double,
        topologyRails: Collection<CoreRail>
    ): List<Endpoint> =
        topologyRails
            .flatMap { rail -> listOf(rail.viewAB, rail.viewBA) }
            .filter { it.endPoint.position.distance(position) <= tolerance }
            .map(::Endpoint)

    private fun transitionAngle(
        firstToJoint: RailView,
        firstNext: RailView,
        secondToJoint: RailView,
        secondNext: RailView
    ): Double? {
        val forward = RailConnectionPolicy.transitionAngleDegrees(firstToJoint, firstNext)
        val reverse = RailConnectionPolicy.transitionAngleDegrees(secondToJoint, secondNext)
        if (forward == null || reverse == null) return null
        return max(forward, reverse)
    }

    private fun setAllowedNext(view: RailView, allowed: List<RailView>, preferred: RailView?): Boolean {
        val allowedDistinct = allowed.distinct()
        val oldNext = view.mutableNextRails.toList()
        val oldActive = view.activeRail
        val ordered = buildList {
            addAll(oldNext.filter { it in allowedDistinct })
            if (preferred != null && preferred in allowedDistinct && preferred !in this) add(preferred)
            addAll(allowedDistinct.filterNot { it in this })
        }
        if (oldNext == ordered && oldActive in ordered) return false
        view.activeRail = null
        view.mutableNextRails.clear()
        view.mutableNextRails.addAll(ordered)
        view.activeRail = oldActive?.takeIf { it in ordered } ?: ordered.firstOrNull()
        return oldNext != ordered || oldActive !== view.activeRail
    }

    private fun horizontalGrade(tangent: org.joml.Vector3dc): Double? {
        val horizontal = kotlin.math.hypot(tangent.x(), tangent.z())
        if (horizontal <= 1.0e-12) return null
        return tangent.y() / horizontal
    }

    private fun addNextPreservingActive(fromView: RailView, nextView: RailView): Boolean {
        val added = if (!fromView.mutableNextRails.contains(nextView)) {
            fromView.mutableNextRails.add(nextView)
            true
        } else {
            false
        }
        if (fromView.activeRail == null) fromView.activeRail = nextView
        return added
    }

    private fun closestJoint(first: CoreRail, second: CoreRail): Joint {
        val candidates = listOf(
            Joint(first.viewBA, second.viewBA, first.viewBA.endPoint.position.distance(second.viewBA.endPoint.position)),
            Joint(first.viewBA, second.viewAB, first.viewBA.endPoint.position.distance(second.viewAB.endPoint.position)),
            Joint(first.viewAB, second.viewBA, first.viewAB.endPoint.position.distance(second.viewBA.endPoint.position)),
            Joint(first.viewAB, second.viewAB, first.viewAB.endPoint.position.distance(second.viewAB.endPoint.position))
        )
        return candidates.minBy { it.distance }
    }

    private const val MAX_ENDPOINTS_PER_ANCHOR = 3
    private const val STEM_SCORE_EPSILON = 1.0e-6
    private const val TURNOUT_GRADE_TOLERANCE = 1.0e-4
}
