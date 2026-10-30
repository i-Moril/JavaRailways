package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import java.util.UUID

/** Current in-memory definition document. Derived topology and compiled restrictions are not stored. */
@ConsistentCopyVisibility
internal data class SimpleSignalsWorldDefinitions private constructor(
    val worldId: UUID,
    val definitionRevision: Long,
    val regions: List<ProtectionRegionDefinition>,
    val routeTables: List<ProtectionRouteTableDefinition>,
    val fixedBlocks: List<FixedBlockDefinition>,
    val fixedBlockSignals: List<FixedBlockSignalDefinition>,
    val protectionSignals: List<ProtectionSignalDefinition>,
    val routeIndicators: List<RouteIndicatorDefinition>,
    val attachedSignals: List<AttachedSignalDefinition>,
    val pendingProtectionBoundaries: List<ProtectionBoundaryCut>,
    val boundaryNames: List<BoundaryName>
) {
    constructor(
        worldId: UUID,
        definitionRevision: Long,
        regions: Collection<ProtectionRegionDefinition>,
        routeTables: Collection<ProtectionRouteTableDefinition>,
        fixedBlocks: Collection<FixedBlockDefinition> = emptyList(),
        fixedBlockSignals: Collection<FixedBlockSignalDefinition> = emptyList(),
        protectionSignals: Collection<ProtectionSignalDefinition> = emptyList(),
        routeIndicators: Collection<RouteIndicatorDefinition> = emptyList(),
        attachedSignals: Collection<AttachedSignalDefinition> = emptyList(),
        pendingProtectionBoundaries: Collection<ProtectionBoundaryCut> = emptyList(),
        boundaryNames: Collection<BoundaryName> = emptyList()
    ) : this(
        worldId,
        definitionRevision,
        java.util.List.copyOf(regions),
        java.util.List.copyOf(routeTables),
        java.util.List.copyOf(fixedBlocks),
        java.util.List.copyOf(fixedBlockSignals),
        java.util.List.copyOf(protectionSignals), java.util.List.copyOf(routeIndicators),
        java.util.List.copyOf(attachedSignals), java.util.List.copyOf(pendingProtectionBoundaries), java.util.List.copyOf(boundaryNames)
    )

    init {
        require(boundaryNames.map { it.position }.distinct().size == boundaryNames.size) { "Duplicate boundary name anchor" }
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
private data class SimpleSignalsWorldDefinitionsFile(
    val schemaVersion: Int,
    val worldId: String,
    val definitionRevision: Long,
    val protectionRegions: List<SavedProtectionRegionV1>,
    val protectionRouteTables: List<SavedProtectionRouteTableV1>,
    val fixedBlocks: List<SavedFixedBlockV1> = emptyList(),
    val fixedBlockSignals: List<SavedFixedBlockSignalV1> = emptyList(),
    val protectionSignals: List<SavedProtectionSignal> = emptyList(),
    val routeIndicators: List<SavedRouteIndicator> = emptyList(),
    val attachedSignals: List<SavedAttachedSignal> = emptyList(),
    val pendingProtectionBoundaries: List<SavedProtectionCut> = emptyList(),
    val boundaryNames: List<SavedBoundaryName> = emptyList()
)

@Serializable
private data class SavedBoundaryName(val railId: String, val arcLengthInAbMeters: Double, val name: String)

@Serializable
private data class SavedProtectionRegionV1(
    val regionId: String,
    val revision: Long,
    val boundaries: List<SavedProtectionBoundaryV1>,
    val boundaryDraft: List<SavedProtectionCut>? = null
)

@Serializable
private data class SavedProtectionCut(val boundaryId: String, val railId: String, val arcLengthInAbMeters: Double)

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
    val controlledBlockId: String?,
    val inspectionLeadTimeSeconds: Double,
    @SerialName("type") val signalType: String,
    /** Instances persist placement without duplicating the catalog-selected model ID. */
    val placement: SavedModelPlacement?,
    val entryDirection: work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection =
        work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection.FORWARD
)

@Serializable
private data class SavedProtectionSignal(
    val signalId: String, val revision: Long, val kind: ProtectionSignalKind, val bindingId: String?,
    val inspectionLeadTimeSeconds: Double,
    @SerialName("type") val signalType: String,
    /** Instances persist placement without duplicating the catalog-selected model ID. */
    val placement: SavedModelPlacement?
)

@Serializable
private data class SavedRouteIndicator(
    val indicatorId: String, val revision: Long, val entryBoundaryId: String?,
    val noRouteModelVariant: String, val modelVariantsByRouteId: Map<String, String>,
    val model: SavedDeviceModel? = null
)

@Serializable
private data class SavedAttachedSignal(
    val signalId: String,
    val revision: Long,
    val primarySignalId: String,
    val model: SavedDeviceModel
)

@Serializable
private data class SavedDeviceModel(val modelId: String, val placement: SavedModelPlacement)

@Serializable
private sealed interface SavedModelPlacement {
    @Serializable
    @SerialName("rail")
    data class Rail(
        val railId: String,
        val arcLengthInAbMeters: Double,
        val facing: ViewDirection,
        val adjustment: SavedModelPose = SavedModelPose.IDENTITY,
        val followRailTilt: Boolean = false
    ) : SavedModelPlacement

    @Serializable
    @SerialName("ground")
    data class Ground(val pose: SavedModelPose) : SavedModelPlacement
}

@Serializable
private data class SavedModelPose(
    val x: Double, val y: Double, val z: Double,
    val rotationX: Double, val rotationY: Double, val rotationZ: Double, val rotationW: Double
) {
    fun restore() = ModelPose(x, y, z, rotationX, rotationY, rotationZ, rotationW)

    companion object {
        val IDENTITY = SavedModelPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    }
}

/** Strict codec for the independently versioned SimpleSignals world-definition sidecar. */
internal object SimpleSignalsWorldDefinitionsCodec {
    const val CURRENT_FORMAT_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
        classDiscriminator = "kind"
    }

    fun encode(definitions: SimpleSignalsWorldDefinitions): String = json.encodeToString(
        SimpleSignalsWorldDefinitionsFile.serializer(),
        definitions.toFile()
    )

    fun decode(contents: String, expectedWorldId: UUID): SimpleSignalsWorldDefinitions {
        val file = json.decodeFromString(SimpleSignalsWorldDefinitionsFile.serializer(), contents)
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
            file.fixedBlockSignals.map { it.restore() },
            file.protectionSignals.map { signal -> ProtectionSignalDefinition(
                signal.signalId, signal.revision, signal.kind, signal.bindingId,
                signal.inspectionLeadTimeSeconds, signal.signalType,
                signal.placement?.restore(signal.signalId)) },
            file.routeIndicators.map { RouteIndicatorDefinition(it.indicatorId, it.revision, it.entryBoundaryId,
                it.noRouteModelVariant, it.modelVariantsByRouteId, it.model?.restore(it.indicatorId)) },
            file.attachedSignals.map { AttachedSignalDefinition(it.signalId, it.revision, it.primarySignalId,
                it.model.restore(it.signalId)) },
            file.pendingProtectionBoundaries.map { it.restoreCut() },
            file.boundaryNames.map { BoundaryName(RailCutPosition(parseCanonicalUuid(it.railId, "railId"), it.arcLengthInAbMeters), it.name) }
        )
    }

    private fun SimpleSignalsWorldDefinitions.toFile() = SimpleSignalsWorldDefinitionsFile(
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
                },
                region.boundaryDraft?.map { it.savedCut() }
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
                signal.signalType,
                signal.placement?.saved(),
                signal.entryDirection
            )
        },
        protectionSignals.map { signal -> SavedProtectionSignal(signal.signalId, signal.revision, signal.kind,
            signal.bindingId, signal.inspectionLeadTimeSeconds, signal.signalType,
            signal.placement?.saved()) },
        routeIndicators.map { SavedRouteIndicator(it.indicatorId, it.revision, it.entryBoundaryId,
            it.noRouteModelVariant, it.modelVariantsByRouteId, it.model?.saved()) },
        attachedSignals.map { SavedAttachedSignal(it.signalId, it.revision, it.primarySignalId, it.model.saved()) },
        pendingProtectionBoundaries.map { it.savedCut() },
        boundaryNames.map { SavedBoundaryName(it.position.railId.toString(), it.position.arcLengthInAbMeters, it.name) }
    )

    private fun DeviceModelDefinition.saved() = SavedDeviceModel(modelId, placement.saved())

    private fun ModelPlacement.saved(): SavedModelPlacement = when (val value = this) {
        is ModelPlacement.RailAttached -> SavedModelPlacement.Rail(value.position.railId.toString(),
            value.position.arcLengthInAbMeters, value.position.facing, value.adjustment.saved(), value.followRailTilt)
        is ModelPlacement.Ground -> SavedModelPlacement.Ground(value.pose.saved())
    }

    private fun ModelPose.saved() = SavedModelPose(x(), y(), z(), rotationX(), rotationY(), rotationZ(), rotationW())

    private fun SavedDeviceModel.restore(deviceId: String): DeviceModelDefinition = try {
        DeviceModelDefinition(modelId, placement.restore(deviceId))
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("device $deviceId model: ${failure.message}", failure)
    }

    private fun SavedModelPlacement.restore(deviceId: String): ModelPlacement = try {
        when (val value = this) {
            is SavedModelPlacement.Rail -> ModelPlacement.RailAttached(
                RailPositionSnapshot(parseCanonicalUuid(value.railId, "device $deviceId model railId"),
                    value.facing, value.arcLengthInAbMeters), value.adjustment.restore(), value.followRailTilt)
            is SavedModelPlacement.Ground -> ModelPlacement.Ground(value.pose.restore())
        }
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("device $deviceId placement: ${failure.message}", failure)
    }


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
        },
        boundaryDraft?.map { it.restoreCut() }
    )

    private fun ProtectionBoundaryCut.savedCut() = SavedProtectionCut(boundaryId, position.railId.toString(), position.arcLengthInAbMeters)

    private fun SavedProtectionCut.restoreCut() = ProtectionBoundaryCut(boundaryId,
        RailCutPosition(parseCanonicalUuid(railId, "boundary $boundaryId railId"), arcLengthInAbMeters))

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
        signalType,
        entryDirection,
        placement?.restore(signalId)
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
