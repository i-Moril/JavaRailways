package work.art1st.mineRails.bukkit.util

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.train.createTestWorld

class HeavyRailQueriesTest {
    @Test
    fun `looked at rail point refines between streamed line samples`() {
        val world = createTestWorld()
        val rail = CoreRail(
            world,
            PrimeCurve.fromPoints(
                listOf(
                    Curve.RawPoint(
                        Vector3d(0.0, 0.0, 0.0),
                        Vector3d(1.0, 0.0, 0.0),
                        Vector3d(0.0, 1.0, 0.0),
                        0.0,
                        Curve.SegmentType.LINE_START
                    ),
                    Curve.RawPoint(
                        Vector3d(10.0, 0.0, 0.0),
                        Vector3d(1.0, 0.0, 0.0),
                        Vector3d(0.0, 1.0, 0.0),
                        10.0,
                        Curve.SegmentType.LINE_END
                    )
                )
            ),
            mutableListOf(),
            mutableListOf()
        )

        val match = HeavyRailQueries.findLookedAtRailPoint(
            listOf(rail),
            origin = Vector3d(3.375, 5.0, 0.0),
            direction = Vector3d(0.0, -1.0, 0.0),
            maxDistance = 16.0,
            hitRadius = 0.5
        )

        assertNotNull(match)
        assertEquals(rail.id, match!!.railId)
        assertEquals(3.375, match.arcLength, 1e-4)
        assertEquals(0.0, match.distanceToRay, 1e-4)

        val otherRail = CoreRail(
            world,
            rail.curve,
            mutableListOf(),
            mutableListOf()
        )
        val selected = HeavyRailQueries.findNearestWayPoint(
            wayPoints = listOf(
                WayPoint("other", "other", otherRail.id, match.arcLength),
                WayPoint("same", "same", rail.id, 8.0)
            ),
            railsById = mapOf(rail.id to rail, otherRail.id to otherRail),
            targetPosition = match.position,
            requiredRailId = rail.id
        )

        assertEquals("same", selected?.wayPoint?.id)
    }
}
