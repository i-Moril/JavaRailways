package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.signal.ConfiguredSignalOutput
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalClearLevel
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalContinuationRequirement
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDependency
import java.util.UUID

/** Current in-memory definition document. Derived topology and compiled restrictions are not stored. */
@ConsistentCopyVisibility
internal data class SimpleSignalsWorldDefinitions private constructor(
    val worldId: UUID,
    val definitionRevision: Long,
    val regions: List<ProtectionRegionDefinition>,
    val routeTables: List<ProtectionRouteTableDefinition>,
    val fixedBlocks: List<FixedBlockDefinition>,
    val fixedBlockSignals: List<FixedBlockSignalDefinition>
) {
    constructor(
        worldId: UUID,
        definitionRevision: Long,
        regions: Collection<ProtectionRegionDefinition>,
        routeTables: Collection<ProtectionRouteTableDefinition>,
        fixedBlocks: Collection<FixedBlockDefinition> = emptyList(),
        fixedBlockSignals: Collection<FixedBlockSignalDefinition> = emptyList()
    ) : this(
        worldId,
        definitionRevision,
        java.util.List.copyOf(regions),
        java.util.List.copyOf(routeTables),
        java.util.List.copyOf(fixedBlocks),
        java.util.List.copyOf(fixedBlockSignals)
    )

    init {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
    }

    companion object {
        fun empty(worldId: UUID) = SimpleSignalsWorldDefinitions(
            worldId,
            0L,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList()
        )
    }
}

@Serializable
private data class SimpleSignalsWorldDefinitionsFileV1(
    val schemaVersion: Int,
    val worldId: String,
    val definitionRevision: Long,
    val protectionRegions: List<SavedProtectionRegionV1>,
    val protectionRouteTables: List<SavedProtectionRouteTableV1>,
    val fixedBlocks: List<SavedFixedBlockV1> = emptyList(),
    val fixedBlockSignals: List<SavedFixedBlockSignalV1> = emptyList()
)

@Serializable
private data class SavedProtectionRegionV1(
    val regionId: String,
    val revision: Long,
    val boundaries: List<SavedProtectionBoundaryV1>
)

@Serializable
private data class SavedProtectionBoundaryV1(
    val boundaryId: String,
    val railId: String,
    val arcLengthInAbMeters: Double,
    val insideFacing: ViewDirection
)

@Serializable
private data class SavedProtectionRouteTableV1(
    val tableId: String,
    val revision: Long,
    val regionId: String,
    val regionRevision: Long,
    val routes: List<SavedProtectionRouteV1>
)

@Serializable
private data class SavedProtectionRouteV1(
    val routeId: String,
    val entryBoundaryId: String,
    val exitBoundaryId: String,
    val traversals: List<SavedProtectionRouteTraversalV1>
)

@Serializable
private data class SavedProtectionRouteTraversalV1(
    val railId: String,
    val facing: ViewDirection,
    val lowerAbMeters: Double,
    val upperAbMeters: Double
)

@Serializable
private data class SavedFixedBlockV1(
    val blockId: String,
    val revision: Long,
    val traversals: List<SavedFixedBlockTraversalV1>
)

@Serializable
private data class SavedFixedBlockTraversalV1(
    val railId: String,
    val facing: ViewDirection,
    val lowerAbMeters: Double,
    val upperAbMeters: Double
)

@Serializable
private data class SavedFixedBlockSignalV1(
    val signalId: String,
    val revision: Long,
    val controlledBlockId: String,
    val inspectionLeadTimeSeconds: Double,
    val occupiedOutput: SavedSignalOutputV1,
    val clearLevels: List<SavedFixedBlockSignalClearLevelV1>,
    val downstreamSignals: List<SavedFixedBlockSignalDependencyV1>
)

@Serializable
private data class SavedSignalOutputV1(
    val speedLimitMetersPerSecond: Double,
    val modelVariant: String
)

@Serializable
private data class SavedFixedBlockSignalClearLevelV1(
    val minimumDownstreamSpeedMetersPerSecond: Double,
    val output: SavedSignalOutputV1
)

@Serializable
private data class SavedFixedBlockSignalDependencyV1(
    val signalId: String,
    val requiredContinuations: List<SavedFixedBlockSignalContinuationRequirementV1>
)

@Serializable
private data class SavedFixedBlockSignalContinuationRequirementV1(
    val entryRailId: String,
    val entryEndpoint: RailEndpoint,
    val selectedExitRailId: String,
    val selectedExitEndpoint: RailEndpoint
)

/** Strict codec for the independently versioned SimpleSignals world-definition sidecar. */
internal object SimpleSignalsWorldDefinitionsCodec {
    const val CURRENT_FORMAT_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(definitions: SimpleSignalsWorldDefinitions): String = json.encodeToString(
        SimpleSignalsWorldDefinitionsFileV1.serializer(),
        definitions.toFile()
    )

    fun decode(contents: String, expectedWorldId: UUID): SimpleSignalsWorldDefinitions {
        val file = json.decodeFromString(SimpleSignalsWorldDefinitionsFileV1.serializer(), contents)
        require(file.schemaVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported SimpleSignals world definitions version: ${file.schemaVersion}"
        }
        val worldId = parseCanonicalUuid(file.worldId, "worldId")
        require(worldId == expectedWorldId) {
            "SimpleSignals definitions belong to world $worldId, expected $expectedWorldId"
        }
        require(file.definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        return SimpleSignalsWorldDefinitions(
            worldId,
            file.definitionRevision,
            file.protectionRegions.map { it.restore() },
            file.protectionRouteTables.map { it.restore() },
            file.fixedBlocks.map { it.restore() },
            file.fixedBlockSignals.map { it.restore() }
        )
    }

    private fun SimpleSignalsWorldDefinitions.toFile() = SimpleSignalsWorldDefinitionsFileV1(
        CURRENT_FORMAT_VERSION,
        worldId.toString(),
        definitionRevision,
        regions.map { region ->
            SavedProtectionRegionV1(
                region.regionId,
                region.revision,
                region.boundaries.map { boundary ->
                    SavedProtectionBoundaryV1(
                        boundary.boundaryId,
                        boundary.position.railId.toString(),
                        boundary.position.arcLengthInAbMeters,
                        boundary.insideFacing
                    )
                }
            )
        },
        routeTables.map { table ->
            SavedProtectionRouteTableV1(
                table.tableId,
                table.revision,
                table.regionId,
                table.regionRevision,
                table.routes.map { route ->
                    SavedProtectionRouteV1(
                        route.routeId,
                        route.entryBoundaryId,
                        route.exitBoundaryId,
                        route.traversals.map { traversal ->
                            SavedProtectionRouteTraversalV1(
                                traversal.railId.toString(),
                                traversal.facing,
                                traversal.lowerAbMeters,
                                traversal.upperAbMeters
                            )
                        }
                    )
                }
            )
        },
        fixedBlocks.map { block ->
            SavedFixedBlockV1(
                block.blockId,
                block.revision,
                block.traversals.map { traversal ->
                    SavedFixedBlockTraversalV1(
                        traversal.railId.toString(),
                        traversal.facing,
                        traversal.lowerAbMeters,
                        traversal.upperAbMeters
                    )
                }
            )
        },
        fixedBlockSignals.map { signal ->
            SavedFixedBlockSignalV1(
                signal.signalId,
                signal.revision,
                signal.controlledBlockId,
                signal.inspectionLeadTimeSeconds,
                signal.occupiedOutput.saved(),
                signal.clearLevels.map { level ->
                    SavedFixedBlockSignalClearLevelV1(
                        level.minimumDownstreamSpeedMetersPerSecond,
                        level.output.saved()
                    )
                },
                signal.downstreamSignals.map { downstream ->
                    SavedFixedBlockSignalDependencyV1(
                        downstream.signalId,
                        downstream.requiredContinuations.map { requirement ->
                            SavedFixedBlockSignalContinuationRequirementV1(
                                requirement.entry.railId.toString(),
                                requirement.entry.endpoint,
                                requirement.selectedExit.railId.toString(),
                                requirement.selectedExit.endpoint
                            )
                        }
                    )
                }
            )
        }
    )

    private fun ConfiguredSignalOutput.saved() = SavedSignalOutputV1(
        speedLimitMetersPerSecond,
        modelVariant
    )

    private fun SavedProtectionRegionV1.restore() = ProtectionRegionDefinition(
        regionId,
        revision,
        boundaries.map { boundary ->
            ProtectionBoundaryDefinition(
                boundary.boundaryId,
                RailPositionSnapshot(
                    parseCanonicalUuid(boundary.railId, "boundary ${boundary.boundaryId} railId"),
                    boundary.insideFacing,
                    boundary.arcLengthInAbMeters
                )
            )
        }
    )

    private fun SavedProtectionRouteTableV1.restore() = ProtectionRouteTableDefinition(
        tableId,
        revision,
        regionId,
        regionRevision,
        routes.map { route ->
            ProtectionRouteDefinition(
                route.routeId,
                route.entryBoundaryId,
                route.exitBoundaryId,
                route.traversals.map { traversal ->
                    ProtectionRouteTraversal(
                        parseCanonicalUuid(
                            traversal.railId,
                            "route ${route.routeId} traversal railId"
                        ),
                        traversal.facing,
                        traversal.lowerAbMeters,
                        traversal.upperAbMeters
                    )
                }
            )
        }
    )

    private fun SavedFixedBlockV1.restore() = FixedBlockDefinition(
        blockId,
        revision,
        traversals.map { traversal ->
            FixedBlockTraversal(
                parseCanonicalUuid(
                    traversal.railId,
                    "fixed block $blockId traversal railId"
                ),
                traversal.facing,
                traversal.lowerAbMeters,
                traversal.upperAbMeters
            )
        }
    )

    private fun SavedFixedBlockSignalV1.restore() = FixedBlockSignalDefinition(
        signalId,
        revision,
        controlledBlockId,
        inspectionLeadTimeSeconds,
        occupiedOutput.restore(),
        clearLevels.map { level ->
            FixedBlockSignalClearLevel(
                level.minimumDownstreamSpeedMetersPerSecond,
                level.output.restore()
            )
        },
        downstreamSignals.map { downstream ->
            FixedBlockSignalDependency(
                downstream.signalId,
                downstream.requiredContinuations.map { requirement ->
                    FixedBlockSignalContinuationRequirement(
                        RailEndpointRef(
                            parseCanonicalUuid(
                                requirement.entryRailId,
                                "signal $signalId continuation entry railId"
                            ),
                            requirement.entryEndpoint
                        ),
                        RailEndpointRef(
                            parseCanonicalUuid(
                                requirement.selectedExitRailId,
                                "signal $signalId continuation exit railId"
                            ),
                            requirement.selectedExitEndpoint
                        )
                    )
                }
            )
        }
    )

    private fun SavedSignalOutputV1.restore() = ConfiguredSignalOutput(
        speedLimitMetersPerSecond,
        modelVariant
    )

    private fun parseCanonicalUuid(value: String, field: String): UUID {
        val parsed = try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("$field must be a canonical UUID: $value", failure)
        }
        require(value == parsed.toString()) { "$field must be a canonical UUID: $value" }
        return parsed
    }
}
