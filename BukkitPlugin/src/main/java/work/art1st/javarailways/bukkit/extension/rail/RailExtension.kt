package work.art1st.javarailways.bukkit.extension.rail

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.OffsetConfig
import work.art1st.javarailways.bukkit.config.RailBrightnessConfig
import work.art1st.javarailways.bukkit.config.RailDisplayType
import work.art1st.javarailways.bukkit.config.RailElementConfig
import work.art1st.javarailways.bukkit.config.RailModelConfig
import work.art1st.javarailways.bukkit.config.RotationConfig
import work.art1st.javarailways.bukkit.config.ScaleConfig
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.bukkit.extension.BukkitRenderable
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualContent
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.bukkit.render.backend.displayViewRangeForDistance
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

internal data class RailDisplayPlacement(
    val visualPosition: Vector3dc,
    val lightProbePosition: Vector3dc,
    val visualTranslation: Vector3dc
)

/**
 * Computes a display's visual position and a compensated entity position used as its light probe.
 * Both offsets use curve-local axes: tangent → x, normal → y, tangent × normal → z.
 */
internal fun computeRailDisplayPlacement(
    curvePosition: Vector3dc,
    tangent: Vector3dc,
    normal: Vector3dc,
    visualOffset: OffsetConfig,
    lightProbeOffset: OffsetConfig
): RailDisplayPlacement {
    val binormal = Vector3d()
    tangent.cross(normal, binormal).normalize()

    fun applyLocalOffset(origin: Vector3dc, offset: OffsetConfig): Vector3d = Vector3d(
        origin.x() + tangent.x() * offset.x + normal.x() * offset.y + binormal.x() * offset.z,
        origin.y() + tangent.y() * offset.x + normal.y() * offset.y + binormal.y() * offset.z,
        origin.z() + tangent.z() * offset.x + normal.z() * offset.y + binormal.z() * offset.z
    )

    val visualPosition = applyLocalOffset(curvePosition, visualOffset)
    val lightProbePosition = applyLocalOffset(visualPosition, lightProbeOffset)
    return RailDisplayPlacement(
        visualPosition = visualPosition,
        lightProbePosition = lightProbePosition,
        visualTranslation = Vector3d(visualPosition).sub(lightProbePosition)
    )
}

/**
 * 轨道分段渲染扩展：曲线静态时只在首次发送时构建实体。
 */
class RailExtension : RenderableExtension<RailArcVisualSegment>, BukkitRenderable<RailArcVisualSegment> {
    private lateinit var self: RailArcVisualSegment
    private val audience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun initialize(host: RailArcVisualSegment, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    override fun commit() = Unit

    override fun save(): Pair<String, SerializableExtensionData>? = null

    @MainThread
    @Synchronized
    override fun sendTo(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "RailExtension.sendTo")
        audience.add(player.uniqueId)
        buildModel()
        displayVisuals.forEach { it.showTo(player) }
    }

    /** Unload static rail entities after the last nearby player leaves. */
    @MainThread
    @Synchronized
    fun hideFrom(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "RailExtension.hideFrom")
        if (!audience.remove(player.uniqueId)) return
        displayVisuals.forEach { it.hideFrom(player) }
        if (audience.isEmpty()) destroyModel()
    }

    /**
     * 主线程：根据配置构建所有 ItemDisplay 实体。
     * 仅构建当前分段 [RailArcVisualSegment.arcStart] ~ [RailArcVisualSegment.arcEnd]。
     */
    @MainThread
    @Synchronized
    protected fun buildModel() {
        if (displayVisuals.isNotEmpty()) return
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val config = self.templateSnapshot?.toModelConfig() ?: BukkitPlugin.INSTANCE.railModelConfig
        val curve = self.rail.curve

        val repeatLength = config.repeatLength
        val totalLength = curve.totalArcLength
        val segmentStart = self.arcStart.coerceIn(0.0, totalLength)
        val segmentEnd = self.arcEnd.coerceIn(segmentStart, totalLength)

        val requestedPointArcs = if (self.pointArcs.isNotEmpty()) {
            self.pointArcs
        } else {
            RailDecorationLayout.arcsInSegment(
                RailDecorationLayout.endpointInclusiveArcs(totalLength, repeatLength),
                segmentStart,
                segmentEnd,
                includeEnd = segmentEnd >= totalLength - 1.0e-9
            )
        }
        val pointArcs = RailDecorationLayout.sanitizeArcsInSegment(
            requestedPointArcs,
            segmentStart,
            segmentEnd
        )
        val created = mutableListOf<DisplayVisual>()
        try {
            for (arc in pointArcs) {
                val point = curve.getPointAt(arc)
                for (element in config.elements) {
                    created += createDisplayVisual(bukkitWorld, point, element)
                }
            }

            if (created.isEmpty()) {
                val point = curve.getPointAt(segmentStart)
                for (element in config.elements) {
                    created += createDisplayVisual(bukkitWorld, point, element)
                }
            }
            displayVisuals += created
        } catch (error: Throwable) {
            created.forEach { it.destroy() }
            throw error
        }
    }

    @MainThread
    @Synchronized
    override fun destroy() {
        audience.clear()
        self.world.renderChunkManager.remove(self)
        destroyModel()
    }

    /**
     * 主线程：销毁所有 ItemDisplay 实体。
     */
    @Synchronized
    protected fun destroyModel() {
        displayVisuals.forEach { it.destroy() }
        displayVisuals.clear()
    }

    private val displayVisuals = mutableListOf<DisplayVisual>()

    /** Create one backend-specific display visual at the requested curve point. */
    private fun createDisplayVisual(
        bukkitWorld: org.bukkit.World,
        point: Curve.Point,
        element: RailElementConfig
    ): DisplayVisual {
        val placement = computeRailDisplayPlacement(
            curvePosition = point.position,
            tangent = point.tangent,
            normal = point.normal,
            visualOffset = element.offset,
            lightProbeOffset = element.lightProbeOffset
        )
        val rotation = computeCurveRotation(point).mul(computeElementRotation(element.rotation))
        val transformation = Transformation(
            Vector3f(
                placement.visualTranslation.x().toFloat(),
                placement.visualTranslation.y().toFloat(),
                placement.visualTranslation.z().toFloat()
            ),
            rotation.get(Quaternionf()),
            Vector3f(element.scale.x.toFloat(), element.scale.y.toFloat(), element.scale.z.toFloat()),
            Quaternionf()
        )
        val content = when (element.type) {
            RailDisplayType.BLOCK -> {
                val materialName = requireNotNull(element.blockMaterial) {
                    "BLOCK rail display element requires blockMaterial"
                }
                val material = try {
                    Material.valueOf(materialName.uppercase())
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("Unknown rail block material '$materialName'", failure)
                }
                val data: BlockData = material.createBlockData()
                DisplayVisualContent.Block(data)
            }

            RailDisplayType.ITEM -> DisplayVisualContent.Item(
                requireNotNull(element.item) { "ITEM rail display element requires item configuration" }.toItemStack()
            )
        }
        return BukkitPlugin.INSTANCE.displayRenderBackend.create(
            bukkitWorld,
            DisplayVisualDescriptor(
                location = Location(
                    bukkitWorld,
                    placement.lightProbePosition.x(),
                    placement.lightProbePosition.y(),
                    placement.lightProbePosition.z()
                ),
                content = content,
                transformation = transformation,
                viewRange = displayViewRangeForDistance(
                    BukkitPlugin.INSTANCE.javaRailwaysConfig.displayRenderer.playerDistance.maxBlocks
                )
            )
        ).also { visual ->
            element.brightness?.let { visual.setBrightness(it.block, it.sky) }
        }
    }

    /**
     * 根据曲线点的切线和法线计算旋转四元数。
     * local X = tangent, local Y = normal, local Z = tangent × normal.
     */
    private fun computeCurveRotation(point: Curve.Point): Quaterniond {
        val tangent = point.tangent
        val normal = point.normal
        val binormal = Vector3d()
        tangent.cross(normal, binormal).normalize()

        // Matrix3d(a,b,c,d,e,f,g,h,i) fills columns: Col0=(a,b,c), Col1=(d,e,f), Col2=(g,h,i)
        // Col0 = world dir of model+X = tangent, Col1 = normal, Col2 = binormal.
        val rotationMatrix = Matrix3d(
            tangent.x(), tangent.y(), tangent.z(),
            normal.x(), normal.y(), normal.z(),
            binormal.x(), binormal.y(), binormal.z()
        )
        return Quaterniond().setFromNormalized(rotationMatrix)
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
                        work.art1st.javarailways.bukkit.config.RailItemConfig(
                            material = it.type.name,
                            customModelData = customModelData
                        )
                    },
                    blockMaterial = element.blockMaterial,
                    brightness = if (element.brightnessBlock != null || element.brightnessSky != null) {
                        RailBrightnessConfig(
                            block = element.brightnessBlock ?: 0,
                            sky = element.brightnessSky ?: 0
                        )
                    } else null,
                    offset = OffsetConfig(element.offsetX, element.offsetY, element.offsetZ),
                    lightProbeOffset = OffsetConfig(
                        element.lightProbeOffsetX,
                        element.lightProbeOffsetY,
                        element.lightProbeOffsetZ
                    ),
                    rotation = RotationConfig(element.rotationX, element.rotationY, element.rotationZ),
                    scale = ScaleConfig(element.scaleX, element.scaleY, element.scaleZ)
                )
            }
        )
    }
}
