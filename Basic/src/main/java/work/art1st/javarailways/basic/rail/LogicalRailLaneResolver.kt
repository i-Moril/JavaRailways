package work.art1st.javarailways.basic.rail

import java.util.UUID

/** Resolves every physical segment in one authored logical lane, excluding companion parallel lanes. */
fun resolveLogicalRailLaneIds(
    selectedRailId: UUID,
    metadataByRail: Map<UUID, RailInfrastructureMetadata>
): List<UUID> {
    val selectedAssembly = metadataByRail[selectedRailId]?.authoring?.assembly ?: return listOf(selectedRailId)
    val ordered = metadataByRail.entries
        .filter { (_, metadata) ->
            metadata.authoring?.assembly?.let { assembly ->
                assembly.groupId == selectedAssembly.groupId &&
                    assembly.laneIndex == selectedAssembly.laneIndex
            } == true
        }
        .sortedBy { (_, metadata) -> requireNotNull(metadata.authoring?.assembly).segmentIndex }

    require(ordered.size == selectedAssembly.segmentCount) {
        "Incomplete logical rail lane ${selectedAssembly.groupId}:${selectedAssembly.laneIndex}"
    }
    require(ordered.map { (_, metadata) -> requireNotNull(metadata.authoring?.assembly).segmentIndex } ==
        (0 until selectedAssembly.segmentCount).toList()
    ) {
        "Invalid logical rail segment order ${selectedAssembly.groupId}:${selectedAssembly.laneIndex}"
    }
    return ordered.map { it.key }
}
