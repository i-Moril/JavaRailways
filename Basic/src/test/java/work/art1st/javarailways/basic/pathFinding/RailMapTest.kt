package work.art1st.javarailways.basic.pathFinding

import work.art1st.javarailways.core.physics.createRailForTest

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld

/**
 * RailMap 基本功能测试。
 */
class RailMapTest {

    private lateinit var world: work.art1st.javarailways.core.physics.CoreWorld

    @BeforeEach
    fun setup() {
        world = createTestWorld()
    }

    @Test
    fun `addDestination and hasDestination - single label`() {
        val segments = PrimeCurveGenerator.buildStraight(50.0)
        val rail = createStraightRailFromSegments(world, segments)
        val map = RailMap()

        assertFalse(map.hasDestination("station"))
        map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "station"))
        assertTrue(map.hasDestination("station"))
    }

    @Test
    fun `addDestination - multiple labels on same view`() {
        val segments = PrimeCurveGenerator.buildStraight(50.0)
        val rail = createStraightRailFromSegments(world, segments)
        val map = RailMap()

        map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "station"))
        map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "depot"))

        assertTrue(map.hasDestination("station"))
        assertTrue(map.hasDestination("depot"))
    }

    @Test
    fun `removeDestination - removes only matching label`() {
        val segments = PrimeCurveGenerator.buildStraight(50.0)
        val rail = createStraightRailFromSegments(world, segments)
        val map = RailMap()

        val station = RailDestination(rail.viewAB, "station")
        map.addDestination(rail.viewAB, station)
        map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "depot"))
        map.removeDestination(rail.viewAB, "station")

        assertFalse(map.hasDestination("station"))
        assertTrue(map.hasDestination("depot"))
        assertEquals(RailObjectLifecycleState.REMOVED, station.lifecycleState)
    }

    @Test
    fun `removeDestination - non-existent label does not throw`() {
        val segments = PrimeCurveGenerator.buildStraight(50.0)
        val rail = createStraightRailFromSegments(world, segments)
        val map = RailMap()

        map.addDestination(rail.viewAB, RailDestination(rail.viewAB, "station"))
        map.removeDestination(rail.viewAB, "nonexistent") // 不抛异常

        assertTrue(map.hasDestination("station"))
    }

    @Test
    fun `empty map has no destinations`() {
        val map = RailMap()
        assertFalse(map.hasDestination("any"))
    }

    @Test
    fun `basicWorldData requires explicit registration`() {
        assertThrows(IllegalStateException::class.java) {
            world.basicWorldData
        }

        val data = TestBasicWorldDataRegistry.getOrCreate(world)
        assertEquals(data, world.basicWorldData)
    }

    @Nested
    inner class RailViewExtensions {
        @Test
        fun `RailView addDestination and removeDestination delegate to RailMap`() {
            val segments = PrimeCurveGenerator.buildStraight(50.0)
            val rail = createStraightRailFromSegments(world, segments)
            val map = RailMap()
            // 注册到测试 registry（绕过 BasicPlugin 初始化）
            work.art1st.javarailways.basic.pathFinding.TestRailMapRegistry.register(world, map)

            rail.viewAB.addDestination(RailDestination(rail.viewAB, "label1"))

            assertTrue(map.hasDestination("label1"))

            rail.viewAB.removeDestination("label1")

            assertFalse(map.hasDestination("label1"))
        }
    }
}

// 来自 Core 测试工具的内联版本
private fun createStraightRailFromSegments(
    world: work.art1st.javarailways.core.physics.CoreWorld,
    segments: List<work.art1st.javarailways.core.api.physics.curve.PrimeCurve>
): work.art1st.javarailways.core.physics.rail.CoreRail {
    if (segments.isEmpty()) error("至少需要一段轨道")
    val rails = segments.map(world::createRailForTest)
    for (i in 0 until rails.lastIndex) {
        work.art1st.javarailways.core.physics.rail.CoreRail.connect(rails[i], rails[i + 1])
    }
    return rails.first()
}
