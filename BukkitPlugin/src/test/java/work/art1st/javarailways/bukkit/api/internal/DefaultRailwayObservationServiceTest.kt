package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class DefaultRailwayObservationServiceTest {
    @Test
    fun `latest and subscriptions receive each completed epoch once`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val epochs = mutableListOf<Long>()
        val subscription = service.subscribe(plugin("signal"), worldId) { callbackWorldId, observation ->
            assertEquals(worldId, callbackWorldId)
            epochs += observation.stamp.physicsEpoch
        }

        assertEquals(1L, service.publish(worldId, frame(1L), 1L)?.stamp?.physicsEpoch)
        assertEquals(null, service.publish(worldId, frame(1L), 1L))
        assertEquals(2L, service.publish(worldId, frame(2L), 2L)?.stamp?.physicsEpoch)

        assertEquals(listOf(1L, 2L), epochs)
        assertEquals(2L, service.latest(worldId).orElseThrow().stamp.physicsEpoch)
        subscription.close()
        subscription.close()
        service.publish(worldId, frame(3L), 3L)
        assertEquals(listOf(1L, 2L), epochs)
    }

    @Test
    fun `listener failures are isolated and owner cleanup is identity scoped`() {
        val errorCount = AtomicInteger()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level.intValue() >= Level.SEVERE.intValue()) errorCount.incrementAndGet()
                }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val service = DefaultRailwayObservationService(logger)
        val worldId = UUID.randomUUID()
        val failingOwner = plugin("failing")
        val healthyOwner = plugin("healthy")
        val healthyCalls = AtomicInteger()
        service.subscribe(failingOwner, worldId) { _, _ -> error("listener failure") }
        service.subscribe(healthyOwner, worldId) { _, _ -> healthyCalls.incrementAndGet() }

        service.publish(worldId, frame(1L), 1L)
        service.unregisterOwner(failingOwner)
        service.unregisterOwner(failingOwner)
        service.publish(worldId, frame(2L), 2L)

        assertEquals(1, errorCount.get())
        assertEquals(2, healthyCalls.get())
    }

    @Test
    fun `world removal and close clear retained state and registrations`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val calls = AtomicInteger()
        service.subscribe(plugin("signal"), worldId) { _, _ -> calls.incrementAndGet() }
        service.publish(worldId, frame(1L), 1L)

        service.removeWorld(worldId)
        assertTrue(service.latest(worldId).isEmpty)

        service.close()
        service.close()
        assertEquals(null, service.publish(worldId, frame(2L), 2L))
        assertTrue(service.latest(worldId).isEmpty)
        assertEquals(1, calls.get())
        assertThrows(IllegalStateException::class.java) {
            service.subscribe(plugin("late"), worldId) { _, _ -> Unit }
        }
    }

    @Test
    fun `current owner views retain one notification per physical epoch across topology changes`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val epochs = mutableListOf<Long>()
        service.subscribe(plugin("signal"), worldId) { _, observation -> epochs += observation.stamp.physicsEpoch }
        val firstFrame = frame(1L)
        val first = service.current(worldId, firstFrame)
        assertSame(first, service.current(worldId, firstFrame))
        assertTrue(epochs.isEmpty())
        assertSame(first, service.publish(worldId, firstFrame, 1L))

        val editedFrame = frame(1L, topologyRevision = 5L)
        val edited = service.current(worldId, editedFrame)
        assertEquals(5L, edited?.stamp?.topologyRevision)
        assertNull(service.publish(worldId, editedFrame, 1L))
        assertSame(edited, service.latest(worldId).orElseThrow())
        assertEquals(listOf(1L), epochs)

        val nextFrame = frame(2L, topologyRevision = 5L)
        val next = service.current(worldId, nextFrame)
        assertSame(next, service.publish(worldId, nextFrame, 2L))
        assertEquals(listOf(1L, 2L), epochs)
        assertThrows(IllegalArgumentException::class.java) { service.current(worldId, editedFrame) }
        assertSame(next, service.latest(worldId).orElseThrow())
        service.close()
        assertNull(service.current(worldId, frame(3L)))
    }

    @Test
    fun `maintenance placement immediately replaces current occupancy without another epoch notification`() {
        val world = createTestWorld()
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1))
            val train = createTrainWithConfig("placed", rail, world, listOf(10.0))
            world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
            val beforeFrame = world.latestWorldFrame()
            val previousIntervals = beforeFrame.trains.single().occupiedIntervals
            val targetInterval = RailInterval(rail.id, 90.0, 100.0)
            var notifications = 0
            service.subscribe(plugin("signal"), worldId) { _, _ -> notifications++ }
            val before = requireNotNull(service.publish(worldId, beforeFrame, beforeFrame.physicsEpoch))
            assertEquals(listOf(train.id), before.occupancy.blockingTrainIds(previousIntervals))
            assertTrue(before.occupancy.blockingTrainIds(listOf(targetInterval)).isEmpty())

            world.onOwnerForTest {
                train.setLocation(rail.viewAB.getRailLocationAt(100.0))
                world.publishOwnerChanges(listOf(train))
            }
            val afterFrame = world.latestWorldFrame()
            val current = requireNotNull(service.current(worldId, afterFrame))
            assertEquals(before.stamp.physicsEpoch, current.stamp.physicsEpoch)
            assertTrue(current.occupancy.blockingTrainIds(previousIntervals).isEmpty())
            assertEquals(listOf(train.id), current.occupancy.blockingTrainIds(listOf(targetInterval)))
            assertSame(current, service.latest(worldId).orElseThrow())
            assertSame(current, service.current(worldId, afterFrame))
            assertNull(service.publish(worldId, afterFrame, afterFrame.physicsEpoch))
            assertEquals(1, notifications)
            assertEquals(listOf(train.id), before.occupancy.blockingTrainIds(previousIntervals))
        } finally {
            service.close()
            world.physicsCoordinator.close()
            world.releaseRuntimeBinding()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `publication rejects a frame from a different ticket epoch`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        assertThrows(IllegalArgumentException::class.java) {
            service.publish(UUID.randomUUID(), frame(2L), 1L)
        }
    }

    private fun frame(epoch: Long, topologyRevision: Long = 4L) = RailwayWorldFrameSnapshot.create(
        epoch,
        topologyRevision,
        emptyList(),
        ActiveRailContinuationsSnapshot.empty(topologyRevision)
    )

    private fun plugin(name: String): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName" -> name
                "isEnabled" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Plugin($name)"
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
