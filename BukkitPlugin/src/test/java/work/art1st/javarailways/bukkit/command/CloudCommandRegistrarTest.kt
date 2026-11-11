package work.art1st.javarailways.bukkit.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import sun.misc.Unsafe
import work.art1st.javarailways.bukkit.BukkitPlugin
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.tool.giveTurnoutWand
import java.lang.reflect.Proxy
import java.util.Locale

class CloudCommandRegistrarTest {
    private var previousInstance: BukkitPlugin? = null

    @BeforeEach
    fun activateRuntime() {
        previousInstance = BukkitPlugin.instanceOrNull()
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val plugin = unsafe.allocateInstance(BukkitPlugin::class.java) as BukkitPlugin
        BukkitPlugin::class.java.getDeclaredField("isRuntimeActive").apply { isAccessible = true }.set(plugin, true)
        BukkitPlugin.INSTANCE = plugin
    }

    @AfterEach
    fun restoreRuntime() {
        BukkitPlugin::class.java.getDeclaredField("currentInstance").apply { isAccessible = true }.set(null, previousInstance)
    }

    @Test
    fun `tokenizer preserves the empty token required for trailing-space suggestions`() {
        assertEquals(listOf("train", "create", ""), CloudCommandRegistrar.tokenize("train create "))
        assertEquals(listOf(""), CloudCommandRegistrar.tokenize(""))
    }

    @Test
    fun `cloud tree includes resource reload and representative nested commands`() {
        val routes = CloudCommandRegistrar.registeredRoutePaths()
        assertTrue("resources reload" in routes)
        assertTrue("train create" in routes)
        assertTrue("timetable replan" in routes)
        assertFalse(routes.any { it == "depot" || it.startsWith("depot ") })
        assertTrue("wand rail" in routes)
        assertTrue("wand turnout" in routes)
        assertFalse(routes.any { it == "rail" || it.startsWith("rail ") })
    }

    @Test
    fun `issues route accepts optional world and issue selection arguments`() {
        assertTrue("issues" in CloudCommandRegistrar.registeredRoutePaths())
        assertTrue(CloudCommandRegistrar.routeAcceptsArguments("issues"))
    }

    @Test
    fun `global reload is registered for both timetable aliases and suggests confirmation`() {
        val command = JavaRailwaysCommand()
        val sender = sender(CommandSender::class.java)
        for (alias in listOf("timetable", "tt")) {
            assertTrue("$alias reload-all" in CloudCommandRegistrar.registeredRoutePaths())
            assertTrue(CloudCommandRegistrar.routeAcceptsArguments("$alias reload-all"))
            assertTrue("reload-all" in command.suggest(sender, arrayOf(alias, "")))
            assertEquals(listOf("confirm"), command.suggest(sender, arrayOf(alias, "reload-all", "")))
        }
    }

    @Test
    fun `global reload denies an unauthorized console sender`() {
        val messages = mutableListOf<Component>()
        val command = JavaRailwaysCommand()
        assertTrue(command.execute(sender(CommandSender::class.java, messages = messages), "jr", arrayOf("timetable", "reload-all", "confirm")))
        assertEquals(Component.text("没有权限全局重载时刻表", net.kyori.adventure.text.format.NamedTextColor.RED), messages.single())
    }

    @Test
    fun `turnout command completion includes trailing empty and partial tokens`() {
        val sender = sender(CommandSender::class.java)
        val command = JavaRailwaysCommand()
        assertTrue("turnout" in command.suggest(sender, CloudCommandRegistrar.tokenize("wand ").toTypedArray()))
        assertEquals(listOf("turnout"), command.suggest(sender, arrayOf("wand", "tur")))
        assertEquals(emptyList<String>(), command.suggest(sender, arrayOf("wand", "turnout", "")))
    }

    @Test
    fun `turnout command rejects console and players without permission before inventory access`() {
        val messages = mutableListOf<Component>()
        val command = JavaRailwaysCommand()
        assertTrue(command.execute(sender(CommandSender::class.java, messages = messages), "jr", arrayOf("wand", "turnout")))
        assertEquals(Component.text("Only players can receive a Turnout Wand."), messages.single())
        messages.clear()
        assertTrue(command.execute(sender(Player::class.java, messages = messages), "jr", arrayOf("wand", "turnout")))
        assertEquals(Component.text("You need javarailways.admin to use the Turnout Wand."), messages.single())
    }

    @Test
    fun `authorized turnout issuance delegates exactly once to the chosen player`() {
        val player = sender(Player::class.java, permitted = true)
        val recipients = mutableListOf<Player>()
        giveTurnoutWand(player, recipients::add)
        assertEquals(listOf(player), recipients)
    }

    private fun <T> sender(type: Class<T>, permitted: Boolean = false, messages: MutableList<Component> = mutableListOf()): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "hasPermission" -> permitted
                "locale" -> Locale.US
                "sendMessage" -> { messages += args!!.filterIsInstance<Component>(); null }
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                "toString" -> "CommandTestSender"
                else -> error("Unexpected sender access: ${method.name}")
            }
        })
}
