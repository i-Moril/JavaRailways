package work.art1st.mineRails.bukkit.render.backend

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.DisplayRendererBackend

object DisplayRenderBackendFactory {
    fun create(plugin: BukkitPlugin): DisplayRenderBackend {
        val configuredBackend = plugin.mineRailsConfig.displayRenderer.backend
        val backend = if (plugin.platformCapabilities.folia) {
            if (configuredBackend != DisplayRendererBackend.ENTITYLIB) {
                plugin.logger.info("Folia detected: using the region-safe EntityLib display backend")
            }
            DisplayRendererBackend.ENTITYLIB
        } else {
            configuredBackend
        }
        return when (backend) {
            DisplayRendererBackend.NATIVE -> NativeDisplayRenderBackend(plugin)
            DisplayRendererBackend.ENTITYLIB -> EntityLibDisplayRenderBackend()
        }
    }
}
