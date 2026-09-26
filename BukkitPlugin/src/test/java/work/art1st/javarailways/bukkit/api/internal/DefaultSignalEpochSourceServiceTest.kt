package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import java.lang.reflect.Proxy
import java.util.UUID

class DefaultSignalEpochSourceServiceTest {
    private val railId = UUID.nameUUIDFromBytes("signal-service-rail".toByteArray())

    @Test
    fun `owner replacement preserves other owners and invalid union changes nothing`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val calls = mutableListOf<String>()
        val runtime = runtime()
        service.registerWorld(worldId, runtime, { emptyList() }) { reason, failure ->
            throw IllegalStateException(reason, failure)
        }
        service.replaceSources(firstOwner, worldId, listOf(source("test:first", 0.0) {
            calls += "first-old"
        }))
        service.replaceSources(secondOwner, worldId, listOf(source("test:second", 20.0) {
            calls += "second"
        }))
        runtime.process(frame(1L))
        assertEquals(listOf("first-old", "second"), calls)
        calls.clear()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSources(secondOwner, worldId, listOf(source("test:first", 40.0) {
                calls += "invalid"
            }))
        }
        service.replaceSources(firstOwner, worldId, listOf(source("test:first", 60.0) {
            calls += "first-new"
        }))
        runtime.process(frame(2L))

        assertEquals(listOf("first-new", "second"), calls)
    }

    @Test
    fun `planned removal requires explicit empty restriction streams`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        var active = listOf("train/test:main@4")
        val runtime = runtime()
        service.registerWorld(worldId, runtime, { sourceIds ->
            if ("test:main" in sourceIds) active else emptyList()
        }) { _, _ -> }
        service.replaceSources(owner, worldId, listOf(source("test:main", 0.0) {}))

        val failure = assertThrows(IllegalStateException::class.java) {
            service.replaceSources(owner, worldId, emptyList())
        }
        assertEquals(true, failure.message.orEmpty().contains("train/test:main@4"))

        active = emptyList()
        service.unregisterOwner(owner)
        service.unregisterOwner(owner)
        runtime.process(frame(1L))
    }

    @Test
    fun `forced disable removes callbacks and stalls every affected world`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        var enabled = true
        val disabledOwner = plugin("disabled") { enabled }
        val survivingOwner = plugin("surviving")
        val calls = mutableListOf<String>()
        val stalls = mutableListOf<String>()
        val runtime = runtime()
        service.registerWorld(worldId, runtime, { emptyList() }) { reason, _ -> stalls += reason }
        service.replaceSources(disabledOwner, worldId, listOf(source("test:disabled", 0.0) {
            calls += "disabled"
        }))
        service.replaceSources(survivingOwner, worldId, listOf(source("test:surviving", 20.0) {
            calls += "surviving"
        }))

        enabled = false
        service.forcedDisableOwner(disabledOwner)
        runtime.process(frame(1L))

        assertEquals(1, stalls.size)
        assertEquals(true, stalls.single().contains("disabled"))
        assertEquals(listOf("surviving"), calls)
    }

    @Test
    fun `world unload invalidates its binding without stalling`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        val runtime = runtime()
        service.registerWorld(worldId, runtime, { emptyList() }) { reason, failure ->
            throw IllegalStateException(reason, failure)
        }
        service.replaceSources(owner, worldId, listOf(source("test:main", 0.0) {}))

        service.removeWorld(worldId)
        service.removeWorld(worldId)

        assertThrows(IllegalStateException::class.java) {
            service.replaceSources(owner, worldId, listOf(source("test:main", 0.0) {}))
        }
        service.close()
        service.close()
    }

    private fun runtime() = SignalEpochRuntime(
        "world",
        TrainRestrictionPublisher { snapshot ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        }
    )

    private fun source(sourceId: String, start: Double, callback: () -> Unit): SignalEpochSource {
        val areaId = RailObjectRef("test", sourceId.substringAfter(':'))
        val area = RailwayOccupancyAreaDefinition.create(
            areaId,
            1L,
            7L,
            listOf(RailInterval(railId, start, start + 10.0)),
            listOf(
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${areaId.id}-entry"),
                    RailPositionSnapshot(railId, ViewDirection.AB, start)
                ),
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${areaId.id}-exit"),
                    RailPositionSnapshot(railId, ViewDirection.BA, start + 10.0)
                )
            )
        )
        return SignalEpochSource(sourceId, listOf(area)) {
            callback()
            SignalEpochResult.empty()
        }
    }

    private fun frame(epoch: Long) = RailwayWorldFrameSnapshot.create(epoch, 7L, emptyList())

    private fun plugin(name: String, enabled: () -> Boolean = { true }): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName" -> name
                "isEnabled" -> enabled()
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
