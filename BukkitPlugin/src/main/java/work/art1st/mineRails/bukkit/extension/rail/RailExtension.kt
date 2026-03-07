package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker
import work.art1st.mineRails.bukkit.render.chunk.RenderChunk
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.getTransform
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData

@OptIn(ExtensionData::class)
val Rail.renderer: RailExtension
    get() = getExtensionData(BukkitPlugin)

class RailExtension : RenderableExtension<Rail> {
    private lateinit var self: Rail
    // TODO: Build model
    private models Collection<>
    var models: Collection<CompositeModel>
    private val audienceTracker: ModelAudienceTracker = ModelAudienceTracker()

    override fun commit() {}

    override fun destroy() {
        unloadModel()
    }

    override fun initialize(host: Rail) {
        self = host
    }

    fun buildModel(): CompositeModel {
        TODO("Not implemented yet")
    }

    @MainThread
    private fun loadModel() {
        if (model == null) {
            model = buildModel()
            model!!.updateState(self.getTransform(), self.velocity)
        }
    }

    fun applyAnimation(name: String) {
        model?.applyAnimation(name)
    }

    private fun unloadModel() {
        self.world.tickManager.scheduleMainThreadAfterCommitTask {
            model?.destroy()
            model = null
        }
    }
}