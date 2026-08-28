package work.art1st.javarailways.bukkit.extension.rail

import org.joml.Vector3d
import org.joml.Vector3dc
import org.bukkit.inventory.ItemStack
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.RailDisplayType
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.physics.rail.CoreRail

data class RailArcSegmentMeta(
    val arcStart: Double,
    val arcEnd: Double,
    val pointArcs: List<Double> = emptyList()
)

data class RailElementTemplateSnapshot(
    val type: RailDisplayType,
    val itemStack: ItemStack? = null,
    val blockMaterial: String? = null,
    val brightnessBlock: Int? = null,
    val brightnessSky: Int? = null,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val rotationX: Double,
    val rotationY: Double,
    val rotationZ: Double,
    val scaleX: Double,
    val scaleY: Double,
    val scaleZ: Double,
    val lightProbeOffsetX: Double = 0.0,
    val lightProbeOffsetY: Double = 0.0,
    val lightProbeOffsetZ: Double = 0.0
)

data class RailTemplateSnapshot(
    val templateId: String,
    val repeatLength: Double,
    val elements: List<RailElementTemplateSnapshot>
)

class RailArcVisualSegment(
    val rail: CoreRail,
    val arcStart: Double,
    val arcEnd: Double,
    val pointArcs: List<Double> = emptyList(),
    val templateSnapshot: RailTemplateSnapshot? = null,
    private val extensionDataContainer: ExtensionDataContainer<RenderableExtension<RailArcVisualSegment>> = ExtensionDataContainer()
) : Renderable, Extendable<RenderableExtension<RailArcVisualSegment>> by extensionDataContainer {

    override val world = rail.world

    private val midPoint = rail.curve.getPointAt(((arcStart + arcEnd) / 2.0).coerceIn(0.0, rail.curve.totalArcLength))
    override val position: Vector3dc = Vector3d(midPoint.position)
    override val direction: Vector3dc = Vector3d(midPoint.tangent)
    override val normal: Vector3dc = Vector3d(midPoint.normal)
    override val velocity: Vector3dc = ZERO_VELOCITY

    override fun destroy() {
        forEachExtensionData { it.destroy() }
    }

    companion object {
        const val SEGMENT_ARC_LENGTH: Double = 8.0
        private val ZERO_VELOCITY: Vector3dc = Vector3d()
    }
}

@OptIn(ExtensionData::class)
val RailArcVisualSegment.renderer: RailExtension
    get() {
        val plugin = BukkitPlugin.instanceOrNull()
            ?: throw IllegalStateException("BukkitPlugin.INSTANCE 尚未初始化；请在访问 rail segment renderer 前先初始化 BukkitPlugin")
        return requireExtensionData(plugin)
    }
