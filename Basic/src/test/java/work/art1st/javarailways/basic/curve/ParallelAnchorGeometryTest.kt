package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParallelAnchorGeometryTest {
    @Test
    fun `orders parallel cross sections geometrically despite tangent signs and input order`() {
        val start = listOf(anchor("s-right", 0.0, -4.0, -1.0), anchor("s-left", 0.0, 4.0, 1.0))
        val end = listOf(anchor("e-left", 20.0, 4.0, -1.0), anchor("e-right", 20.0, -4.0, 1.0))

        val result = ParallelAnchorGeometry.match(start, end, expectedSpacing = 8.0)

        assertTrue(result is ParallelAnchorMatch.Success, "$result")
        result as ParallelAnchorMatch.Success
        assertEquals(listOf("s-right", "s-left"), result.start.map(Anchor::id))
        assertEquals(listOf("e-right", "e-left"), result.end.map(Anchor::id))
    }

    @Test
    fun `rejects anchors that do not share a cross section`() {
        val start = listOf(anchor("s0", 0.0, 0.0), anchor("s1", 2.0, 4.0))
        val end = listOf(anchor("e0", 20.0, 0.0), anchor("e1", 20.0, 4.0))

        val result = ParallelAnchorGeometry.match(start, end, expectedSpacing = 4.0)

        assertTrue(result is ParallelAnchorMatch.Failure)
    }

    @Test
    fun `rejects mismatched spacing without persisted correspondence`() {
        val start = listOf(anchor("s0", 0.0, 0.0), anchor("s1", 0.0, 4.0))
        val end = listOf(anchor("e0", 20.0, 0.0), anchor("e1", 20.0, 6.0))

        val result = ParallelAnchorGeometry.match(start, end, expectedSpacing = 4.0)

        assertTrue(result is ParallelAnchorMatch.Failure)
    }

    private fun anchor(id: String, x: Double, z: Double, sign: Double = 1.0) = Anchor(
        id,
        Vector3d(x, 64.0, z),
        Vector3d(sign, 0.0, 0.0)
    )
}
