package work.art1st.javarailways.simplesignals.signalpack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import work.art1st.javarailways.simplesignals.runtime.SectionOccupancyObservation
import work.art1st.javarailways.simplesignals.runtime.SectionOccupancySnapshot
import work.art1st.javarailways.simplesignals.runtime.SectionSignalOutput
import work.art1st.javarailways.simplesignals.runtime.SectionSignalSnapshot
import work.art1st.javarailways.simplesignals.runtime.SectionSignalState
import work.art1st.javarailways.simplesignals.runtime.SectionSignalEvaluation
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionSnapshot
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import java.util.Collections
import java.util.UUID
import java.time.Instant

/** Explicit in-memory association; a placed device ID is never inferred from a catalog signal type ID. */
internal data class SignalTypeBinding(
    val deviceId: String,
    /** Stable SimpleSignals catalog type; its filesystem or add-on origin is not part of this identity. */
    val signalType: String
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(deviceId)) { "signal deviceId must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signal type must be namespaced" }
    }
}

/**
 * Synchronous projection and evaluation for one exact world publication and immutable signal catalog.
 *
 * This object owns neither an executor nor a second epoch. The enclosing section source invokes it
 * once, after occupancy and route-selection facts for the current host epoch have been assembled.
 */
internal class SignalPackEpochRuntime private constructor(
    val publication: SimpleSignalsRuntimePublication,
    private val logic: SignalLogicRuntime?,
    private val bindingsByDeviceId: Map<String, PreparedBinding>,
    private val evaluationOrder: List<String>,
    private val unavailableReasonsByDeviceId: Map<String, String> = emptyMap(),
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> }
) {
    internal var issueChanged: (String, String?) -> Unit = { _, _ -> }

    val watchedContinuationEntries: Set<RailEndpointRef> = Collections.unmodifiableSet(
        bindingsByDeviceId.values.flatMapTo(sortedSetOf()) { prepared ->
            prepared.plans.flatMapTo(linkedSetOf()) { it.observation.watchedContinuationEntries }
        }
    )

    /** Low-frequency topology invalidation lookup; no graph traversal occurs in the epoch callback. */
    fun observedSections(deviceId: String): Set<work.art1st.javarailways.simplesignals.runtime.SectionRef> =
        bindingsByDeviceId[deviceId]?.observedSections.orEmpty()

    fun evaluate(
        previous: SectionSignalSnapshot?,
        occupancy: SectionOccupancySnapshot,
        input: SignalEpochInput,
        selection: ProtectionRouteSelectionSnapshot?,
        evaluatedProtectionEntries: Set<String>,
        forcedDeviceIds: Set<String>,
        hostUnavailable: (String) -> String?,
        fixedRouteReady: (String) -> Boolean
    ): SectionSignalSnapshot {
        require(input.observation.stamp == occupancy.stamp) {
            "signal observation does not belong to the current occupancy epoch"
        }
        require(input.observation.stamp.worldId == publication.definitions.worldId.toString()) {
            "signal observation belongs to another world"
        }
        val candidate = linkedMapOf<String, SectionSignalState>()
        val evaluations = previous?.evaluations?.filterKeys(bindingsByDeviceId::containsKey)?.toMutableMap()
            ?: linkedMapOf()
        val changed = linkedSetOf<String>()
        unavailableReasonsByDeviceId.forEach { (deviceId, reason) ->
            val next = SectionSignalState.Unavailable(reason)
            candidate[deviceId] = next
            if (previous?.states?.get(deviceId) != next) {
                changed += deviceId
                issueChanged(deviceId, reason)
            }
        }
        // Unrelated topology edits keep this publication valid. The per-device topology
        // guard below rejects affected plans until their local revalidation completes.
        if (previous != null) bindingsByDeviceId.keys.forEach { deviceId ->
            previous.states[deviceId]?.let { candidate[deviceId] = it }
        }
        val changedContinuationEntries = input.continuationUpdates.mapTo(hashSetOf()) { it.entry }
        evaluationOrder.forEach { deviceId ->
            val prepared = bindingsByDeviceId.getValue(deviceId)
            val dirty = previous == null || input.topologyBaseline || deviceId in forcedDeviceIds ||
                prepared.observedSections.any { it in occupancy.changedSections } ||
                prepared.watchedContinuationEntries.any(changedContinuationEntries::contains) ||
                prepared.protectionEntryIds.any { it in evaluatedProtectionEntries } ||
                prepared.downstreamDeviceIds.any { it in changed }
            if (!dirty) return@forEach
            var actualInput: JsonElement? = null
            var evaluationFailure: Exception? = null
            val next = try {
                hostUnavailable(deviceId)?.let { throw HostUnavailable(it) }
                val plan = prepared.selectPlan(selection, fixedRouteReady)
                val json = project(prepared, plan, occupancy, input.observation.activeContinuations, selection, candidate)
                actualInput = json
                val output = checkNotNull(logic).evaluate(prepared.signal.logicId, json)
                require(output.modelVariant in prepared.signal.variants) {
                    "signal device '$deviceId' selected undeclared model variant '${output.modelVariant}'"
                }
                SectionSignalState.Available(
                    SectionSignalOutput(output.speedLimitMetresPerSecond, output.modelVariant)
                )
            } catch (failure: HostUnavailable) {
                SectionSignalState.Unavailable(failure.message!!)
            } catch (failure: Exception) {
                if (failure !is ExpectedUnavailable) evaluationFailure = failure
                SectionSignalState.Unavailable(
                    "signal device '$deviceId' (type ${prepared.signal.signalType}) failed: " +
                        (failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.name)
                )
            }
            evaluations[deviceId] = SectionSignalEvaluation(occupancy.stamp, Instant.now(), actualInput)
            candidate[deviceId] = next
            if (previous?.states?.get(deviceId) != next) {
                changed += deviceId
                if (evaluationFailure != null) issueChanged(deviceId, (next as SectionSignalState.Unavailable).reason)
                else issueChanged(deviceId, null)
                evaluationFailure?.let { failure ->
                    reportFailure(
                        "signal-rule-failed world=${publication.definitions.worldId} device=$deviceId " +
                            "type=${prepared.signal.signalType} logic=${prepared.signal.logicId} " +
                            "stamp=${occupancy.stamp} definition=${publication.definitions.definitionRevision}", failure)
                }
            }
        }
        val expected = bindingsByDeviceId.keys + unavailableReasonsByDeviceId.keys
        require(candidate.keys == expected) { "signal epoch did not establish an exact signal result" }
        return SectionSignalSnapshot(
            occupancy.stamp,
            candidate,
            changed,
            evaluations
        )
    }

    private fun project(
        prepared: PreparedBinding,
        selectedPlan: PreparedPlan,
        occupancy: SectionOccupancySnapshot,
        continuations: ActiveRailContinuationsSnapshot,
        selection: ProtectionRouteSelectionSnapshot?,
        states: Map<String, SectionSignalState>
    ): JsonElement {
        val plan = selectedPlan.observation
        fun observation(node: SignalObservationPlanNode): SectionOccupancyObservation =
            requireNotNull(occupancy.sections[node.state.section]) {
                "section ${node.state.section} has no same-epoch observation"
            }.also {
                if (!it.available) throw ExpectedUnavailable("section ${node.state.section} is unavailable")
            }

        fun end(termination: SignalObservationTermination): JsonObject = jsonObject(
            "reason" to JsonPrimitive(termination.reason.jsonName),
            "distanceMeters" to JsonPrimitive(termination.distanceMeters),
            "cycleStartDistanceMeters" to termination.cycleStartDistanceMeters?.let(::JsonPrimitive),
            "cycleLengthMeters" to termination.cycleLengthMeters?.let(::JsonPrimitive)
        )

        fun branchFacts(requirements: SignalObservationBranchRequirements): Pair<String, Boolean?> {
            val continuationSelected = requirements.requiredContinuations.all { required ->
                continuations.selectedContinuation(required.entry) == required.selectedExit
            }
            val route = requirements.protectionRoute
            val selectedRoute = route?.let { selection?.selections?.get(it.entryBoundaryId) }
            val routeSelected = route == null || selectedRoute?.let {
                it.available && it.routeId == route.routeId
            } == true
            val applicable = requirements.requiredContinuations.isNotEmpty() || route != null
            val state = if (!applicable) "not-applicable"
                else if (continuationSelected && routeSelected) "selected" else "not-selected"
            return state to route?.let { selectedRoute?.routeReady == true && routeSelected }
        }

        fun downstream(node: SignalObservationPlanNode): JsonObject? {
            if (!node.exposesDownstreamSignal || node.deviceIds.isEmpty()) return null
            var speed: Double? = null
            for (id in node.deviceIds) {
                val state = states[id]
                val limit = if (state == null) 0.0 else state.effectiveSpeedLimitMetersPerSecond
                if (limit != null && (speed == null || limit < speed)) speed = limit
            }
            return jsonObject("speedLimitMetersPerSecond" to (speed?.let(::JsonPrimitive) ?: JsonNull))
        }

        lateinit var nodeValue: (SignalObservationPlanNode, SignalObservationBranchRequirements) -> JsonObject
        lateinit var branchValue: (SignalObservationPlanBranch) -> JsonObject
        branchValue = { branch ->
            val (selected, ready) = branchFacts(branch.requirements)
            branch.target?.let { nodeValue(it, branch.requirements) } ?: jsonObject(
                "distanceMeters" to JsonPrimitive(checkNotNull(branch.termination).distanceMeters),
                "selection" to JsonPrimitive(selected),
                "routeReady" to ready?.let(::JsonPrimitive),
                "end" to end(branch.termination)
            )
        }
        nodeValue = { node, requirements ->
            val section = observation(node)
            val (selected, ready) = branchFacts(requirements)
            jsonObject(
                "distanceMeters" to JsonPrimitive(node.distanceMeters),
                "section" to jsonObject(
                    "occupied" to JsonPrimitive(section.occupants.isNotEmpty()),
                    "lengthMeters" to JsonPrimitive(node.lengthMeters)
                ),
                "selection" to JsonPrimitive(selected),
                "routeReady" to ready?.let(::JsonPrimitive),
                "downstreamSignal" to downstream(node),
                "next" to JsonArray(node.branches.map(branchValue)),
                "end" to node.termination?.let(::end)
            )
        }

        val root = observation(plan.root)
        return jsonObject(
            "scanDistanceMeters" to JsonPrimitive(plan.maximumDistanceMeters),
            "referenceSpeedMetersPerSecond" to selectedPlan.referenceSpeedMetersPerSecond?.let(::JsonPrimitive),
            "referenceDecelerationMetersPerSecondSquared" to
                prepared.signal.logic.referenceDecelerationMetresPerSecondSquared?.let(::JsonPrimitive),
            "self" to jsonObject(
                "occupied" to JsonPrimitive(root.occupants.isNotEmpty()),
                "lengthMeters" to JsonPrimitive(plan.root.lengthMeters)
            ),
            "forward" to JsonArray(plan.root.branches.map(branchValue)),
            "end" to plan.root.termination?.let(::end)
        )
    }

    /** One route-specific observation tree and its captured reference speed; the tree owns D. */
    private class PreparedPlan(
        val observation: SignalObservationPlan,
        val referenceSpeedMetersPerSecond: Double?
    )

    private class PreparedBinding(
        val signal: SignalRuleType,
        val plans: List<PreparedPlan>,
        private val fixedRouteId: String?,
        private val currentEntryId: String?
    ) {
        val observedSections: Set<work.art1st.javarailways.simplesignals.runtime.SectionRef> =
            plans.flatMapTo(linkedSetOf()) { it.observation.observedSections }
        val downstreamDeviceIds: Set<String> = plans.flatMapTo(linkedSetOf()) { it.observation.downstreamDeviceIds }
        val watchedContinuationEntries: Set<RailEndpointRef> =
            plans.flatMapTo(linkedSetOf()) { it.observation.watchedContinuationEntries }
        val protectionEntryIds: Set<String> = plans.flatMapTo(linkedSetOf()) { plan ->
            plan.observation.protectionRoutes.map(ProtectionRouteChoice::entryBoundaryId)
        } + listOfNotNull(currentEntryId) + if (fixedRouteId != null) {
            plans.map { it.observation.root.state.entryBoundaryId }
        } else emptyList()

        fun selectPlan(
            selection: ProtectionRouteSelectionSnapshot?,
            fixedRouteReady: (String) -> Boolean
        ): PreparedPlan {
            fixedRouteId?.let { routeId ->
                if (!fixedRouteReady(routeId)) throw ExpectedUnavailable("fixed protection route '$routeId' is not ready")
                return plans.single()
            }
            currentEntryId?.let { entryId ->
                val selected = selection?.selections?.get(entryId)
                if (selected?.available != true || !selected.routeReady)
                    throw ExpectedUnavailable("protection entry '$entryId' has no ready route")
                val routeId = requireNotNull(selected.routeId) { "protection entry '$entryId' has no selected route" }
                return plans.singleOrNull { it.observation.root.state.protectionRouteId == routeId }
                    ?: throw IllegalArgumentException("selected route '$routeId' has no observation plan")
            }
            return plans.single()
        }
    }

    private class HostUnavailable(message: String) : IllegalStateException(message)
    private class ExpectedUnavailable(message: String) : IllegalStateException(message)

    companion object {
        fun compile(
            publication: SimpleSignalsRuntimePublication,
            catalog: SignalPackCatalog,
            logic: SignalLogicRuntime,
            bindings: Collection<SignalTypeBinding>,
            reportFailure: (String, Throwable) -> Unit = { _, _ -> },
            railSpeedLimits: ((TopologyState, Collection<UUID>) -> Map<UUID, Double?>)? = null
        ): SignalPackEpochRuntime = compile(publication, catalog.rules, logic, bindings, reportFailure, railSpeedLimits)

        fun compile(
            publication: SimpleSignalsRuntimePublication,
            catalog: SignalRuleCatalog,
            logic: SignalLogicRuntime,
            bindings: Collection<SignalTypeBinding>,
            reportFailure: (String, Throwable) -> Unit = { _, _ -> },
            railSpeedLimits: ((TopologyState, Collection<UUID>) -> Map<UUID, Double?>)? = null
        ): SignalPackEpochRuntime {
            val byDevice = bindings.associateBy(SignalTypeBinding::deviceId)
            require(byDevice.size == bindings.size) { "signal device bindings must be unique" }
            val expected = (publication.definitions.fixedBlockSignals.map { definition ->
                definition.signalId to definition.signalType
            } + publication.definitions.protectionSignals.map { definition ->
                definition.signalId to definition.signalType
            }).toMap()
            require(byDevice.mapValues { it.value.signalType } == expected) {
                "signal bindings must exactly match logical signal definitions"
            }
            val graph = publication.signalObservationGraph
            require(graph.isFor(publication.topology, publication.definitions)) {
                "signal observation graph is stale"
            }
            val fixed = publication.definitions.fixedBlockSignals.associateBy { it.signalId }
            val protection = publication.definitions.protectionSignals.associateBy { it.signalId }
            val dynamic = byDevice.filter { (deviceId, binding) ->
                catalog.signals[binding.signalType]?.logic?.referenceDecelerationMetresPerSecondSquared != null &&
                    graph.logicalRootsByDeviceId[deviceId].orEmpty().isNotEmpty()
            }
            val controlledRails = dynamic.keys.flatMap { graph.logicalRootsByDeviceId.getValue(it) }
                .associate { root ->
                    val rails = root.state.protectionRouteId?.let { routeId ->
                        publication.routeSelection.routesById.getValue(routeId).definition.traversals
                            .mapTo(linkedSetOf()) { it.railId }
                    } ?: publication.fixedBlocks.snapshot(publication.topology).blocksById.getValue(root.state.section.id)
                        .traversals.mapTo(linkedSetOf()) { it.railId }
                    root.state to rails
                }
            val speeds = if (controlledRails.isEmpty()) emptyMap() else {
                requireNotNull(railSpeedLimits) { "static rail speed limits are unavailable for reference-deceleration scanning" }
                    .invoke(publication.topology, controlledRails.values.flatten().toSet())
            }
            val referenceSpeeds = controlledRails.mapValues { (state, rails) ->
                rails.maxOf { railId ->
                    requireNotNull(speeds[railId]) { "signal section '${state.section.id}' rail '$railId' has no finite static speed limit" }
                        .also { require(it.isFinite() && it > 0.0) { "reference speed must be finite and positive" } }
                }
            }
            val unavailable = linkedMapOf<String, String>()
            val prepared = byDevice.mapNotNull { (deviceId, binding) ->
                require(deviceId in fixed || deviceId in protection) {
                    "signal binding '$deviceId' is not a placed logical signal device"
                }
                val signal = requireNotNull(catalog.signals[binding.signalType]) {
                    "signal device '$deviceId' references unknown signal type '${binding.signalType}'"
                }
                val roots = graph.logicalRootsByDeviceId[deviceId].orEmpty()
                if (roots.isEmpty()) {
                    unavailable[deviceId] = "signal device '$deviceId' has no usable observation root"
                    return@mapNotNull null
                }
                val plans = roots.map { root ->
                    val referenceSpeed = referenceSpeeds[root.state]
                        .takeIf { signal.logic.referenceDecelerationMetresPerSecondSquared != null }
                    val distance = signal.logic.scanDistanceMetres ?: run {
                        val speed = checkNotNull(referenceSpeed)
                        val deceleration = checkNotNull(signal.logic.referenceDecelerationMetresPerSecondSquared)
                        (speed * speed / (2.0 * deceleration)).also {
                            require(it.isFinite() && it > 0.0) { "signal device '$deviceId' calculated scan distance must be finite and positive" }
                        }
                    }
                    PreparedPlan(SignalObservationPlanCompiler.compile(graph.graph, root.state, distance), referenceSpeed)
                }
                val definition = protection[deviceId]
                deviceId to PreparedBinding(
                    signal,
                    java.util.List.copyOf(plans),
                    definition?.bindingId?.takeIf { definition.kind == ProtectionSignalKind.FIXED_ROUTE },
                    definition?.bindingId?.takeIf { definition.kind == ProtectionSignalKind.CURRENT_ENTRY }
                )
            }.toMap()
            val order = topologicalOrder(prepared)
            return SignalPackEpochRuntime(
                publication,
                logic,
                Collections.unmodifiableMap(prepared.toSortedMap()),
                java.util.List.copyOf(order),
                Collections.unmodifiableMap(unavailable),
                reportFailure
            )
        }

        /**
         * Startup-only fallback while the immutable signal catalog is unavailable.
         *
         * Bound devices must not temporarily inherit a stale output because that could retain a
         * permission that the selected signal type's JavaScript logic would have removed.
         */
        fun unavailable(
            publication: SimpleSignalsRuntimePublication,
            bindings: Collection<SignalTypeBinding>,
            reason: String
        ): SignalPackEpochRuntime {
            require(reason.isNotBlank()) { "restrictive signal reason must not be blank" }
            val fixed = publication.definitions.fixedBlockSignals.mapTo(hashSetOf()) { it.signalId }
            val protection = publication.definitions.protectionSignals.mapTo(hashSetOf()) { it.signalId }
            val byDevice = bindings.associateBy(SignalTypeBinding::deviceId)
            require(byDevice.size == bindings.size) { "signal device bindings must be unique" }
            byDevice.keys.forEach { deviceId ->
                require(deviceId in fixed || deviceId in protection) {
                    "signal binding '$deviceId' is not a placed logical signal device"
                }
            }
            return SignalPackEpochRuntime(
                publication,
                null,
                emptyMap(),
                emptyList(),
                Collections.unmodifiableMap(byDevice.keys.sorted().associateWith { reason })
            )
        }

        private fun topologicalOrder(bindings: Map<String, PreparedBinding>): List<String> {
            val order = arrayListOf<String>()
            val active = linkedSetOf<String>()
            val complete = hashSetOf<String>()
            fun visit(deviceId: String) {
                if (deviceId in complete) return
                require(active.add(deviceId)) { "signal downstream device dependency contains a cycle at '$deviceId'" }
                bindings.getValue(deviceId).downstreamDeviceIds
                    .filter(bindings::containsKey).sorted().forEach(::visit)
                active.remove(deviceId)
                complete += deviceId
                order += deviceId
            }
            bindings.keys.sorted().forEach(::visit)
            return order
        }
    }
}

private val SignalObservationEndReason.jsonName: String
    get() = when (this) {
        SignalObservationEndReason.DISTANCE_LIMIT -> "distance-limit"
        SignalObservationEndReason.TRACK_END -> "track-end"
        SignalObservationEndReason.CYCLE -> "cycle"
        SignalObservationEndReason.ROUTE_UNRESOLVED -> "route-unresolved"
        SignalObservationEndReason.TOPOLOGY_UNAVAILABLE -> "topology-unavailable"
    }

private fun jsonObject(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (name, value) -> value?.let { name to it } }.toMap())
