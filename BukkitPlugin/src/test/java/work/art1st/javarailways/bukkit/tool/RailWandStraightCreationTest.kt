package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurveTemplateType

class RailWandStraightCreationTest {
    @Test
    fun `right clicked point defines the plane perpendicular to the start tangent`() {
        val startPosition = Vector3d(2.0, 3.0, -4.0)
        val startTangent = Vector3d(2.0, 1.0, -1.0).normalize()
        val clickedPoint = Vector3d(14.0, 20.0, 9.0)

        val terminal = straightTerminalPosition(startPosition, startTangent, clickedPoint)

        val lineOffset = Vector3d(terminal).sub(startPosition)
        assertTrue(Vector3d(lineOffset).cross(startTangent).length() < 1.0e-9)
        assertEquals(0.0, Vector3d(terminal).sub(clickedPoint).dot(startTangent), 1.0e-9)
    }

    @Test
    fun `projected terminal creates a straight without changing the start tangent`() {
        val startPosition = Vector3d(2.0, 3.0, -4.0)
        val startTangent = Vector3d(2.0, 1.0, -1.0).normalize()
        val clickedPoint = Vector3d(14.0, 20.0, 9.0)
        val terminalPosition = straightTerminalPosition(startPosition, startTangent, clickedPoint)

        val result = RailCurveFactory.build(
            Anchor("A", startPosition, startTangent),
            Anchor("B", terminalPosition, straightTerminalTangent(startTangent)),
            RailCurveBuildOptions(template = RailCurveTemplateType.STRAIGHT)
        )

        assertTrue(result.isSuccess, result.error)
        val curve = result.curve!!
        assertTrue(Vector3d(curve.getPointAt(0.0).tangent).dot(startTangent) > 1.0 - 1.0e-8)
        assertTrue(Vector3d(curve.getPointAt(curve.totalArcLength).tangent).dot(startTangent) > 1.0 - 1.0e-8)
    }

    @Test
    fun `straight terminal anchor reuses the selected tangent representation`() {
        val start = Vector3d(1.0, 0.25, -2.0).normalize()

        val terminal = straightTerminalTangent(start)

        assertEquals(1.0, terminal.length(), 1.0e-12)
        assertTrue(terminal.dot(start) > 1.0 - 1.0e-12)
    }
}
