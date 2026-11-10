package work.art1st.javarailways.decorations.display

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer

class DisplayDistanceCommandTest {
    @Test fun `command waits for entity owner checks permission and refreshes only accepted updates`() {
        val id = UUID.randomUUID()
        val messages = mutableListOf<String>()
        var task: Consumer<Any?>? = null
        var allowed = true
        val scheduler = proxy<EntityScheduler> { name, args ->
            if (name == "run") {
                @Suppress("UNCHECKED_CAST")
                task = args!![1] as Consumer<Any?>
            }
            null
        }
        val player = proxy<Player> { name, args -> when (name) {
            "getScheduler" -> scheduler
            "getUniqueId" -> id
            "hasPermission" -> allowed
            "sendMessage" -> { messages.add(args!![0].toString()); null }
            else -> null
        } }
        val plugin = proxy<Plugin> { name, _ -> if (name == "isEnabled") true else null }
        val settings = DisplayDistanceSettings()
        val refreshed = mutableListOf<UUID>()
        val handler = DisplayDistanceCommand(plugin, settings, refreshed::add)
        val command = object : Command("decorations") {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
        }
        fun invoke(vararg args: String) {
            task = null
            handler.onCommand(player, command, "decorations", args)
            assertNotNull(task)
            task!!.accept(null)
        }
        handler.onCommand(player, command, "decorations", arrayOf("display-distance", "0"))
        assertEquals(32.0, settings.distanceFor(id))
        assertTrue(refreshed.isEmpty())
        task!!.accept(null)
        assertEquals(0.0, settings.distanceFor(id))
        assertEquals(listOf(id), refreshed)
        listOf("NaN", "Infinity", "-1", "129", "oops").forEach { invoke("display-distance", it) }
        assertEquals(0.0, settings.distanceFor(id))
        assertEquals(1, refreshed.size)
        invoke("display-distance", "reset")
        assertEquals(32.0, settings.distanceFor(id))
        assertEquals(2, refreshed.size)
        invoke("display-distance")
        assertEquals(2, refreshed.size)
        assertTrue(messages.last().contains("32.0"))
        allowed = false
        invoke("display-distance", "8")
        assertEquals(32.0, settings.distanceFor(id))
        assertEquals(2, refreshed.size)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "${T::class.java.simpleName} test owner"
                else -> invoke(method.name, args)
            }
        } as T
}
