package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.bukkit.render.model.CompactTransform

@OptIn(ExtensionData::class)
val Rail.renderer: RailExtension
    get() = getExtensionData(BukkitPlugin)

class RailExtension : RenderableExtension<Rail>, Destroyable {
    private lateinit var self: Rail
    private var model: work.art1st.mineRails.bukkit.render.model.CompositeModel? = null
    private val audienceTracker = work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker()

    override fun initialize(host: Rail, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun commit() {}

    override fun destroy() {
        unloadModel()
    }

    fun buildModel(): work.art1st.mineRails.bukkit.render.model.CompositeModel {
        TODO("Not implemented yet")
    }

    @MainThread
    private fun loadModel() {
        if (model == null) {
            model = buildModel()
            // TODO: Rail doesn't have position/velocity - need to get from RailView or curve
            // model!!.updateState(transform, velocity)
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