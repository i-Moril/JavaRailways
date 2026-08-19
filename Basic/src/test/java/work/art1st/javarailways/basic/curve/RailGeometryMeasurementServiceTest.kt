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
    }
}
