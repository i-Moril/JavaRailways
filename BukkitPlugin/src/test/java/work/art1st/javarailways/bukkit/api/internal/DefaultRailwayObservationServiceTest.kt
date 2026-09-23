package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
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
    fun `publication rejects a frame from a different ticket epoch`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        assertThrows(IllegalArgumentException::class.java) {
            service.publish(UUID.randomUUID(), frame(2L), 1L)
        }
    }

    private fun frame(epoch: Long) = RailwayWorldFrameSnapshot.create(epoch, 4L, emptyList())

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
