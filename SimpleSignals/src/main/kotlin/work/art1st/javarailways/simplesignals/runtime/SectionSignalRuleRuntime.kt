package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import work.art1st.javarailways.simplesignals.signal.CompiledProtectionRoute

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
    downstreamSignals: Collection<DownstreamSignalView>,
    val preparedRoute: CompiledProtectionRoute? = null
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
    val requiredContinuations: List<RequiredActiveContinuation> = emptyList(),
    val protectionRouteId: String? = null
) {
    constructor(
        signalId: String,
        distanceMeters: Double,
        requiredContinuations: Collection<RequiredActiveContinuation> = emptyList(),
        protectionRouteId: String? = null
    ) : this(
        signalId,
        distanceMeters,
        java.util.List.copyOf(requiredContinuations), protectionRouteId
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
    val observedSections: Collection<SectionRef> = listOf(controlledSection),
    val entryBoundaryId: String? = null,
    val preparedRoutesBySection: Map<SectionRef, CompiledProtectionRoute> = emptyMap(),
    val declarationId: String = work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId(controlledSection.id),
    val continuationEntries: Collection<RailEndpointRef> = emptyList(),
    val unreadyOutput: SectionSignalOutput? = null,
    val rule: SectionSignalRule
) {
    constructor(signalId: String, controlledSection: SectionRef,
        downstreamSignals: Collection<DownstreamSignalDependency>, rule: SectionSignalRule
    ) : this(signalId, controlledSection, downstreamSignals, listOf(controlledSection),
        null, emptyMap(), work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId(controlledSection.id),
        emptyList(), null, rule)
    val downstreamSignals: List<DownstreamSignalDependency> = java.util.List.copyOf(
        downstreamSignals.sortedWith(
            compareBy(DownstreamSignalDependency::distanceMeters, DownstreamSignalDependency::signalId)
        )
    )

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(this.downstreamSignals.map { it.signalId to it.protectionRouteId }.distinct().size ==
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
    changedSignalIds: Collection<String>,
    resolvedSectionsBySignal: Map<String, SectionRef?> = emptyMap()
) {
    val resolvedSectionsBySignal: Map<String, SectionRef?> = java.util.Collections.unmodifiableMap(
        resolvedSectionsBySignal.toSortedMap())
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
    private val knownEntries = bindingsBySignal.values.mapNotNull { it.entryBoundaryId }.toSet()
    val watchedContinuationEntries: Set<RailEndpointRef> = java.util.Collections.unmodifiableSet(
        bindingsBySignal.values.flatMapTo(sortedSetOf()) { binding ->
            binding.continuationEntries + binding.downstreamSignals.flatMap { dependency ->
                dependency.requiredContinuations.map(RequiredActiveContinuation::entry)
            }
        }
    )

    fun signalIdFor(section: SectionRef): String? = signalIdBySection[section]

    fun invalidatedDeclarationIds(snapshot: SectionSignalSnapshot): Set<String> =
        snapshot.changedSignalIds.mapTo(sortedSetOf()) { signalId ->
            bindingsBySignal.getValue(signalId).declarationId
        }

    fun evaluate(
        previous: SectionSignalSnapshot?,
        occupancy: SectionOccupancySnapshot,
        activeContinuations: ActiveRailContinuationsSnapshot,
        changedContinuationEntries: Collection<RailEndpointRef>,
        changedEntries: Collection<String> = emptyList(),
        resolveSection: (SectionSignalRuleBinding) -> SectionRef? = { it.controlledSection }
    ): SectionSignalSnapshot {
        val candidateStates = previous?.states?.toMutableMap() ?: linkedMapOf()
        val resolvedSections = previous?.resolvedSectionsBySignal?.toMutableMap() ?: linkedMapOf()
        val changedOutputs = linkedSetOf<String>()
        val changedProviders = buildList {
            occupancy.changedSections.mapTo(this, RuleNode::section)
            changedContinuationEntries.filter { it in watchedContinuationEntries }.mapTo(this, RuleNode::continuation)
            changedEntries.filter { it in knownEntries }
                .mapTo(this, RuleNode::entry)
        }

        graph.propagate(changedProviders) { node ->
            check(node.kind == RuleNodeKind.SIGNAL) {
                "only signal nodes may consume invalidation updates"
            }
            val binding = bindingsBySignal.getValue(node.identity)
            val section = resolveSection(binding)
            val previousSection = resolvedSections.put(binding.signalId, section)
            val controlledSection = section?.let(occupancy.sections::get)
            val preparedRoute = section?.let(binding.preparedRoutesBySection::get)
            val activeDownstream = binding.downstreamSignals.filter { dependency ->
                (dependency.protectionRouteId == null ||
                    dependency.protectionRouteId == preparedRoute?.definition?.routeId) &&
                dependency.requiredContinuations.all { required ->
                    activeContinuations.selectedContinuation(required.entry) == required.selectedExit
                }
            }
            if (previous != null && binding.preparedRoutesBySection.isNotEmpty() &&
                previousSection == section &&
                section !in occupancy.changedSections &&
                activeDownstream.none { it.signalId in changedOutputs } &&
                binding.downstreamSignals.filter { it.protectionRouteId == null ||
                    it.protectionRouteId == preparedRoute?.definition?.routeId }.none { dependency ->
                    dependency.requiredContinuations.any { it.entry in changedContinuationEntries } }
            ) return@propagate false
            val downstreamStates = activeDownstream.map { dependency ->
                dependency to (candidateStates[dependency.signalId] ?:
                    SectionSignalState.Unavailable("downstream signal has no baseline state"))
            }
            val unavailableDownstream = downstreamStates.firstOrNull { (_, state) ->
                state is SectionSignalState.Unavailable
            }
            val next = if (controlledSection == null) {
                binding.unreadyOutput?.let(SectionSignalState::Available) ?:
                    SectionSignalState.Unavailable("protection route is unavailable or unready")
            } else if (binding.downstreamSignals.any { it.protectionRouteId == null ||
                it.protectionRouteId == preparedRoute?.definition?.routeId } && activeDownstream.isEmpty()) {
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
                                },
                                preparedRoute
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
        return SectionSignalSnapshot(occupancy.stamp, candidateStates, changedOutputs, resolvedSections)
    }

    private enum class RuleNodeKind {
        SECTION,
        CONTINUATION,
        SIGNAL,
        ENTRY
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
            fun entry(entryId: String): RuleNode = RuleNode(RuleNodeKind.ENTRY, entryId)
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
            val sectionBindings = bindings.filter { it.preparedRoutesBySection.isEmpty() }
            val signalIdBySection = sectionBindings.associate { it.controlledSection to it.signalId }
            require(signalIdBySection.size == sectionBindings.size) {
                "one section may be controlled by only one signal rule binding"
            }
            bindings.forEach { binding ->
                require(binding.observedSections.all { it in sectionsSet }) {
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
                    binding.continuationEntries + binding.downstreamSignals.flatMap { dependency ->
                        dependency.requiredContinuations.map(RequiredActiveContinuation::entry)
                    }
                }.mapTo(this, RuleNode::continuation)
                bindingsBySignal.keys.mapTo(this, RuleNode::signal)
                bindings.mapNotNull { it.entryBoundaryId }.distinct().mapTo(this, RuleNode::entry)
            }
            val dependencies = buildSet {
                bindings.forEach { binding ->
                    binding.observedSections.forEach { section -> add(
                        InvalidationDependency(
                            RuleNode.section(section),
                            RuleNode.signal(binding.signalId)
                        )
                    ) }
                    binding.entryBoundaryId?.let { entry -> add(InvalidationDependency(
                        RuleNode.entry(entry), RuleNode.signal(binding.signalId))) }
                    binding.continuationEntries.forEach { entry -> add(InvalidationDependency(
                        RuleNode.continuation(entry), RuleNode.signal(binding.signalId))) }
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
