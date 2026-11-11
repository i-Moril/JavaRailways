package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.physics.createRailForTest

import org.bukkit.event.block.Action
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld

class DirectionRestrictionTargetLookupTest {
    @Test
    fun `direction restriction accepts right click air`() {
        assertTrue(isDirectionRestrictionInteraction(Action.RIGHT_CLICK_AIR))
    }

    @Test
    fun `direction restriction targets virtual rail from eye ray without a clicked block`() {
        val rail = straightRail()

        val lookup = findDirectionRestrictionTarget(
            candidates = listOf(rail),
            rayOrigin = Vector3d(15.0, 5.0, 0.0),
            rayDirection = Vector3d(0.0, -1.0, 0.0),
            playerPosition = Vector3d(2.0, 1.0, 0.0),
            maxDistance = 16.0,
            hitRadius = 0.5
        )

        assertNotNull(lookup)
        assertEquals(rail.id, lookup!!.pointB.railId)
        assertEquals(15.0, lookup.pointB.arcLength, 1.0e-4)
        assertEquals(2.0, lookup.pointA.arcLength, 1.0e-4)
    }

    @Test
    fun `direction restriction rejects an eye ray that misses the virtual rail`() {
        val rail = straightRail()

        val lookup = findDirectionRestrictionTarget(
            candidates = listOf(rail),
            rayOrigin = Vector3d(15.0, 5.0, 0.0),
            rayDirection = Vector3d(0.0, 1.0, 0.0),
            playerPosition = Vector3d(2.0, 1.0, 0.0),
            maxDistance = 16.0,
            hitRadius = 0.5
        )

        assertNull(lookup)
    }

    private fun straightRail(): CoreRail {
        val world = createTestWorld()
        return world.createRailForTest(
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
                        Vector3d(20.0, 0.0, 0.0),
                        Vector3d(1.0, 0.0, 0.0),
                        Vector3d(0.0, 1.0, 0.0),
                        20.0,
                        Curve.SegmentType.LINE_END
                    )
                )
            )
        )
    }
}
