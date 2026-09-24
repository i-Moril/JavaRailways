package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class RailTopologyEditorTest {
    @Test
    fun `split preserves outer topology and merge restores geometry`() {
        val world = createTestWorld()
        val previous = rail(world, -5.0, 0.0)
        val original = world.createRail(straightCurve(0.0, 10.0))
        val next = rail(world, 10.0, 15.0)
        CoreRail.connect(previous, original)
        CoreRail.connect(original, next)
        val originalCurve = original.curve
        val (beforeCurve, afterCurve) = originalCurve.splitAt(4.25)
        val revisionBeforeSplit = world.topologyRevision

        val split = RailTopologyEditor.splitWithPreparedCurves(
            original,
            beforeCurve,
            afterCurve,
            operationId = "split-test"
        )

        assertEquals(revisionBeforeSplit + 1L, world.topologyRevision)
        assertNull(world.getRail(original.id))
        assertEquals(4, world.allRails.size)
        assertEquals(4.25, split.before.curve.totalArcLength, 1e-12)
        assertEquals(5.75, split.after.curve.totalArcLength, 1e-12)
        assertEquals("split-test", split.operationId)
        assertSame(split.before.viewAB, previous.viewAB.nextActiveRail)
        assertSame(split.after.viewAB, split.before.viewAB.nextActiveRail)
        assertSame(split.before.viewBA, split.after.viewBA.nextActiveRail)
        assertSame(next.viewAB, split.after.viewAB.nextActiveRail)

        val mergedCurve = split.before.curve.concatenate(split.after.curve)
        val revisionBeforeMerge = world.topologyRevision
        val merged = RailTopologyEditor.mergeWithPreparedCurve(split.before, split.after, mergedCurve)

        assertEquals(revisionBeforeMerge + 1L, world.topologyRevision)
        assertNull(world.getRail(split.before.id))
        assertNull(world.getRail(split.after.id))
        assertEquals(3, world.allRails.size)
        assertSame(merged.viewAB, previous.viewAB.nextActiveRail)
        assertSame(next.viewAB, merged.viewAB.nextActiveRail)
        for (index in 0..40) {
            val arc = originalCurve.totalArcLength * index / 40.0
            assertTrue(originalCurve.getPointAt(arc).position.distance(merged.curve.getPointAt(arc).position) < 1e-9)
        }
    }

    @Test
    fun `split is rejected while affected rail is occupied`() {
        val world = createTestWorld()
        val original = rail(world, 0.0, 20.0)
        createTrainWithConfig("occupied", original, world, listOf(8.0))
        val (beforeCurve, afterCurve) = original.curve.splitAt(10.0)

        assertThrows(IllegalArgumentException::class.java) {
            RailTopologyEditor.splitWithPreparedCurves(original, beforeCurve, afterCurve)
        }

        assertSame(original, world.getRail(original.id))
        assertEquals(1, world.allRails.size)
    }

    @Test
    fun `merge is rejected when another rail connects to the split anchor`() {
        val world = createTestWorld()
        val original = rail(world, 0.0, 10.0)
        val (beforeCurve, afterCurve) = original.curve.splitAt(4.0)
        val split = RailTopologyEditor.splitWithPreparedCurves(original, beforeCurve, afterCurve, "branched")
        val branch = world.createRail(
            PrimeCurve.fromPoints(
                listOf(
                    rawPoint(Vector3d(4.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, Curve.SegmentType.LINE_START),
                    rawPoint(Vector3d(4.0, 0.0, 5.0), Vector3d(0.0, 0.0, 1.0), 5.0, Curve.SegmentType.LINE_END)
                )
            )
        )
        CoreRail.connect(split.before, branch)

        val error = RailTopologyEditor.mergeEligibilityError(split.before, split.after)

        assertFalse(error.isNullOrBlank())
        assertTrue(error!!.contains("其他轨道"))
    }

    private fun rail(world: work.art1st.javarailways.core.physics.CoreWorld, from: Double, to: Double): CoreRail =
        world.createRail(straightCurve(from, to))

    private fun straightCurve(from: Double, to: Double): PrimeCurve {
        val length = to - from
        return PrimeCurve.fromPoints(
            listOf(
                rawPoint(Vector3d(from, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
                rawPoint(Vector3d(to, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), length, Curve.SegmentType.LINE_END)
            )
        )
    }

    private fun rawPoint(
        position: Vector3d,
        tangent: Vector3d,
        arc: Double,
        type: Curve.SegmentType
    ): Curve.RawPoint = Curve.RawPoint(position, tangent, Vector3d(0.0, 1.0, 0.0), arc, type)
}
