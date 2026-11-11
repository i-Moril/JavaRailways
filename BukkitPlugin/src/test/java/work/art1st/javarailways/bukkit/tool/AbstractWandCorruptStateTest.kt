package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginLogger
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import sun.misc.Unsafe
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import java.lang.reflect.Proxy
import java.util.UUID

class AbstractWandCorruptStateTest {
    @Test
    fun `corrupt bytes survive interaction save item switch and quit while replacement item is writable`() {
        ModelRuntimeTestSupport.bootstrap()
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        val plugin = unsafe.allocateInstance(BukkitPlugin::class.java) as BukkitPlugin
        val description = PluginDescriptionFile("WandTest", "1", "unused")
        JavaPlugin::class.java.getDeclaredField("description").apply { isAccessible = true }
            .set(plugin, description)
        JavaPlugin::class.java.getDeclaredField("pluginMeta").apply { isAccessible = true }
            .set(plugin, description)
        JavaPlugin::class.java.getDeclaredField("server").apply { isAccessible = true }
            .set(plugin, proxy<org.bukkit.Server> { name, _ ->
                check(name == "getLogger")
                java.util.logging.Logger.getAnonymousLogger()
            })
        JavaPlugin::class.java.getDeclaredField("logger").apply { isAccessible = true }
            .set(plugin, PluginLogger(plugin))
        val stateKey = NamespacedKey(plugin, "wand_state")
        val corrupt = TestItem(plugin, "{corrupt-original-payload")
        val replacement = TestItem(plugin, RailObjectWandStateCodec.encode(RailObjectWandState()))
        var held = corrupt
        val inventory = proxy<PlayerInventory> { name, _ -> when (name) {
            "getItemInMainHand", "getItemInOffHand" -> held
            "getContents" -> arrayOf(corrupt, replacement)
            else -> error(name)
        } }
        val id = UUID.randomUUID()
        val player = proxy<Player> { name, _ -> when (name) {
            "getUniqueId" -> id
            "getInventory" -> inventory
            else -> error(name)
        } }
        val wand = TestWand(plugin)
        wand.save(player, RailObjectWandState(pendingDeleteWayPoint = "direct-before-decode"))
        assertEquals("{corrupt-original-payload", corrupt.values[stateKey])
        val recovered = wand.getState(player)
        recovered.pendingDeleteWayPoint = "changed-in-memory"
        wand.save(player, recovered)
        assertEquals("{corrupt-original-payload", corrupt.values[stateKey])
        held = replacement
        val fresh = wand.getState(player) // Automatically persists the previous binding.
        fresh.pendingDeleteWayPoint = "replacement-edited"
        wand.save(player, fresh)
        assertNotEquals("{corrupt-original-payload", replacement.values[stateKey])
        assertEquals("replacement-edited", RailObjectWandStateCodec.decode(replacement.values[stateKey] as String)!!.pendingDeleteWayPoint)
        held = corrupt
        wand.getState(player)
        wand.onQuit(PlayerQuitEvent(player, Component.empty()))
        assertEquals("{corrupt-original-payload", corrupt.values[stateKey])
        wand.save(player, RailObjectWandState(pendingDeleteWayPoint = "late-after-quit"))
        assertEquals("{corrupt-original-payload", corrupt.values[stateKey])
    }

    private class TestWand(plugin: BukkitPlugin) : AbstractWand<RailObjectWandState, RailObjectWandMode>(
        plugin, "test_wand", Material.STICK, Component.empty(), emptyList(), RailObjectWandMode.entries
    ) {
        override fun createDefaultState() = RailObjectWandState()
        override fun encodeState(state: RailObjectWandState) = RailObjectWandStateCodec.encode(state)
        override fun decodeState(encoded: String) = RailObjectWandStateCodec.decode(encoded)
        override fun handleWandInteract(player: Player, state: RailObjectWandState, event: PlayerInteractEvent) = Unit
        fun save(player: Player, state: RailObjectWandState) = persistState(player, state)
    }

    private class TestItem(plugin: BukkitPlugin, encoded: String) : ItemStack() {
        val values = mutableMapOf<NamespacedKey, Any>(
            NamespacedKey(plugin, "test_wand") to true,
            NamespacedKey(plugin, "wand_instance_id") to UUID.randomUUID().toString(),
            NamespacedKey(plugin, "wand_state") to encoded
        )
        private val container = proxy<PersistentDataContainer> { name, args -> when (name) {
            "get" -> values[args!![0]]
            "has" -> values.containsKey(args!![0])
            "set" -> { values[args!![0] as NamespacedKey] = args[2]!!; null }
            else -> error(name)
        } }
        private val meta = proxy<ItemMeta> { name, _ -> when (name) {
            "getPersistentDataContainer" -> container
            else -> error(name)
        } }
        override fun getType(): Material = Material.STICK
        override fun getItemMeta(): ItemMeta = meta
        override fun setItemMeta(meta: ItemMeta?): Boolean = true
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args)
            } as T
    }
}
