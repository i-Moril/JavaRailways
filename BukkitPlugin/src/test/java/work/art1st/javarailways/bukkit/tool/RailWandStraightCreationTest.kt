package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.curve.RailCurveBuildResult
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurveTemplateType

class RailWandStraightCreationTest {
    @Test
    fun `right clicked point sets elevation while horizontal position follows the start bearing`() {
        val startPosition = Vector3d(2.0, 3.0, -4.0)
        val startTangent = Vector3d(2.0, 1.0, -1.0).normalize()
        val clickedPoint = Vector3d(14.0, 20.0, 9.0)

        val terminal = straightTerminalPosition(startPosition, startTangent, clickedPoint)

        val horizontalOffset = Vector3d(terminal).sub(startPosition).also { it.y = 0.0 }
        val horizontalTangent = Vector3d(startTangent.x, 0.0, startTangent.z).normalize()
        assertTrue(Vector3d(horizontalOffset).cross(horizontalTangent).length() < 1.0e-9)
        assertEquals(clickedPoint.y, terminal.y, 1.0e-9)
    }

    @Test
    fun `projected terminal creates a straight to the clicked elevation`() {
        val startPosition = Vector3d(2.0, 3.0, -4.0)
        val startTangent = Vector3d(2.0, 1.0, -1.0).normalize()
        val clickedPoint = Vector3d(14.0, 20.0, 9.0)
        val terminalPosition = straightTerminalPosition(startPosition, startTangent, clickedPoint)
        val terminalTangent = straightTerminalTangent(startPosition, terminalPosition)

        val result = RailCurveFactory.build(
            Anchor("A", startPosition, startTangent),
            Anchor("B", terminalPosition, terminalTangent),
            RailCurveBuildOptions(template = RailCurveTemplateType.STRAIGHT)
        )

        val resultSuccess = assertInstanceOf(RailCurveBuildResult.Success::class.java, result)
        val curve = resultSuccess.curve
        assertEquals(clickedPoint.y, curve.getPointAt(curve.totalArcLength).position.y(), 1.0e-9)
        assertTrue(Vector3d(curve.getPointAt(0.0).tangent).dot(startTangent) > 1.0 - 1.0e-8)
        assertTrue(Vector3d(curve.getPointAt(curve.totalArcLength).tangent).dot(terminalTangent) > 1.0 - 1.0e-8)
    }

    @Test
    fun `two point straight gives an empty endpoint level pitch along the horizontal chord`() {
        val tangent = twoPointStraightTangent(
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(4.0, 2.0, 0.0),
            Vector3d(0.0, 0.0, 1.0),
            endpointIsEmpty = true
        )!!

        assertEquals(0.0, tangent.y, 1.0e-12)
        assertTrue(tangent.dot(Vector3d(1.0, 0.0, 0.0)) > 1.0 - 1.0e-12)
    }

    @Test
    fun `two point straight rejects a connected endpoint with a conflicting tangent`() {
        val tangent = twoPointStraightTangent(
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(4.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 1.0),
            endpointIsEmpty = false
        )

        assertEquals(null, tangent)
    }

    @Test
    fun `two point straight preserves manual pitch at a connected endpoint`() {
        val current = Vector3d(1.0, 0.2, 0.0).normalize()
        val tangent = twoPointStraightTangent(
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(4.0, 2.0, 0.0),
            current,
            endpointIsEmpty = false
        )!!

        assertTrue(tangent.dot(current) > 1.0 - 1.0e-12)
    }

    @Test
    fun `two point straight preserves equivalent reverse tangent at connected endpoint`() {
        val tangent = twoPointStraightTangent(
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(4.0, 0.0, 0.0),
            Vector3d(-1.0, 0.0, 0.0),
            endpointIsEmpty = false
        )!!

        assertTrue(tangent.dot(Vector3d(-1.0, 0.0, 0.0)) > 1.0 - 1.0e-12)
    }

    @Test
    fun `level connection does not rebuild adjacent vertical profiles`() {
        val first = straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val second = straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))

        assertFalse(requiresRailWandVerticalSmoothing(listOf(first, second)))
    }

    @Test
    fun `grade discontinuity requests adjacent vertical profile smoothing`() {
        val first = straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val second = straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 2.0, 0.0))

        assertTrue(requiresRailWandVerticalSmoothing(listOf(first, second)))
    }

    private fun straightCurve(from: Vector3d, to: Vector3d) = assertInstanceOf(
        RailCurveBuildResult.Success::class.java,
        RailCurveFactory.build(
            Anchor("from", from, Vector3d(to).sub(from).normalize()),
            Anchor("to", to, Vector3d(to).sub(from).normalize()),
            RailCurveBuildOptions(template = RailCurveTemplateType.STRAIGHT)
        )
    ).curve

    @Test
    fun `straight terminal anchor defaults to level pitch along the horizontal chord`() {
        val start = Vector3d(1.0, 2.0, 3.0)
        val terminalPosition = Vector3d(5.0, 5.0, 1.0)

        val terminal = straightTerminalTangent(start, terminalPosition)
        val expected = Vector3d(terminalPosition).sub(start).also { it.y = 0.0 }.normalize()

        assertEquals(1.0, terminal.length(), 1.0e-12)
        assertEquals(0.0, terminal.y, 1.0e-12)
        assertTrue(terminal.dot(expected) > 1.0 - 1.0e-12)
    }
}
