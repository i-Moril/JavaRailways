package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Uses Core's already published constant-time adjacency; never builds a world index per click. */
internal fun TopologyState.railLength(railId: UUID): Double {
    val projection = railProjection[railId]?.singleOrNull()
        ?: throw IllegalArgumentException("Rail is absent or has ambiguous projection: $railId")
    return max(projection.railStartAbMeters, projection.railEndAbMeters)
}

internal fun TopologyState.continuations(port: RailEndpointRef): Collection<RailEndpointRef> =
    when (val node = structuralNodeAt(port)) {
        is TopologyNode.Turnout -> node.legalContinuations[port].orEmpty()
        is TopologyNode.LoopAnchor -> node.ports.filter { it != port }
        is TopologyNode.Terminus -> emptyList()
        null -> listOfNotNull(ordinaryContinuationAt(port))
    }

internal fun ViewDirection.reversed(): ViewDirection = if (this == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB

internal fun samePoint(first: RailPositionSnapshot, second: RailPositionSnapshot, tolerance: Double = Curve.TOLERANCE): Boolean =
    first.railId == second.railId && abs(first.arcLengthInAbMeters - second.arcLengthInAbMeters) <= tolerance

/** Only exact ordinary endpoint continuity joins different rail coordinates; spatial proximity never does. */
internal fun TopologyState.sameBlockBoundary(first: RailPositionSnapshot, second: RailPositionSnapshot): Boolean {
    if (samePoint(first, second)) return true
    fun endpoint(point: RailPositionSnapshot): RailEndpointRef? {
        val end = when {
            point.arcLengthInAbMeters <= Curve.TOLERANCE -> RailEndpoint.A
            abs(point.arcLengthInAbMeters - railLength(point.railId)) <= Curve.TOLERANCE -> RailEndpoint.B
            else -> return null
        }
        return RailEndpointRef(point.railId, end)
    }
    val a = endpoint(first) ?: return false
    val b = endpoint(second) ?: return false
    return ordinaryContinuationAt(a) == b ||
        (structuralNodeAt(a) as? TopologyNode.LoopAnchor)?.ports?.let { a in it && b in it } == true
}

/** Two selected positions define exactly one simple, bounded, turnout-free physical path. */
internal object BlockPathBuilder {
    fun path(topology: TopologyState, first: RailPositionSnapshot, second: RailPositionSnapshot): List<FixedBlockTraversal> {
        require(!samePoint(first, second)) { "Choose two distinct boundary positions" }
        listOf(first, second).forEach {
            require(it.arcLengthInAbMeters <= topology.railLength(it.railId)) { "Boundary lies beyond its rail" }
        }
        val paths = ViewDirection.entries.mapNotNull { facing -> walk(topology, first, second, facing) }
        require(paths.size == 1) {
            if (paths.isEmpty()) "No turnout-free path; blocks cannot cross any turnout"
            else "More than one path joins the selected boundaries"
        }
        return paths.single()
    }

    fun definition(blockId: String, path: List<FixedBlockTraversal>, directions: Map<UUID, ViewDirection?>): FixedBlockDefinition {
        require(path.all { directions.containsKey(it.railId) }) { "Operating direction snapshot is incomplete" }
        val both = path.all { directions[it.railId] == null }
        val forward = path.all { directions[it.railId] == it.facing }
        val backward = path.all { directions[it.railId] == it.facing.reversed() }
        require(both || forward || backward) { "Operating directions are inconsistent across the complete block path" }
        val oriented = if (backward) path.asReversed().map { it.copy(facing = it.facing.reversed()) } else path
        return FixedBlockDefinition(blockId, 0, oriented, both)
    }

    private fun walk(topology: TopologyState, first: RailPositionSnapshot, target: RailPositionSnapshot,
        initialFacing: ViewDirection): List<FixedBlockTraversal>? {
        val path = mutableListOf<FixedBlockTraversal>()
        val visited = hashSetOf<Pair<UUID, ViewDirection>>()
        var rail = first.railId
        var position = first.arcLengthInAbMeters
        var facing = initialFacing
        while (visited.add(rail to facing)) {
            if (rail == target.railId && abs(position - target.arcLengthInAbMeters) <= Curve.TOLERANCE && path.isNotEmpty()) return path
            val length = topology.railLength(rail)
            val reachesTarget = rail == target.railId &&
                (if (facing == ViewDirection.AB) target.arcLengthInAbMeters > position + Curve.TOLERANCE
                else target.arcLengthInAbMeters < position - Curve.TOLERANCE)
            val end = if (reachesTarget) target.arcLengthInAbMeters else if (facing == ViewDirection.AB) length else 0.0
            val span = abs(end - position)
            if (span > Curve.TOLERANCE) path += FixedBlockTraversal(rail, facing, min(position, end), max(position, end))
            if (reachesTarget) return path.takeIf { it.isNotEmpty() }
            val port = RailEndpointRef(rail, if (facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A)
            if (topology.structuralNodeAt(port) is TopologyNode.Turnout) return null
            val next = topology.continuations(port).singleOrNull() ?: return null
            rail = next.railId
            facing = if (next.endpoint == RailEndpoint.A) ViewDirection.AB else ViewDirection.BA
            position = if (next.endpoint == RailEndpoint.A) 0.0 else topology.railLength(rail)
        }
        return null
    }
}
