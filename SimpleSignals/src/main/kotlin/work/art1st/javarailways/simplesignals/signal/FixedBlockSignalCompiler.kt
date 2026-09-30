package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.CompiledFixedBlock
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.runtime.DownstreamSignalDependency
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import work.art1st.javarailways.simplesignals.runtime.SectionSignalOutput
import work.art1st.javarailways.simplesignals.runtime.SectionSignalRuleBinding
import work.art1st.javarailways.simplesignals.runtime.SectionSignalRuleRuntime
import java.util.UUID
import kotlin.math.abs

/** Immutable concrete rule set compiled together with one world-definition publication. */
internal class CompiledFixedBlockSignals private constructor(
    val ruleRuntime: SectionSignalRuleRuntime?,
    val bindings: List<SectionSignalRuleBinding>,
    inspectionLeadTimeByBlockId: Map<String, Double>
) {
    private val inspectionLeadTimeByBlockId = java.util.Map.copyOf(inspectionLeadTimeByBlockId)

    fun inspectionLeadTimeSeconds(blockId: String): Double =
        inspectionLeadTimeByBlockId[blockId] ?: 0.0

    fun hasSignalForBlock(blockId: String): Boolean =
        inspectionLeadTimeByBlockId.containsKey(blockId)

    companion object {
        fun compile(
            definitions: Collection<FixedBlockSignalDefinition>,
            fixedBlocks: FixedBlockReadSnapshot,
            externalSignalGeometry: Map<String, CompiledFixedBlock> = emptyMap(),
            compileRuntime: Boolean = true
        ): CompiledFixedBlockSignals {
            val copiedDefinitions = java.util.List.copyOf(definitions.sortedBy { it.signalId })
            val blocksById = fixedBlocks.blocksById
            copiedDefinitions.forEach { definition ->
                require(definition.controlledBlockId in blocksById) {
                    "signal ${definition.signalId} controls unknown fixed block " +
                        definition.controlledBlockId
                }
            }
            val definitionsBySignal = copiedDefinitions.associateBy(FixedBlockSignalDefinition::signalId)
            val bindings = copiedDefinitions.map { definition ->
                val sourceBlock = blocksById.getValue(definition.controlledBlockId)
                SectionSignalRuleBinding(
                    definition.signalId,
                    SectionRef(SectionKind.FIXED_BLOCK, definition.controlledBlockId),
                    definition.downstreamSignals.map { dependency ->
                        val downstreamGeometry = definitionsBySignal[dependency.signalId]?.let {
                            blocksById.getValue(it.controlledBlockId)
                        } ?: requireNotNull(externalSignalGeometry[dependency.signalId]) {
                            "signal ${definition.signalId} depends on unknown signal " +
                                dependency.signalId
                        }
                        val path = compileDownstreamPath(
                            definition.signalId,
                            dependency,
                            sourceBlock,
                            downstreamGeometry,
                            fixedBlocks
                        )
                        DownstreamSignalDependency(
                            dependency.signalId,
                            path.distanceMeters,
                            path.requiredContinuations,
                            affectedRailIds = path.affectedRailIds
                        )
                    }
                ) { input ->
                    val selected = if (input.controlledSection.occupants.isNotEmpty()) {
                        definition.occupiedOutput
                    } else {
                        val downstreamLimit = input.downstreamSignals.minOfOrNull {
                            it.output.speedLimitMetersPerSecond
                        } ?: Double.POSITIVE_INFINITY
                        definition.clearLevels.last {
                            it.minimumDownstreamSpeedMetersPerSecond <= downstreamLimit
                        }.output
                    }
                    SectionSignalOutput(selected.speedLimitMetersPerSecond, selected.modelVariant)
                }
            }
            val sections = blocksById.keys.sorted().map { SectionRef(SectionKind.FIXED_BLOCK, it) }
            val runtime = bindings.takeIf { it.isNotEmpty() && compileRuntime }?.let {
                SectionSignalRuleRuntime.compile(sections, it)
            }
            return CompiledFixedBlockSignals(
                runtime,
                java.util.List.copyOf(bindings),
                copiedDefinitions.associate {
                    it.controlledBlockId to it.inspectionLeadTimeSeconds
                }
            )
        }

        fun compileDownstreamPath(
            sourceSignalId: String,
            dependency: FixedBlockSignalDependency,
            sourceBlock: CompiledFixedBlock,
            downstreamBlock: CompiledFixedBlock,
            fixedBlocks: FixedBlockReadSnapshot
        ): CompiledDownstreamPath {
            val requirements = dependency.requiredContinuations.associateBy { it.entry }
            dependency.requiredContinuations.forEach { requirement ->
                val turnout = fixedBlocks.topology.structuralNodeAt(requirement.entry)
                require(turnout is TopologyNode.Turnout) {
                    "signal $sourceSignalId continuation requirement ${requirement.entry} " +
                        "is not a turnout entry"
                }
                require(requirement.selectedExit in turnout.legalContinuations.getValue(requirement.entry)) {
                    "signal $sourceSignalId selects illegal continuation " +
                        "${requirement.entry} -> ${requirement.selectedExit}"
                }
            }
            val usedRequirements = linkedSetOf<RailEndpointRef>()
            val conditions = sourceBlock.traversals.zipWithNext().mapNotNull { (from, to) ->
                requiredContinuation(from, to, fixedBlocks)
            }.toMutableList()
            var state = PathState.fromEnd(sourceBlock.traversals.last())
            var distance = sourceBlock.lengthMeters
            val visited = linkedSetOf(state.directedRail())
            val affectedRailIds = sourceBlock.traversals.mapTo(linkedSetOf()) { it.railId }
            val target = downstreamBlock.traversals.first()

            while (!state.reaches(target)) {
                val rail = requireNotNull(fixedBlocks.routeTopology.rails[state.railId]) {
                    "signal $sourceSignalId path references missing rail ${state.railId}"
                }
                distance += abs(state.exitAbMeters(rail.lengthMeters) - state.positionAbMeters)
                val entry = state.exitEndpoint()
                val exits = legalExits(fixedBlocks.topology, entry)
                require(exits.isNotEmpty()) {
                    "signal $sourceSignalId cannot reach downstream signal ${dependency.signalId}"
                }
                val required = requirements[entry]
                val selectedExit = when {
                    required != null -> {
                        require(required.selectedExit in exits) {
                            "signal $sourceSignalId selects illegal continuation " +
                                "${required.entry} -> ${required.selectedExit}"
                        }
                        usedRequirements += entry
                        conditions += RequiredActiveContinuation(entry, required.selectedExit)
                        required.selectedExit
                    }
                    exits.size == 1 -> exits.single()
                    else -> throw IllegalArgumentException(
                        "signal $sourceSignalId must select a continuation at $entry"
                    )
                }
                val nextRail = fixedBlocks.routeTopology.rails.getValue(selectedExit.railId)
                state = PathState.entering(selectedExit, nextRail.lengthMeters)
                affectedRailIds += state.railId
                require(visited.add(state.directedRail())) {
                    "signal $sourceSignalId path to ${dependency.signalId} contains a cycle"
                }
            }
            distance += abs(target.travelStartAbMeters - state.positionAbMeters)
            require(usedRequirements == requirements.keys) {
                "signal $sourceSignalId path to ${dependency.signalId} contains unused turnout " +
                    "continuation requirements"
            }
            val distinctConditions = conditions.distinct()
            require(distinctConditions.groupBy(RequiredActiveContinuation::entry).all { (_, values) ->
                values.map(RequiredActiveContinuation::selectedExit).distinct().size == 1
            }) {
                "signal $sourceSignalId path to ${dependency.signalId} requires conflicting " +
                    "turnout continuations"
            }
            return CompiledDownstreamPath(
                distance,
                java.util.List.copyOf(distinctConditions),
                java.util.Set.copyOf(affectedRailIds)
            )
        }

        private fun requiredContinuation(
            from: FixedBlockTraversal,
            to: FixedBlockTraversal,
            fixedBlocks: FixedBlockReadSnapshot
        ): RequiredActiveContinuation? {
            if (from.railId == to.railId) return null
            val entry = PathState.fromEnd(from).exitEndpoint()
            val targetRail = fixedBlocks.routeTopology.rails.getValue(to.railId)
            val exit = PathState.enteringEndpoint(to, targetRail.lengthMeters)
            require(exit in legalExits(fixedBlocks.topology, entry)) {
                "compiled fixed-block traversal is not continuous"
            }
            return (fixedBlocks.topology.structuralNodeAt(entry) as? TopologyNode.Turnout)
                ?.let { RequiredActiveContinuation(entry, exit) }
        }

        private fun legalExits(
            topology: TopologyState,
            entry: RailEndpointRef
        ): List<RailEndpointRef> {
            topology.ordinaryContinuationAt(entry)?.let { return listOf(it) }
            return when (val node = topology.structuralNodeAt(entry)) {
                is TopologyNode.Turnout -> node.legalContinuations.getValue(entry).sorted()
                is TopologyNode.LoopAnchor -> listOf(node.ports.single { it != entry })
                is TopologyNode.Terminus, null -> emptyList()
            }
        }

        internal data class CompiledDownstreamPath(
            val distanceMeters: Double,
            val requiredContinuations: List<RequiredActiveContinuation>,
            val affectedRailIds: Set<UUID>
        )

        private data class DirectedRail(val railId: UUID, val facing: ViewDirection)

        private data class PathState(
            val railId: UUID,
            val facing: ViewDirection,
            val positionAbMeters: Double
        ) {
            fun reaches(target: FixedBlockTraversal): Boolean =
                railId == target.railId && facing == target.facing && when (facing) {
                    ViewDirection.AB -> target.travelStartAbMeters + Curve.TOLERANCE >= positionAbMeters
                    ViewDirection.BA -> target.travelStartAbMeters - Curve.TOLERANCE <= positionAbMeters
                }

            fun directedRail(): DirectedRail = DirectedRail(railId, facing)

            fun exitAbMeters(railLength: Double): Double =
                if (facing == ViewDirection.AB) railLength else 0.0

            fun exitEndpoint(): RailEndpointRef = RailEndpointRef(
                railId,
                if (facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A
            )

            companion object {
                fun fromEnd(traversal: FixedBlockTraversal) = PathState(
                    traversal.railId,
                    traversal.facing,
                    traversal.travelEndAbMeters
                )

                fun entering(endpoint: RailEndpointRef, railLength: Double): PathState = when (
                    endpoint.endpoint
                ) {
                    RailEndpoint.A -> PathState(endpoint.railId, ViewDirection.AB, 0.0)
                    RailEndpoint.B -> PathState(endpoint.railId, ViewDirection.BA, railLength)
                }

                fun enteringEndpoint(
                    traversal: FixedBlockTraversal,
                    railLength: Double
                ): RailEndpointRef {
                    require(
                        abs(traversal.travelStartAbMeters) <= Curve.TOLERANCE ||
                            abs(traversal.travelStartAbMeters - railLength) <= Curve.TOLERANCE
                    ) { "cross-rail traversal must begin at an endpoint" }
                    return RailEndpointRef(
                        traversal.railId,
                        if (traversal.facing == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
                    )
                }
            }
        }
    }
}
