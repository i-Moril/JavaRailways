package work.art1st.javarailways.simpleato.api.constraint

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.simpleato.api.ato.AtoPlanningContext
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.MinimumConstraintPlanner

class BrakingConstraintProjectionTest {
    private val key = ConstraintKey("test", "limit")
    private val profile = AtoSafetyProfile(reactionTimeSeconds = 0.7,
        safetyDistanceMeters = 5.0)

    @Test
    fun `physical targets retain station protection following and explicit margins`() {
        val cases = listOf(
            StopConstraint(key, 100.0) to (100.0 to 0.0),
            StopConstraint(key, 100.0, 0.0) to (100.0 to 0.0),
            MovementAuthorityConstraint(key, 100.3, 0.0) to (100.3 to 0.0),
            MovementAuthorityConstraint(key, 100.0) to (95.0 to 0.0),
            MovementAuthorityConstraint(key, 100.0, 0.3) to (99.7 to 0.0),
            FollowConstraint(key, 30.0, 2.0) to (25.0 to 2.0),
            RouteSpeedLimitConstraint(key, 12.0, 3.0) to (12.0 to 3.0),
            StopConstraint(key, 0.01) to (0.01 to 0.0)
        )
        cases.forEach { (constraint, expected) ->
            val limit = requireNotNull(constraint.brakingLimit(profile))
            assertEquals(expected.first, limit.distanceMeters, 1e-12)
            assertEquals(expected.second, limit.targetSpeedMetersPerSecond, 1e-12)
            assertEquals(0.7, limit.reactionTimeSeconds)
        }
    }

    @Test
    fun `planner carries spatial targets while instant limits and departure holds remain active`() {
        val manager = ConstraintManager()
        val owner = ConstraintOwner(ConstraintOwnerType.SYSTEM_SAFETY, "test")
        val stop = StopConstraint(key, 100.0, 0.0)
        val cap = CurrentSpeedLimitConstraint(ConstraintKey("test", "cap"), 0.2)
        val hold = DepartureHoldConstraint(ConstraintKey("test", "hold"))
        manager.replace(owner, listOf(stop, cap))
        val context = AtoPlanningContext(0.0, 10.0, profile, manager,
            brakingGradeSegments = listOf(BrakingGradeSegment(200.0, -0.01)),
            maximumControlDeltaSeconds = 0.2)
        val moving = MinimumConstraintPlanner.plan(context)
        assertEquals(0.2, moving.targetSpeedMetersPerSecond)
        val constraints = requireNotNull(moving.brakingConstraints)
        assertEquals(listOf(stop.brakingLimit(profile)), constraints.limits)
        assertEquals(context.brakingGradeSegments, constraints.segments)
        assertEquals(0.2, constraints.maximumControlDeltaSeconds)
        manager.replace(owner, listOf(stop, cap, hold))
        assertEquals(0.0, MinimumConstraintPlanner.plan(context).targetSpeedMetersPerSecond)
        assertNull(cap.brakingLimit(profile))
        assertNull(hold.brakingLimit(profile))
    }
}
