package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy
import kotlin.math.acos
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

class RailCurveFactoryTemplateTest {
    @Test
    fun `auto selects straight when tangent follows chord`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 1.0).normalize())
        val to = Anchor("B", Vector3d(10.0, 0.0, 10.0), Vector3d(1.0, 0.0, 1.0).normalize())

        val result = RailCurveFactory.build(from, to, RailCurveBuildOptions())

        assertTrue(result.isSuccess, result.error)
        assertEquals(RailCurveTemplateType.STRAIGHT, result.selectedTemplate)
        assertNotNull(result.curve)
    }

    @Test
    fun `auto builds s curve for same tangent offset`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("B", Vector3d(10.0, 0.0, 5.0), Vector3d(1.0, 0.0, 0.0))

        val result = RailCurveFactory.build(from, to, RailCurveBuildOptions())

        assertTrue(result.isSuccess, result.error)
        assertEquals(RailCurveTemplateType.S_CURVE, result.selectedTemplate)
        assertNotNull(result.curve)
        assertTrue(result.curve!!.totalArcLength > 10.0)
        assertTrue(result.curve!!.getPointAt(0.0).tangent.normalize(Vector3d()).dot(Vector3d(1.0, 0.0, 0.0)) > 0.999)
        assertTrue(result.curve!!.getPointAt(result.curve!!.totalArcLength).tangent.normalize(Vector3d()).dot(Vector3d(1.0, 0.0, 0.0)) > 0.999)
        assertTangentContinuity(result.curve!!)
    }

    @Test
    fun `straight template defaults to tangent continuity`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("B", Vector3d(10.0, 0.0, 5.0), Vector3d(1.0, 0.0, 0.0))

        val result = RailCurveFactory.build(
            from,
            to,
            RailCurveBuildOptions(template = RailCurveTemplateType.STRAIGHT)
        )

        assertFalse(result.isSuccess)
        assertEquals(RailCurveTemplateType.STRAIGHT, result.selectedTemplate)
        assertTrue(result.error!!.contains("切线"))
    }

    @Test
    fun `builds five segment parallel curves by offsetting centerline`() {
        val spacing = 4.5
        val starts = listOf(
            Anchor("A0", Vector3d(0.0, 0.0, spacing), Vector3d(1.0, 0.0, 0.0)),
            Anchor("A1", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0)),
            Anchor("A2", Vector3d(0.0, 0.0, -spacing), Vector3d(1.0, 0.0, 0.0))
        )
        val ends = listOf(
            Anchor("B0", Vector3d(50.0 - spacing, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0)),
            Anchor("B1", Vector3d(50.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0)),
            Anchor("B2", Vector3d(50.0 + spacing, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0))
        )

        val result = RailCurveFactory.buildParallel(
            starts,
            ends,
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0)
        )

        assertTrue(result.isSuccess, result.error)
        assertEquals(3, result.curves!!.size)
        assertEquals(RailCurveTemplateType.FIVE_SEGMENT, result.selectedTemplate)
        result.curves.forEachIndexed { index, curve ->
            assertTrue(curve.getPointAt(0.0).position.distance(starts[index].position) < Curve.TOLERANCE)
            assertTrue(curve.getPointAt(curve.totalArcLength).position.distance(ends[index].position) < Curve.TOLERANCE)
        }
        val mid0 = result.curves[0].getPointAt(result.curves[0].totalArcLength * 0.5).position
        val mid1 = result.curves[1].getPointAt(result.curves[1].totalArcLength * 0.5).position
        val mid2 = result.curves[2].getPointAt(result.curves[2].totalArcLength * 0.5).position
        assertTrue(mid0.distance(mid1) > spacing * 0.8)
        assertTrue(mid1.distance(mid2) > spacing * 0.8)
    }

    @Test
    fun `turnout template builds a branch without superelevation`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("B", Vector3d(50.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0))
        val result = RailCurveFactory.build(
            from,
            to,
            RailCurveBuildOptions(
                template = RailCurveTemplateType.TURNOUT,
                radius = 30.0,
                superelevation = SuperelevationConfig(designSpeedKmH = 80.0)
            )
        )

        assertTrue(result.isSuccess, result.error)
        assertEquals(RailCurveTemplateType.TURNOUT, result.selectedTemplate)
        val middleNormal = result.curve!!.getPointAt(result.curve!!.totalArcLength * 0.5).normal
        assertTrue(middleNormal.dot(Vector3d(0.0, 1.0, 0.0)) > 0.999)
    }

    @Test
    fun `five segment curve applies automatic superelevation`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("B", Vector3d(50.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0))
        val result = RailCurveFactory.build(
            from,
            to,
            RailCurveBuildOptions(
                template = RailCurveTemplateType.FIVE_SEGMENT,
                radius = 30.0,
                superelevation = SuperelevationConfig(
                    designSpeedKmH = 80.0,
                    maxSuperelevation = 0.15
                )
            )
        )

        assertTrue(result.isSuccess, result.error)
        val curve = result.curve!!
        val middleNormal = curve.getPointAt(curve.totalArcLength * 0.5).normal
        assertTrue(kotlin.math.abs(middleNormal.x()) + kotlin.math.abs(middleNormal.z()) > 0.01)
        assertTrue(curve.getPointAt(0.0).normal.dot(Vector3d(0.0, 1.0, 0.0)) > 0.999)
        assertTrue(curve.getPointAt(curve.totalArcLength).normal.dot(Vector3d(0.0, 1.0, 0.0)) > 0.999)
    }

    @Test
    fun `parallel curves match endpoint sides when end tangent is reversed`() {
        val spacing = 4.5
        val starts = listOf(
            Anchor("A0", Vector3d(0.0, 0.0, spacing / 2.0), Vector3d(1.0, 0.0, 0.0)),
            Anchor("A1", Vector3d(0.0, 0.0, -spacing / 2.0), Vector3d(1.0, 0.0, 0.0))
        )
        val ends = listOf(
            Anchor("B0", Vector3d(50.0 - spacing / 2.0, 0.0, 50.0), Vector3d(0.0, 0.0, -1.0)),
            Anchor("B1", Vector3d(50.0 + spacing / 2.0, 0.0, 50.0), Vector3d(0.0, 0.0, -1.0))
        )

        val result = RailCurveFactory.buildParallel(
            starts,
            ends,
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0)
        )

        assertTrue(result.isSuccess, result.error)
        assertEquals(2, result.curves!!.size)
        val centerResult = RailCurveFactory.build(
            Anchor("center-start", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0)),
            Anchor("center-end", Vector3d(50.0, 0.0, 50.0), Vector3d(0.0, 0.0, -1.0)),
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0)
        )
        assertTrue(centerResult.isSuccess, centerResult.error)
        assertTangentContinuity(centerResult.curve!!)
        result.curves.forEach(::assertTangentContinuity)
        val generatedEnds = result.curves.map { curve -> curve to curve.getPointAt(curve.totalArcLength) }
        ends.forEach { expected ->
            val (curve, generated) = generatedEnds.minBy { (_, point) -> point.position.distance(expected.position) }
            assertTrue(generated.position.distance(expected.position) < Curve.TOLERANCE)
            val expectedTangent = Vector3d(expected.tangent).normalize()
            val tangentAlignment = kotlin.math.abs(Vector3d(generated.tangent).normalize().dot(expectedTangent))
            assertTrue(tangentAlignment > 1.0 - 1e-8, "endpoint ${expected.id} tangent alignment=$tangentAlignment")

            val nearEnd = curve.getPointAt(curve.totalArcLength - 1.0e-4).position
            val finalSegmentDirection = Vector3d(generated.position).sub(nearEnd).normalize()
            val geometryAlignment = kotlin.math.abs(finalSegmentDirection.dot(expectedTangent))
            assertTrue(geometryAlignment > 0.99999, "endpoint ${expected.id} geometry alignment=$geometryAlignment")
        }
    }

    @Test
    fun `left turn parallel curves keep tangent continuity at every sample`() {
        val spacing = 4.5
        val starts = listOf(
            Anchor("A0", Vector3d(0.0, 0.0, -spacing), Vector3d(1.0, 0.0, 0.0)),
            Anchor("A1", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0)),
            Anchor("A2", Vector3d(0.0, 0.0, spacing), Vector3d(1.0, 0.0, 0.0))
        )
        val ends = listOf(
            Anchor("B0", Vector3d(50.0 + spacing, 0.0, -50.0), Vector3d(0.0, 0.0, 1.0)),
            Anchor("B1", Vector3d(50.0, 0.0, -50.0), Vector3d(0.0, 0.0, 1.0)),
            Anchor("B2", Vector3d(50.0 - spacing, 0.0, -50.0), Vector3d(0.0, 0.0, 1.0))
        )

        val result = RailCurveFactory.buildParallel(
            starts,
            ends,
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0)
        )

        assertTrue(result.isSuccess, result.error)
        assertEquals(3, result.curves!!.size)
        result.curves.forEach(::assertTangentContinuity)
    }

    @Test
    fun `straight template can manually use chord direction`() {
        val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("B", Vector3d(10.0, 0.0, 5.0), Vector3d(1.0, 0.0, 0.0))

        val result = RailCurveFactory.build(
            from,
            to,
            RailCurveBuildOptions(
                template = RailCurveTemplateType.STRAIGHT,
                straightMode = StraightMode.USE_CHORD
            )
        )

        assertTrue(result.isSuccess, result.error)
        assertEquals(RailCurveTemplateType.STRAIGHT, result.selectedTemplate)
    }

    @OptIn(Heavy::class)
    private fun assertTangentContinuity(curve: PrimeCurve) {
        val points = curve.stream().toList()
        assertTrue(points.size >= 2)
        points.zipWithNext().forEachIndexed { index, (from, to) ->
            val fromTangent = Vector3d(from.tangent).normalize()
            val toTangent = Vector3d(to.tangent).normalize()
            val chord = Vector3d(to.position).sub(from.position)
            if (chord.lengthSquared() < 1.0e-12) {
                val tangentAngle = Math.toDegrees(acos(fromTangent.dot(toTangent).coerceIn(-1.0, 1.0)))
                assertTrue(tangentAngle < 1.0e-6, "tangent jump at duplicate sample $index: $tangentAngle degrees")
                return@forEachIndexed
            }
            chord.normalize()
            val meanTangent = Vector3d(fromTangent).add(toTangent)
            assertTrue(meanTangent.lengthSquared() > 1.0e-12, "opposite tangents at sample $index")
            meanTangent.normalize()
            val geometryAngle = Math.toDegrees(acos(chord.dot(meanTangent).coerceIn(-1.0, 1.0)))
            assertTrue(geometryAngle < 1.0, "geometry tangent jump at sample $index: $geometryAngle degrees")
        }
        for (index in 1 until points.lastIndex) {
            val centeredChord = Vector3d(points[index + 1].position).sub(points[index - 1].position)
            if (centeredChord.lengthSquared() < 1.0e-12) continue
            centeredChord.normalize()
            val tangent = Vector3d(points[index].tangent).normalize()
            val centeredAngle = Math.toDegrees(acos(centeredChord.dot(tangent).coerceIn(-1.0, 1.0)))
            assertTrue(centeredAngle < 1.0, "stored tangent differs from local geometry at sample $index: $centeredAngle degrees")
        }
    }

    @Test
    fun `build fails on installed main thread`() {
        val mainThread = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === mainThread
        })
        try {
            val from = Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
            val to = Anchor("B", Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
            assertThrows(IllegalStateException::class.java) {
                RailCurveFactory.build(from, to, RailCurveBuildOptions())
            }
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }
}
