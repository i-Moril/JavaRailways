package work.art1st.javarailways.tunnelbridgebuilder.rail

import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import java.util.UUID

/** Resolves one authored logical lane without sweeping companion parallel lanes. */
internal fun resolveLogicalSegmentRailIds(
    selectedRailId: UUID,
    metadataByRail: Map<UUID, RailInfrastructureMetadata>
): List<UUID> {
    val selectedAssembly = metadataByRail[selectedRailId]?.assembly ?: return listOf(selectedRailId)
    val ordered = metadataByRail.entries
        .filter { (_, metadata) ->
            metadata.assembly?.let { assembly ->
                assembly.groupId == selectedAssembly.groupId &&
                    assembly.laneIndex == selectedAssembly.laneIndex
            } == true
        }
        .sortedBy { (_, metadata) -> requireNotNull(metadata.assembly).segmentIndex }

    require(ordered.size == selectedAssembly.segmentCount) {
        "Incomplete logical rail lane ${selectedAssembly.groupId}:${selectedAssembly.laneIndex}"
    }
    require(ordered.map { (_, metadata) -> requireNotNull(metadata.assembly).segmentIndex } ==
        (0 until selectedAssembly.segmentCount).toList()
    ) {
        "Invalid logical rail segment order ${selectedAssembly.groupId}:${selectedAssembly.laneIndex}"
    }
    return ordered.map(Map.Entry<UUID, RailInfrastructureMetadata>::key)
}
