package work.art1st.mineRails.basic.pathFinding

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createLoopRailFromSegments
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

/**
 * PathFinder A* 路径查找测试。
 */
class PathFinderTest {

    private lateinit var world: work.art1st.mineRails.core.physics.CoreWorld

    @BeforeEach
    fun setup() {
        world = createTestWorld()
    }

    @Nested
    inner class FindPathLinear {
        @Test
        fun `findPath - single segment straight track to destination`() {
            val segments = PrimeCurveGenerator.buildStraight(50.0)
            val rail = createStraightRailFromSegments(world, segments)
            val map = RailMap()
            map.addDestination(rail.viewBA, RailDestination(rail.viewBA, "station"))

            val finder = PathFinder(map)
            val path = finder.findPath(rail.viewAB, "station")

            assertNotNull(path)
            assertTrue(path!!.isNotEmpty())
            assertTrue(path.any { it.rail == rail })
        }

        @Test
        fun `findPath - non-existent label returns null`() {
            val segments = PrimeCurveGenerator.buildStraight(50.0)
            val rail = createStraightRailFromSegments(world, segments)
            val map = RailMap()
            map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "station"))

            val finder = PathFinder(map)
            val path = finder.findPath(rail.viewAB, "nonexistent")

            assertNull(path)
        }

        @Test
        fun `findPath - starts at same view as destination returns single element path`() {
            val segments = PrimeCurveGenerator.buildStraight(50.0)
            val rail = createStraightRailFromSegments(world, segments)
            val map = RailMap()
            map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "here"))

            val finder = PathFinder(map)
            val path = finder.findPath(rail.viewAB, "here")

            assertNotNull(path)
            assertEquals(1, path!!.size)
            assertEquals(rail.viewAB, path[0])
        }
    }

    @Nested
    inner class FindPathLoop {
        @Test
        @Disabled("createLoopRailFromSegments 使用的 RailView.connect 不兼容 buildCircle 生成的弧段端点")
        fun `findPath - loop track returns correct path`() {
            val segments = PrimeCurveGenerator.buildCircle(40.0)
            val rail = createLoopRailFromSegments(world, segments)
            val map = RailMap()
            map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "depot"))

            val finder = PathFinder(map)
            val path = finder.findPath(rail.viewAB, "depot")

            assertNotNull(path)
            assertTrue(path!!.isNotEmpty())
            // 路径应该从起点开始
            assertEquals(rail.viewAB, path.first())
            // 路径中应包含目的地视图
            assertTrue(path.any { it == rail.viewAB })
        }
    }

    @Nested
    inner class FindPathMultiSegment {
        @Test
        fun `findPath - multi-segment track traverses rails`() {
            // 创建三段直轨道连成一条线
            val seg1 = PrimeCurveGenerator.buildStraight(30.0)
            val seg2 = PrimeCurveGenerator.buildStraight(30.0)
            val seg3 = PrimeCurveGenerator.buildStraight(30.0)
            val rail1 = createStraightRailFromSegments(world, seg1)
            val rail2 = createStraightRailFromSegments(world, seg2)
            val rail3 = createStraightRailFromSegments(world, seg3)

            // 手动连接：seg1 -> seg2 -> seg3
            work.art1st.mineRails.core.physics.rail.CoreRail.connect(rail1, rail2)
            work.art1st.mineRails.core.physics.rail.CoreRail.connect(rail2, rail3)

            val map = RailMap()
            map.addDestination(rail3.viewAB, RailDestination(rail3.viewAB, "end"))

            val finder = PathFinder(map)
            val path = finder.findPath(rail1.viewAB, "end")

            assertNotNull(path)
            // 路径应经过多段轨道
            assertTrue(path!!.map { it.rail }.distinct().size >= 2, "路径应包含多段轨道")
        }
    }
}