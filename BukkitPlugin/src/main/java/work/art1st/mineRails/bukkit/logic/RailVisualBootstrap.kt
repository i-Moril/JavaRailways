package work.art1st.mineRails.bukkit.logic

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.RailDisplayType
import work.art1st.mineRails.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.mineRails.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.mineRails.bukkit.extension.rail.RailElementTemplateSnapshot
import work.art1st.mineRails.bukkit.extension.rail.RailExtension
import work.art1st.mineRails.bukkit.extension.rail.RailTemplateSnapshot
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

object RailVisualBootstrap {
    private val railSegments: MutableMap<UUID, MutableList<RailArcVisualSegment>> = ConcurrentHashMap()

    fun registerRail(rail: CoreRail): PersistedRailRenderMeta {
        val templateSnapshot = createTemplateSnapshot()
        val segments = createSegments(rail, templateSnapshot)
        registerSegments(rail.id, segments)
        return PersistedRailRenderMeta(
            railId = rail.id,
            segments = segments.map { RailArcSegmentMeta(it.arcStart, it.arcEnd, it.pointIndices) },
            templateSnapshot = templateSnapshot
        )
    }

    fun unregisterRail(railId: UUID) {
        railSegments.remove(railId)?.forEach { it.destroy() }
    }

    fun fromPersisted(coreWorld: CoreWorld, persisted: PersistedRailRenderMeta): Boolean {
        val rail = coreWorld.getRail(persisted.railId) ?: return false
        val segments = persisted.segments.map { meta ->
            RailArcVisualSegment(
                rail = rail,
                arcStart = meta.arcStart,
                arcEnd = meta.arcEnd,
                pointIndices = meta.pointIndices,
                templateSnapshot = persisted.templateSnapshot
            )
        }
        registerSegments(rail.id, segments)
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
            // 轨道是静态几何，注册后立即构建实体，避免仅靠 sendTo 的懒触发。
            extension.ensureBuilt()
            renderManager.update(segment, null, segment.position)
        }
        railSegments[railId] = segments.toMutableList()
    }

    private fun createSegments(rail: CoreRail, templateSnapshot: RailTemplateSnapshot): List<RailArcVisualSegment> {
        val total = rail.curve.totalArcLength
        if (total <= 0.0) return emptyList()
        val segmentCount = ceil(total / RailArcVisualSegment.SEGMENT_ARC_LENGTH).toInt()
        val repeatLength = templateSnapshot.repeatLength.coerceAtLeast(0.1)
        return (0 until segmentCount).map { index ->
            val arcStart = index * RailArcVisualSegment.SEGMENT_ARC_LENGTH
            val arcEnd = minOf((index + 1) * RailArcVisualSegment.SEGMENT_ARC_LENGTH, total)
            val pointIndices = computePointIndices(rail, arcStart, arcEnd, repeatLength)
            RailArcVisualSegment(
                rail = rail,
                arcStart = arcStart,
                arcEnd = arcEnd,
                pointIndices = pointIndices,
                templateSnapshot = templateSnapshot
            )
        }
    }

    private fun computePointIndices(rail: CoreRail, arcStart: Double, arcEnd: Double, repeatLength: Double): List<Int> {
        val indices = LinkedHashSet<Int>()
        var arcLength = ceil(arcStart / repeatLength) * repeatLength
        if (arcLength < arcStart) {
            arcLength += repeatLength
        }
        val epsilon = 1e-6
        while (arcLength <= arcEnd + epsilon) {
            indices.add(rail.curve.getIdxAt(arcLength))
            arcLength += repeatLength
        }
        if (indices.isEmpty()) {
            indices.add(rail.curve.getIdxAt(arcStart))
        }
        return indices.toList()
    }

    private fun createTemplateSnapshot(): RailTemplateSnapshot {
        val config = BukkitPlugin.INSTANCE.railModelConfig
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
