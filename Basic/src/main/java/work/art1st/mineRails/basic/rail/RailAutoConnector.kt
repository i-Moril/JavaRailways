package work.art1st.mineRails.basic.rail

import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailView
import kotlin.math.max

object RailAutoConnector {
    data class Options(
        val endpointTolerance: Double = Curve.TOLERANCE,
        val maxTangentAngleDegrees: Double = RailConnectionPolicy.DEFAULT_MAX_TANGENT_ANGLE_DEGREES
    )

    enum class Outcome {
        CONNECTED,
        ALREADY_CONNECTED,
        TOO_FAR,
        ANGLE_TOO_SHARP,
        INVALID_TANGENT
    }

    data class Result(
        val outcome: Outcome,
        val distance: Double? = null,
        val angleDegrees: Double? = null
    )

    private data class Joint(
        val firstViewToJoint: RailView,
        val secondViewToJoint: RailView,
        val distance: Double
    )

    fun connectIfAllowed(
        first: CoreRail,
        second: CoreRail,
        options: Options = Options()
    ): Result {
        if (first == second) return Result(Outcome.ALREADY_CONNECTED)
        if (areConnected(first, second)) return Result(Outcome.ALREADY_CONNECTED)

        val joint = closestJoint(first, second)
        if (joint.distance > options.endpointTolerance) {
            return Result(Outcome.TOO_FAR, distance = joint.distance)
        }

        val firstNext = joint.secondViewToJoint.reversed
        val secondNext = joint.firstViewToJoint.reversed
        val forwardAngle = RailConnectionPolicy.transitionAngleDegrees(joint.firstViewToJoint, firstNext)
        val reverseAngle = RailConnectionPolicy.transitionAngleDegrees(joint.secondViewToJoint, secondNext)
        val angle = when {
            forwardAngle == null -> reverseAngle
            reverseAngle == null -> forwardAngle
            else -> max(forwardAngle, reverseAngle)
        }
        if (forwardAngle == null || reverseAngle == null) {
            return Result(Outcome.INVALID_TANGENT, distance = joint.distance, angleDegrees = angle)
        }
        if (!RailConnectionPolicy.canTransition(joint.firstViewToJoint, firstNext, options.maxTangentAngleDegrees) ||
            !RailConnectionPolicy.canTransition(joint.secondViewToJoint, secondNext, options.maxTangentAngleDegrees)) {
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
            .filter(::isDirectionAllowed)
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

    private fun addNextPreservingActive(fromView: RailView, nextView: RailView): Boolean {
        val added = if (!fromView.mutableNextRails.contains(nextView)) {
            fromView.mutableNextRails.add(nextView)
            true
        } else {
            false
        }
        if (fromView.activeRail == null && isDirectionAllowed(nextView)) {
            fromView.activeRail = nextView
        }
        return added
    }

    private fun isDirectionAllowed(view: RailView): Boolean =
        view.rail.metadata.allowedDirection?.let { it == view.viewDirection } ?: true

    private fun closestJoint(first: CoreRail, second: CoreRail): Joint {
        val candidates = listOf(
            Joint(first.viewBA, second.viewBA, first.viewBA.endPoint.position.distance(second.viewBA.endPoint.position)),
            Joint(first.viewBA, second.viewAB, first.viewBA.endPoint.position.distance(second.viewAB.endPoint.position)),
            Joint(first.viewAB, second.viewBA, first.viewAB.endPoint.position.distance(second.viewBA.endPoint.position)),
            Joint(first.viewAB, second.viewAB, first.viewAB.endPoint.position.distance(second.viewAB.endPoint.position))
        )
        return candidates.minBy { it.distance }
    }
}
