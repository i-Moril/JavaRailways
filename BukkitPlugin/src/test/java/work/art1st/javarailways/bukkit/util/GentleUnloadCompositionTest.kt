package work.art1st.javarailways.bukkit.util

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.ServicesManager
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sun.misc.Unsafe
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.lifecycle.GentleUnloadPreparation
import work.art1st.javarailways.bukkit.lifecycle.PlugManXGentleUnload
import work.art1st.javarailways.bukkit.logic.WorldManager
import work.art1st.javarailways.core.util.ThreadGuard
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class GentleUnloadCompositionTest {
    @Test
    fun `composition approves only after snapshot writer and reuses completed preparation on disable`() {
        val global = ArrayDeque<Runnable>()
        val scheduler = proxy(GlobalRegionScheduler::class.java) { method, args ->
            check(method == "execute")
            global.add(args!![1] as Runnable)
            null
        }
        val services = proxy(ServicesManager::class.java) { method, _ -> check(method == "unregisterAll"); null }
        val manager = proxy(Plugin::class.java) { method, _ ->
            when (method) {
                "getDescription" -> PluginDescriptionFile("PlugManX", "3.2.1", "fixture.Main")
                else -> error(method)
            }
        }
        val plugins = proxy(PluginManager::class.java) { method, _ -> check(method == "getPlugin"); manager }
        val server = proxy(Server::class.java) { method, _ ->
            when (method) {
                "getGlobalRegionScheduler" -> scheduler
                "getServicesManager" -> services
                "getPluginManager" -> plugins
                else -> error(method)
            }
        }
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val previousInstance = BukkitPlugin.instanceOrNull()
        val plugin = unsafe.allocateInstance(BukkitPlugin::class.java) as BukkitPlugin
        set(JavaPlugin::class.java, plugin, "server", server)
        set(JavaPlugin::class.java, plugin, "isEnabled", true)
        set(JavaPlugin::class.java, plugin, "logger", Logger.getAnonymousLogger())
        set(JavaPlugin::class.java, plugin, "description", PluginDescriptionFile("JavaRailways", "test", "fixture.Main"))
        set(JavaPlugin::class.java, plugin, "pluginMeta", PluginDescriptionFile("JavaRailways", "test", "fixture.Main"))
        set(BukkitPlugin::class.java, plugin, "shutdownFailures", mutableListOf<String>())
        plugin.platformCapabilities = PlatformCapabilities(true)
        plugin.platformDispatcher = PaperPlatformDispatcher(plugin)
        plugin.worldManager = WorldManager()
        BukkitPlugin.INSTANCE = plugin
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val writes = AtomicInteger()
        val write = object : Future<Unit> {
            override fun cancel(interrupt: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = releaseWriter.count == 0L
            override fun get() { writes.incrementAndGet(); writerEntered.countDown(); check(releaseWriter.await(5, TimeUnit.SECONDS)) }
            override fun get(timeout: Long, unit: TimeUnit) = get()
        }
        @Suppress("UNCHECKED_CAST")
        val snapshotWrites = WorldManager::class.java.getDeclaredField("snapshotWrites").apply { isAccessible = true }
            .get(plugin.worldManager) as MutableMap<World, Future<*>>
        val world = proxy(World::class.java) { method, _ -> when (method) { "hashCode" -> 1; else -> error(method) } }
        snapshotWrites[world] = write
        ThreadGuard.installPlatformBridge(BukkitThreadGuardBridge)
        try {
            call(plugin, "installGentleUnload")
            val preparation = BukkitPlugin::class.java.getDeclaredField("gentleUnload").apply { isAccessible = true }
                .get(plugin) as GentleUnloadPreparation
            assertFalse(preparation.request())
            assertTrue(writes.get() == 0)
            global.remove().run() // Starts preparation, queues final global stage without waiting.
            assertFalse(preparation.request())
            val finalStage = global.remove()
            val finishing = CompletableFuture.runAsync { finalStage.run() }
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
            assertFalse(preparation.request(), "Owner cleanup alone must not approve unload")
            releaseWriter.countDown()
            finishing.get(5, TimeUnit.SECONDS)
            assertTrue(preparation.request())
            call(plugin, "prepareShutdown")
            call(plugin, "prepareShutdown")
            assertEquals(1, writes.get(), "Disable must reuse final save rather than run it again")
        } finally {
            releaseWriter.countDown()
            (BukkitPlugin::class.java.getDeclaredField("gentleUnloadRegistration").apply { isAccessible = true }
                .get(plugin) as? PlugManXGentleUnload)?.close()
            (WorldManager::class.java.getDeclaredField("snapshotWriter").apply { isAccessible = true }
                .get(plugin.worldManager) as java.util.concurrent.ExecutorService).shutdownNow()
            BukkitPlugin::class.java.getDeclaredField("currentInstance").apply { isAccessible = true }
                .set(null, previousInstance)
            ThreadGuard.clearPlatformBridge()
        }
    }

    private fun call(plugin: BukkitPlugin, method: String) = BukkitPlugin::class.java.getDeclaredMethod(method)
        .apply { isAccessible = true }.invoke(plugin)
    private fun set(type: Class<*>, instance: Any, field: String, value: Any) = type.getDeclaredField(field)
        .apply { isAccessible = true }.set(instance, value)
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, action: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> action(method.name, args) } as T
}
