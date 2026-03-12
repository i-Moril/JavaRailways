package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.extension.ModelLoader
import work.art1st.mineRails.bukkit.extension.bukkitLocation
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.extension.exception.CorruptedExtensionDataException
import work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker
import work.art1st.mineRails.bukkit.render.chunk.RenderChunk
import work.art1st.mineRails.bukkit.render.config.CompositeModelConfigV1
import work.art1st.mineRails.bukkit.render.config.ModelConfigV1
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.getTransform
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

@OptIn(ExtensionData::class)
val Car.renderer: TrainComponentExtension<Car>
    get() = getExtensionData(BukkitPlugin)

@OptIn(ExtensionData::class)
val Bogie.renderer: TrainComponentExtension<Bogie>
    get() = getExtensionData(BukkitPlugin)

val Renderable.chunk
    get() = RenderChunk(position)

/**
 * 自动实现模型加载和卸载
 */
abstract class TrainComponentExtension<T : Renderable> : BukkitRenderable<T> {
    protected open lateinit var self: T
    protected lateinit var modelLoader: ModelLoader
    // TODO: Fill model
    private val audienceTracker: ModelAudienceTracker = ModelAudienceTracker()
    var model: CompositeModel? = null

    override fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
        val extensionData = savedExtensionData[BukkitPlugin.EXTENSION_ID] ?: throw CorruptedExtensionDataException("Missing data for bukkit extension")
        if (extensionData !is CompositeModelConfigV1) {
            throw CorruptedExtensionDataException("Corrupted bukkit extension data")
        }
        modelLoader = ModelLoader {
            val rootEntity = host.world.bukkitWorldData.bukkitWorld.spawnEntity(
                host.bukkitLocation,
                org.bukkit.entity.EntityType.ITEM_DISPLAY
            )
            rootEntity.isPersistent = false
            CompositeModel(
                self.world,
                extensionData.attachedModels.map {
                    it.load(self)
                },
                rootEntity
            )
        }
    }

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