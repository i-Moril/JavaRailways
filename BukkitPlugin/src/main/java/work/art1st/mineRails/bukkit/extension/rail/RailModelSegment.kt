package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.extension.ModelLoader
import work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.core.api.util.annotation.MainThread

class RailModelSegment(
    private val modelLoader: ModelLoader
) {
    private var model: CompositeModel? = null
    private val audienceTracker: ModelAudienceTracker = ModelAudienceTracker()

    @MainThread
    fun sendTo(player: Player) {
        audienceTracker.update()
        model = modelLoader.load()
        model?.showTo(player)
        model?.updatePlayerVisibility(player)
    }
}