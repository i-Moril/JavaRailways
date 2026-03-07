package work.art1st.mineRails.bukkit.extension

import org.bukkit.entity.Player
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread

interface BukkitRenderable<T> : RenderableExtension<T> {
    @MainThread
    fun sendTo(player: Player)
}