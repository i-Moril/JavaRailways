package work.art1st.javarailways.bukkit.logic

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.RailDisplayType
import work.art1st.javarailways.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.javarailways.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.javarailways.bukkit.extension.rail.RailDecorationLayout
import work.art1st.javarailways.bukkit.extension.rail.RailElementTemplateSnapshot
import work.art1st.javarailways.bukkit.extension.rail.RailExtension
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.util.ThreadGuard
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RailVisualBootstrap {
    private val railSegments: MutableMap<UUID, MutableList<RailArcVisualSegment>> = ConcurrentHashMap()

    fun registerRail(rail: CoreRail, railType: String = "standard"): PersistedRailRenderMeta =
        registerRailWithTemplate(rail, createTemplateSnapshot(railType))

    fun registerRailWithTemplate(
        rail: CoreRail,
        templateSnapshot: RailTemplateSnapshot
    ): PersistedRailRenderMeta {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.registerRailWithTemplate")
        val segments = createSegments(rail, templateSnapshot)
        registerSegments(rail.id, segments)
        return PersistedRailRenderMeta(
            railId = rail.id,
            segments = segments.map { RailArcSegmentMeta(it.arcStart, it.arcEnd, it.pointArcs) },
            templateSnapshot = templateSnapshot
        )
    }

    fun unregisterRail(railId: UUID) {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.unregisterRail")
        railSegments.remove(railId)?.forEach { it.destroy() }
    }

    fun fromPersisted(coreWorld: CoreWorld, persisted: PersistedRailRenderMeta): Boolean {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.fromPersisted")
        val rail = coreWorld.getRail(persisted.railId) ?: return false
        registerSegments(rail.id, createSegments(rail, persisted.templateSnapshot))
        return true
    }

    private fun registerSegments(railId: UUID, segments: List<RailArcVisualSegment>) {
        unregisterRail(railId)
        if (segments.isEmpty()) return
        val renderManager = segments.first().world.renderChunkManager
        segments.forEach { segment ->
            val extension = RailExtension()
            segment.registerExtensionData(BukkitPlugin.INSTANCE, extension)
            extension.initialize(segment, emptyMap())
            // Register lightweight metadata only. RenderChunkManager builds entities when the
            // first nearby player becomes an audience member and unloads them after the last leaves.
            renderManager.update(segment, null, segment.position)
        }
        railSegments[railId] = segments.toMutableList()
    }

    private fun createSegments(rail: CoreRail, templateSnapshot: RailTemplateSnapshot): List<RailArcVisualSegment> {
        val total = rail.curve.totalArcLength
        if (total <= 0.0) return emptyList()
        val segmentCount = kotlin.math.ceil(total / RailArcVisualSegment.SEGMENT_ARC_LENGTH).toInt()
        val allArcs = RailDecorationLayout.endpointInclusiveArcs(
            totalArcLength = total,
            repeatLength = templateSnapshot.repeatLength.coerceAtLeast(0.1)
        )
        return (0 until segmentCount).mapNotNull { index ->
            val arcStart = index * RailArcVisualSegment.SEGMENT_ARC_LENGTH
            val arcEnd = minOf((index + 1) * RailArcVisualSegment.SEGMENT_ARC_LENGTH, total)
            val pointArcs = RailDecorationLayout.arcsInSegment(
                allArcs,
                arcStart,
                arcEnd,
                includeEnd = index == segmentCount - 1
            )
            pointArcs.takeIf { it.isNotEmpty() }?.let {
                RailArcVisualSegment(
                    rail = rail,
                    arcStart = arcStart,
                    arcEnd = arcEnd,
                    pointArcs = it,
                    templateSnapshot = templateSnapshot
                )
            }
        }
    }

    private fun createTemplateSnapshot(railType: String): RailTemplateSnapshot {
        // A named rail package overrides the bundled standard rail configuration.
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val config = if (modelManager.hasRailPackages) {
            modelManager.getRailConfig(railType) ?: BukkitPlugin.INSTANCE.railModelConfig
        } else {
            BukkitPlugin.INSTANCE.railModelConfig
        }
        val elements = config.elements.map { element ->
            RailElementTemplateSnapshot(
                type = element.type,
                itemStack = element.item?.toItemStack(),
                blockMaterial = element.blockMaterial,
                offsetX = element.offset.x,
                offsetY = element.offset.y,
                offsetZ = element.offset.z,
                rotationX = element.rotation.x,
                rotationY = element.rotation.y,
                rotationZ = element.rotation.z,
                scaleX = element.scale.x,
                scaleY = element.scale.y,
                scaleZ = element.scale.z
            )
        }
        val raw = buildString {
            append(config.repeatLength)
            elements.forEach { e ->
                append('|')
                append(e.type.name)
                append(',')
                append(e.blockMaterial ?: "")
                append(',')
                append(e.itemStack?.type?.name ?: "")
                append(',')
                val itemMeta = e.itemStack?.itemMeta
                val customModelData = if (itemMeta != null && itemMeta.hasCustomModelData()) {
                    itemMeta.customModelData
                } else null
                append(customModelData ?: "")
                append(',')
                append(e.offsetX).append(',').append(e.offsetY).append(',').append(e.offsetZ)
                append(',')
                append(e.rotationX).append(',').append(e.rotationY).append(',').append(e.rotationZ)
                append(',')
                append(e.scaleX).append(',').append(e.scaleY).append(',').append(e.scaleZ)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return RailTemplateSnapshot(
            templateId = hash,
            repeatLength = config.repeatLength,
            elements = elements
        )
    }
}
