package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
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
import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.RailDisplayType
import work.art1st.mineRails.bukkit.config.RotationConfig
import work.art1st.mineRails.bukkit.config.ScaleConfig
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import kotlin.math.ceil

/**
 * 轨道分段渲染扩展：曲线静态时只在首次发送时构建实体。
 */
class RailExtension : RenderableExtension<RailArcVisualSegment>, BukkitRenderable<RailArcVisualSegment> {
    private lateinit var self: RailArcVisualSegment

    override fun initialize(host: RailArcVisualSegment, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    override fun commit() = Unit

    override fun save(): Pair<String, SerializableExtensionData>? = null

    @MainThread
    override fun sendTo(player: Player) {
        buildModel()
    }

    /**
     * 在主线程主动构建模型（幂等）。
     * 供 bootstrap 在注册分段后立即触发，避免依赖 sendTo 懒加载。
     */
    @MainThread
    fun ensureBuilt() {
        buildModel()
    }

    /**
     * 主线程：根据配置构建所有 ItemDisplay 实体。
     * 仅构建当前分段 [RailArcVisualSegment.arcStart] ~ [RailArcVisualSegment.arcEnd]。
     */
    @MainThread
    protected fun buildModel() {
        if (displayEntities.isNotEmpty()) return
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val config = self.templateSnapshot?.toModelConfig() ?: BukkitPlugin.INSTANCE.railModelConfig
        val curve = self.rail.curve

        val repeatLength = config.repeatLength.coerceAtLeast(0.1)
        val totalLength = curve.totalArcLength
        val segmentStart = self.arcStart.coerceIn(0.0, totalLength)
        val segmentEnd = self.arcEnd.coerceIn(segmentStart, totalLength)

        val pointArcs = if (self.pointArcs.isNotEmpty()) {
            self.pointArcs
        } else {
            computePointArcs(curve, segmentStart, segmentEnd, repeatLength)
        }
        for (arc in pointArcs) {
            val point = curve.getPointAt(arc)
            for (element in config.elements) {
                val entity = createDisplayEntity(bukkitWorld, point, element)
                displayEntities.add(entity)
            }
        }

        if (displayEntities.isEmpty()) {
            val point = curve.getPointAt(segmentStart)
            for (element in config.elements) {
                val entity = createDisplayEntity(bukkitWorld, point, element)
                displayEntities.add(entity)
            }
        }
    }

    // 直接存弧长，不再需要 getIdxAt
    private fun computePointArcs(
        curve: PrimeCurve,
        segmentStart: Double,
        segmentEnd: Double,
        repeatLength: Double
    ): List<Double> {
        val arcs = LinkedHashSet<Double>()
        var arcLength = ceil(segmentStart / repeatLength) * repeatLength
        if (arcLength < segmentStart) {
            arcLength += repeatLength
        }
        val epsilon = 1e-6
        while (arcLength <= segmentEnd + epsilon) {
            arcs.add(arcLength)
            arcLength += repeatLength
        }
        if (arcs.isEmpty()) {
            arcs.add(segmentStart)
        }
        return arcs.toList()
    }

    @MainThread
    override fun destroy() {
        self.world.renderChunkManager.remove(self)
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

    private val displayEntities = mutableListOf<Display>()

    /**
     * 在曲线指定点创建单个显示实体。
     */
    private fun createDisplayEntity(
        bukkitWorld: org.bukkit.World,
        point: Curve.Point,
        element: RailElementConfig
    ): Display {
        val worldPos = computeWorldPosition(point, element.offset)
        val entity: Display = when (element.type) {
            RailDisplayType.BLOCK -> {
                val display = bukkitWorld.spawnEntity(
                    Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z()),
                    org.bukkit.entity.EntityType.BLOCK_DISPLAY
                ) as BlockDisplay
                val material = runCatching {
                    Material.valueOf((element.blockMaterial ?: "STONE").uppercase())
                }.getOrElse { Material.STONE }
                val data: BlockData = material.createBlockData()
                display.block = data
                display
            }
            RailDisplayType.ITEM -> {
                val display = bukkitWorld.spawnEntity(
                    Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z()),
                    org.bukkit.entity.EntityType.ITEM_DISPLAY
                ) as ItemDisplay
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                display.setItemStack(element.item?.toItemStack() ?: ItemStack(Material.RAIL))
                display
            }
        }
        entity.isPersistent = false

        updateDisplayEntity(entity, point, element)
        return entity
    }

    /**
     * 更新显示实体的变换（位置、朝向、缩放）。
     */
    private fun updateDisplayEntity(
        entity: Display,
        point: Curve.Point,
        element: RailElementConfig
    ) {
        val worldPos = computeWorldPosition(point, element.offset)
        val rotation = computeCurveRotation(point).mul(computeElementRotation(element.rotation))
        // Display 实体的服务端位置必须与可视位置一致，否则玩家可能收不到该实体的追踪包。
        entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))

        entity.setTransformation(
            Transformation(
                Vector3f(0f, 0f, 0f),
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
     * right = tangent × normal（右手系）
     * 矩阵列 = [right, normal, tangent]（JOML Matrix3d 构造函数按 row-major 填充）
     */
    private fun computeCurveRotation(point: Curve.Point): Quaterniond {
        val tangent = point.tangent
        val normal = point.normal
        val binormal = Vector3d()
        normal.cross(tangent, binormal).normalize()

        // Matrix3d(a,b,c,d,e,f,g,h,i) fills row-major: Row0=(a,b,c), Row1=(d,e,f), Row2=(g,h,i)
        // Col0 = world dir of model+X = binormal, Col1 = normal, Col2 = tangent
        // 然后取共轭（取反 Y 轴）：q' = (w, x, -y, z)，将绕 Y 轴旋转取反
        val rotationMatrix = Matrix3d(
            binormal.x(), normal.x(), tangent.x(),
            binormal.y(), normal.y(), tangent.y(),
            binormal.z(), normal.z(), tangent.z()
        )
        val q = Quaterniond().setFromNormalized(rotationMatrix)
        // 取共轭（取反 X 轴）：q' = (w, -x, y, z)，将绕 Y 轴旋转方向取反
        return Quaterniond(q.w(), -q.x(), q.y(), q.z())
    }

    private fun computeElementRotation(rotation: RotationConfig): Quaterniond {
        return Quaterniond().rotateXYZ(
            Math.toRadians(rotation.x),
            Math.toRadians(rotation.y),
            Math.toRadians(rotation.z)
        )
    }

    private fun RailTemplateSnapshot.toModelConfig(): RailModelConfig {
        return RailModelConfig(
            repeatLength = repeatLength,
            elements = elements.map { element ->
                RailElementConfig(
                    type = element.type,
                    item = element.itemStack?.let {
                        val meta = it.itemMeta
                        val customModelData = if (meta != null && meta.hasCustomModelData()) {
                            meta.customModelData
                        } else null
                        work.art1st.mineRails.bukkit.config.RailItemConfig(
                            material = it.type.name,
                            customModelData = customModelData
                        )
                    },
                    blockMaterial = element.blockMaterial,
                    offset = OffsetConfig(element.offsetX, element.offsetY, element.offsetZ),
                    rotation = RotationConfig(element.rotationX, element.rotationY, element.rotationZ),
                    scale = ScaleConfig(element.scaleX, element.scaleY, element.scaleZ)
                )
            }
        )
    }
}
