package work.art1st.javarailways.bukkit.render.backend

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.DisplayRendererBackend

object DisplayRenderBackendFactory {
    fun create(plugin: BukkitPlugin): DisplayRenderBackend {
        return when (plugin.javaRailwaysConfig.displayRenderer.backend) {
            DisplayRendererBackend.NATIVE -> NativeDisplayRenderBackend(plugin, plugin.renderCleanupDispatcher)
            DisplayRendererBackend.ENTITYLIB -> EntityLibDisplayRenderBackend(plugin.packetDisplayChunks)
        }
    }
}
