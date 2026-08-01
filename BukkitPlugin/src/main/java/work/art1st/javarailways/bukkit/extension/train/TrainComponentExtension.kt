package work.art1st.javarailways.bukkit.extension.train

import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.BukkitRenderable
import work.art1st.javarailways.bukkit.extension.bukkitLocation
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import java.util.UUID

@OptIn(ExtensionData::class)
val Car.rendererOrNull: TrainComponentExtension<Car>?
    get() {
        val plugin = BukkitPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

val Car.renderer: TrainComponentExtension<Car>
    get() = rendererOrNull
        ?: throw IllegalStateException("Car renderer 未注册到车厢 ${this.hashCode()} 上")

val Car.carRendererOrNull: CarExtension?
    get() = rendererOrNull as? CarExtension

val Car.carRenderer: CarExtension
    get() = carRendererOrNull
        ?: throw IllegalStateException("车厢 ${this.hashCode()} 上注册的渲染扩展不是 CarExtension")

/**
 * 列车组件渲染扩展基类。
 * 子类通过覆写 [buildModel] 和 [updateModel] 管理可视化模型。
 */
abstract class TrainComponentExtension<T : Renderable> : BukkitRenderable<T>, Destroyable {
    protected open lateinit var self: T
    private var modelBuilt: Boolean = false
    private var retired: Boolean = false
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
        BukkitThreadOwnership.ensureEntity(player, "TrainComponentExtension.sendTo")
        sendToOwned(player)
    }

    /** Caller already owns [player]; fresh models must receive one complete frame before exposure. */
    @Synchronized
    internal fun sendToOwned(player: Player) {
        if (retired) return
        audience.add(player.uniqueId)
        if (!modelBuilt) {
            buildModel()
            modelBuilt = true
            updateModel()
        }
        refreshFor(player)
    }

    @MainThread
    @Synchronized
    fun hideFrom(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "TrainComponentExtension.hideFrom")
        if (!audience.remove(player.uniqueId)) return
        onAudienceRemoved(player)
        if (audience.isNotEmpty() || !modelBuilt) return
        modelBuilt = false
        destroyModel()
    }

    @Synchronized
    override fun commit() {
        BukkitThreadOwnership.ensureLocation(self.bukkitLocation, "TrainComponentExtension.commit")
        if (retired || !modelBuilt) return
        if (audience.isEmpty()) {
            modelBuilt = false
            destroyModel()
            return
        }
        updateModel()
    }

    @MainThread
    @Synchronized
    override fun destroy() {
        if (retired) return
        retired = true
        audience.clear()
        modelBuilt = false
        destroyModel()
    }

    protected fun hasAudience(): Boolean = audience.isNotEmpty()

    open fun applyAnimation(name: String): Boolean = false

    open fun applyAnimation(name: String, sceneName: String): Boolean = false
}
