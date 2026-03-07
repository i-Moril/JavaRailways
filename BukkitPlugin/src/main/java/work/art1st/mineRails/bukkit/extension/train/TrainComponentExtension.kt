package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.extension.ModelLoader
import work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker
import work.art1st.mineRails.bukkit.render.chunk.RenderChunk
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.getTransform
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData

@OptIn(ExtensionData::class)
val Car.renderer: TrainComponentExtension<Car>
    get() = getExtensionData(BukkitPlugin)

@OptIn(ExtensionData::class)
val Bogie.renderer: TrainComponentExtension<Bogie>
    get() = getExtensionData(BukkitPlugin)

val Renderable.chunk
    get() = RenderChunk(position)

abstract class TrainComponentExtension<T : Renderable> (
    var modelLoader: ModelLoader
) : BukkitRenderable<T> {
    protected open lateinit var self: T
    // TODO: Fill model
    private val audienceTracker: ModelAudienceTracker = ModelAudienceTracker()
    var model: CompositeModel? = null

    override fun commit() {
        if (audienceTracker.isValid) {
            model?.updateState(self.getTransform(), self.velocity)
        } else {
            unloadModel()
        }
        audienceTracker.commit()
    }

    override fun destroy() {
        unloadModel()
    }

    override fun initialize(host: T) {
        self = host
    }

    /**
     * 调用时若没有加载模型会加载模型
     */
    @MainThread
    override fun sendTo(player: Player) {
        audienceTracker.update()
        loadModel()
        model!!.updatePlayerVisibility(player)
    }

    @MainThread
    private fun loadModel() {
        if (model == null) {
            model = modelLoader.load()
            model?.updateState(self.getTransform(), self.velocity)
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