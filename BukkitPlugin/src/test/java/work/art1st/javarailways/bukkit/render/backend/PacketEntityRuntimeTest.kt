package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.PacketEvent
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.netty.NettyManager
import com.github.retrooper.packetevents.util.PEVersion
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.EntityLibAPI
import me.tofaa.entitylib.Platform
import me.tofaa.entitylib.event.EventHandler
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import java.lang.reflect.Proxy
import java.util.logging.Logger
import sun.misc.Unsafe

class PacketEntityRuntimeTest {
    @Test
    fun `runtime reenable uses shared API without owning its lifecycle`() = withApi { shared ->
        val foreign = object : PacketListenerAbstract() {}
        shared.eventManager.registerListener(foreign)
        val platform = TestPlatform()
        val runtime = PacketEntityRuntime(uninitializedPlugin(), { shared }, { platform.proxy })
        repeat(3) {
            runtime.enable()
            assertSame(shared, EntityLib.getApi<Any>().packetEvents)
            assertFalse(platform.config.shouldTickTickables())
            assertFalse(platform.config.shouldCheckForUpdate())
            assertFalse(platform.config.shouldUseBstats())
            runtime.close()
            runtime.close()
            assertEquals(listOf(foreign), listeners(shared.eventManager))
        }
        assertTrue(shared.isInitialized)
        assertSame(shared, PacketEvents.getAPI())
    }

    @Test
    fun `partial EntityLib startup cleanup never terminates shared API`() = withApi { shared ->
        val platform = TestPlatform(failSetup = true)
        val runtime = PacketEntityRuntime(uninitializedPlugin(), { shared }, { platform.proxy })
        assertThrows(IllegalStateException::class.java) { runtime.enable() }
        runtime.close()
        assertTrue(shared.isInitialized)
        assertSame(shared, PacketEvents.getAPI())
    }

    @Test
    fun `uninitialized external dependency fails before EntityLib setup`() = withApi { shared ->
        shared.initialized = false
        val runtime = PacketEntityRuntime(uninitializedPlugin(), { shared }, { error("Must not create EntityLib") })
        assertThrows(IllegalStateException::class.java) { runtime.enable() }
        runtime.close()
    }

    @Test
    fun `terminated external dependency fails before EntityLib setup`() = withApi { shared ->
        shared.terminated = true
        val runtime = PacketEntityRuntime(uninitializedPlugin(), { shared }, { error("Must not create EntityLib") })
        assertThrows(IllegalStateException::class.java) { runtime.enable() }
        runtime.close()
    }

    @Test
    fun `too old external dependency fails with actionable minimum version`() = withApi { shared ->
        shared.currentVersion = PEVersion.fromString("2.11.1")
        val runtime = PacketEntityRuntime(uninitializedPlugin(), { shared }, { error("Must not create EntityLib") })
        val failure = assertThrows(IllegalStateException::class.java) { runtime.enable() }
        assertTrue(failure.message!!.contains("2.12.1"))
        runtime.close()
    }

    @Test
    fun `motion removes only owned listener over repeated starts`() = withApi { shared ->
        val manager = shared.eventManager
        val foreign = object : PacketListenerAbstract() {}
        manager.registerListener(foreign)
        val motion = PacketEntityMotion(
            proxy(Plugin::class.java) { error("No Bukkit access expected") },
            proxy(PlatformDispatcher::class.java) { error("No dispatch expected") },
            TrainPacketBundleCoordinator(), manager
        )
        repeat(3) {
            motion.start()
            assertEquals(2, listeners(manager).size)
            // Closing must use its original manager even if a global API is replaced.
            PacketEvents.setAPI(SharedApi())
            motion.close()
            motion.close()
            assertEquals(listOf(foreign), listeners(manager))
            PacketEvents.setAPI(shared)
        }
    }

    @Test
    fun `motion partial registration is removable without touching foreign listener`() = withApi { shared ->
        val manager = object : EventManager() {
            override fun registerListener(listener: PacketListenerCommon): PacketListenerCommon {
                super.registerListener(listener)
                throw IllegalStateException("Injected registration failure after side effect")
            }
        }
        val foreign = object : PacketListenerAbstract() {}
        manager.registerListeners(foreign)
        val motion = PacketEntityMotion(
            proxy(Plugin::class.java) { error("No Bukkit access expected") },
            proxy(PlatformDispatcher::class.java) { error("No dispatch expected") },
            TrainPacketBundleCoordinator(), manager
        )
        assertThrows(IllegalStateException::class.java) { motion.start() }
        motion.close()
        assertEquals(listOf(foreign), listeners(manager))
        assertTrue(shared.isInitialized)
    }

    private fun listeners(manager: EventManager): List<PacketListenerCommon> {
        val found = mutableListOf<PacketListenerCommon>()
        manager.callEvent(object : PacketEvent() {
            override fun call(listener: PacketListenerCommon) { found += listener }
        })
        return found
    }

    private fun withApi(action: (SharedApi) -> Unit) {
        val previous = PacketEvents.getAPI()
        try {
            val shared = SharedApi()
            PacketEvents.setAPI(shared)
            action(shared)
        } finally { PacketEvents.setAPI(previous) }
    }

    private class SharedApi : PacketEventsAPI<Any>() {
        var initialized = true
        var terminated = false
        var currentVersion = PEVersion.fromString("2.12.1")
        override fun load(): Unit = error("Consumer must not load external API")
        override fun init(): Unit = error("Consumer must not initialize external API")
        override fun terminate(): Unit = error("Consumer must not terminate external API")
        override fun isLoaded() = true
        override fun isInitialized() = initialized
        override fun isTerminated() = terminated
        override fun getVersion() = currentVersion
        override fun getPlugin(): Any = error("Not needed")
        override fun getServerManager(): ServerManager = error("Not needed")
        override fun getProtocolManager(): ProtocolManager = error("Not needed")
        override fun getPlayerManager(): PlayerManager = error("Not needed")
        override fun getNettyManager(): NettyManager = error("Not needed")
        override fun getInjector(): ChannelInjector = error("Consumer must not access external injector")
    }

    private class TestPlatform(val failSetup: Boolean = false) {
        lateinit var config: APIConfig
        private val handler = EventHandler.create()
        private val api = proxy(EntityLibAPI::class.java) { (method, _) ->
            when (method) {
                "getSettings" -> config
                "getPacketEvents" -> config.packetEvents
                "getAllEntities", "getTickContainers" -> emptyList<Any>()
                else -> error("Unexpected API call: $method")
            }
        }
        val proxy = proxy(Platform::class.java) { (method, args) ->
            when (method) {
                "setupApi" -> {
                    config = args!![0] as APIConfig
                    if (failSetup) throw IllegalStateException("Injected setup failure")
                    null
                }
                "getAPI" -> api
                "getLogger" -> Logger.getLogger("EntityLibTest")
                "getEventHandler" -> handler
                else -> error("Unexpected platform call: $method")
            }
        }
    }

    private class TestPlugin : JavaPlugin()

    companion object {
        // JavaPlugin's normal constructor requires a real Bukkit plugin classloader. No methods are invoked here.
        private fun uninitializedPlugin(): JavaPlugin {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }
            return (field.get(null) as Unsafe).allocateInstance(TestPlugin::class.java) as JavaPlugin
        }
        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, invoke: (Pair<String, Array<out Any?>?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method.name to args) } as T
    }
}
