package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.EntityLibAPI
import me.tofaa.entitylib.Platform
import com.github.retrooper.packetevents.util.PEVersion
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.util.PlatformDispatcher

/** Owns JavaRailways' relocated EntityLib and listeners; PacketEvents belongs to its server plugin. */
class PacketEntityRuntime internal constructor(
    private val plugin: JavaPlugin,
    private val apiProvider: () -> PacketEventsAPI<*> = { PacketEvents.getAPI() },
    private val platformFactory: () -> Platform<*> = { SpigotEntityLibPlatform(plugin) }
) : AutoCloseable {
    internal val displayChunks = PacketDisplayChunks()
    private var enabled = false
    private var entityLibPlatform: Platform<*>? = null
    private var entityLibApi: EntityLibAPI<*>? = null
    private var packetEntityMotion: PacketEntityMotion? = null

    /** Runs after the hard dependency's onEnable, before any packet visual is constructed. */
    fun enable() {
        check(!enabled && entityLibPlatform == null) { "Packet entity runtime is already enabled" }
        val api = apiProvider()
        check(api.isInitialized && !api.isTerminated) { "The installed packetevents plugin must be enabled before JavaRailways" }
        check(!api.version.isOlderThan(PEVersion.fromString("2.12.1"))) {
            "JavaRailways requires PacketEvents 2.12.1 or newer (installed: ${api.version})"
        }
        val platform = platformFactory()
        entityLibPlatform = platform // Keep the partially initialized platform reachable for failure cleanup.
        EntityLib.init(platform, APIConfig(api).usePlatformLogger().disableBStats())
        entityLibApi = platform.api
        enabled = true
    }

    fun enablePacketEntityMotion(
        dispatcher: PlatformDispatcher,
        bundles: TrainPacketBundleCoordinator
    ): PacketEntityMotion {
        check(enabled) { "Packet entity runtime must be enabled before packet motion starts" }
        check(packetEntityMotion == null) { "Packet entity motion is already enabled" }
        return PacketEntityMotion(plugin, dispatcher, bundles).also {
            packetEntityMotion = it
            it.start()
        }
    }

    override fun close() {
        val failures = mutableListOf<Exception>()
        fun cleanup(action: () -> Unit) {
            try { action() } catch (failure: Exception) { failures.add(failure) }
        }
        cleanup { displayChunks.close() }
        cleanup { packetEntityMotion?.close() }
        packetEntityMotion = null
        val ownedApi = entityLibApi ?: entityLibPlatform?.api
        if (ownedApi != null) {
            cleanup { ownedApi.allEntities.toList().forEach { entity -> cleanup { entity.remove() } } }
            cleanup { ownedApi.tickContainers.toList().forEach { container ->
                (container.handle as? org.bukkit.scheduler.BukkitTask)?.cancel()
            } }
        }
        entityLibPlatform?.let { platform -> cleanup { platform.eventHandler.eventCallbacksMap.clear() } }
        enabled = false
        entityLibApi = null
        entityLibPlatform = null
        if (failures.isNotEmpty()) throw IllegalStateException("Packet entity runtime did not close cleanly").also {
            failures.forEach(it::addSuppressed)
        }
    }
}
