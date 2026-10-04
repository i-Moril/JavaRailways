package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import java.util.UUID

/** One bounded, coherent policy read; geometry and authored definitions are never erased. */
internal class FixedBlockDirectionPolicy(
    private val read: (TopologyState, Collection<UUID>) -> RailOperatingDirections = { topology, rails ->
        RailOperatingDirections(topology, rails.associateWith { null })
    }
) {
    fun disabledBlocks(snapshot: FixedBlockReadSnapshot, blocks: Collection<CompiledFixedBlock>): Set<String> {
        if (blocks.isEmpty()) return emptySet()
        val rails = blocks.flatMapTo(linkedSetOf()) { block -> block.traversals.map { it.railId } }
        val subset = read(snapshot.topology, rails)
        check(subset.topology === snapshot.topology && subset.directions.keys == rails) {
            "fixed-block operating direction subset is incomplete or stale"
        }
        return java.util.Set.copyOf(blocks.filter { block ->
            block.traversals.any { traversal ->
                val permitted: ViewDirection? = subset.directions.getValue(traversal.railId)
                if (block.definition.bidirectional) permitted != null else permitted != traversal.facing
            }
        }.map { it.definition.blockId })
    }

    fun normalWindows(snapshot: FixedBlockReadSnapshot, windows: List<FixedBlockRouteWindow>, disabled: Set<String>):
        List<FixedBlockRouteWindow> {
        windows.forEach { window ->
            check(window.blockId !in disabled) {
                "ATO admission is forbidden by unavailable fixed block ${window.blockId}: operating directions disagree"
            }
            check(!window.reversed || snapshot.blocksById.getValue(window.blockId).definition.bidirectional) {
                "reverse admission is forbidden by active single-direction fixed block ${window.blockId}"
            }
        }
        return windows
    }
}
