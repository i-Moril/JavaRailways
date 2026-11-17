package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.rail.railObjects
import work.art1st.javarailways.basic.api.route.planning.operatingDirectionOverlay
import work.art1st.javarailways.basic.basicWorldDataOrNull
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import java.util.UUID

class InfrastructureAccessorsTest {
    @Test
    fun `missing Basic data is not interpreted as an empty store or unrestricted direction`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val topology = world.topologyState
            assertThrows(IllegalStateException::class.java) { world.wayPoints }
            assertThrows(IllegalStateException::class.java) { world.railObjects }
            assertThrows(IllegalStateException::class.java) { world.operatingDirection(UUID.randomUUID()) }
            assertThrows(IllegalStateException::class.java) { world.operatingDirections(topology, emptyList()) }
            assertThrows(IllegalStateException::class.java) { world.operatingDirectionOverlay(topology) }
            assertNull(world.basicWorldDataOrNull)
        }
    }

    @Test
    fun `waypoint access uses the sole world store and preserves update and rename semantics`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val data = BasicWorldData()
            TestBasicWorldDataRegistry.register(world, data)
            try {
                val access = world.wayPoints
                assertSame(data.wayPoints, access)
                assertSame(data.railObjects, world.railObjects)
                val railId = UUID.randomUUID()
                val first = WayPoint("first", railId, 10.0)
                val last = WayPoint("last", railId, 30.0)
                access.add(last)
                data.wayPoints.add(first)
                val captured = access.all()
                assertEquals(listOf(first, last), captured)
                val replacement = first.copy(arcLength = 20.0)
                access.add(replacement)
                assertEquals(replacement, data.wayPoints[first.id])
                assertEquals(listOf(first, last), captured)
                assertThrows(IllegalArgumentException::class.java) { access.rename(first.id, last.id) }
                assertThrows(IllegalArgumentException::class.java) { access.rename(first.id, " ") }
                assertThrows(NoSuchElementException::class.java) { access.rename("missing", "middle") }
                assertThrows(IllegalArgumentException::class.java) { access.add(first.copy(id = " ")) }
                assertEquals(listOf(replacement, last), access.all())
                assertEquals(replacement, access.rename(first.id, first.id))
                val renamed = access.rename(first.id, "middle")
                assertEquals(replacement.copy(id = "middle"), renamed)
                assertNull(data.wayPoints[first.id])
                assertEquals(renamed, data.wayPoints[renamed.id])
                assertEquals(renamed, access.remove(renamed.id))
                assertNull(access.remove(renamed.id))
                assertEquals(listOf(last), access.all())
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `direction queries capture detached complete subsets and sparse overlays from the same store`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            val second = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            val data = BasicWorldData()
            TestBasicWorldDataRegistry.register(world, data)
            try {
                val topology = world.topologyState
                data.operatingDirections.put(first.id, ViewDirection.AB)
                val subset = world.operatingDirections(topology, listOf(first.id, second.id))
                val overlay = world.operatingDirectionOverlay(topology)
                assertSame(topology, subset.topology)
                assertEquals(mapOf(first.id to ViewDirection.AB, second.id to null), subset.directions)
                assertEquals(mapOf(first.id to ViewDirection.AB), overlay.allowedDirections)
                assertEquals(ViewDirection.AB, world.operatingDirection(first.id))
                assertNull(world.operatingDirection(second.id))
                data.operatingDirections.replaceRails(listOf(first.id), mapOf(second.id to ViewDirection.BA))
                assertNull(world.operatingDirection(first.id))
                assertEquals(ViewDirection.BA, world.operatingDirection(second.id))
                assertEquals(mapOf(first.id to ViewDirection.AB, second.id to null), subset.directions)
                assertEquals(mapOf(first.id to ViewDirection.AB), overlay.allowedDirections)
                assertThrows(UnsupportedOperationException::class.java) { (subset.directions as MutableMap).clear() }
                assertThrows(UnsupportedOperationException::class.java) { (overlay.allowedDirections as MutableMap).clear() }
                assertThrows(IllegalArgumentException::class.java) {
                    world.operatingDirections(topology, listOf(UUID.randomUUID()))
                }
                assertEquals(mapOf(second.id to ViewDirection.BA), world.operatingDirectionOverlay(topology).allowedDirections)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }
}
