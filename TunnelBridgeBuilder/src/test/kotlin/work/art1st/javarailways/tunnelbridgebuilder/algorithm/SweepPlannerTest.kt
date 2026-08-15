package work.art1st.javarailways.tunnelbridgebuilder.algorithm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SweepPlannerTest {
    @Test
    fun `sweeps a cross section through every center-line block and cuts flat ends`() {
        val section = CrossSection(
            rows = listOf(
                listOf(Ignore, Place("roof"), Ignore),
                listOf(Place("left"), Carve, Place("right")),
                listOf(Ignore, Place("foundation"), Ignore)
            ),
            anchor = SectionCoordinate(column = 1, row = 1)
        )
        val path = straightPath(
            start = Vec3(0.5, 64.5, 0.5),
            end = Vec3(0.5, 64.5, 2.5),
            tangent = Vec3.UNIT_Z,
            normal = Vec3.UNIT_Y
        )

        val changes = SweepPlanner.plan(section, path)
        val byPosition = changes.associateBy(BlockPositionChange<String>::position)

        assertEquals(15, changes.size)
        for (z in 0..2) {
            assertEquals(Place("roof"), byPosition[BlockPosition(0, 65, z)]?.operation)
            assertEquals(Place("left"), byPosition[BlockPosition(-1, 64, z)]?.operation)
            assertEquals(Carve, byPosition[BlockPosition(0, 64, z)]?.operation)
            assertEquals(Place("right"), byPosition[BlockPosition(1, 64, z)]?.operation)
            assertEquals(Place("foundation"), byPosition[BlockPosition(0, 63, z)]?.operation)
        }
        assertEquals(
            Vec3(0.5, 64.5, 1.5),
            byPosition[BlockPosition(0, 64, 1)]?.cellCenter
        )
        assertEquals(
            Vec3(0.5, 65.5, 1.5),
            byPosition[BlockPosition(0, 65, 1)]?.cellCenter
        )
        assertFalse(changes.any { it.position.z < 0 || it.position.z > 2 })
        assertEquals(changes.size, changes.map { it.position }.toSet().size)
    }

    @Test
    fun `inverse mapping follows a diagonal line without skipping center blocks`() {
        val section = CrossSection(
            rows = listOf(listOf(Place("center"))),
            anchor = SectionCoordinate(0, 0)
        )
        val direction = Vec3(1.0, 0.0, 1.0).normalized()
        val path = straightPath(
            start = Vec3(0.5, 20.5, 0.5),
            end = Vec3(3.5, 20.5, 3.5),
            tangent = direction,
            normal = Vec3.UNIT_Y
        )

        val positions = SweepPlanner.plan(section, path).map { it.position }.toSet()

        assertEquals(
            setOf(
                BlockPosition(0, 20, 0),
                BlockPosition(1, 20, 1),
                BlockPosition(2, 20, 2),
                BlockPosition(3, 20, 3)
            ),
            positions
        )
    }

    @Test
    fun `curved path rotates the section frame`() {
        val section = CrossSection(
            rows = listOf(listOf(Carve, Place("right"))),
            anchor = SectionCoordinate(0, 0)
        )
        val path = SampledCenterLine(
            listOf(
                SweepFrame(Vec3(0.5, 10.5, 0.5), Vec3.UNIT_Z, Vec3.UNIT_Y),
                SweepFrame(Vec3(0.5, 10.5, 1.5), Vec3(1.0, 0.0, 1.0), Vec3.UNIT_Y),
                SweepFrame(Vec3(1.5, 10.5, 2.5), Vec3.UNIT_X, Vec3.UNIT_Y)
            )
        )

        val changes = SweepPlanner.plan(section, path)
        val byPosition = changes.associateBy(BlockPositionChange<String>::position)

        assertEquals(Carve, byPosition[BlockPosition(0, 10, 0)]?.operation)
        assertEquals(Place("right"), byPosition[BlockPosition(1, 10, 0)]?.operation)
        assertEquals(Carve, byPosition[BlockPosition(1, 10, 2)]?.operation)
        assertEquals(Place("right"), byPosition[BlockPosition(1, 10, 1)]?.operation)
    }

    @Test
    fun `section validates rectangular rows and anchor bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            CrossSection(
                rows = listOf(listOf(Place("a")), listOf(Place("b"), Place("c"))),
                anchor = SectionCoordinate(0, 0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrossSection(
                rows = listOf(listOf(Place("a"))),
                anchor = SectionCoordinate(1, 0)
            )
        }
    }

    @Test
    fun `sampled path rejects frames that face against path order`() {
        assertThrows(IllegalArgumentException::class.java) {
            SampledCenterLine(
                listOf(
                    SweepFrame(Vec3.ZERO, -Vec3.UNIT_Z, Vec3.UNIT_Y),
                    SweepFrame(Vec3.UNIT_Z, -Vec3.UNIT_Z, Vec3.UNIT_Y)
                )
            )
        }
    }

    @Test
    fun `frame rebuilds an orthonormal section basis`() {
        val frame = SweepFrame(
            position = Vec3.ZERO,
            tangent = Vec3(0.0, 0.2, 1.0),
            normal = Vec3(0.0, 1.0, 0.1)
        )

        assertEquals(1.0, frame.tangent.length(), 1.0e-12)
        assertEquals(1.0, frame.normal.length(), 1.0e-12)
        assertEquals(1.0, frame.right.length(), 1.0e-12)
        assertEquals(0.0, frame.tangent dot frame.normal, 1.0e-12)
        assertEquals(0.0, frame.tangent dot frame.right, 1.0e-12)
        assertEquals(0.0, frame.normal dot frame.right, 1.0e-12)
        assertTrue(frame.right.x > 0.0)
    }

    @Test
    fun `upright frame removes cant roll while following rail gradient`() {
        val tangent = Vec3(1.0, 0.25, 1.0)
        val frame = SweepFrame.upright(Vec3.ZERO, tangent)

        assertEquals(0.0, frame.right.y, 1.0e-12)
        assertEquals(0.0, frame.tangent dot frame.normal, 1.0e-12)
        assertEquals(0.0, frame.tangent dot frame.right, 1.0e-12)
        assertTrue(frame.normal.y > 0.0)
    }

    private fun straightPath(
        start: Vec3,
        end: Vec3,
        tangent: Vec3,
        normal: Vec3
    ): SampledCenterLine = SampledCenterLine(
        listOf(
            SweepFrame(start, tangent, normal),
            SweepFrame(end, tangent, normal)
        )
    )
}
