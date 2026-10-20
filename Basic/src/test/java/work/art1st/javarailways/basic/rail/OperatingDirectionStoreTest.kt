package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OperatingDirectionStoreTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    private fun newTestWorld() = TestWorldFixture.create().also { fixtures += it }.world

    @AfterEach
    fun closeTestWorlds() {
        var firstFailure: Throwable? = null
        fixtures.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure else primary.addSuppressed(failure)
            }
        }
        fixtures.clear()
        firstFailure?.let { throw it }
    }

    @Test
    fun `requested subset never mixes two scoped replacement states`() {
        val world = newTestWorld()
        val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val second = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val topology = world.topologyState
        val rails = listOf(first.id, second.id)
        val store = OperatingDirectionStore()
        val forward = rails.associateWith { ViewDirection.AB }
        val reverse = rails.associateWith { ViewDirection.BA }
        store.restore(forward)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                start.await()
                repeat(1000) { store.replaceRails(rails, if (it % 2 == 0) reverse else forward) }
            }
            val reader = executor.submit {
                start.await()
                repeat(1000) {
                    val directions = store.snapshot(topology, rails).directions
                    assertTrue(directions == forward || directions == reverse)
                }
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `change callback executes outside store lock`() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var store: OperatingDirectionStore
        store = OperatingDirectionStore {
            executor.submit<Map<UUID, ViewDirection>> { store.entries() }.get(5, TimeUnit.SECONDS)
        }
        try {
            store.put(UUID.randomUUID(), ViewDirection.AB)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Basic direction overlay revalidates old topology and rejects orphan policy`() {
        val world = newTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val topology = world.topologyState
        val store = BasicWorldData().operatingDirections
        store.put(rail.id, ViewDirection.BA)
        val overlay = store.overlay(topology)
        val forbidden = requireNotNull(overlay.toRestrictions(topology)).forbiddenTraversals.single()
        assertEquals(rail.id, forbidden.railId)
        assertEquals(ViewDirection.AB, forbidden.facing)
        assertEquals(0.0, forbidden.lowerAbMeters)
        assertEquals(100.0, forbidden.upperAbMeters, 1.0e-6)
        store.put(UUID.randomUUID(), ViewDirection.AB)
        assertNull(store.overlay(topology).toRestrictions(topology))
        createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val revalidated = requireNotNull(overlay.toRestrictions(world.topologyState))
        assertEquals(world.topologyState.revision, revalidated.topologyRevision)
        assertEquals(listOf(forbidden), revalidated.forbiddenTraversals)
    }

    @Test
    fun `Basic world owns detached complete direction subsets without SimpleATO`() {
        val world = newTestWorld()
        val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val second = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val data = BasicWorldData()
        val other = BasicWorldData()
        data.operatingDirections.put(first.id, ViewDirection.AB)
        val topology = world.topologyState
        val read = data.operatingDirections.snapshot(topology, listOf(first.id, second.id))
        assertEquals(topology, read.topology)
        assertEquals(mapOf(first.id to ViewDirection.AB, second.id to null), read.directions)
        assertNull(other.operatingDirections[first.id])
        data.operatingDirections.replaceRails(listOf(first.id, second.id), mapOf(second.id to ViewDirection.BA))
        assertEquals(ViewDirection.AB, read.directions[first.id])
        assertNull(read.directions[second.id])
        assertThrows(UnsupportedOperationException::class.java) { (read.directions as MutableMap).clear() }
        assertThrows(IllegalArgumentException::class.java) { data.operatingDirections.snapshot(topology, listOf(UUID.randomUUID())) }
    }

    @Test
    fun `replacement collision refuses the complete batch and emits no notification`() {
        val changes = arrayListOf<Set<UUID>>()
        val store = OperatingDirectionStore { changes += it }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.restore(mapOf(first to ViewDirection.AB, second to ViewDirection.BA))
        assertThrows(IllegalArgumentException::class.java) { store.replaceRails(listOf(first), mapOf(second to ViewDirection.AB)) }
        assertEquals(mapOf(first to ViewDirection.AB, second to ViewDirection.BA), store.entries())
        assertEquals(listOf(setOf(first, second)), changes)
    }

    @Test
    fun `central mutations notify only actual changed rails after publication`() {
        val changes = arrayListOf<Set<UUID>>()
        lateinit var store: OperatingDirectionStore
        store = OperatingDirectionStore { ids ->
            changes += ids
            store.entries() // a callback can read the already published state
        }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.put(first, null)
        store.restore(mapOf(first to ViewDirection.AB))
        store.put(first, ViewDirection.AB)
        store.replaceRails(listOf(first), mapOf(first to ViewDirection.AB))
        assertEquals(listOf(setOf(first)), changes)
        store.replaceRails(listOf(first), mapOf(second to ViewDirection.BA))
        assertEquals(setOf(first, second), changes.last())
        store.put(second, null)
        assertEquals(setOf(second), changes.last())
    }

    @Test
    fun `missing entry permits both directions and stored entry permits one`() {
        val store = OperatingDirectionStore()
        val railId = UUID.randomUUID()

        assertTrue(store.allows(railId, ViewDirection.AB))
        assertTrue(store.allows(railId, ViewDirection.BA))

        store.put(railId, ViewDirection.BA)
        assertFalse(store.allows(railId, ViewDirection.AB))
        assertTrue(store.allows(railId, ViewDirection.BA))

        store.put(railId, null)
        assertNull(store[railId])
    }

    @Test
    fun `rail replacement transfers one owned policy atomically`() {
        val store = OperatingDirectionStore()
        val old = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.put(old, ViewDirection.AB)

        store.replaceRails(
            removedRailIds = listOf(old),
            replacementDirections = mapOf(first to ViewDirection.AB, second to ViewDirection.AB)
        )

        assertNull(store[old])
        assertEquals(ViewDirection.AB, store[first])
        assertEquals(ViewDirection.AB, store[second])
    }

    @Test
    fun `arc authoring resolver rejects an ambiguous gesture`() {
        assertEquals(ViewDirection.AB, DirectionRestrictionResolver.fromArcPoints(10.0, 20.0))
        assertEquals(ViewDirection.BA, DirectionRestrictionResolver.fromArcPoints(20.0, 10.0))
        assertNull(DirectionRestrictionResolver.fromArcPoints(10.0, 10.1))
    }
}
