package work.art1st.javarailways.bukkit.extension.train

import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.render.Renderable
import java.lang.reflect.Proxy
import java.util.UUID

class TrainComponentExtensionAudienceTest {
    @Test
    fun `first audience initializes fresh model before refresh exposes it`() {
        val calls = mutableListOf<String>()
        val extension = RecordingExtension(calls)
        val player = fakePlayer(UUID.randomUUID())

        extension.sendToOwned(player)
        extension.sendToOwned(player)

        assertEquals(
            listOf("build", "update", "refresh", "refresh"),
            calls
        )
    }

    @Test
    fun `late audience task cannot rebuild a destroyed model`() {
        val calls = mutableListOf<String>()
        val extension = RecordingExtension(calls)
        val player = fakePlayer(UUID.randomUUID())

        extension.sendToOwned(player)
        extension.destroy()
        extension.sendToOwned(player)

        assertEquals(
            listOf("build", "update", "refresh", "destroy"),
            calls
        )
    }

    private class RecordingExtension(
        private val calls: MutableList<String>
    ) : TrainComponentExtension<Renderable>() {
        override fun buildModel() {
            calls += "build"
        }

        override fun updateModel() {
            calls += "update"
        }

        override fun refreshFor(player: Player) {
            calls += "refresh"
        }

        override fun destroyModel() {
            calls += "destroy"
        }
    }

    private fun fakePlayer(id: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getUniqueId" -> id
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "FakePlayer($id)"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    } as Player
}
