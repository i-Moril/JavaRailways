package work.art1st.javarailways.bukkit.extension.rail

import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps


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
 * Both offsets use curve-local axes: normal × tangent → x, normal → y, tangent → z.
 */
internal fun computeRailDisplayPlacement(
    curvePosition: Vector3dc,
    tangent: Vector3dc,
    normal: Vector3dc,
    visualOffset: OffsetConfig,
    lightProbeOffset: OffsetConfig
): RailDisplayPlacement {
    val lateral = Vector3d(normal).cross(tangent).normalize()

    fun applyLocalOffset(origin: Vector3dc, offset: OffsetConfig): Vector3d = Vector3d(
        origin.x() + lateral.x() * offset.x + normal.x() * offset.y + tangent.x() * offset.z,
        origin.y() + lateral.y() * offset.x + normal.y() * offset.y + tangent.y() * offset.z,
        origin.z() + lateral.z() * offset.x + normal.z() * offset.y + tangent.z() * offset.z
    )

    val visualPosition = applyLocalOffset(curvePosition, visualOffset)
    val lightProbePosition = applyLocalOffset(visualPosition, lightProbeOffset)
    return RailDisplayPlacement(
        visualPosition = visualPosition,
        lightProbePosition = lightProbePosition,
        visualTranslation = Vector3d(visualPosition).sub(lightProbePosition)
    )
}

/** Maps model +X to lateral, +Y to normal and +Z to the curve tangent. */
internal fun computeRailCurveRotation(tangent: Vector3dc, normal: Vector3dc): Quaterniond {
    val lateral = Vector3d(normal).cross(tangent).normalize()
    val rotationMatrix = Matrix3d(
        lateral.x(), lateral.y(), lateral.z(),
        normal.x(), normal.y(), normal.z(),
        tangent.x(), tangent.y(), tangent.z()
    )
    return Quaterniond().setFromNormalized(rotationMatrix)
}

/**
 * 轨道分段渲染扩展：曲线静态时只在首次发送时构建实体。
 */
class RailExtension : RenderableExtension<RailArcVisualSegment>, BukkitRenderable<RailArcVisualSegment> {
    private lateinit var self: RailArcVisualSegment
    private val audience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var retired = false

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
        if (retired || displayVisuals.isNotEmpty()) return
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
            val cleanup = DestructionSteps()
            created.forEach { visual -> cleanup.await { visual.destroy() } }
            cleanup.finish().whenComplete { _, failure ->
                if (failure != null) {
                    error.addSuppressed(failure)
                    BukkitPlugin.INSTANCE.logger.log(java.util.logging.Level.SEVERE,
                        "Rail model construction cleanup failed", failure)
                }
            }
            throw error
        }
    }

    private val destruction = Destruction()

    @MainThread
    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        retired = true
        audience.clear()
        steps.await { self.world.renderChunkManager.remove(self) }
        steps.await { destroyModel() }
        pendingModelCleanup.toList().forEach { completion -> steps.await { completion } }
        steps.finish()
    }

    /**
     * 主线程：销毁所有 ItemDisplay 实体。
     */
    @Synchronized
    protected fun destroyModel(): CompletionStage<Void> {
        val steps = DestructionSteps()
        displayVisuals.forEach { visual -> steps.await { visual.destroy() } }
        displayVisuals.clear()
        val completion = steps.finish()
        pendingModelCleanup += completion
        completion.whenComplete { _, failure ->
            if (failure == null) synchronized(this) { pendingModelCleanup.remove(completion) }
        }
        return completion
    }

    private val pendingModelCleanup = mutableListOf<CompletionStage<Void>>()
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
        val rotation = computeRailCurveRotation(point.tangent, point.normal).mul(computeElementRotation(element.rotation))
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
