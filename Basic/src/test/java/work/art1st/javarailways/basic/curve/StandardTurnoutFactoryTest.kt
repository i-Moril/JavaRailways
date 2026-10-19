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
            val curve = requireNotNull(StandardTurnoutFactory.build(anchor, towards, geometry))
            val end = curve.getPointAt(curve.totalArcLength)
            assertTrue(Vector3d(end.tangent).normalize().dot(towards) > 1.0 - 1.0e-10)
            val nearEnd = curve.getPointAt(curve.totalArcLength - 1.0e-4).position
            val geometryDirection = Vector3d(end.position).sub(nearEnd).normalize()
            assertTrue(geometryDirection.dot(towards) > 0.99999)
        }
    }

    @Test
    fun `turnout keeps the anchor grade constant throughout its length`() {
        val grade = 0.025
        val anchorTangent = Vector3d(1.0, grade, 0.0).normalize()
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(4.0, 12.0, -3.0), anchorTangent),
            Vector3d(1.0, 0.0, 0.2),
            StandardTurnoutGeometry(number = 9, radiusMeters = 90.0, divergingSpeedKmH = 25.0),
            sampleStepMeters = 1.0
        ))

        val points = curve.stream().toList()
        points.forEach { point ->
            val actualGrade = point.tangent.y() / kotlin.math.hypot(point.tangent.x(), point.tangent.z())
            assertEquals(grade, actualGrade, 1.0e-9)
        }
        val horizontalLength = 90.0 * StandardTurnoutGeometry(9, 90.0, 25.0).branchAngleRadians
        assertEquals(12.0 + grade * horizontalLength, points.last().position.y(), 1.0e-8)
    }

    @Test
    fun `reversed turnout direction reverses the represented grade`() {
        val grade = 0.025
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, grade, 0.0).normalize()),
            Vector3d(-1.0, 0.0, 0.2),
            StandardTurnoutGeometry(number = 9, radiusMeters = 90.0, divergingSpeedKmH = 25.0)
        ))

        val start = curve.getPointAt(0.0).tangent
        assertEquals(-grade, start.y() / kotlin.math.hypot(start.x(), start.z()), 1.0e-9)
    }

    @Test
    fun `configured turnout geometry and sample step drive generated curve`() {
        val geometry = StandardTurnoutGeometry(number = 9, radiusMeters = 90.0, divergingSpeedKmH = 25.0)
        val step = 1.0
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, 0.2),
            geometry,
            step
        ))

        assertEquals(geometry.radiusMeters * geometry.branchAngleRadians, curve.totalArcLength, 1.0e-9)
        assertEquals(kotlin.math.ceil(curve.totalArcLength / step).toLong() + 1L,
            curve.stream().count())
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
            val curve = requireNotNull(StandardTurnoutFactory.build(
                Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
                Vector3d(1.0, 0.0, 0.2),
                geometry
            ))
            assertEquals(geometry.radiusMeters * geometry.branchAngleRadians, curve.totalArcLength, 1.0e-9)
        }
    }
}
