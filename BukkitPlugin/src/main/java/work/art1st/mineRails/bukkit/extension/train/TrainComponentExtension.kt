package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.Destroyable
import java.util.UUID

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
    private val audience: MutableSet<UUID> = hashSetOf()

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
     * 玩家仍在可见范围内时的轻量刷新入口。
     */
    open fun refreshFor(player: Player) = Unit

    /**
     * 玩家离开可见范围时的清理入口。
     */
    protected open fun onAudienceRemoved(player: Player) = Unit

    /**
     * 主线程：销毁所有可视化实体。
     */
    protected abstract fun destroyModel()

    @MainThread
    override fun sendTo(player: Player) {
        audience.add(player.uniqueId)
        if (!modelBuilt) {
            buildModel()
            modelBuilt = true
            updateModel()
        }
        refreshFor(player)
    }

    @MainThread
    fun hideFrom(player: Player) {
        if (!audience.remove(player.uniqueId)) return
        onAudienceRemoved(player)
        if (audience.isNotEmpty() || !modelBuilt) return
        modelBuilt = false
        destroyModel()
    }

    override fun commit() {
        if (!modelBuilt) return
        if (audience.isEmpty()) {
            modelBuilt = false
            destroyModel()
            return
        }
        updateModel()
    }

    @MainThread
    override fun destroy() {
        audience.clear()
        modelBuilt = false
        destroyModel()
    }

    protected fun forEachAudiencePlayer(action: (Player) -> Unit) {
        audience.forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let(action)
        }
    }

    open fun applyAnimation(name: String): Boolean = false

    open fun applyAnimation(name: String, sceneName: String): Boolean = false
}
