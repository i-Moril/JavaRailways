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
import java.util.UUID

/** Worker-prepared authoring geometry. No train, signal output, platform world or player state. */
internal class SectionOverlayDrawing(labels: Collection<EditorTextLabel>,
    val unavailableGeometry: Set<UUID>) {
    val labels: List<EditorTextLabel> = java.util.List.copyOf(labels)

    fun nearby(x: Double, y: Double, z: Double): SectionOverlayDrawing {
        fun near(px: Double, py: Double, pz: Double): Boolean =
            (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) <= 64.0 * 64.0
        return SectionOverlayDrawing(labels.filter { near(it.x(), it.y(), it.z()) }, unavailableGeometry)
    }

    companion object {
        fun prepare(definitions: SimpleSignalsWorldDefinitions,
            unavailableBlocks: Set<String>, unavailableRegions: Set<String>,
            regionInteriors: Map<String, List<ProtectionRegionInteriorSlice>> = emptyMap(),
            findCurve: (UUID) -> Curve?): SectionOverlayDrawing {
            val labels = arrayListOf<EditorTextLabel>()
            val missing = linkedSetOf<UUID>()
            val curves = hashMapOf<UUID, Curve?>()
            val labelRows = hashMapOf<Triple<Double, Double, Double>, Int>()
            fun curve(id: UUID): Curve? = if (curves.containsKey(id)) curves[id] else
                findCurve(id).also { curves[id] = it }
            fun valid(id: UUID, ab: Double): Curve? = curve(id)?.takeIf { ab <= it.totalArcLength }
                .also { if (it == null) missing += id }
            fun glyph(id: String, geometry: Curve, ab: Double, kind: EditorMarkerGlyph,
                width: Double, length: Double, unavailable: Boolean, facing: ViewDirection = ViewDirection.AB) {
                val point = geometry.getPointAt(ab)
                val tangent = Vector3d(point.tangent).also { if (facing == ViewDirection.BA) it.negate() }
                labels += EditorMarkerLabels.at(id, kind, point.position, tangent, point.normal, width, length,
                    if (unavailable) NamedTextColor.RED else NamedTextColor.AQUA)
            }
            fun mark(id: String, text: String, position: RailPositionSnapshot, unavailable: Boolean,
                entry: Boolean = true) {
                val geometry = valid(position.railId, position.arcLengthInAbMeters) ?: return
                val p = geometry.getPointAt(position.arcLengthInAbMeters).position
                glyph("$id/boundary", geometry, position.arcLengthInAbMeters, EditorMarkerGlyph.BOUNDARY,
                    3.0, 0.3, unavailable)
                if (entry) glyph("$id/direction", geometry, position.arcLengthInAbMeters, EditorMarkerGlyph.DIRECTION,
                    1.5, 2.0, unavailable, position.facing)
                val key = Triple(p.x(), p.y(), p.z())
                val row = labelRows[key] ?: 0
                labelRows[key] = row + 1
                labels += EditorTextLabel(id, p.x(), p.y() + 1.4 + row * 0.35, p.z(), Component.text(text))
            }
            fun coverage(id: String, text: String, rail: UUID, lower: Double, upper: Double, unavailable: Boolean,
                facing: ViewDirection? = null, both: Boolean = false) {
                val geometry = valid(rail, upper) ?: return
                // One sparse marker per eight metres, not one entity per former particle sample.
                val count = kotlin.math.ceil((upper - lower) / 8.0).toInt().coerceAtLeast(1)
                for (index in 0 until count) {
                    val ab = lower + (upper - lower) * (index + 0.5) / count
                    glyph("$id/coverage/$index", geometry, ab, EditorMarkerGlyph.COVERAGE, 2.0,
                        ((upper - lower) / count).coerceAtMost(4.0), unavailable)
                    if (index % 4 == 0) {
                        val p = geometry.getPointAt(ab).position
                        labels += EditorTextLabel("$id/coverage/$index/name", p.x(), p.y() + 0.85, p.z(), Component.text(text))
                    }
                    if (facing != null) {
                        glyph("$id/coverage/$index/direction", geometry, ab, EditorMarkerGlyph.DIRECTION,
                            if (both) 0.7 else 1.1, 1.5, unavailable, facing)
                        if (both) glyph("$id/coverage/$index/reverse", geometry,
                            (ab + (upper - lower) / count / 4).coerceAtMost(upper), EditorMarkerGlyph.DIRECTION,
                            0.7, 1.5, unavailable, facing.reversed())
                    }
                }
            }
            definitions.fixedBlocks.forEach { block ->
                val invalid = block.traversals.any { valid(it.railId, it.upperAbMeters) == null }
                val unavailable = invalid || block.blockId in unavailableBlocks
                val status = if (unavailable) "unavailable" else "defined"
                val name = sectionAuthoringName(block.blockId, "Block")
                val first = block.traversals.first()
                val last = block.traversals.last()
                mark("block/${block.blockId}/start", "$name start entry · $status", RailPositionSnapshot(
                    first.railId, first.facing, first.travelStartAbMeters), unavailable)
                mark("block/${block.blockId}/end", "$name ${if (block.bidirectional) "reverse entry" else "exit"} · $status", RailPositionSnapshot(
                    last.railId, last.facing.reversed(), last.travelEndAbMeters), unavailable, entry = block.bidirectional)
                block.traversals.forEachIndexed { index, span -> coverage("block/${block.blockId}/$index",
                    "$name · $status", span.railId, span.lowerAbMeters, span.upperAbMeters, unavailable,
                    span.facing, block.bidirectional) }
            }
            definitions.regions.forEach { region ->
                val interior = regionInteriors[region.regionId].orEmpty()
                val invalid = region.effectiveBoundaries.any { valid(it.position.railId, it.position.arcLengthInAbMeters) == null } ||
                    interior.any { valid(it.railId, it.endAbMeters) == null }
                val status = if (region.boundaryDraft != null) "draft · unavailable" else
                    if (invalid || region.regionId in unavailableRegions) "unavailable" else "defined"
                val name = sectionAuthoringName(region.regionId, "Protection region")
                region.effectiveBoundaries.forEach { boundary -> mark("region/${region.regionId}/${boundary.boundaryId}",
                    "$name / ${sectionAuthoringName(boundary.boundaryId, "Boundary")} · $status", boundary.position,
                    invalid || region.regionId in unavailableRegions || region.boundaryDraft != null) }
                interior.forEachIndexed { index, span -> coverage("region/${region.regionId}/$index",
                    "$name · $status", span.railId, span.startAbMeters, span.endAbMeters,
                    invalid || region.regionId in unavailableRegions || region.boundaryDraft != null) }
            }
            definitions.pendingProtectionBoundaries.forEach { boundary -> mark("pending/${boundary.boundaryId}",
                "${sectionAuthoringName(boundary.boundaryId, "Protection boundary")} · pending · unavailable", boundary.position, true) }
            return SectionOverlayDrawing(labels, java.util.Set.copyOf(missing))
        }
    }
}
