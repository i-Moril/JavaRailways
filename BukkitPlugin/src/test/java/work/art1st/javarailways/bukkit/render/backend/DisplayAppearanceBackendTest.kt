package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.NettyManager
import me.tofaa.entitylib.meta.Metadata
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import work.art1st.javarailways.bukkit.render.model.toItemStack
import java.lang.reflect.Proxy

class DisplayAppearanceBackendTest {
    @Test
    fun `native ordinary model captures current flags and display outline baseline on owner`() {
        val display = Proxy.newProxyInstance(ItemDisplay::class.java.classLoader, arrayOf(ItemDisplay::class.java)) { _, method, _ ->
            when (method.name) {
                "getEntityId" -> 42
                "isVisualFire", "isSneaking", "isInvisible", "isGlowing" -> true
                "getGlowColorOverride" -> org.bukkit.Color.fromRGB(0x123456)
                else -> error("Unexpected snapshot call: ${method.name}")
            }
        } as ItemDisplay
        val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            error("Unexpected plugin call: ${method.name}")
        } as Plugin
        val visual = NativeDisplayVisual(plugin, display, work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher())
        assertEquals(42, visual.packetEntityId)
        assertEquals(0x63.toByte() to 0x123456, visual.packetGlowBaseline)
    }

    @Test
    fun `native visual sends complete real items and removes its brightness override`() {
        ModelRuntimeTestSupport.bootstrap()
        var item: ItemStack? = null
        var brightness: Display.Brightness? = null
        val display = Proxy.newProxyInstance(ItemDisplay::class.java.classLoader, arrayOf(ItemDisplay::class.java)) { _, method, args ->
            when (method.name) {
                "setItemStack" -> { item = args!![0] as ItemStack; null }
                "setBrightness" -> { brightness = args!![0] as Display.Brightness?; null }
                "remove" -> null
                else -> error("Unexpected native call: ${method.name}")
            }
        } as ItemDisplay
        val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            error("Unexpected plugin call: ${method.name}")
        } as Plugin
        val visual = NativeDisplayVisual(plugin, display,
            work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher().apply { immediate = true })
        val baseline = ItemAppearance(Material.PAPER, null, null, null).toItemStack()
        val changed = ItemAppearance(Material.DIAMOND, "another:signals/lamp", 42, null).toItemStack()
        try {
            visual.setItemStack(changed)
            assertNotSame(changed, item)
            assertEquals(Material.DIAMOND, item!!.type)
            assertEquals(Key.key("another:signals/lamp"), item!!.getData(DataComponentTypes.ITEM_MODEL))
            assertEquals(listOf(42f), item!!.getData(DataComponentTypes.CUSTOM_MODEL_DATA)!!.floats())
            visual.setBrightness(7, 12)
            assertEquals(7, brightness!!.blockLight)
            assertEquals(12, brightness!!.skyLight)
            visual.setItemStack(baseline)
            visual.clearBrightness()
            assertEquals(Material.PAPER, item!!.type)
            assertFalse(item!!.isDataOverridden(DataComponentTypes.ITEM_MODEL))
            assertFalse(item!!.isDataOverridden(DataComponentTypes.CUSTOM_MODEL_DATA))
            assertNull(brightness)
            visual.setBrightness(0, 0)
            assertNotNull(brightness, "fixed darkness is distinct from natural light")
        } finally { visual.destroy() }
    }

    @Test
    fun `EntityLib metadata distinguishes natural light sentinel from fixed darkness`() = withPacketVersion {
        val meta = ItemDisplayMeta(42, Metadata(42))
        assertEquals(-1, meta.brightnessOverride)
        meta.setBrightnessOverride((7 shl 4) or (12 shl 20))
        meta.setBrightnessOverride(-1)
        assertEquals(-1, meta.brightnessOverride)
        meta.setBrightnessOverride(0)
        assertEquals(0, meta.brightnessOverride)
        meta.setBrightnessOverride(-1)
        assertEquals(-1, meta.brightnessOverride)
    }

    @Test
    fun `native destroy waits for removal and repeated calls keep the failed result`() {
        var removes = 0
        val failure = IllegalStateException("cannot remove display")
        val display = Proxy.newProxyInstance(ItemDisplay::class.java.classLoader, arrayOf(ItemDisplay::class.java)) { _, method, _ ->
            when (method.name) {
                "remove" -> { removes++; throw failure }
                else -> error("Unexpected native call: ${method.name}")
            }
        } as ItemDisplay
        val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            error("Unexpected plugin call: ${method.name}")
        } as Plugin
        val dispatcher = work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher()
        val visual = NativeDisplayVisual(plugin, display, dispatcher)
        val completion = visual.destroy()
        val result = completion.toCompletableFuture()
        assertFalse(result.isDone)
        assertSame(completion, visual.destroy())
        assertEquals(0, removes)
        dispatcher.action!!.invoke()
        assertTrue(result.isCompletedExceptionally)
        assertSame(failure, org.junit.jupiter.api.Assertions.assertThrows(
            java.util.concurrent.CompletionException::class.java
        ) { result.join() }.cause)
        assertSame(completion, visual.destroy())
        assertEquals(1, removes)
    }

}

internal fun withPacketVersion(block: () -> Unit) = synchronized(PacketEvents::class.java) {
    val previous = PacketEvents.getAPI()
    // The official API binding supplies version information for real EntityLib metadata only.
    // No network/platform lifecycle is started, and unexpected platform access fails the test.
    PacketEvents.setAPI(object : PacketEventsAPI<Any>() {
        override fun getServerManager(): ServerManager = ServerManager { ServerVersion.V_1_21_11 }
        override fun getPlugin(): Any = error("No packet plugin used")
        override fun getProtocolManager(): ProtocolManager = error("No protocol manager used")
        override fun getPlayerManager(): PlayerManager = error("No player manager used")
        override fun getNettyManager(): NettyManager = error("No netty manager used")
        override fun getInjector(): ChannelInjector = error("No injector used")
        override fun load() = error("No packet load used")
        override fun init() = error("No packet init used")
        override fun terminate() = error("No packet terminate used")
        override fun isLoaded() = false
        override fun isInitialized() = false
        override fun isTerminated() = false
    })
    try { block() } finally { PacketEvents.setAPI(previous) }
}
