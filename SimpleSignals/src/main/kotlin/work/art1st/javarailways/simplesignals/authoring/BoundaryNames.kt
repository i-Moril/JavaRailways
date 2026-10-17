package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.persistence.BoundaryName
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.persistence.normalizeBoundaryName
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.cutPosition

/** Captured topology only; raw authored anchors survive ordinary connections splitting. */
internal class BoundaryNames(val definitions: SimpleSignalsWorldDefinitions, private val topology: TopologyState,
    private val curve: (java.util.UUID) -> work.art1st.javarailways.core.api.physics.curve.Curve? = { null }) {
    val anchors = anchors(definitions)
    private val anchorSet = anchors.toHashSet()
    private fun physical(position: RailCutPosition) = topology.overlayBoundaryIdentity(
        RailPositionSnapshot(position.railId, ViewDirection.AB, position.arcLengthInAbMeters)).cutPosition()
    private val representatives = anchors.map(::physical).groupBy { it.railId }.mapValues { (_, positions) ->
        val result = arrayListOf<RailCutPosition>()
        positions.sortedBy { it.arcLengthInAbMeters }.forEach { position ->
            if (result.lastOrNull()?.let { samePoint(it, position) } != true) result += position
        }
        result
    }
    private fun identity(position: RailCutPosition): RailCutPosition {
        val point = physical(position)
        val candidates = representatives[point.railId] ?: return point
        val found = candidates.binarySearchBy(point.arcLengthInAbMeters) { it.arcLengthInAbMeters }
        if (found >= 0) return candidates[found]
        val insertion = -found - 1
        return listOfNotNull(candidates.getOrNull(insertion - 1), candidates.getOrNull(insertion))
            .firstOrNull { samePoint(it, point) } ?: point
    }
    private val groups = anchors.groupBy(::identity)
    private val names = definitions.boundaryNames.associate { it.position to it.name }
    fun group(position: RailCutPosition): List<RailCutPosition> = groups[identity(position)].orEmpty()
    fun values(position: RailCutPosition): Set<String> = group(position).mapNotNull(names::get).toSet()
    fun label(position: RailPositionSnapshot): WandText = label(position.cutPosition())
    fun label(position: RailCutPosition): WandText {
        val values = values(position)
        val name: Any = when (values.size) { 0 -> WandText.key("boundary.unnamed"); 1 -> values.single(); else -> WandText.key("boundary.conflict") }
        val anchor = group(position).minWithOrNull(compareBy({ it.railId }, { it.arcLengthInAbMeters })) ?: position
        val geometry = curve(anchor.railId)
        val point = geometry?.takeIf { anchor.arcLengthInAbMeters <= it.totalArcLength }?.getPointAt(anchor.arcLengthInAbMeters)?.position
        return if (point == null) WandText.key("boundary.position_unavailable", name)
            else WandText.key("boundary.label", name, "%.1f".format(java.util.Locale.ROOT, point.x()),
                "%.1f".format(java.util.Locale.ROOT, point.y()), "%.1f".format(java.util.Locale.ROOT, point.z()))
    }

    fun rename(position: RailCutPosition, input: String): List<BoundaryName> {
        val group = group(position).toSet()
        require(group.isNotEmpty()) { "Boundary is no longer placed" }
        val name = normalizeBoundaryName(input)
        return sorted(definitions.boundaryNames.filter { it.position !in group } +
            if (name.isEmpty()) emptyList() else group.map { BoundaryName(it, name) })
    }
    fun nearest(point: RailCutPosition): RailCutPosition? {
        // An exact ordinary endpoint alias may have no authored anchor on the clicked rail.
        group(point).firstOrNull()?.let { return it }
        val nearby = anchors.filter { it.railId == point.railId && kotlin.math.abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) <= SectionAuthoringActions.SNAP_METERS }
            .sortedBy { kotlin.math.abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) }
        val first = nearby.firstOrNull() ?: return null
        require(nearby.drop(1).none { identity(it) != identity(first) &&
            kotlin.math.abs(kotlin.math.abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) - kotlin.math.abs(first.arcLengthInAbMeters - point.arcLengthInAbMeters)) <= 1e-9 }) { "Several boundaries are equally near; select more precisely" }
        return first
    }
    companion object {
        fun anchors(definitions: SimpleSignalsWorldDefinitions): List<RailCutPosition> =
            (definitions.pendingProtectionBoundaries.map { it.position } + definitions.regions.flatMap { it.effectiveCuts }.map { it.position } +
                definitions.fixedBlocks.flatMap { block -> listOf(
                    block.traversals.first().let { RailCutPosition(it.railId, it.travelStartAbMeters) },
                    block.traversals.last().let { RailCutPosition(it.railId, it.travelEndAbMeters) }) }).distinct()
        private fun sorted(names: List<BoundaryName>) = names.sortedWith(compareBy({ it.position.railId }, { it.position.arcLengthInAbMeters }))
        fun normalize(definitions: SimpleSignalsWorldDefinitions, topology: TopologyState): SimpleSignalsWorldDefinitions {
            val resolver = BoundaryNames(definitions, topology)
            val retained = definitions.boundaryNames.associateByTo(linkedMapOf()) { it.position }
            val namesByGroup = definitions.boundaryNames.groupBy { resolver.identity(it.position) }
            resolver.groups.values.forEach { group ->
                val key = resolver.identity(group.first())
                val values = namesByGroup[key].orEmpty().map { it.name }.toSet()
                if (values.size == 1) group.forEach { retained[it] = BoundaryName(it, values.single()) }
            }
            return SimpleSignalsWorldDefinitions(definitions.worldId, definitions.definitionRevision, definitions.regions,
                definitions.routeTables, definitions.fixedBlocks, definitions.fixedBlockSignals, definitions.protectionSignals,
                definitions.routeIndicators, definitions.attachedSignals, definitions.pendingProtectionBoundaries, sorted(retained.values.filter { it.position in resolver.anchorSet }))
        }
    }
}
