package work.art1st.javarailways.core.physics.rail.topology

import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.UUID

/** Captures the mutable Core rail model exactly; the builder rejects incomplete connectivity. */
internal object CoreTopologyInputAdapter {
    /** Reconstructs transient builder input from the single authoritative immutable root. */
    fun capture(source: TopologyState): TopologyInput {
        val rails = source.railProjection.map { (railId, slices) ->
            val slice = slices.single()
            TopologyInputRail(railId, kotlin.math.abs(slice.railEndAbMeters - slice.railStartAbMeters), source.railIdentities.getValue(railId))
        }
        val continuations = linkedMapOf<RailEndpointRef, Set<RailEndpointRef>>()
        rails.forEach { rail ->
            RailEndpoint.entries.forEach { endpoint ->
                val port = RailEndpointRef(rail.id, endpoint)
                source.ordinaryContinuationAt(port)?.let { continuations[port] = setOf(it) }
            }
        }
        source.nodes.values.forEach { node ->
            when (node) {
                is TopologyNode.Terminus -> Unit
                is TopologyNode.Turnout -> node.legalContinuations.forEach { (port, targets) ->
                    continuations[port] = targets
                }
                is TopologyNode.LoopAnchor -> {
                    val (first, second) = node.ports
                    continuations[first] = setOf(second)
                    continuations[second] = setOf(first)
                }
            }
        }
        return TopologyInput(source.revision, rails, continuations, source.atGradeCrossings)
    }

    fun capture(
        revision: Long,
        sourceRails: Collection<CoreRail>,
        atGradeCrossings: List<AtGradeRailCrossing> = emptyList()
    ): TopologyInput {
        val railsById = sourceRails.associateByUniqueId()
        val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()

        railsById.values.forEach { rail ->
            sequenceOf(rail.viewAB, rail.viewBA).forEach { sourceView ->
                val sourceEndpoint = sourceView.endEndpoint()
                sourceView.mutableNextRails.forEach { targetView ->
                    val capturedTarget = railsById[targetView.rail.id]
                    require(capturedTarget === targetView.rail) {
                        "rail ${rail.id} references rail ${targetView.rail.id} outside the captured world"
                    }
                    val targetEndpoint = targetView.startEndpoint()
                    continuations.getOrPut(sourceEndpoint, ::linkedSetOf).add(targetEndpoint)
                }
            }
        }

        return TopologyInput(
            revision = revision,
            rails = railsById.values.map { rail ->
                TopologyInputRail(rail.id, rail.curve.totalArcLength, rail.topologyIdentity)
            },
            continuations = continuations.mapValues { (_, targets) -> targets.toSet() },
            atGradeCrossings = atGradeCrossings
        )
    }

    private fun Collection<CoreRail>.associateByUniqueId(): Map<UUID, CoreRail> {
        val result = linkedMapOf<UUID, CoreRail>()
        forEach { rail ->
            require(result.putIfAbsent(rail.id, rail) == null) { "duplicate rail id: ${rail.id}" }
        }
        return result
    }

    private fun RailView.endEndpoint(): RailEndpointRef = RailEndpointRef(
        railId = rail.id,
        endpoint = when (viewDirection) {
            ViewDirection.AB -> RailEndpoint.B
            ViewDirection.BA -> RailEndpoint.A
        }
    )

    private fun RailView.startEndpoint(): RailEndpointRef = RailEndpointRef(
        railId = rail.id,
        endpoint = when (viewDirection) {
            ViewDirection.AB -> RailEndpoint.A
            ViewDirection.BA -> RailEndpoint.B
        }
    )
}
