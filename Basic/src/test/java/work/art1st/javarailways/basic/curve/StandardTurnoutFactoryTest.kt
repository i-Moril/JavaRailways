package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.annotation.Heavy

@OptIn(Heavy::class)
class StandardTurnoutFactoryTest {
    @Test
    fun `selects closest of four valid branch directions`() {
        val anchor = Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0))
        val geometry = StandardTurnoutGeometry(9, 180.0, 30.0)
        val angle = geometry.branchAngleRadians
        val directions = listOf(1.0, -1.0).flatMap { mainDirection ->
            listOf(1.0, -1.0).map { turnDirection ->
                Vector3d(mainDirection, 0.0, 0.0).rotateY(turnDirection * angle).normalize()
            }
        }

        directions.forEach { towards ->
            val result = requireNotNull(StandardTurnoutFactory.build(anchor, towards, geometry))
            assertTrue(result.endTangent.dot(towards) > 1.0 - 1.0e-10)
            val end = result.curve.getPointAt(result.curve.totalArcLength)
            assertTrue(Vector3d(end.tangent).normalize().dot(towards) > 1.0 - 1.0e-10)
            val nearEnd = result.curve.getPointAt(result.curve.totalArcLength - 1.0e-4).position
            val geometryDirection = Vector3d(end.position).sub(nearEnd).normalize()
            assertTrue(geometryDirection.dot(towards) > 0.99999)
        }
    }

    @Test
    fun `configured turnout geometry and sample step drive generated curve`() {
        val geometry = StandardTurnoutGeometry(number = 9, radiusMeters = 90.0, divergingSpeedKmH = 25.0)
        val step = 1.0
        val result = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, 0.2),
            geometry,
            step
        ))

        assertEquals(geometry.radiusMeters * geometry.branchAngleRadians, result.curve.totalArcLength, 1.0e-9)
        assertEquals(kotlin.math.ceil(result.curve.totalArcLength / step).toLong() + 1L,
            result.curve.stream().count())
    }

    @Test
    fun `catalog includes compact and mainline turnout numbers with speed limits`() {
        assertEquals(
            listOf(4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 20, 24, 30, 38, 42, 60),
            StandardTurnout.entries.map { it.number }
        )
        StandardTurnout.entries.forEach { standard ->
            val geometry = StandardTurnoutGeometry(
                number = standard.number,
                radiusMeters = standard.number * 10.0,
                divergingSpeedKmH = 20.0
            )
            val result = requireNotNull(StandardTurnoutFactory.build(
                Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
                Vector3d(1.0, 0.0, 0.2),
                geometry
            ))
            assertEquals(geometry.radiusMeters * geometry.branchAngleRadians, result.curve.totalArcLength, 1.0e-9)
        }
    }
}
