package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
            assertTrue(curve.getPointAt(0.0).position.distance(starts[index].position) < 0.25)
            assertTrue(curve.getPointAt(curve.totalArcLength).position.distance(ends[index].position) < 0.25)
        }
        val mid0 = result.curves[0].getPointAt(result.curves[0].totalArcLength * 0.5).position
        val mid1 = result.curves[1].getPointAt(result.curves[1].totalArcLength * 0.5).position
        val mid2 = result.curves[2].getPointAt(result.curves[2].totalArcLength * 0.5).position
        assertTrue(mid0.distance(mid1) > spacing * 0.8)
        assertTrue(mid1.distance(mid2) > spacing * 0.8)
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
}
