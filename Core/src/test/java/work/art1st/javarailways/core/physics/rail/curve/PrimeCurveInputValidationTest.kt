package work.art1st.javarailways.core.physics.rail.curve

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy

@OptIn(Heavy::class)
class PrimeCurveInputValidationTest {
    @Test
    fun `constructor rejects fewer than two raw points`() {
        for (points in listOf(emptyList(), listOf(point(0.0)))) {
            assertThrows(IllegalArgumentException::class.java) { PrimeCurve.fromPoints(points) }
        }
    }

    @Test
    fun `constructor requires zero start and strictly increasing finite arcs`() {
        val invalidArcs = listOf(
            listOf(1.0, 10.0),
            listOf(-1.0, 10.0),
            listOf(Double.MIN_VALUE, 10.0),
            listOf(0.0, 0.0),
            listOf(0.0, -1.0),
            listOf(0.0, Double.NaN),
            listOf(0.0, Double.POSITIVE_INFINITY),
            listOf(0.0, 10.0, 5.0)
        )

        for (arcs in invalidArcs) {
            assertThrows(IllegalArgumentException::class.java, {
                PrimeCurve.fromPoints(arcs.map { point(it) })
            }, "arcs=$arcs")
        }
    }

    @Test
    fun `constructor rejects nonfinite position tangent and normal components`() {
        val invalidPoints = listOf(
            point(10.0).copy(position = Vector3d(Double.NaN, 0.0, 0.0)),
            point(10.0).copy(tangent = Vector3d(1.0, Double.POSITIVE_INFINITY, 0.0)),
            point(10.0).copy(normal = Vector3d(0.0, 1.0, Double.NEGATIVE_INFINITY))
        )

        for (invalidPoint in invalidPoints) {
            assertThrows(IllegalArgumentException::class.java) {
                PrimeCurve.fromPoints(listOf(point(0.0), invalidPoint))
            }
        }
    }

    @Test
    fun `constructor rejects nonnormalizable and parallel frame vectors`() {
        val invalidPoints = listOf(
            point(10.0).copy(tangent = Vector3d()),
            point(10.0).copy(normal = Vector3d()),
            point(10.0).copy(normal = Vector3d(2.0, 0.0, 0.0)),
            point(10.0).copy(tangent = Vector3d(1e300, 0.0, 0.0)),
            point(10.0).copy(tangent = Vector3d(1e-300, 0.0, 0.0))
        )

        for (invalidPoint in invalidPoints) {
            assertThrows(IllegalArgumentException::class.java) {
                PrimeCurve.fromPoints(listOf(point(0.0), invalidPoint))
            }
        }
    }

    @Test
    fun `constructor copies usable nonorthogonal frames and permits equal positions`() {
        val tangent = Vector3d(2.0, 0.0, 0.0)
        val normal = Vector3d(1.0, 3.0, 0.0)
        val endPosition = Vector3d(10.0, 0.0, 0.0)
        val rawPoints = mutableListOf(
            Curve.RawPoint(Vector3d(), tangent, normal, 0.0),
            Curve.RawPoint(Vector3d(), tangent, normal, 1.0),
            Curve.RawPoint(endPosition, tangent, normal, 11.0)
        )
        val curve = PrimeCurve.fromPoints(rawPoints)

        tangent.zero()
        normal.zero()
        endPosition.zero()
        rawPoints.clear()

        val end = curve.getPointAt(11.0)
        assertEquals(11.0, curve.totalArcLength)
        assertEquals(0.0, end.position.distance(Vector3d(10.0, 0.0, 0.0)), 1e-9)
        assertEquals(0.0, end.tangent.distance(Vector3d(2.0, 0.0, 0.0)), 1e-9)
        assertEquals(0.0, end.normal.distance(Vector3d(1.0, 3.0, 0.0)), 1e-9)
        val interior = curve.getPointAt(5.4)
        assertEquals(4.4, interior.position.x(), 1e-9)
        assertEquals(1.0, interior.tangent.length(), 1e-9)
        assertEquals(1.0, interior.normal.length(), 1e-9)
        assertEquals(0.0, interior.tangent.dot(interior.normal), 1e-9)
        assertEquals(
            Double.MIN_VALUE,
            PrimeCurve.fromPoints(listOf(point(0.0), point(Double.MIN_VALUE))).totalArcLength
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"pointList":[]}""",
        """{"pointList":[{"position":{"x":0,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":0}]}""",
        """{"pointList":[{"position":{"x":0,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":1},{"position":{"x":10,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":10}]}""",
        """{"pointList":[{"position":{"x":0,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":0},{"position":{"x":10,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":0}]}""",
        """{"pointList":[{"position":{"x":0,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":0},{"position":{"x":10,"y":0,"z":0},"tangent":{"x":0,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":10}]}"""
    ])
    fun `saved malformed point lists fail at load`(input: String) {
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(PrimeCurveImpl.SavedPrimeCurveV1.serializer(), input).load(Unit)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"y":0,"z":0}""", """{"x":0,"z":0}""", """{"x":0,"y":0}"""])
    fun `vector decoder rejects each missing coordinate`(input: String) {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(Vector3dSerializer, input)
        }
    }

    @Test
    fun `fixed current saved points load with explicit zero coordinates`() {
        val input = """{"pointList":[{"position":{"x":0,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":0},{"position":{"x":10,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":10}]}"""

        val curve = Json.decodeFromString(PrimeCurveImpl.SavedPrimeCurveV1.serializer(), input).load(Unit)

        assertEquals(10.0, curve.totalArcLength)
        assertEquals(4.4, curve.getPointAt(4.4).position.x(), 1e-9)
    }

    @Test
    fun `line stream emits each arc and the final endpoint exactly once`() {
        val curve = PrimeCurve.fromPoints(listOf(
            point(0.0).copy(segmentType = Curve.SegmentType.LINE_START),
            point(2.5).copy(segmentType = Curve.SegmentType.LINE_END)
        ))

        val points = curve.stream().toList()

        assertEquals(listOf(0.0, 1.25, 2.5), points.map { it.arcLength })
        assertEquals(listOf(0.0, 1.25, 2.5), points.map { it.position.x() })
    }

    private fun point(arcLength: Double) = Curve.RawPoint(
        Vector3d(arcLength, 0.0, 0.0),
        Vector3d(1.0, 0.0, 0.0),
        Vector3d(0.0, 1.0, 0.0),
        arcLength
    )
}
