package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.runtime.DownstreamSignalDependency
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import work.art1st.javarailways.simplesignals.runtime.SectionSignalOutput
import work.art1st.javarailways.simplesignals.runtime.SectionSignalRuleBinding
import work.art1st.javarailways.simplesignals.runtime.SectionSignalRuleRuntime

/** Immutable concrete rule set compiled together with one world-definition publication. */
internal class CompiledFixedBlockSignals private constructor(
    val ruleRuntime: SectionSignalRuleRuntime?,
    inspectionDistanceByBlockId: Map<String, Double>
) {
    private val inspectionDistanceByBlockId = java.util.Map.copyOf(inspectionDistanceByBlockId)

    fun inspectionDistanceMeters(blockId: String): Double =
        inspectionDistanceByBlockId[blockId] ?: 0.0

    companion object {
        fun compile(
            definitions: Collection<FixedBlockSignalDefinition>,
            blockIds: Collection<String>
        ): CompiledFixedBlockSignals {
            val copiedDefinitions = java.util.List.copyOf(definitions.sortedBy { it.signalId })
            val knownBlocks = blockIds.toSet()
            copiedDefinitions.forEach { definition ->
                require(definition.controlledBlockId in knownBlocks) {
                    "signal ${definition.signalId} controls unknown fixed block " +
                        definition.controlledBlockId
                }
            }

            val bindings = copiedDefinitions.map { definition ->
                SectionSignalRuleBinding(
                    definition.signalId,
                    SectionRef(SectionKind.FIXED_BLOCK, definition.controlledBlockId),
                    definition.downstreamSignals.map { dependency ->
                        DownstreamSignalDependency(dependency.signalId, dependency.distanceMeters)
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
                    SectionSignalOutput(
                        selected.speedLimitMetersPerSecond,
                        selected.modelVariant
                    )
                }
            }
            val sections = knownBlocks.sorted().map { SectionRef(SectionKind.FIXED_BLOCK, it) }
            val runtime = bindings.takeIf { it.isNotEmpty() }?.let {
                SectionSignalRuleRuntime.compile(sections, it)
            }
            return CompiledFixedBlockSignals(
                runtime,
                copiedDefinitions.associate {
                    it.controlledBlockId to it.inspectionDistanceMeters
                }
            )
        }
    }
}
