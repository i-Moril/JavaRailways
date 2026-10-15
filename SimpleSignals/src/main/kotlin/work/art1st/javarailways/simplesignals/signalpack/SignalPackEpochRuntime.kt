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
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionSnapshot
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import java.util.Collections
import java.util.UUID

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
    private val unavailableReasonsByDeviceId: Map<String, String> = emptyMap()
) {
    val watchedContinuationEntries: Set<RailEndpointRef> = Collections.unmodifiableSet(
        bindingsByDeviceId.values.flatMapTo(sortedSetOf()) { prepared ->
            prepared.plans.flatMapTo(linkedSetOf()) { it.watchedContinuationEntries }
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
        val changed = linkedSetOf<String>()
        unavailableReasonsByDeviceId.forEach { (deviceId, reason) ->
            val next = SectionSignalState.Unavailable(reason)
            candidate[deviceId] = next
            if (previous?.states?.get(deviceId) != next) changed += deviceId
        }
        require(bindingsByDeviceId.isEmpty() ||
            input.observation.stamp.topologyRevision == publication.topology.revision) {
            "signal observation topology does not match its prepared publication"
        }
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
            val next = try {
                hostUnavailable(deviceId)?.let { throw HostUnavailable(it) }
                val plan = prepared.selectPlan(selection, fixedRouteReady)
                val json = project(prepared, plan, occupancy, input.observation.activeContinuations, selection, candidate)
                val output = checkNotNull(logic).evaluate(prepared.signal.logicId, json)
                require(output.modelVariant in prepared.signal.model.materialized.variants()) {
                    "signal device '$deviceId' selected undeclared model variant '${output.modelVariant}'"
                }
                SectionSignalState.Available(
                    SectionSignalOutput(output.speedLimitMetresPerSecond, output.modelVariant)
                )
            } catch (failure: HostUnavailable) {
                SectionSignalState.Unavailable(failure.message!!)
            } catch (failure: Exception) {
                SectionSignalState.Unavailable(
                    "signal device '$deviceId' (type ${prepared.signal.signalType}) failed: " +
                        (failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.name)
                )
            }
            candidate[deviceId] = next
            if (previous?.states?.get(deviceId) != next) changed += deviceId
        }
        val expected = bindingsByDeviceId.keys + unavailableReasonsByDeviceId.keys
        require(candidate.keys == expected) { "signal epoch did not establish an exact signal result" }
        return SectionSignalSnapshot(
            occupancy.stamp,
            candidate,
            changed
        )
    }

    private fun project(
        prepared: PreparedBinding,
        plan: SignalObservationPlan,
        occupancy: SectionOccupancySnapshot,
        continuations: ActiveRailContinuationsSnapshot,
        selection: ProtectionRouteSelectionSnapshot?,
        states: Map<String, SectionSignalState>
    ): JsonElement {
        fun observation(node: SignalObservationPlanNode): SectionOccupancyObservation =
            requireNotNull(occupancy.sections[node.state.section]) {
                "section ${node.state.section} has no same-epoch observation"
            }.also {
                require(it.available) { "section ${node.state.section} is unavailable" }
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
            "scanDistanceMeters" to JsonPrimitive(prepared.scanDistanceMeters),
            "referenceSpeedMetersPerSecond" to prepared.referenceSpeedMetersPerSecond?.let(::JsonPrimitive),
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

    private class PreparedBinding(
        val signal: SignalPackSignal,
        val plans: List<SignalObservationPlan>,
        val scanDistanceMeters: Double,
        val referenceSpeedMetersPerSecond: Double?,
        private val fixedRouteId: String?,
        private val currentEntryId: String?
    ) {
        val observedSections: Set<work.art1st.javarailways.simplesignals.runtime.SectionRef> =
            plans.flatMapTo(linkedSetOf()) { it.observedSections }
        val downstreamDeviceIds: Set<String> = plans.flatMapTo(linkedSetOf()) { it.downstreamDeviceIds }
        val watchedContinuationEntries: Set<RailEndpointRef> =
            plans.flatMapTo(linkedSetOf()) { it.watchedContinuationEntries }
        val protectionEntryIds: Set<String> = plans.flatMapTo(linkedSetOf()) { plan ->
            plan.protectionRoutes.map(ProtectionRouteChoice::entryBoundaryId)
        } + listOfNotNull(currentEntryId)

        fun selectPlan(
            selection: ProtectionRouteSelectionSnapshot?,
            fixedRouteReady: (String) -> Boolean
        ): SignalObservationPlan {
            fixedRouteId?.let { routeId ->
                require(fixedRouteReady(routeId)) { "fixed protection route '$routeId' is not ready" }
                return plans.single()
            }
            currentEntryId?.let { entryId ->
                val selected = selection?.selections?.get(entryId)
                require(selected?.available == true && selected.routeReady) {
                    "protection entry '$entryId' has no ready route"
                }
                val routeId = requireNotNull(selected.routeId) { "protection entry '$entryId' has no selected route" }
                return plans.singleOrNull { it.root.state.protectionRouteId == routeId }
                    ?: throw IllegalArgumentException("selected route '$routeId' has no observation plan")
            }
            return plans.single()
        }
    }

    private class HostUnavailable(message: String) : IllegalStateException(message)

    companion object {
        fun compile(
            publication: SimpleSignalsRuntimePublication,
            catalog: SignalPackCatalog,
            logic: SignalLogicRuntime,
            bindings: Collection<SignalTypeBinding>,
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
            val boundaries = checkNotNull(publication.regions.compilationSnapshot()).boundariesById
            val entryRails = dynamic.mapValues { (deviceId, _) ->
                fixed[deviceId]?.let { definition ->
                    publication.fixedBlocks.snapshot(publication.topology).blocksById.getValue(definition.controlledBlockId)
                        .entryTraversal(definition.entryDirection == FixedBlockEntryDirection.REVERSE).railId
                } ?: boundaries.getValue(graph.logicalRootsByDeviceId.getValue(deviceId).first().state.entryBoundaryId).position.railId
            }
            val speeds = if (entryRails.isEmpty()) emptyMap() else {
                requireNotNull(railSpeedLimits) { "static rail speed limits are unavailable for reference-deceleration scanning" }
                    .invoke(publication.topology, entryRails.values.toSet())
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
                val referenceSpeed = entryRails[deviceId]?.let { railId ->
                    requireNotNull(speeds[railId]) { "signal device '$deviceId' entry rail '$railId' has no finite static speed limit" }
                        .also { require(it.isFinite() && it > 0.0) { "reference speed must be finite and positive" } }
                }
                val distance = signal.logic.scanDistanceMetres ?: run {
                    val speed = checkNotNull(referenceSpeed)
                    val deceleration = checkNotNull(signal.logic.referenceDecelerationMetresPerSecondSquared)
                    (speed * speed / (2.0 * deceleration)).also {
                        require(it.isFinite() && it > 0.0) { "signal device '$deviceId' calculated scan distance must be finite and positive" }
                    }
                }
                val plans = roots.map { root ->
                    SignalObservationPlanCompiler.compile(graph.graph, root.state, distance)
                }
                val definition = protection[deviceId]
                deviceId to PreparedBinding(
                    signal,
                    java.util.List.copyOf(plans),
                    distance,
                    referenceSpeed,
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
                Collections.unmodifiableMap(unavailable)
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
