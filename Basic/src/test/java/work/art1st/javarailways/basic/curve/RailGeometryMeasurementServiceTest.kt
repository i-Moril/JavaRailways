package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import work.art1st.javarailways.core.api.util.annotation.Heavy
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RailGeometryMeasurementServiceTest {
    @OptIn(Heavy::class)
    @Test
    fun `measures actual horizontal radius and superelevation from final curve`() {
        val radius = 200.0
        val gauge = 1.435
        val superelevation = 0.12
        val roll = atan2(superelevation, gauge)
        val points = (0..180).map { index ->
            val angle = PI / 2.0 * index / 180.0
            val tangent = Vector3d(cos(angle), 0.0, sin(angle))
            val baseNormal = Vector3d(0.0, 1.0, 0.0)
            val normal = Vector3d(baseNormal).mul(cos(roll))
                .add(Vector3d(tangent).cross(baseNormal).mul(sin(roll)))
                .normalize()
            Curve.RawPoint(
                position = Vector3d(radius * sin(angle), 64.0, radius * (1.0 - cos(angle))),
                tangent = tangent,
                normal = normal,
                arcLength = radius * angle
            )
        }
        val curve = PrimeCurve.fromPoints(points)

        val measured = RailGeometryMeasurementService.measure(listOf(curve), gauge)

        assertEquals(radius, requireNotNull(measured.minimumHorizontalRadiusMeters), 0.1)
        assertEquals(superelevation, measured.maximumSuperelevationMeters, 1.0e-6)
        assertEquals(0.0, measured.maximumAbsoluteGrade, 1.0e-12)
    }

    @OptIn(Heavy::class)
    @Test
    fun `measures maximum absolute grade from final curve tangents`() {
        val grades = listOf(0.01, -0.035, 0.02)
        val points = grades.mapIndexed { index, grade ->
            Curve.RawPoint(
                position = Vector3d(index.toDouble(), 64.0, 0.0),
                tangent = Vector3d(1.0, grade, 0.0).normalize(),
                normal = Vector3d(-grade, 1.0, 0.0).normalize(),
                arcLength = index * sqrt(1.0 + grade * grade)
            )
        }
        val curve = PrimeCurve.fromPoints(points)

        val measured = RailGeometryMeasurementService.measure(listOf(curve), 1.435)

        assertEquals(0.035, measured.maximumAbsoluteGrade, 1.0e-12)
    }
}
