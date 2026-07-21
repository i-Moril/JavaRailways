package work.art1st.mineRails.bukkit.render.backend

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.DisplayRendererBackend

object DisplayRenderBackendFactory {
    fun create(plugin: BukkitPlugin): DisplayRenderBackend {
        return when (plugin.mineRailsConfig.displayRenderer.backend) {
            DisplayRendererBackend.NATIVE -> NativeDisplayRenderBackend(plugin)
            DisplayRendererBackend.ENTITYLIB -> EntityLibDisplayRenderBackend()
        }
    }
}
