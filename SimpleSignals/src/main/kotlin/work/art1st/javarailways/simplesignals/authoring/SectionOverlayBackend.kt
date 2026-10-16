package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.UUID

internal interface SectionOverlayCapture
internal interface SectionOverlayBackend {
    fun capture(worldId: UUID): SectionOverlayCapture?
    fun current(capture: SectionOverlayCapture): Boolean
    fun prepare(capture: SectionOverlayCapture): SectionOverlayDrawing
}

/** O(1) capture/current checks. Sampling only occurs after an actual captured input changes. */
internal class RuntimeSectionOverlayBackend(
    private val runtimeForWorld: (UUID) -> SimpleSignalsWorldRuntime?,
    private val infrastructure: InfrastructureResourceService,
    private val targets: RailTargetService
) : SectionOverlayBackend {
    private data class Capture(val worldId: UUID, val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication, val topology: TopologyState,
        val guard: SectionTopologyGuard.View) : SectionOverlayCapture

    override fun capture(worldId: UUID): SectionOverlayCapture? {
        val runtime = runtimeForWorld(worldId)?.takeUnless { it.closed } ?: return null
        return Capture(worldId, runtime, runtime.authoringPublication(), infrastructure.currentTopology(worldId),
            runtime.topologyGuard.capture())
    }

    override fun current(capture: SectionOverlayCapture): Boolean {
        val value = capture as Capture
        return !value.runtime.closed && runtimeForWorld(value.worldId) === value.runtime &&
            value.runtime.authoringPublication() === value.publication &&
            infrastructure.currentTopology(value.worldId) === value.topology &&
            value.runtime.topologyGuard.capture() === value.guard
    }

    override fun prepare(capture: SectionOverlayCapture): SectionOverlayDrawing {
        val value = capture as Capture
        val unavailable = value.guard.pending.keys + value.guard.index.unavailable
        return SectionOverlayDrawing.prepare(value.publication.definitions,
            unavailable.filter { it.kind == TopologySectionKind.BLOCK }.map { it.id }.toSet(),
            unavailable.filter { it.kind == TopologySectionKind.REGION }.map { it.id }.toSet(),
            value.publication.regions.compilationSnapshot()!!.regionsById.mapValues { it.value.interior },
            boundaryIdentity = { value.topology.overlayBoundaryIdentity(it) }) {
            targets.findCurve(value.worldId, it).orElse(null)
        }
    }
}

/** Canonicalize only real ordinary connections (including a degree-two loop anchor), never geometric crossings. */
internal fun TopologyState.overlayBoundaryIdentity(position: RailPositionSnapshot): RailPositionSnapshot {
    val length = railProjection[position.railId]?.singleOrNull()?.let {
        maxOf(it.railStartAbMeters, it.railEndAbMeters)
    } ?: return position.copy(facing = ViewDirection.AB)
    val endpoint = when {
        position.arcLengthInAbMeters <= Curve.TOLERANCE -> RailEndpoint.A
        kotlin.math.abs(position.arcLengthInAbMeters - length) <= Curve.TOLERANCE -> RailEndpoint.B
        else -> return position.copy(facing = ViewDirection.AB)
    }
    val port = RailEndpointRef(position.railId, endpoint)
    val peer = ordinaryContinuationAt(port) ?: (structuralNodeAt(port) as? TopologyNode.LoopAnchor)
        ?.ports?.singleOrNull { it != port }
    val canonical = listOfNotNull(port, peer).minWith(compareBy({ it.railId }, { it.endpoint.ordinal }))
    return RailPositionSnapshot(canonical.railId, ViewDirection.AB,
        if (canonical.endpoint == RailEndpoint.A) 0.0 else railLength(canonical.railId))
}
