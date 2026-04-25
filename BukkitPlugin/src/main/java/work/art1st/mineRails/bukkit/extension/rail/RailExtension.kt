package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.OffsetConfig
import work.art1st.mineRails.bukkit.config.RailElementConfig
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.extension.bukkitWorldData

@OptIn(ExtensionData::class)
val Rail.renderer: RailExtension
    get() = getExtensionData(BukkitPlugin)

/**
 * 轨道渲染扩展：从 car_model.yml 加载可视化配置，
 * 沿轨道曲线按 [BukkitPlugin.railModelConfig] 的 repeatLength 间隔放置重复的元素模型。
 * 实现 [Synchronizable]，每帧通过 [commit] 更新所有 ItemDisplay 实体位置。
 */
class RailExtension : RenderableExtension<Rail>, BukkitRenderable<Rail>, Synchronizable, TickingObject, Destroyable {
    private lateinit var self: Rail

    override fun initialize(host: Rail, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
        // 注册到 tick manager，确保 commit() 每帧被调用
        self.world.tickManager.scheduleMainThreadPostTickTask {
            self.world.tickManager.register(this)
        }
    }

    override fun tick(deltaT: Double) {
        // 无需物理更新，ItemDisplay 实体通过 commit() 更新位置
    }

    override fun commit() {
        updateModel()
    }

    override fun save(): Pair<String, SerializableExtensionData>? = null

    @MainThread
    override fun sendTo(player: Player) {
        buildModel()
    }

    /**
     * 主线程：根据配置构建所有 ItemDisplay 实体。
     * 沿曲线每隔 repeatLength 米放置一组元素。
     * 仅在首次发送给玩家时调用。
     */
    @MainThread
    protected fun buildModel() {
        if (displayEntities.isNotEmpty()) return
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val config = BukkitPlugin.railModelConfig
        val curve = self.curve

        val repeatLength = config.repeatLength
        val totalLength = curve.totalArcLength

        var arcLength = 0.0
        while (arcLength <= totalLength) {
            val point = curve.getPointAt(arcLength)
            for (element in config.elements) {
                val entity = createDisplayEntity(bukkitWorld, point, element)
                displayEntities.add(entity)
            }
            arcLength += repeatLength
        }
    }

    /**
     * 每帧更新所有实体的位置和朝向。
     */
    private fun updateModel() {
        if (displayEntities.isEmpty()) return
        val curve = self.curve
        val config = BukkitPlugin.railModelConfig
        val repeatLength = config.repeatLength
        val totalLength = curve.totalArcLength

        var entityIdx = 0
        var arcLength = 0.0
        while (arcLength <= totalLength) {
            val point = curve.getPointAt(arcLength)
            for (element in config.elements) {
                val entity = displayEntities[entityIdx]
                updateDisplayEntity(entity, point, element)
                entityIdx++
            }
            arcLength += repeatLength
        }
    }

    @MainThread
    override fun destroy() {
        destroyModel()
    }

    /**
     * 主线程：销毁所有 ItemDisplay 实体。
     */
    protected fun destroyModel() {
        for (entity in displayEntities) {
            entity.remove()
        }
        displayEntities.clear()
    }

    private val displayEntities = mutableListOf<ItemDisplay>()

    /**
     * 在曲线指定点创建单个显示实体。
     */
    private fun createDisplayEntity(
        bukkitWorld: org.bukkit.World,
        point: Curve.Point,
        element: RailElementConfig
    ): ItemDisplay {
        val entity = bukkitWorld.spawnEntity(
            Location(bukkitWorld, 0.0, 0.0, 0.0),
            org.bukkit.entity.EntityType.ITEM_DISPLAY
        ) as ItemDisplay
        entity.isPersistent = false

        val material = try { Material.valueOf(element.material.uppercase()) } catch (e: Exception) { Material.STONE }
        entity.setItemStack(ItemStack(material))

        updateDisplayEntity(entity, point, element)
        return entity
    }

    /**
     * 更新显示实体的变换（位置、朝向、缩放）。
     */
    private fun updateDisplayEntity(
        entity: ItemDisplay,
        point: Curve.Point,
        element: RailElementConfig
    ) {
        val worldPos = computeWorldPosition(point, element.offset)
        val rotation = computeCurveRotation(point)

        entity.setTransformation(
            Transformation(
                Vector3f(worldPos.x().toFloat(), worldPos.y().toFloat(), worldPos.z().toFloat()),
                rotation.get(Quaternionf()),
                Vector3f(element.scale.x.toFloat(), element.scale.y.toFloat(), element.scale.z.toFloat()),
                Quaternionf(1f, 0f, 0f, 0f)
            )
        )
    }

    /**
     * 计算曲线点处元素的 world 坐标。
     * tangent → local-x, normal → local-y, binormal → local-z
     */
    private fun computeWorldPosition(point: Curve.Point, offset: OffsetConfig): Vector3dc {
        val tangent = point.tangent
        val normal = point.normal
        val binormal = Vector3d()
        normal.cross(tangent, binormal).normalize()

        return Vector3d(
            point.position.x() + tangent.x() * offset.x + normal.x() * offset.y + binormal.x() * offset.z,
            point.position.y() + tangent.y() * offset.x + normal.y() * offset.y + binormal.y() * offset.z,
            point.position.z() + tangent.z() * offset.x + normal.z() * offset.y + binormal.z() * offset.z
        )
    }

    /**
     * 根据曲线点的切线和法线计算旋转四元数。
     * tangent → local-x, normal → local-y, binormal → local-z
     */
    private fun computeCurveRotation(point: Curve.Point): Quaterniond {
        val tangent = point.tangent
        val normal = point.normal
        val binormal = Vector3d()
        normal.cross(tangent, binormal).normalize()

        val rotationMatrix = Matrix3d(
            tangent.x(), normal.x(), binormal.x(),
            tangent.y(), normal.y(), binormal.y(),
            tangent.z(), normal.z(), binormal.z()
        )
        return Quaterniond().setFromNormalized(rotationMatrix)
    }
}
