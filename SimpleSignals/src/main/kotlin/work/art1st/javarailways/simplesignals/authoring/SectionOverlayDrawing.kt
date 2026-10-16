package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.cutPosition
import java.util.UUID

/** Worker-prepared authoring geometry. No train, signal output, platform world or player state. */
internal class SectionOverlayDrawing(labels: Collection<EditorTextLabel>,
    val unavailableGeometry: Set<UUID>, private val captions: Map<String, WandText> = emptyMap()) {
    val labels: List<EditorTextLabel> = java.util.List.copyOf(labels)

    fun nearby(x: Double, y: Double, z: Double): SectionOverlayDrawing {
        fun near(px: Double, py: Double, pz: Double): Boolean =
            (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) <= 64.0 * 64.0
        return SectionOverlayDrawing(labels.filter { near(it.x(), it.y(), it.z()) }, unavailableGeometry, captions)
    }

    fun localizedLabels(locale: java.util.Locale): List<EditorTextLabel> = labels.map { label ->
        captions[label.id()]?.let { caption -> EditorTextLabel(label.id(), label.x(), label.y(), label.z(),
            Component.text(caption.render(locale)), label.appearance()) } ?: label
    }

    companion object {
        fun prepare(definitions: SimpleSignalsWorldDefinitions,
            unavailableBlocks: Set<String>, unavailableRegions: Set<String>,
            regionInteriors: Map<String, List<ProtectionRegionInteriorSlice>> = emptyMap(),
            boundaryIdentity: (RailPositionSnapshot) -> RailPositionSnapshot = { it.copy(facing = ViewDirection.AB) },
            findCurve: (UUID) -> Curve?): SectionOverlayDrawing {
            val labels = arrayListOf<EditorTextLabel>()
            val captions = hashMapOf<String, WandText>()
            val missing = linkedSetOf<UUID>()
            val curves = hashMapOf<UUID, Curve?>()
            val labelRows = hashMapOf<Triple<Double, Double, Double>, Int>()
            fun curve(id: UUID): Curve? = if (curves.containsKey(id)) curves[id] else
                findCurve(id).also { curves[id] = it }
            fun valid(id: UUID, ab: Double): Curve? = curve(id)?.takeIf { ab <= it.totalArcLength }
                .also { if (it == null) missing += id }
            fun mark(id: String, text: WandText, position: RailCutPosition, unavailable: Boolean) {
                val geometry = valid(position.railId, position.arcLengthInAbMeters) ?: return
                val point = geometry.getPointAt(position.arcLengthInAbMeters)
                val p = point.position
                labels += EditorMarkerLabels.at("$id/boundary", EditorMarkerGlyph.BOUNDARY,
                    p, point.tangent, point.normal, 3.0, 0.3,
                    if (unavailable) NamedTextColor.RED else NamedTextColor.AQUA)
                val key = Triple(p.x(), p.y(), p.z())
                val row = labelRows[key] ?: 0
                labelRows[key] = row + 1
                captions[id] = text
                labels += EditorTextLabel(id, p.x(), p.y() + 1.4 + row * 0.35, p.z(), Component.text(text.render(java.util.Locale.US)))
            }
            fun coverage(id: String, rail: UUID, lower: Double, upper: Double, unavailable: Boolean) {
                val geometry = valid(rail, upper) ?: return
                // Join actual curve samples by short chords, never stretch a tangent across a curve.
                val count = kotlin.math.ceil(upper - lower).toInt().coerceAtLeast(1)
                var start = geometry.getPointAt(lower)
                for (index in 0 until count) {
                    val end = geometry.getPointAt(if (index == count - 1) upper else lower + (upper - lower) * (index + 1) / count)
                    val chord = Vector3d(end.position).sub(start.position)
                    val length = chord.length()
                    val tangent = chord.div(length)
                    // At internal joins overlap at most 5mm to conceal normal-lift seams. Never extend the span ends.
                    val overlap = minOf(0.005, length / 100)
                    val before = if (index == 0) 0.0 else overlap
                    val after = if (index == count - 1) 0.0 else overlap
                    val center = Vector3d(start.position).add(end.position).mul(0.5)
                        .add(Vector3d(tangent).mul((after - before) / 2))
                    val normal = Vector3d(start.normal).add(end.normal)
                    labels += EditorMarkerLabels.at("$id/coverage/$index", EditorMarkerGlyph.COVERAGE,
                        center, tangent, normal, 0.12, length + before + after,
                        if (unavailable) NamedTextColor.RED else NamedTextColor.AQUA)
                    start = end
                }
            }
            class BlockBoundary(val id: String, val position: RailPositionSnapshot, val names: MutableSet<String>,
                var unavailable: Boolean)
            val blockBoundaries = linkedMapOf<RailPositionSnapshot, BlockBoundary>()
            fun blockBoundary(id: String, name: String, position: RailPositionSnapshot, unavailable: Boolean) {
                // Same rail coordinate or a real captured endpoint connection; never merge spatial crossings.
                val key = boundaryIdentity(position)
                val existing = blockBoundaries[key]
                if (existing == null) blockBoundaries[key] = BlockBoundary(id, position, linkedSetOf(name), unavailable)
                else { existing.names += name; existing.unavailable = existing.unavailable || unavailable }
            }
            definitions.fixedBlocks.forEach { block ->
                val invalid = block.traversals.any { valid(it.railId, it.upperAbMeters) == null }
                val unavailable = invalid || block.blockId in unavailableBlocks
                val name = sectionAuthoringName(block.blockId, "Block")
                val first = block.traversals.first()
                val last = block.traversals.last()
                blockBoundary("block/${block.blockId}/start", name, RailPositionSnapshot(
                    first.railId, first.facing, first.travelStartAbMeters), unavailable)
                blockBoundary("block/${block.blockId}/end", name, RailPositionSnapshot(
                    last.railId, last.facing, last.travelEndAbMeters), unavailable)
                block.traversals.forEachIndexed { index, span -> coverage("block/${block.blockId}/$index",
                    span.railId, span.lowerAbMeters, span.upperAbMeters, unavailable) }
            }
            blockBoundaries.values.forEach { boundary ->
                val name = boundary.names.singleOrNull() ?: "Block"
                mark(boundary.id, WandText.key("overlay.block", if (name == "Block") WandText.key("name.block.overlay") else name,
                    WandText.key(if (boundary.unavailable) "overlay.unavailable" else "overlay.defined")),
                    boundary.position.cutPosition(), boundary.unavailable)
            }
            val tablesByRegion = definitions.routeTables.groupBy { it.regionId }
            definitions.regions.forEach { region ->
                val interior = regionInteriors[region.regionId].orEmpty()
                val invalid = region.effectiveCuts.any { valid(it.position.railId, it.position.arcLengthInAbMeters) == null } ||
                    interior.any { valid(it.railId, it.endAbMeters) == null }
                val status = WandText.key(if (region.boundaryDraft != null) "overlay.draft" else
                    if (invalid || region.regionId in unavailableRegions) "overlay.unavailable" else "overlay.defined")
                val name = authoringName(region.regionId, "name.region")
                region.effectiveCuts.forEach { boundary -> mark("region/${region.regionId}/${boundary.boundaryId}",
                    WandText.key("overlay.region", name, authoringName(boundary.boundaryId, "name.boundary"), status), boundary.position,
                    invalid || region.regionId in unavailableRegions || region.boundaryDraft != null) }
                routeCoverage(region, tablesByRegion[region.regionId].orEmpty()).forEachIndexed { index, span -> coverage("region/${region.regionId}/$index",
                    span.railId, span.startAbMeters, span.endAbMeters,
                    invalid || region.regionId in unavailableRegions || region.boundaryDraft != null) }
            }
            definitions.pendingProtectionBoundaries.forEach { boundary -> mark("pending/${boundary.boundaryId}",
                WandText.key("overlay.pending", authoringName(boundary.boundaryId, "name.protection-boundary")), boundary.position, true) }
            return SectionOverlayDrawing(labels, java.util.Set.copyOf(missing), captions)
        }

        /** Saved paths only. Draft cuts do not grant passage or trigger another route search. */
        private fun routeCoverage(region: ProtectionRegionDefinition,
            tables: List<ProtectionRouteTableDefinition>): List<ProtectionRegionInteriorSlice> {
            val entrances = region.placedEntrances.mapTo(hashSetOf()) { it.boundaryId }
            val spans = tables.flatMap { it.routes }.filter {
                it.entryBoundaryId in entrances && it.exitBoundaryId in entrances
            }.flatMap { it.traversals }.map {
                ProtectionRegionInteriorSlice(it.railId, it.lowerAbMeters, it.upperAbMeters)
            }.sortedWith(compareBy({ it.railId }, { it.startAbMeters }, { it.endAbMeters }))
            val merged = arrayListOf<ProtectionRegionInteriorSlice>()
            spans.forEach { span ->
                val previous = merged.lastOrNull()
                if (previous?.railId == span.railId && span.startAbMeters <= previous.endAbMeters)
                    merged[merged.lastIndex] = previous.copy(endAbMeters = maxOf(previous.endAbMeters, span.endAbMeters))
                else merged += span
            }
            return merged
        }
    }
}
