package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.util.PlatformDispatcher

/** Owns the lifecycle of the shaded PacketEvents and EntityLib instances. */
class PacketEntityRuntime(
    private val plugin: JavaPlugin
) : AutoCloseable {
    private var loaded = false
    private var enabled = false
    private var foliaEntityMotion: FoliaPacketEntityMotion? = null

    /** Must run from JavaPlugin.onLoad, before PacketEvents starts injecting connections. */
    fun load() {
        check(!loaded) { "Packet entity runtime is already loaded" }
        val api = SpigotPacketEventsBuilder.build(
            plugin,
            PacketEventsSettings().checkForUpdates(false)
        )
        PacketEvents.setAPI(api)
        api.load()
        check(api.isLoaded) { "PacketEvents did not reach the loaded state" }
        loaded = true
    }

    /** Must run early in JavaPlugin.onEnable, before any packet visual is constructed. */
    fun enable() {
        check(loaded) { "Packet entity runtime must be loaded before it is enabled" }
        check(!enabled) { "Packet entity runtime is already enabled" }
        val api = PacketEvents.getAPI()
        api.init()
        check(api.isInitialized) { "PacketEvents did not initialize for ${plugin.server.bukkitVersion}" }

        EntityLib.init(
            SpigotEntityLibPlatform(plugin),
            APIConfig(api)
                .usePlatformLogger()
                .disableBStats()
        )
        enabled = true
    }

    fun enableFoliaEntityMotion(
        dispatcher: PlatformDispatcher,
        bundles: TrainPacketBundleCoordinator
    ): FoliaPacketEntityMotion {
        check(enabled) { "Packet entity runtime must be enabled before Folia motion starts" }
        check(foliaEntityMotion == null) { "Folia packet entity motion is already enabled" }
        return FoliaPacketEntityMotion(plugin, dispatcher, bundles).also {
            it.start()
            foliaEntityMotion = it
        }
    }

    override fun close() {
        foliaEntityMotion?.close()
        foliaEntityMotion = null
        if (enabled) {
            EntityLib.getOptionalApi().ifPresent { api ->
                api.allEntities.toList().forEach { it.remove() }
            }
            enabled = false
        }
        if (loaded) {
            PacketEvents.getAPI().terminate()
            SpigotPacketEventsBuilder.clearBuildCache()
            loaded = false
        }
    }
}
