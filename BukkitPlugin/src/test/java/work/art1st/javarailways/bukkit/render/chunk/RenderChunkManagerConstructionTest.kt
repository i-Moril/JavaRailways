package work.art1st.javarailways.bukkit.render.chunk

import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import java.lang.reflect.Proxy

class RenderChunkManagerConstructionTest {
    @Test
    fun `initial player refresh failure unregisters the already registered manager`() {
        val primary = IllegalStateException("initial refresh failed")
        var registered: Listener? = null
        var unregistered: Listener? = null
        val dispatcher = proxy(PlatformDispatcher::class.java) { methodName ->
            if (methodName == "runForEntity") throw primary
            throw UnsupportedOperationException("Unexpected PlatformDispatcher.$methodName")
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            RenderChunkManager(
                world = proxy(World::class.java),
                dispatcher = dispatcher,
                registerListener = { listener -> registered = listener },
                unregisterListener = { listener -> unregistered = listener },
                initialPlayers = listOf(proxy(Player::class.java))
            )
        }

        assertSame(primary, thrown)
        assertSame(registered, unregistered)
    }

    @Test
    fun `listener cleanup failure is suppressed on construction failure`() {
        val primary = IllegalArgumentException("registration failed")
        val cleanupFailure = IllegalStateException("unregister failed")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RenderChunkManager(
                world = proxy(World::class.java),
                dispatcher = proxy(PlatformDispatcher::class.java),
                registerListener = { throw primary },
                unregisterListener = { throw cleanupFailure },
                initialPlayers = emptyList()
            )
        }

        assertSame(primary, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        unexpected: (String) -> Any? = { methodName ->
            throw UnsupportedOperationException("Unexpected ${type.simpleName}.$methodName")
        }
    ): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type)
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "${type.simpleName}Proxy"
            "hashCode" -> System.identityHashCode(type)
            "equals" -> false
            else -> unexpected(method.name)
        }
    } as T
}
