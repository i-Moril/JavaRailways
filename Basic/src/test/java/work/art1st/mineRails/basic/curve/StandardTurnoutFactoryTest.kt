package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.util.annotation.Heavy

@OptIn(Heavy::class)
class StandardTurnoutFactoryTest {
    @Test
    fun `selects closest of four valid branch directions`() {
        val anchor = Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0))
        val angle = StandardTurnout.N9.branchAngleRadians
        val directions = listOf(1.0, -1.0).flatMap { mainDirection ->
            listOf(1.0, -1.0).map { turnDirection ->
                Vector3d(mainDirection, 0.0, 0.0).rotateY(turnDirection * angle).normalize()
            }
        }

        directions.forEach { towards ->
            val result = requireNotNull(StandardTurnoutFactory.build(anchor, towards, StandardTurnout.N9))
            assertTrue(result.endTangent.dot(towards) > 1.0 - 1.0e-10)
            val end = result.curve.getPointAt(result.curve.totalArcLength)
            assertTrue(Vector3d(end.tangent).normalize().dot(towards) > 1.0 - 1.0e-10)
            val nearEnd = result.curve.getPointAt(result.curve.totalArcLength - 1.0e-4).position
            val geometryDirection = Vector3d(end.position).sub(nearEnd).normalize()
            assertTrue(geometryDirection.dot(towards) > 0.99999)
        }
    }

    @Test
    fun `standard number defines radius angle and diverging speed`() {
        val standard = StandardTurnout.N12
        val result = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, 0.2),
            standard
        ))

        assertEquals(12, standard.number)
        assertEquals(330.0, standard.radiusMeters)
        assertEquals(45.0, standard.divergingSpeedKmH)
        assertEquals(standard.radiusMeters * standard.branchAngleRadians, result.curve.totalArcLength, 1.0e-9)
    }
}
