package work.art1st.mineRails.bukkit.extension.rail

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.rail.RailView
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.chunk.ModelAudienceTracker
import work.art1st.mineRails.bukkit.render.model.*

@OptIn(ExtensionData::class)
val Rail.renderer: RailExtension
    get() = getExtensionData(BukkitPlugin)

class RailExtension : RenderableExtension<Rail>, Destroyable {
    private lateinit var self: Rail
    private var model: CompositeModel? = null
    private val audienceTracker = ModelAudienceTracker()

    override fun initialize(host: Rail, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun commit() {}

    override fun destroy() {
        unloadModel()
    }

    /**
     * 根据铁轨曲线构建渲染模型。
     * 沿曲线生成多个 RailSegmentModel 点段，形成完整的铁轨外观。
     */
    fun buildModel(): CompositeModel {
        val world = self.world
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val curve = self.curve

        // 使用 curve 作为主视图（正向）
        val models = buildRailModels(curve, world)

        // 创建根实体（用于定位整体铁轨）
        val rootEntity = bukkitWorld.spawn(
            Location(bukkitWorld, curve.getPointAt(0).position.x(), curve.getPointAt(0).position.y(), curve.getPointAt(0).position.z()),
            org.bukkit.entity.ItemDisplay::class.java
        )
        rootEntity.isPersistent = false

        return CompositeModel(world, models, rootEntity)
    }

    /**
     * 沿曲线构建铁轨段模型。
     * 每个点生成一个 DisplayModel，并附加到父模型。
     */
    private fun buildRailModels(curve: PrimeCurve, world: World): List<AbstractModel> {
        val models = mutableListOf<AbstractModel>()
        val totalPoints = curve.totalPoints

        // 采样间隔 - 每隔一定数量的点创建一个模型段
        val step = maxOf(1, totalPoints / RAIL_SEGMENT_COUNT)
        val lastIdx = totalPoints - 1

        for (idx in 0..lastIdx step step) {
            val point = curve.getPointAt(idx)
            val model = createRailSegmentModel(point, world)
            models.add(model)

            // 最后一个点不需要额外处理
            if (idx == lastIdx) break

            // 创建中间连接段
            val nextIdx = minOf(idx + step, lastIdx)
            if (nextIdx != idx) {
                val nextPoint = curve.getPointAt(nextIdx)
                models.addAll(createConnectionSegments(point, nextPoint, world))
            }
        }

        return models
    }

    /**
     * 创建单个铁轨段模型。
     */
    private fun createRailSegmentModel(point: Curve.Point, world: World): DisplayModel {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val bukkitEntity = bukkitWorld.spawn(
            Location(bukkitWorld, point.position.x(), point.position.y(), point.position.z()),
            org.bukkit.entity.ItemDisplay::class.java
        )
        bukkitEntity.isPersistent = false

        // 使用默认铁轨材质
        bukkitEntity.setItemStack(ItemStack(Material.RAIL))

        // 构建基于曲线点朝向的旋转
        val rotation = computeCurveRotation(point)

        val state = ModelState(
            localTransform = CompactTransform(
                position = Vector3d(point.position.x(), point.position.y(), point.position.z()),
                rotation = rotation
            )
        )

        return DisplayModel(
            world = world,
            state = state,
            attachedModels = emptyList(),
            animation = emptyMap(),
            entity = bukkitEntity
        )
    }

    /**
     * 根据曲线点的切线和法线计算旋转四元数。
     */
    private fun computeCurveRotation(point: Curve.Point): Quaterniond {
        // 切线方向作为前向
        val tangent = point.tangent
        // 法线方向作为上向
        val normal = point.normal

        // 构建正交基: right = up x forward
        val right = Vector3d()
        normal.cross(tangent, right).normalize()

        // 构建旋转矩阵（列主序）
        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), tangent.x(),
            right.y(), normal.y(), tangent.y(),
            right.z(), normal.z(), tangent.z()
        )

        return Quaterniond().setFromNormalized(rotationMatrix)
    }

    /**
     * 创建两点之间的连接段模型。
     */
    private fun createConnectionSegments(from: Curve.Point, to: Curve.Point, world: World): List<DisplayModel> {
        // 简化的实现：只在中间点创建一个连接模型
        val midPosition = Vector3d(
            (from.position.x() + to.position.x()) / 2,
            (from.position.y() + to.position.y()) / 2,
            (from.position.z() + to.position.z()) / 2
        )

        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val bukkitEntity = bukkitWorld.spawn(
            Location(bukkitWorld, midPosition.x, midPosition.y, midPosition.z),
            org.bukkit.entity.ItemDisplay::class.java
        )
        bukkitEntity.isPersistent = false

        bukkitEntity.setItemStack(ItemStack(Material.RAIL))

        // 计算中间朝向
        val midTangent = Vector3d(
            to.position.x() - from.position.x(),
            to.position.y() - from.position.y(),
            to.position.z() - from.position.z()
        ).normalize()

        val state = ModelState(
            localTransform = CompactTransform(
                position = midPosition,
                rotation = computeRotationFromTangent(midTangent, from.normal)
            )
        )

        return listOf(DisplayModel(
            world = world,
            state = state,
            attachedModels = emptyList(),
            animation = emptyMap(),
            entity = bukkitEntity
        ))
    }

    /**
     * 根据切线和法线计算旋转四元数。
     */
    private fun computeRotationFromTangent(tangent: org.joml.Vector3dc, normal: org.joml.Vector3dc): Quaterniond {
        val right = Vector3d()
        normal.cross(tangent, right).normalize()

        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), tangent.x(),
            right.y(), normal.y(), tangent.y(),
            right.z(), normal.z(), tangent.z()
        )

        return Quaterniond().setFromNormalized(rotationMatrix)
    }

    @MainThread
    private fun loadModel() {
        if (model == null) {
            model = buildModel()
        }
    }

    /**
     * 根据指定方向获取铁轨视图的变换。
     */
    private fun getViewTransform(view: RailView): CompactTransform {
        val startPoint = view.startPoint
        val rotation = computeCurveRotation(startPoint)
        return CompactTransform(
            position = Vector3d(startPoint.position.x(), startPoint.position.y(), startPoint.position.z()),
            rotation = rotation
        )
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

    companion object {
        // 铁轨模型的段数量（沿曲线的采样点数量）
        private const val RAIL_SEGMENT_COUNT = 10
    }
}