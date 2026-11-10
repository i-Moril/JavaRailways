package work.art1st.javarailways.decorations.display

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.decorations.api.display.DisplayStyleSelection
import work.art1st.javarailways.decorations.api.display.DisplayStyleService
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer

/** Deferred execution: prepared now; run with final integrated acceptance. */
class DisplayStyleCommandTest {
    @Test fun `style routing uses world and permission on entity owner with safe tab completion`() {
        val expectedWorldId = UUID.randomUUID()
        val messages = mutableListOf<String>()
        var task: Consumer<Any?>? = null
        var owner = false
        var allowed = true
        var worldReads = 0
        val scheduler = proxy<EntityScheduler> { name, args ->
            if (name == "run") {
                @Suppress("UNCHECKED_CAST")
                task = args!![1] as Consumer<Any?>
            }
            null
        }
        val world = proxy<World> { name, _ -> if (name == "getUID") expectedWorldId else null }
        val player = proxy<Player> { name, args -> when (name) {
            "getScheduler" -> scheduler
            "getWorld" -> { assertTrue(owner); worldReads++; world }
            "hasPermission" -> { assertTrue(owner); args!![0] == DisplayStyleCommand.PERMISSION && allowed }
            "sendMessage" -> { assertTrue(owner); messages.add(args!![0].toString()); null }
            else -> null
        } }
        val plugin = proxy<Plugin> { name, _ -> if (name == "isEnabled") true else null }
        val operations = mutableListOf<String>()
        val styles = object : DisplayStyleService {
            private var selectedOverride: String? = null
            override fun unitIds(worldId: UUID): Set<String> = error("No live tab lookup")
            override fun categories(worldId: UUID, unitId: String): Set<String> = error("No live tab lookup")
            override fun inspect(worldId: UUID, unitId: String, category: String): DisplayStyleSelection {
                assertTrue(owner)
                assertEquals(expectedWorldId, worldId)
                operations.add("inspect")
                return DisplayStyleSelection(worldId, unitId, category, selectedOverride, setOf("standard", "night"), 2)
            }
            override fun set(worldId: UUID, unitId: String, category: String, templateId: String): DisplayStyleSelection {
                if (templateId == "unknown") throw IllegalArgumentException("Unknown template")
                selectedOverride = templateId
                operations.add("set:$templateId")
                return inspect(worldId, unitId, category)
            }
            override fun reset(worldId: UUID, unitId: String, category: String): DisplayStyleSelection {
                selectedOverride = null
                operations.add("reset")
                return inspect(worldId, unitId, category)
            }
        }
        val style = DisplayStyleCommand(plugin, styles)
        val router = DecorationsCommand(DisplayDistanceCommand(plugin, DisplayDistanceSettings()) {}, style)
        val command = object : Command("decorations") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
        }
        fun invoke(vararg args: String) {
            task = null
            router.onCommand(player, command, "decorations", args)
            assertNotNull(task)
            assertFalse(owner)
            owner = true
            try { task!!.accept(null) } finally { owner = false }
        }
        assertEquals(listOf("display-distance", "display-style"), router.onTabComplete(player, command, "decorations", arrayOf("display-")))
        assertEquals(listOf("reset"), style.onTabComplete(player, command, "decorations", arrayOf("display-style", "unit", "sign", "r")))
        assertTrue(style.onTabComplete(player, command, "decorations", arrayOf("display-style", "u")).isEmpty())
        assertEquals(0, worldReads)
        invoke("display-style", "unit", "sign")
        assertTrue(messages.any { it.contains("使用各屏幕默认模板") })
        assertTrue(messages.any { it.contains("night, standard") })
        invoke("display-style", "unit", "sign", "night")
        assertTrue(messages.any { it.contains("覆盖模板 night") })
        invoke("display-style", "unit", "sign", "reset")
        assertTrue(operations.contains("reset"))
        invoke("display-style", "unit", "sign", "unknown")
        assertTrue(messages.last().contains("Unknown template"))
        val count = operations.size
        allowed = false
        invoke("display-style", "unit", "sign", "night")
        assertEquals(count, operations.size)
        assertTrue(messages.last().contains("没有权限"))
    }

    @Test fun `console cannot mutate styles or schedule player work`() {
        val messages = mutableListOf<String>()
        val console = proxy<CommandSender> { name, args ->
            if (name == "sendMessage") messages.add(args!![0].toString())
            null
        }
        val plugin = proxy<Plugin> { _, _ -> error("Console must not touch plugin scheduler") }
        val styles = proxy<DisplayStyleService> { _, _ -> error("Console must not mutate styles") }
        val command = object : Command("decorations") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
        }
        assertTrue(DisplayStyleCommand(plugin, styles).onCommand(console, command, "decorations", arrayOf("display-style", "u", "c", "night")))
        assertTrue(messages.single().contains("只能由玩家"))
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
