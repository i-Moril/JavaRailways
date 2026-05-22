package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.util.Destroyable

@OptIn(ExtensionData::class)
val Car.renderer: TrainComponentExtension<Car>
    get() = getExtensionData(BukkitPlugin.INSTANCE)

@OptIn(ExtensionData::class)
val Bogie.renderer: TrainComponentExtension<Bogie>
    get() = getExtensionData(BukkitPlugin.INSTANCE)

/**
 * 车厢/转向架等列车组件的渲染扩展基类。
 * 子类通过覆写 [buildModel] 和 [updateModel] 管理可视化模型。
 */
abstract class TrainComponentExtension<T : Renderable> : BukkitRenderable<T>, Destroyable {
    protected open lateinit var self: T
    private var modelBuilt: Boolean = false

    override fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    override fun save(): Pair<String, SerializableExtensionData>? = null

    /**
     * 主线程：构建并生成所有可视化实体。
     * 在 [sendTo] 首次调用时触发。
     */
    protected abstract fun buildModel()

    /**
     * 每帧更新模型状态（位置、朝向等）。
     * 在 commit 阶段调用。
     */
    protected abstract fun updateModel()

    /**
     * 主线程：销毁所有可视化实体。
     */
    protected abstract fun destroyModel()

    @MainThread
    override fun sendTo(player: Player) {
        if (!modelBuilt) {
            buildModel()
            modelBuilt = true
            updateModel()
        }
    }

    override fun commit() {
        if (modelBuilt) {
            updateModel()
        }
    }

    @MainThread
    override fun destroy() {
        modelBuilt = false
        destroyModel()
    }

    fun applyAnimation(name: String) {
        // 暂不支持
    }
}
