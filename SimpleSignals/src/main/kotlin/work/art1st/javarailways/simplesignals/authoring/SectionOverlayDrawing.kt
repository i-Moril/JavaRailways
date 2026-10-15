package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import java.util.UUID

/** Worker-prepared authoring geometry. No train, signal output, platform world or player state. */
internal class SectionOverlayDrawing(points: Collection<PreviewParticle>, labels: Collection<EditorTextLabel>,
    val unavailableGeometry: Set<UUID>) {
    val points: List<PreviewParticle> = java.util.List.copyOf(points)
    val labels: List<EditorTextLabel> = java.util.List.copyOf(labels)

    fun nearby(x: Double, y: Double, z: Double): SectionOverlayDrawing {
        fun near(px: Double, py: Double, pz: Double): Boolean =
            (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) <= 64.0 * 64.0
        return SectionOverlayDrawing(points.filter { near(it.x, it.y, it.z) },
            labels.filter { near(it.x(), it.y(), it.z()) }, unavailableGeometry)
    }

    companion object {
        fun prepare(definitions: SimpleSignalsWorldDefinitions,
            unavailableBlocks: Set<String>, unavailableRegions: Set<String>,
            regionInteriors: Map<String, List<ProtectionRegionInteriorSlice>> = emptyMap(),
            findCurve: (UUID) -> Curve?): SectionOverlayDrawing {
            val points = arrayListOf<PreviewParticle>()
            val labels = arrayListOf<EditorTextLabel>()
            val missing = linkedSetOf<UUID>()
            val curves = hashMapOf<UUID, Curve?>()
            val labelRows = hashMapOf<Triple<Double, Double, Double>, Int>()
            fun curve(id: UUID): Curve? = if (curves.containsKey(id)) curves[id] else
                findCurve(id).also { curves[id] = it }
            fun valid(id: UUID, ab: Double): Curve? = curve(id)?.takeIf { ab <= it.totalArcLength }
                .also { if (it == null) missing += id }
            fun arrow(position: RailPositionSnapshot, geometry: Curve) {
                RoutePreviewDrawing.chevron(points, geometry, position.arcLengthInAbMeters, position.facing)
            }
            fun mark(id: String, text: String, position: RailPositionSnapshot, reverse: Boolean = false) {
                val geometry = valid(position.railId, position.arcLengthInAbMeters) ?: return
                val p = geometry.getPointAt(position.arcLengthInAbMeters).position
                repeat(4) { points += PreviewParticle(p.x(), p.y() + 0.15 + it * 0.15, p.z(), PreviewParticleKind.OVERLAY_BOUNDARY) }
                arrow(position, geometry)
                if (reverse) arrow(position.copy(facing = position.facing.reversed()), geometry)
                val key = Triple(p.x(), p.y(), p.z())
                val row = labelRows[key] ?: 0
                labelRows[key] = row + 1
                labels += EditorTextLabel(id, p.x(), p.y() + 1.4 + row * 0.35, p.z(), Component.text(text))
            }
            fun coverage(id: String, text: String, rail: UUID, lower: Double, upper: Double, unavailable: Boolean,
                facing: ViewDirection? = null, both: Boolean = false) {
                val geometry = valid(rail, upper) ?: return
                val start = points.size
                RoutePreviewDrawing.sampleSpan(points, geometry, lower, upper,
                    if (unavailable) PreviewParticleKind.WARNING else PreviewParticleKind.PATH)
                // Keep long sections identifiable even when both boundaries are outside the editor's range.
                // Anchor labels at samples already prepared above; no second curve lookup or owner sampling.
                val end = points.size
                for (index in start until end step 16) {
                    val p = points[index]
                    labels += EditorTextLabel("$id/coverage/${index - start}", p.x, p.y + 0.85, p.z, Component.text(text))
                }
                if (facing != null) for (index in 0 until end - start step 4) {
                    val ab = lower + (upper - lower) * index / (end - start - 1)
                    RoutePreviewDrawing.chevron(points, geometry, ab, facing)
                    if (both) RoutePreviewDrawing.chevron(points, geometry, ab, facing.reversed())
                }
            }
            definitions.fixedBlocks.forEach { block ->
                val invalid = block.traversals.any { valid(it.railId, it.upperAbMeters) == null }
                val unavailable = invalid || block.blockId in unavailableBlocks
                val status = if (unavailable) "unavailable" else "defined"
                val first = block.traversals.first()
                val last = block.traversals.last()
                mark("block/${block.blockId}/start", "${block.blockId} start · $status", RailPositionSnapshot(
                    first.railId, first.facing, first.travelStartAbMeters), block.bidirectional)
                mark("block/${block.blockId}/end", "${block.blockId} end · $status", RailPositionSnapshot(
                    last.railId, last.facing, last.travelEndAbMeters), block.bidirectional)
                block.traversals.forEachIndexed { index, span -> coverage("block/${block.blockId}/$index",
                    "${block.blockId} · $status", span.railId, span.lowerAbMeters, span.upperAbMeters, unavailable,
                    span.facing, block.bidirectional) }
            }
            definitions.regions.forEach { region ->
                val interior = regionInteriors[region.regionId].orEmpty()
                val invalid = region.effectiveBoundaries.any { valid(it.position.railId, it.position.arcLengthInAbMeters) == null } ||
                    interior.any { valid(it.railId, it.endAbMeters) == null }
                val status = if (region.boundaryDraft != null) "draft · unavailable" else
                    if (invalid || region.regionId in unavailableRegions) "unavailable" else "defined"
                region.effectiveBoundaries.forEach { boundary -> mark("region/${region.regionId}/${boundary.boundaryId}",
                    "${region.regionId} / ${boundary.boundaryId} · $status", boundary.position) }
                interior.forEachIndexed { index, span -> coverage("region/${region.regionId}/$index",
                    "${region.regionId} · $status", span.railId, span.startAbMeters, span.endAbMeters,
                    invalid || region.regionId in unavailableRegions || region.boundaryDraft != null) }
            }
            definitions.pendingProtectionBoundaries.forEach { boundary -> mark("pending/${boundary.boundaryId}",
                "${boundary.boundaryId} · pending · unavailable", boundary.position) }
            return SectionOverlayDrawing(points, labels, java.util.Set.copyOf(missing))
        }
    }
}
