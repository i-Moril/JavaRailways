package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

/** Validated rule output; model variants are opaque to the Java/Kotlin runtime. */
internal data class SectionSignalOutput(
    val speedLimitMetersPerSecond: Double,
    val modelVariant: String
) {
    init {
        require(speedLimitMetersPerSecond.isFinite() && speedLimitMetersPerSecond >= 0.0) {
            "signal speed limit must be finite and non-negative"
        }
        require(modelVariant.isNotBlank()) { "signal model variant must not be blank" }
    }
}

/** Published rule state; unavailable state is a host safety result, not a signal model variant. */
internal sealed interface SectionSignalState {
    val effectiveSpeedLimitMetersPerSecond: Double

    data class Available(val output: SectionSignalOutput) : SectionSignalState {
        override val effectiveSpeedLimitMetersPerSecond: Double
            get() = output.speedLimitMetersPerSecond
    }

    data class Unavailable(val reason: String) : SectionSignalState {
        init {
            require(reason.isNotBlank()) { "unavailable signal reason must not be blank" }
        }

        override val effectiveSpeedLimitMetersPerSecond: Double
            get() = 0.0
    }
}

/** One already-published downstream signal output at a compiled distance. */
internal data class DownstreamSignalView(
    val signalId: String,
    val distanceMeters: Double,
    val output: SectionSignalOutput
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) {
            "downstream signalId must be namespaced"
        }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "downstream signal distance must be finite and non-negative"
        }
    }
}

/** Immutable bounded input supplied to one configured signal rule. */
internal class SectionSignalRuleInput(
    val stamp: RailwayStateStamp,
    val signalId: String,
    val controlledSection: SectionOccupancyObservation,
    downstreamSignals: Collection<DownstreamSignalView>
) {
    val downstreamSignals: List<DownstreamSignalView> = java.util.List.copyOf(downstreamSignals)
}

/** Rule implementation supplied by the concrete signal configuration/runtime. */
internal fun interface SectionSignalRule {
    fun evaluate(input: SectionSignalRuleInput): SectionSignalOutput
}

/** One compiled downstream dependency; distance is measured from the consuming signal. */
@ConsistentCopyVisibility
internal data class DownstreamSignalDependency private constructor(
    val signalId: String,
    val distanceMeters: Double,
    val requiredContinuations: List<RequiredActiveContinuation> = emptyList()
) {
    constructor(
        signalId: String,
        distanceMeters: Double,
        requiredContinuations: Collection<RequiredActiveContinuation> = emptyList()
    ) : this(
        signalId,
        distanceMeters,
        java.util.List.copyOf(requiredContinuations)
    )

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) {
            "downstream signalId must be namespaced"
        }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "downstream signal distance must be finite and non-negative"
        }
        require(requiredContinuations.map { it.entry }.distinct().size == requiredContinuations.size) {
            "compiled downstream continuation entries must be unique"
        }
    }
}

/** One turnout selection that must be active for a compiled downstream path to be reachable. */
internal data class RequiredActiveContinuation(
    val entry: RailEndpointRef,
    val selectedExit: RailEndpointRef
) {
    init {
        require(entry != selectedExit) { "required continuation cannot return through its entry" }
    }
}

/**
 * Precompiled association between one signal, its controlled section and its configured rule.
 *
 * The runtime does not infer a model vocabulary or speed table from this value. A future Java,
 * JavaScript or YAML-backed rule provider may build the same binding.
 */
internal class SectionSignalRuleBinding(
    val signalId: String,
    val controlledSection: SectionRef,
    downstreamSignals: Collection<DownstreamSignalDependency>,
    val rule: SectionSignalRule
) {
    val downstreamSignals: List<DownstreamSignalDependency> = java.util.List.copyOf(
        downstreamSignals.sortedWith(
            compareBy(DownstreamSignalDependency::distanceMeters, DownstreamSignalDependency::signalId)
        )
    )

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(this.downstreamSignals.map(DownstreamSignalDependency::signalId).distinct().size ==
            this.downstreamSignals.size
        ) {
            "downstream signal IDs must be unique within one rule binding"
        }
    }
}

/** Complete immutable outputs after one successful section update wave. */
internal class SectionSignalSnapshot(
    val stamp: RailwayStateStamp,
    states: Map<String, SectionSignalState>,
    changedSignalIds: Collection<String>
) {
    val states: Map<String, SectionSignalState> = java.util.Collections.unmodifiableMap(
        states.toSortedMap()
    )
    val changedSignalIds: Set<String> = java.util.Collections.unmodifiableSet(
        changedSignalIds.toSortedSet()
    )
}

/** One rule state published with the physical observation that produced it. */
internal data class PublishedSectionSignal(
    val stamp: RailwayStateStamp,
    val state: SectionSignalState
)

/**
 * Evaluates configured section signals through the precompiled one-way invalidation graph.
 *
 * This runtime owns no tick loop and no concrete signal rule. It evaluates a signal only when its
 * controlled section changed or a downstream signal produced a different output. A complete
 * candidate map is published only after every reached rule has produced either a validated output
 * or an explicit unavailable state.
 */
internal class SectionSignalRuleRuntime private constructor(
    private val bindingsBySignal: Map<String, SectionSignalRuleBinding>,
    private val signalIdBySection: Map<SectionRef, String>,
    private val graph: OneWayInvalidationGraph<RuleNode>
) {
    val watchedContinuationEntries: Set<RailEndpointRef> = java.util.Collections.unmodifiableSet(
        bindingsBySignal.values.flatMapTo(sortedSetOf()) { binding ->
            binding.downstreamSignals.flatMap { dependency ->
                dependency.requiredContinuations.map(RequiredActiveContinuation::entry)
            }
        }
    )

    fun signalIdFor(section: SectionRef): String? = signalIdBySection[section]

    fun invalidatedDeclarationIds(snapshot: SectionSignalSnapshot): Set<String> =
        snapshot.changedSignalIds.mapTo(sortedSetOf()) { signalId ->
            bindingsBySignal.getValue(signalId).controlledSection.id
        }

    fun evaluate(
        previous: SectionSignalSnapshot?,
        occupancy: SectionOccupancySnapshot,
        activeContinuations: ActiveRailContinuationsSnapshot,
        changedContinuationEntries: Collection<RailEndpointRef>
    ): SectionSignalSnapshot {
        val candidateStates = previous?.states?.toMutableMap() ?: linkedMapOf()
        val changedOutputs = linkedSetOf<String>()
        val changedProviders = buildList {
            occupancy.changedSections.mapTo(this, RuleNode::section)
            changedContinuationEntries.mapTo(this, RuleNode::continuation)
        }

        graph.propagate(changedProviders) { node ->
            check(node.kind == RuleNodeKind.SIGNAL) {
                "only signal nodes may consume invalidation updates"
            }
            val binding = bindingsBySignal.getValue(node.identity)
            val controlledSection = occupancy.sections.getValue(binding.controlledSection)
            val activeDownstream = binding.downstreamSignals.filter { dependency ->
                dependency.requiredContinuations.all { required ->
                    activeContinuations.selectedContinuation(required.entry) == required.selectedExit
                }
            }
            val downstreamStates = activeDownstream.map { dependency ->
                dependency to checkNotNull(candidateStates[dependency.signalId]) {
                    "downstream signal ${dependency.signalId} has no baseline state"
                }
            }
            val unavailableDownstream = downstreamStates.firstOrNull { (_, state) ->
                state is SectionSignalState.Unavailable
            }
            val next = if (binding.downstreamSignals.isNotEmpty() && activeDownstream.isEmpty()) {
                SectionSignalState.Unavailable("no configured downstream path is active")
            } else if (unavailableDownstream != null) {
                SectionSignalState.Unavailable(
                    "downstream signal ${unavailableDownstream.first.signalId} is unavailable"
                )
            } else {
                try {
                    SectionSignalState.Available(
                        binding.rule.evaluate(
                            SectionSignalRuleInput(
                                occupancy.stamp,
                                binding.signalId,
                                controlledSection,
                                downstreamStates.map { (dependency, state) ->
                                    DownstreamSignalView(
                                        dependency.signalId,
                                        dependency.distanceMeters,
                                        (state as SectionSignalState.Available).output
                                    )
                                }
                            )
                        )
                    )
                } catch (failure: Exception) {
                    SectionSignalState.Unavailable(
                        failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.name
                    )
                }
            }
            val changed = candidateStates.put(binding.signalId, next) != next
            if (changed) changedOutputs += binding.signalId
            changed
        }

        require(candidateStates.size == bindingsBySignal.size) {
            "first signal rule update must establish every configured signal state"
        }
        return SectionSignalSnapshot(occupancy.stamp, candidateStates, changedOutputs)
    }

    private enum class RuleNodeKind {
        SECTION,
        CONTINUATION,
        SIGNAL
    }

    private data class RuleNode(
        val kind: RuleNodeKind,
        val identity: String
    ) : Comparable<RuleNode> {
        override fun compareTo(other: RuleNode): Int =
            compareValuesBy(this, other, RuleNode::kind, RuleNode::identity)

        companion object {
            fun section(section: SectionRef): RuleNode =
                RuleNode(RuleNodeKind.SECTION, "${section.kind.name}:${section.id}")

            fun continuation(entry: RailEndpointRef): RuleNode =
                RuleNode(RuleNodeKind.CONTINUATION, entry.toString())

            fun signal(signalId: String): RuleNode = RuleNode(RuleNodeKind.SIGNAL, signalId)
        }
    }

    companion object {
        fun compile(
            sections: Collection<SectionRef>,
            bindings: Collection<SectionSignalRuleBinding>
        ): SectionSignalRuleRuntime {
            require(sections.toSet().size == sections.size) { "compiled sections must be unique" }
            val sectionsSet = sections.toSet()
            val bindingsBySignal = bindings.associateBy(SectionSignalRuleBinding::signalId)
            require(bindingsBySignal.size == bindings.size) { "signal rule IDs must be unique" }
            val signalIdBySection = bindings.associate { it.controlledSection to it.signalId }
            require(signalIdBySection.size == bindings.size) {
                "one section may be controlled by only one signal rule binding"
            }
            bindings.forEach { binding ->
                require(binding.controlledSection in sectionsSet) {
                    "signal ${binding.signalId} controls an unknown section ${binding.controlledSection}"
                }
                binding.downstreamSignals.forEach { downstream ->
                    require(downstream.signalId in bindingsBySignal) {
                        "signal ${binding.signalId} depends on unknown signal ${downstream.signalId}"
                    }
                }
            }

            val nodes = buildList {
                sectionsSet.mapTo(this, RuleNode::section)
                bindings.flatMapTo(linkedSetOf()) { binding ->
                    binding.downstreamSignals.flatMap { dependency ->
                        dependency.requiredContinuations.map(RequiredActiveContinuation::entry)
                    }
                }.mapTo(this, RuleNode::continuation)
                bindingsBySignal.keys.mapTo(this, RuleNode::signal)
            }
            val dependencies = buildSet {
                bindings.forEach { binding ->
                    add(
                        InvalidationDependency(
                            RuleNode.section(binding.controlledSection),
                            RuleNode.signal(binding.signalId)
                        )
                    )
                    binding.downstreamSignals.forEach { downstream ->
                        add(
                            InvalidationDependency(
                                RuleNode.signal(downstream.signalId),
                                RuleNode.signal(binding.signalId)
                            )
                        )
                        downstream.requiredContinuations.forEach { required ->
                            add(
                                InvalidationDependency(
                                    RuleNode.continuation(required.entry),
                                    RuleNode.signal(binding.signalId)
                                )
                            )
                        }
                    }
                }
            }
            return SectionSignalRuleRuntime(
                java.util.Collections.unmodifiableMap(bindingsBySignal.toSortedMap()),
                java.util.Collections.unmodifiableMap(signalIdBySection.toSortedMap()),
                OneWayInvalidationGraph.compile(nodes, dependencies)
            )
        }
    }
}
