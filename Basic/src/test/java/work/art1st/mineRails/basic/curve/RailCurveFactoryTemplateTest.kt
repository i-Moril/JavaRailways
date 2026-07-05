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
