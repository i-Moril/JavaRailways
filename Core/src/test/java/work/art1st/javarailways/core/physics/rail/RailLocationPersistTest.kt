package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailLocationPersistTest {

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    /** 创建一条直线 rail，起点 (0,64,0)，沿 +X 方向，长 100m。 */
    private fun createStraightRail(world: CoreWorld): CoreRail {
        val tan = Vector3d(1.0, 0.0, 0.0)
        val nrm = Vector3d(0.0, 1.0, 0.0)
        val start = Vector3d(0.0, 64.0, 0.0)
        val end = Vector3d(100.0, 64.0, 0.0)
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(start, tan, nrm, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(end, tan, nrm, 100.0, Curve.SegmentType.LINE_END)
        ))
        return world.createRailForTest(curve)
    }

    @Test
    fun `AB direction round-trip preserves position`() = withWorld { world ->
        val rail = createStraightRail(world)
        val locAB = RailLocationWithFacing(rail.getView(ViewDirection.AB), 30.0)

        val saved = locAB.save()
        val loaded = saved.load(rail.world) as RailLocationWithFacing

        assertEquals(ViewDirection.AB, loaded.facing)
        assertEquals(30.0, loaded.arcLength, 0.001)
        assertEquals(30.0, loaded.position.x(), 0.001)
        assertEquals(64.0, loaded.position.y(), 0.001)
        assertEquals(0.0, loaded.position.z(), 0.001)
    }

    @Test
    fun `BA direction round-trip preserves position`() = withWorld { world ->
        val rail = createStraightRail(world)
        // 在 BA 视图上弧长 30m 处 = 从终点往回 30m = 全局 X=70
        val locBA = RailLocationWithFacing(rail.getView(ViewDirection.BA), 30.0)

        val saved = locBA.save()
        val loaded = saved.load(rail.world) as RailLocationWithFacing

        assertEquals(ViewDirection.BA, loaded.facing)
        assertEquals(30.0, loaded.arcLength, 0.001)
        // BA 方向弧长 30m: 从终点 (100, 64, 0) 沿 -X 往回 30m → X=70
        assertEquals(70.0, loaded.position.x(), 0.01)
        assertEquals(64.0, loaded.position.y(), 0.01)
        assertEquals(0.0, loaded.position.z(), 0.01)
        // BA 切线 = -AB 切线
        assertEquals(-1.0, loaded.tangent.x(), 0.01)
    }

    @Test
    fun `BA end location round-trip`() = withWorld { world ->
        val rail = createStraightRail(world)
        // BA 起点 = AB 终点 (X=100)
        val locEnd = RailLocationWithFacing(rail.getView(ViewDirection.BA), 0.0)

        val saved = locEnd.save()
        val loaded = saved.load(rail.world) as RailLocationWithFacing

        assertEquals(ViewDirection.BA, loaded.facing)
        assertEquals(0.0, loaded.arcLength, 0.001)
        // BA 弧长 0 = AB 终点 = X=100
        assertEquals(100.0, loaded.position.x(), 0.01)
        assertEquals(64.0, loaded.position.y(), 0.01)
        assertEquals(0.0, loaded.position.z(), 0.01)
    }

    @Test
    fun `AB start location round-trip`() = withWorld { world ->
        val rail = createStraightRail(world)
        val locStart = RailLocationWithFacing(rail.getView(ViewDirection.AB), 0.0)

        val saved = locStart.save()
        val loaded = saved.load(rail.world) as RailLocationWithFacing

        assertEquals(ViewDirection.AB, loaded.facing)
        // AB 弧长 0 = X=0
        assertEquals(0.0, loaded.position.x(), 0.01)
        assertEquals(64.0, loaded.position.y(), 0.01)
        assertEquals(0.0, loaded.position.z(), 0.01)
    }

    @Test
    fun `BA mid location tangent is negated AB tangent`() = withWorld { world ->
        val rail = createStraightRail(world)
        // AB 方向 50m 处 (X=50), AB 切线 = (+1, 0, 0)
        val locAB = RailLocationWithFacing(rail.getView(ViewDirection.AB), 50.0)
        assertEquals(1.0, locAB.tangent.x(), 0.01)

        // BA 方向 50m 处 = 从终点往回 50m = X=50, BA 切线 = (-1, 0, 0)
        val locBA = RailLocationWithFacing(rail.getView(ViewDirection.BA), 50.0)
        assertEquals(-1.0, locBA.tangent.x(), 0.01)

        // 两者位置相同但切线相反
        assertEquals(locAB.position.x(), locBA.position.x(), 0.01)
        assertEquals(-locAB.tangent.x(), locBA.tangent.x(), 0.01)
    }
}
