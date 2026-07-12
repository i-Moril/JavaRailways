package work.art1st.mineRails.core.physics.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.api.physics.rail.RailMetadata
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

class RailDirectionRestrictionTest {
    @Test
    fun `active connection cannot enter rail in restricted direction`() {
        val world = createTestWorld()
        val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(10.0, segmentCount = 1))
        val secondCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(20.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0)
        ))
        val second = CoreRail(world, secondCurve, mutableListOf(), mutableListOf())
        RailView.connect(first.viewAB, second.viewBA)
        assertEquals(second.viewAB, first.viewAB.activeRail)

        second.metadata = RailMetadata(allowedDirection = ViewDirection.BA)
        assertNull(first.viewAB.activeRail)
        val forbiddenBogie = CoreBogie.create(second.viewAB.getRailLocationAt(1.0))
        val allowedBogie = CoreBogie.create(second.viewBA.getRailLocationAt(1.0))
        assertThrows(BogieException::class.java) { forbiddenBogie.moveForward(1.0) }
        assertDoesNotThrow { allowedBogie.moveForward(1.0) }

        second.metadata = second.metadata.copy(allowedDirection = ViewDirection.AB)
        assertEquals(second.viewAB, first.viewAB.activeRail)
    }
}
