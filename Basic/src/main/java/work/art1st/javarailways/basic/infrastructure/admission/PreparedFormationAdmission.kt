package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.event.TrainLinkEvent
import work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity
import work.art1st.javarailways.core.api.physics.infrastructure.PhysicalResourceIdentity
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.train.FormationMovementHold
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Exact engine-owned source, not a caller-supplied claim or release callback. */
internal class FormationSourceGrant(
    val grant: RouteGrantSnapshot,
    val resources: List<work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource>,
    val preparations: Map<InfrastructureResource, InfrastructurePreparationRequirement>,
    val isBusy: () -> Boolean,
    val isInvalidated: () -> Boolean,
    val setFormationHold: (FormationChangeHandle?) -> Unit
)

/**
 * Fixed-event preparation and publication fence. Retains original registry owners unchanged.
 * It intentionally publishes only held output evidence: contact authority and subsequent
 * aggregate output departure/release must be installed before an operating consumer can resume.
 */
internal class PreparedFormationAdmission(
    private val world: World?,
    private val worldId: String,
    private val latestObservation: () -> RailwayObservation?,
    private val failClosed: (String, Exception) -> Unit,
    private val registry: InfrastructureCoordinationRegistry,
    private val hostClaimOwner: Any,
    private val preparationActuator: InfrastructurePreparationActuator,
    private val compileRoute: (RouteResourceIntent, Double?, work.art1st.javarailways.core.api.physics.train.CouplingApproachProof?) -> FormationRouteCompilation,
    private val compileOutputRoute: (RouteResourceIntent, Double?, RouteProgressCursorState) -> FormationRouteCompilation,
    private val authorizeRoute: (FormationRouteContext, RouteResourceIntent) -> FormationRoutePolicyResult,
    private val publishApproachAdmission: (FormationRouteCompilation.Compiled, RouteGrantSnapshot) -> Unit,
    private val outputBoundaries: (FormationRouteCompilation.Compiled, Collection<InfrastructureResource>) -> Map<InfrastructureResource, Double?>,
    private val publishOutput: (Any, FormationChangeHandle, FormationOutputRoute) -> Unit,
    private val detachOutput: (Any, FormationOutputRoute) -> Unit,
    private val completeOutputs: (Any, List<FormationSourceGrant>, List<FormationOutputRoute>) -> Unit,
    private val endManualOutputs: (Any, FormationChangeHandle, List<FormationSourceGrant>, List<FormationOutputRoute>) -> Unit,
    private val outputOwnerAvailable: (Any, InfrastructureOwner, String, List<FormationSourceGrant>) -> Boolean,
    private val outputInvalidated: (InfrastructureOwner) -> Boolean
) {
    private class State(
        val requester: Any,
        val intent: FixedFormationChangeIntent,
        val bindings: List<FormationSourceGrant>,
        val frames: List<TrainFrameSnapshot>,
        val sources: List<FormationTrainIdentity>,
        var outputs: List<FormationTrainIdentity>,
        val handle: FormationChangeHandle = FormationChangeHandle(),
        var phase: FormationChangePhase = FormationChangePhase.PREPARED,
        var outputHolds: List<FormationMovementHold> = emptyList()
    ) {
        val retainedResources = bindings.associate { it.grant.owner to it.resources.toMutableSet() }
        val retainedPreparations = bindings.flatMap { it.preparations.entries }.associate { it.toPair() }.toMutableMap()
        var approachGrant: RouteGrantSnapshot? = null
        var pendingApproach: RouteAdmissionHandle? = null
        var outputAdmission: FormationOutputAdmission? = null
        fun snapshot() = FormationChangeSnapshot(handle, intent.eventId, phase, sources, outputs, outputAdmission?.grants.orEmpty())
    }

    private class ContactPermission(
        val proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        val maximumSpeed: Double
    )
    private val contactPermissionEnabled = java.util.concurrent.atomic.AtomicBoolean(true)
    private val contactPermissions = java.util.concurrent.atomic.AtomicReference<List<ContactPermission>>(emptyList())

    /** Called only by the serialized admission owner, never by the collision callback. */
    private fun publishContactPermissions() {
        if (!contactPermissionEnabled.get()) return
        contactPermissions.set(java.util.List.copyOf(states.values.mapNotNull { state ->
            val intent = state.intent as? FixedFormationChangeIntent.Couple
            val grant = state.approachGrant
            if (intent == null || state.phase != FormationChangePhase.PUBLICATION_SEALED ||
                grant?.couplingContactAuthority?.proof !== intent.proof || grant.maximumSpeedMetersPerSecond == null) null
            else ContactPermission(intent.proof, grant.maximumSpeedMetersPerSecond)
        }))
    }

    fun disableContactPermissions() {
        contactPermissionEnabled.set(false)
        contactPermissions.set(emptyList())
    }

    /** Physics prepass reads only immutable READY permissions and coherent Core publications. */
    @OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
    fun collision(event: work.art1st.javarailways.core.api.event.CollisionEvent) {
        if (event.world !== world || !contactPermissionEnabled.get()) return
        val publication = (event.world as? work.art1st.javarailways.core.physics.CoreWorld)?.latestWorldFrame() ?: return
        for (permission in contactPermissions.get()) {
            val proof = permission.proof
            if (setOf(event.trainAId, event.trainBId) != setOf(proof.request.approachingTrainId, proof.request.stationaryTrainId) ||
                !event.relativeSpeed.isFinite() || event.relativeSpeed > permission.maximumSpeed ||
                !event.world.validatePublishedCouplingApproach(proof)) continue
            val moving = publication.trains.firstOrNull { it.trainId == proof.request.approachingTrainId } ?: continue
            val target = publication.trains.firstOrNull { it.trainId == proof.request.stationaryTrainId } ?: continue
            // A braking step can travel into contact and finish at zero velocity. Core classifies
            // stationarity using both velocity and recorded traversal; the contact permit must do
            // the same rather than rejecting that final physical movement as stationary.
            val travelled = (moving.headTraversal + moving.tailTraversal).any {
                it.startArcLengthInAbMeters != it.endArcLengthInAbMeters
            }
            if (!travelled || !moving.speedMetersPerSecond.isFinite() || moving.speedMetersPerSecond < 0.0 ||
                moving.speedMetersPerSecond > permission.maximumSpeed || !stopped(target) ||
                moving.head.railId != proof.contactHead.railId || moving.head.facing != proof.contactHead.facing ||
                kotlin.math.abs(moving.head.arcLengthInAbMeters - proof.contactHead.arcLengthInAbMeters) > Curve.TOLERANCE) continue
            if (contactPermissionEnabled.get()) event.requestLink()
            return
        }
    }

    private val states = LinkedHashMap<FormationChangeHandle, State>()
    private val aborted = java.util.WeakHashMap<FormationChangeHandle, State>()
    private class ApproachAttempt(
        val state: State,
        val intent: RouteResourceIntent,
        val compiled: FormationRouteCompilation.Compiled,
        val delta: List<InfrastructureResource>,
        val handle: RouteAdmissionHandle,
        val ticket: ResourcePreparationTicket,
        val complete: () -> Boolean,
        val rollback: () -> Boolean
    )
    private val approaches = LinkedHashMap<RouteAdmissionHandle, ApproachAttempt>()
    private val approachResults = java.util.WeakHashMap<RouteAdmissionHandle, Pair<Any, RouteAdmissionResult>>()

    fun requestApproach(requester: Any, handle: FormationChangeHandle, submitted: RouteResourceIntent): RouteAdmissionResult {
        val state = owned(requester, handle) ?: return routeRejected("unknown or foreign formation handle")
        val intent = state.intent as? FixedFormationChangeIntent.Couple ?: return routeRejected("event is not coupling")
        if (state.phase != FormationChangePhase.PUBLICATION_SEALED || state.pendingApproach != null ||
            state.approachGrant != null || !unchangedStoppedSources(state) || submitted.formationContext != null ||
            submitted.owner != state.bindings.first().grant.owner || submitted.trainId != state.sources.first().trainId ||
            world?.validateCouplingApproachGeometry(intent.proof) != true)
            return routeRejected("approach does not match the sealed stopped sources")
        val bodyLength = intent.proof.approachingFrame.bodyTraversal.sumOf {
            kotlin.math.abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters)
        }
        val boundary = bodyLength + intent.proof.distanceToContactMeters
        if (kotlin.math.abs(submitted.desiredAuthority.distanceFromRouteStartMeters - boundary) > 1e-8)
            return routeRejected("approach authority must be the solved contact head")
        val context = context(state, FormationRoutePurpose.COUPLING_APPROACH, intent.maximumApproachSpeedMetersPerSecond)
        val request = submitted.copy(formationContext = context)
        val compiled = when (val result = compileRoute(request, bodyLength, intent.proof)) {
            is FormationRouteCompilation.Rejected -> return RouteAdmissionResult.Rejected(result.failure)
            is FormationRouteCompilation.Compiled -> result
        }
        if (!samePath(prefix(compiled, boundary), intent.proof.approachingFrame.bodyTraversal + intent.proof.headTraversal))
            return routeRejected("approach traversal differs from the host contact proof")
        val policy = authorize(context, compiled.policyIntent)
        if (policy is FormationRoutePolicyResult.Rejected) return RouteAdmissionResult.Rejected(policy.failure)
        if ((policy as FormationRoutePolicyResult.Accepted).clearances.isNotEmpty())
            return routeRejected("contact permission cannot assert output clearance")
        val held = state.retainedResources.values.flatten()
        if (!preparationsAgree(compiled.admission.preparations, held, state.retainedPreparations))
            return routeRejected("approach would change a retained device preparation")
        val delta = uncoveredResources(compiled.admission.resources.resources, held)
        if (delta.isEmpty()) return publishApproach(state, request, compiled, emptyList())
        val reservation = registry.beginAppendClaim(hostClaimOwner, request.owner, delta)
        if (!reservation.result.successful) return RouteAdmissionResult.Rejected(RouteAdmissionFailure(
            RouteAdmissionFailureCode.RESOURCE_CONFLICT, reservation.result.detail.ifBlank { "formation resource conflict" },
            reservation.result.blockers.flatMap { blocker -> blocker.overlappingResources.map {
                RouteAdmissionBlocker.ConflictingResource(it, blocker.owner)
            } }.ifEmpty { return routeRejected(reservation.result.detail.ifBlank { "formation reservation rejected" }) }))
        val preparations = compiled.admission.preparations.filter { it.resource in delta }
        val started = try { registry.withClaim(hostClaimOwner, request.owner) { preparationActuator.start(preparations) } }
        catch (failure: Exception) {
            recovery(state, "approach preparation threw: ${failure.message}")
            return routeRejected("approach preparation is unsafe; resources retained")
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            if (!registry.rollbackClaimAppend(reservation).successful) recovery(state, "approach rollback failed")
            return routeRejected("approach preparation rejected: ${started.failure.message}")
        }
        val admissionHandle = RouteAdmissionHandle()
        state.pendingApproach = admissionHandle
        approaches[admissionHandle] = ApproachAttempt(state, request, compiled, delta, admissionHandle,
            (started as InfrastructurePreparationStart.Accepted).ticket,
            { registry.completeClaimAppend(reservation) }, { registry.rollbackClaimAppend(reservation).successful })
        return checkNotNull(progress(requester, admissionHandle))
    }

    fun progress(requester: Any, handle: RouteAdmissionHandle): RouteAdmissionResult? {
        approachResults[handle]?.let { return if (it.first === requester) it.second else routeRejected("foreign approach handle") }
        val attempt = approaches[handle] ?: run {
            for (state in states.values) if (state.requester === requester) {
                val output = state.outputAdmission ?: continue
                val result = if (state.phase == FormationChangePhase.RECOVERY_REQUIRED) output.cachedResult(handle)
                    else output.progress(handle)
                result?.let { return it }
            }
            return null
        }
        val state = attempt.state
        if (state.requester !== requester) return routeRejected("foreign approach handle")
        val result = try {
            when (val preparation = attempt.ticket.snapshot()) {
                else -> when (preparation.state) {
                    ResourcePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, null)
                    ResourcePreparationState.READY -> {
                        if (!unchangedStoppedSources(state) || state.phase != FormationChangePhase.PUBLICATION_SEALED ||
                            world?.validateCouplingApproachGeometry((state.intent as FixedFormationChangeIntent.Couple).proof) != true)
                            error("approach sources changed while resources prepared")
                        val observation = checkNotNull(latestObservation())
                        val request = attempt.intent.copy(observationStamp = observation.stamp)
                        val refreshed = compileRoute(request, attempt.compiled.observedHeadMeters, (state.intent as FixedFormationChangeIntent.Couple).proof)
                        check(refreshed is FormationRouteCompilation.Compiled &&
                            refreshed.admission.resources == attempt.compiled.admission.resources &&
                            refreshed.admission.preparations == attempt.compiled.admission.preparations)
                        val policy = authorize(checkNotNull(request.formationContext), refreshed.policyIntent)
                        check(policy is FormationRoutePolicyResult.Accepted && policy.clearances.isEmpty())
                        check(attempt.complete()) { "approach resource reservation disappeared" }
                        publishApproach(state, request, refreshed, attempt.delta)
                    }
                    ResourcePreparationState.ABORTED_SAFE -> {
                        check(attempt.rollback()) { "safe approach rollback failed" }
                        RouteAdmissionResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
                            "approach preparation safely aborted"))
                    }
                    ResourcePreparationState.UNSAFE -> error("approach preparation unsafe: ${preparation.detail}")
                }
            }
        } catch (failure: Exception) {
            recovery(state, "approach readiness failed: ${failure.message}")
            routeRejected("approach failed closed; resources retained")
        }
        if (result !is RouteAdmissionResult.Pending) {
            approaches.remove(handle)
            state.pendingApproach = null
            approachResults[handle] = requester to result
        }
        return result
    }

    private fun publishApproach(state: State, intent: RouteResourceIntent,
                                compiled: FormationRouteCompilation.Compiled,
                                delta: Collection<InfrastructureResource>): RouteAdmissionResult {
        state.retainedResources.getValue(intent.owner).addAll(delta)
        compiled.admission.preparations.forEach { state.retainedPreparations[it.resource] = it }
        val grant = RouteGrantSnapshot(RouteGrantId(), intent.owner, intent.trainId, intent.route.routeId,
            intent.route.revision, compiled.observation.stamp, intent.desiredAuthority,
            (state.intent as FixedFormationChangeIntent.Couple).maximumApproachSpeedMetersPerSecond,
            CouplingContactAuthority(state.intent.eventId, state.intent.proof, intent.desiredAuthority,
                state.intent.maximumApproachSpeedMetersPerSecond))
        state.approachGrant = grant
        publishContactPermissions()
        publishApproachAdmission(compiled, grant)
        return RouteAdmissionResult.Granted(grant)
    }

    private fun context(state: State, purpose: FormationRoutePurpose, speed: Double? = null) = FormationRouteContext(
        state.intent.eventId, purpose, state.sources, state.outputs,
        state.retainedResources.values.flatten().filterIsInstance<StableInfrastructureResource>().distinct().filterNot {
            it.typeId == TurnoutResourceIdentity.TYPE_ID || it.typeId == PhysicalResourceIdentity.DIAMOND_TYPE_ID
        }, speed)

    private fun authorize(context: FormationRouteContext, intent: RouteResourceIntent): FormationRoutePolicyResult =
        try { authorizeRoute(context, intent) } catch (failure: Exception) {
            FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "formation policy failed: ${failure.message}"))
        }

    private fun preparationsAgree(required: List<InfrastructurePreparationRequirement>, held: Collection<InfrastructureResource>,
                                  previous: Map<InfrastructureResource, InfrastructurePreparationRequirement>): Boolean = required.all {
        if (it.resource !in held) true else when (it) {
            is ResolvedTurnoutSelection -> (previous[it.resource] as? ResolvedTurnoutSelection)?.let { old ->
                setOf(old.fromPort, old.toPort) == setOf(it.fromPort, it.toPort)
            } == true
            is ResolvedContributedResourcePreparation -> previous[it.resource] == it
        }
    }

    private fun prefix(compiled: FormationRouteCompilation.Compiled, boundary: Double): List<RailTraversalSegmentSnapshot> =
        compiled.projection.occurrences.mapNotNull { occurrence ->
            val end = minOf(boundary, occurrence.routeEndMeters)
            if (end <= occurrence.routeStartMeters) null else RailTraversalSegmentSnapshot(occurrence.segment.railId,
                occurrence.segment.facing, occurrence.canonicalStartAbMeters,
                occurrence.canonicalStartAbMeters + (end - occurrence.routeStartMeters) *
                    if (occurrence.segment.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) 1.0 else -1.0)
        }

    private fun samePath(first: List<RailTraversalSegmentSnapshot>, second: List<RailTraversalSegmentSnapshot>): Boolean {
        fun normalized(path: List<RailTraversalSegmentSnapshot>) = buildList<RailTraversalSegmentSnapshot> {
            for (part in path) {
                if (part.startArcLengthInAbMeters == part.endArcLengthInAbMeters) continue
                val previous = lastOrNull()
                if (previous != null && previous.railId == part.railId && previous.facing == part.facing &&
                    kotlin.math.abs(previous.endArcLengthInAbMeters - part.startArcLengthInAbMeters) <= 1e-8) {
                    removeAt(lastIndex); add(previous.copy(endArcLengthInAbMeters = part.endArcLengthInAbMeters))
                } else add(part)
            }
        }
        val a = normalized(first)
        val b = normalized(second)
        return a.size == b.size && a.indices.all { i -> a[i].railId == b[i].railId && a[i].facing == b[i].facing &&
            kotlin.math.abs(a[i].startArcLengthInAbMeters - b[i].startArcLengthInAbMeters) <= 1e-8 &&
            kotlin.math.abs(a[i].endArcLengthInAbMeters - b[i].endArcLengthInAbMeters) <= 1e-8 }
    }

    private fun routeRejected(detail: String) = RouteAdmissionResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, detail))

    fun prepare(requester: Any, intent: FixedFormationChangeIntent,
                bindings: List<FormationSourceGrant>): FormationChangeResult {
        val host = world ?: return reject("world has no physical formation host")
        if (states.values.any { it.intent.eventId == intent.eventId }) return reject("fixed event is already prepared")
        if (bindings.any { it.isBusy() || it.isInvalidated() }) return reject("source grant is busy or invalidated")
        if (overlapsRetained(bindings.flatMap { it.resources })) return reject("source queue overlaps another retained formation event")
        val observation = latestObservation() ?: return reject("no coherent formation observation")
        if (observation.stamp.worldId != worldId) return reject("formation belongs to another world")
        val frames = bindings.map { binding ->
            val frame = host.getTrain(binding.grant.trainId)?.publishedFrame ?: return reject("source train is absent")
            if (!matchesObservation(frame, observation) || !stopped(frame)) return reject("sources must be coherently stopped")
            if (!bodyCovered(frame, binding)) return reject("source body is not covered by its retained grant")
            frame
        }
        val sources = frames.map(::identity)
        val outputs: List<FormationTrainIdentity>
        when (intent) {
            is FixedFormationChangeIntent.Couple -> {
                if (bindings.size != 2 || !host.validateCouplingApproach(intent.proof) ||
                    sources[0].trainId != intent.proof.request.approachingTrainId ||
                    sources[1].trainId != intent.proof.request.stationaryTrainId ||
                    frames[0] !== intent.proof.approachingFrame || frames[1] !== intent.proof.stationaryFrame)
                    return reject("coupling proof does not identify the exact source grants")
                val masterIndex = sources.indexOfFirst { it.trainId == intent.output.trainId }
                if (masterIndex < 0) return reject("coupling survivor must be one exact source")
                val otherIndex = 1 - masterIndex
                val masterEnd = if (masterIndex == 0) intent.proof.request.approachingEnd else intent.proof.request.stationaryEnd
                val otherEnd = if (otherIndex == 0) intent.proof.request.approachingEnd else intent.proof.request.stationaryEnd
                val incoming = sources[otherIndex].carIds.let { if (masterEnd == otherEnd) it.asReversed() else it }
                val expectedCars = if (masterEnd == TrainEndSide.B) sources[masterIndex].carIds + incoming
                    else incoming + sources[masterIndex].carIds
                if (intent.output.carIds != expectedCars) return reject("coupling output order differs from physical composition")
                outputs = listOf(intent.output)
            }
            is FixedFormationChangeIntent.Split -> {
                if (sources != listOf(intent.source)) return reject("split source differs from current formation")
                val index = intent.source.carIds.indexOf(intent.beforeCarId)
                if (index <= 0 || intent.retained.trainId != intent.source.trainId ||
                    intent.separated.trainId == intent.source.trainId || host.getTrain(intent.separated.trainId) != null ||
                    intent.retained.carIds != intent.source.carIds.take(index) ||
                    intent.separated.carIds != intent.source.carIds.drop(index)) return reject("invalid exact split outputs")
                outputs = listOf(intent.retained, intent.separated)
            }
        }
        if (sources.flatMap { it.carIds }.distinct().size != sources.sumOf { it.carIds.size })
            return reject("source formations share stable cars")
        if (latestObservation() !== observation || bindings.any { it.isBusy() || it.isInvalidated() })
            return reject("formation changed during preparation")
        val state = State(requester, intent, bindings, frames, sources, outputs)
        // No registry resource or operating assignment is removed or rewritten here.
        bindings.forEach { it.setFormationHold(state.handle) }
        states[state.handle] = state
        return FormationChangeResult.Accepted(state.snapshot())
    }

    fun seal(requester: Any, handle: FormationChangeHandle): FormationChangeResult {
        val state = owned(requester, handle) ?: return reject("unknown or foreign formation handle")
        if (state.phase == FormationChangePhase.PUBLICATION_SEALED) return accepted(state)
        if (state.phase != FormationChangePhase.PREPARED || !unchangedStoppedSources(state))
            return reject("formation cannot seal changed or published sources")
        state.phase = FormationChangePhase.PUBLICATION_SEALED
        return accepted(state)
    }

    fun snapshot(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        owned(requester, handle)?.let(::accepted) ?: reject("unknown or foreign formation handle")

    fun abort(requester: Any, handle: FormationChangeHandle): FormationChangeResult {
        val state = owned(requester, handle) ?: return reject("unknown or foreign formation handle")
        if (state.phase == FormationChangePhase.ABORTED) return accepted(state)
        if (state.phase != FormationChangePhase.PREPARED || !unchangedStoppedSources(state))
            return reject("sealed or changed formations retain claims for recovery")
        state.bindings.forEach { it.setFormationHold(null) }
        state.phase = FormationChangePhase.ABORTED
        states.remove(handle)
        aborted[handle] = state
        return accepted(state)
    }

    fun guardLink(event: TrainLinkEvent) {
        if (event.world !== world) return
        val participants = participatingStates(event.firstTrain.id, event.secondTrain.id)
        if (participants.isEmpty()) return
        if (participants.size != 1) {
            event.reject("contact joins independently prepared formation events")
            return
        }
        val state = participants.single()
        val intent = state.intent as? FixedFormationChangeIntent.Couple
        if (intent == null || state.phase != FormationChangePhase.PUBLICATION_SEALED ||
            state.bindings.any { it.isInvalidated() } ||
            setOf(event.firstTrain.id, event.secondTrain.id) != state.sources.map { it.trainId }.toSet() ||
            identity(checkNotNull(event.firstTrain.publishedFrame)) !in state.sources ||
            identity(checkNotNull(event.secondTrain.publishedFrame)) !in state.sources) {
            event.reject("physical link does not match the sealed fixed event")
            return
        }
        val firstApproaches = event.firstTrain.id == intent.proof.request.approachingTrainId
        val approachingEnd = if (firstApproaches) event.firstEnd else event.secondEnd
        val stationaryEnd = if (firstApproaches) event.secondEnd else event.firstEnd
        val approachSpeed = if (firstApproaches) event.firstSpeedBeforeCollision else event.secondSpeedBeforeCollision
        val stationarySpeed = if (firstApproaches) event.secondSpeedBeforeCollision else event.firstSpeedBeforeCollision
        if (approachingEnd != intent.proof.request.approachingEnd || stationaryEnd != intent.proof.request.stationaryEnd ||
            approachSpeed > intent.maximumApproachSpeedMetersPerSecond || stationarySpeed != 0.0) {
            event.reject("contact ends or speeds differ from the prepared event")
            return
        }
        val target = if (firstApproaches) event.secondTrain else event.firstTrain
        val targetFrame = checkNotNull(target.publishedFrame)
        if (!samePlacement(targetFrame, intent.proof.stationaryFrame)) {
            event.reject("stationary participant moved from the prepared contact geometry")
            return
        }
        val contact = state.approachGrant?.couplingContactAuthority
        val solved = if (firstApproaches) event.firstContactHead else event.secondContactHead
        if (contact == null || state.pendingApproach != null ||
            world?.validateCouplingApproachGeometry(intent.proof) != true ||
            solved.railId != intent.proof.contactHead.railId || solved.facing != intent.proof.contactHead.facing ||
            kotlin.math.abs(solved.arcLengthInAbMeters - intent.proof.contactHead.arcLengthInAbMeters) > Curve.TOLERANCE) {
            event.reject("fixed coupling has no matching admitted host-solved contact")
            return
        }
        event.requestMasterTrain(if (event.firstTrain.id == intent.output.trainId) event.firstTrain else event.secondTrain)
        event.requestDirection(intent.output.reversed)
        event.requestMovementHold()
    }

    fun guardSplit(event: TrainSplitEvent) {
        if (event.world !== world) return
        val state = participating(event.train.id) ?: return
        val intent = state.intent as? FixedFormationChangeIntent.Split
        if (intent == null || state.phase != FormationChangePhase.PUBLICATION_SEALED ||
            !unchangedPublicationSources(state) || event.carId != intent.beforeCarId ||
            event.newTrainId != intent.separated.trainId) {
            event.reject("physical split does not match the sealed fixed event")
            return
        }
        event.requestRetainedDirection(intent.retained.reversed)
        event.requestSeparatedDirection(intent.separated.reversed)
        event.requestMovementHold()
    }

    fun observedLink(event: TrainLinkSuccessEvent) {
        if (event.world !== world) return
        val participants = participatingStates(event.firstTrain.id, event.secondTrain.id)
        if (participants.size != 1) {
            participants.forEach { recovery(it, "publication joined independent formation events") }
            return
        }
        val state = participants.single()
        if (state.intent !is FixedFormationChangeIntent.Couple ||
            event.movementHold == null || listOf(identity(checkNotNull(event.train.publishedFrame))) != state.outputs) {
            recovery(state, "published coupling does not match the prepared held output")
            return
        }
        state.outputHolds = listOf(checkNotNull(event.movementHold))
        state.phase = FormationChangePhase.PHYSICAL_OBSERVED
        publishContactPermissions()
    }

    fun observedSplit(event: TrainSplitSuccessEvent) {
        if (event.world !== world) return
        val state = participating(event.train.id) ?: return
        if (state.intent !is FixedFormationChangeIntent.Split || event.retainedMovementHold == null ||
            event.separatedMovementHold == null || listOf(identity(checkNotNull(event.train.publishedFrame)),
                identity(checkNotNull(event.separatedTrain.publishedFrame))) != state.outputs) {
            recovery(state, "published split does not match both prepared held outputs")
            return
        }
        state.outputHolds = listOf(checkNotNull(event.retainedMovementHold), checkNotNull(event.separatedMovementHold))
        state.phase = FormationChangePhase.PHYSICAL_OBSERVED
        publishContactPermissions()
    }

    fun consume(observation: RailwayObservation) {
        for (state in states.values) {
            if (state.phase == FormationChangePhase.RECOVERY_REQUIRED || state.phase == FormationChangePhase.COMPLETED) continue
            state.outputAdmission?.let { output ->
                if (output.activeGrants.any { outputInvalidated(it.owner) }) recovery(state, "output route topology was invalidated")
                else output.consume(observation)
                if (output.promoted) state.phase = FormationChangePhase.COMPLETED
            }
            if (state.outputAdmission != null) continue
            if (state.bindings.any { it.isInvalidated() }) {
                recovery(state, "formation source topology was invalidated")
                continue
            }
            if (state.phase == FormationChangePhase.PHYSICAL_OBSERVED || state.phase == FormationChangePhase.OUTPUTS_HELD) {
                val outputFrames = state.outputs.map { world?.getTrain(it.trainId)?.publishedFrame }
                if (outputFrames.any { it == null } || outputFrames.filterNotNull().map(::identity) != state.outputs ||
                    outputFrames.filterNotNull().any { !stopped(it) }) {
                    recovery(state, "formation outputs differ from the committed physical evidence")
                } else if (outputFrames.filterNotNull().all { matchesObservation(it, observation) }) {
                    state.phase = FormationChangePhase.OUTPUTS_HELD
                }
            } else if (!unchangedSourceIdentities(state) || state.sources.any {
                    world?.getTrain(it.trainId)?.publishedFrame?.derailed != false
                }) {
                val details = state.sources.joinToString("; ") { source ->
                    val frame = world?.getTrain(source.trainId)?.publishedFrame
                    when {
                        frame == null -> "${source.trainId}: missing"
                        frame.derailed -> "${source.trainId}: derailed (${frame.derailReason}), speed=${frame.speedMetersPerSecond}"
                        identity(frame) != source -> "${source.trainId}: formation identity changed"
                        else -> "${source.trainId}: unchanged"
                    }
                }
                recovery(state, "source disappeared or changed without matched physical publication: $details")
            } else if (state.intent is FixedFormationChangeIntent.Couple &&
                (!samePlacement(checkNotNull(world?.getTrain(state.intent.proof.request.stationaryTrainId)?.publishedFrame),
                    state.intent.proof.stationaryFrame) || state.approachGrant?.let { grant ->
                    world?.getTrain(grant.trainId)?.publishedFrame?.speedMetersPerSecond?.let {
                        it > checkNotNull(grant.maximumSpeedMetersPerSecond)
                    } != false
                } == true)) {
                recovery(state, "coupling target moved or approaching speed exceeded its admitted cap")
            }
        }
    }

    fun bindOutputs(requester: Any, handle: FormationChangeHandle, intents: List<RouteResourceIntent>): FormationChangeResult {
        val state = owned(requester, handle) ?: return reject("unknown or foreign formation handle")
        if (state.phase != FormationChangePhase.OUTPUTS_HELD || state.outputAdmission != null ||
            intents.any { !outputOwnerAvailable(requester, it.owner, it.trainId, state.bindings) })
            return reject("outputs are not held or output owner is already used")
        val admission = FormationOutputAdmission(checkNotNull(world), handle, registry, hostClaimOwner,
            state.retainedResources, state.retainedPreparations, { context(state, it) }, { intent, movement -> compileRoute(intent, movement, null) }, compileOutputRoute, latestObservation,
            outputBoundaries, ::authorize, preparationActuator,
            { publishOutput(requester, handle, it) },
            { detachOutput(requester, it) },
            { completeOutputs(requester, state.bindings, it) }, { recovery(state, it) })
        val result = admission.bind(intents, state.outputHolds)
        if (result is FormationChangeResult.Accepted) {
            state.outputAdmission = admission
            state.phase = FormationChangePhase.OUTPUTS_BOUND
        }
        return result
    }

    fun requestOutput(requester: Any, handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult {
        val state = owned(requester, handle) ?: return routeRejected("unknown or foreign formation handle")
        if (state.phase == FormationChangePhase.RECOVERY_REQUIRED) return routeRejected("formation requires recovery")
        return state.outputAdmission?.request(intent) ?: routeRejected("outputs have not been bound")
    }

    fun resumeOutput(requester: Any, handle: FormationChangeHandle, grantId: RouteGrantId): FormationChangeResult {
        val state = owned(requester, handle) ?: return reject("unknown or foreign formation handle")
        if (state.phase == FormationChangePhase.RECOVERY_REQUIRED) return reject("formation requires recovery")
        val result = state.outputAdmission?.resume(grantId) ?: return reject("outputs have not been bound")
        if (result is FormationChangeResult.Accepted && state.phase != FormationChangePhase.COMPLETED)
            state.phase = FormationChangePhase.OUTPUTS_ACTIVE
        return result
    }

    fun finishForManual(requester: Any, handle: FormationChangeHandle, grantId: RouteGrantId): FormationChangeResult {
        val state = owned(requester, handle) ?: return reject("unknown or foreign formation handle")
        if (state.intent !is FixedFormationChangeIntent.Couple || state.phase != FormationChangePhase.OUTPUTS_BOUND)
            return reject("manual handoff requires one completed coupling with bound stopped output")
        val output = state.outputAdmission ?: return reject("outputs have not been bound")
        val result = output.finishForManual(grantId) {
            endManualOutputs(requester, handle, state.bindings, output.routesForManualHandoff())
        }
        if (result is FormationChangeResult.Accepted) {
            state.phase = FormationChangePhase.COMPLETED
            return FormationChangeResult.Accepted(state.snapshot())
        }
        return result
    }

    private fun owned(requester: Any, handle: FormationChangeHandle): State? =
        (states[handle] ?: aborted[handle])?.takeIf { it.requester === requester }

    fun suspendOutputReversal(requester: Any, handle: FormationChangeHandle, grantId: RouteGrantId): RouteGrantReversalResult {
        val state = owned(requester, handle)
        if (state == null || state.phase == FormationChangePhase.RECOVERY_REQUIRED)
            return RouteGrantReversalResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, "formation output unavailable"))
        return state.outputAdmission?.suspendReversal(grantId) ?: RouteGrantReversalResult.Rejected(
            RouteAdmissionFailure(RouteAdmissionFailureCode.REQUEST_BUSY, "source grant is retained by a prepared formation event"))
    }

    fun rebaseOutputReversal(requester: Any, handle: FormationChangeHandle, intent: RouteResourceIntent): RouteGrantReversalResult {
        val state = owned(requester, handle)
        if (state == null || state.phase == FormationChangePhase.RECOVERY_REQUIRED)
            return RouteGrantReversalResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, "formation output unavailable"))
        val output = state.outputAdmission ?: return RouteGrantReversalResult.Rejected(
            RouteAdmissionFailure(RouteAdmissionFailureCode.REQUEST_BUSY, "source grant is retained by a prepared formation event"))
        val result = output.rebaseReversal(intent)
        if (result is RouteGrantReversalResult.Accepted) {
            val identity = checkNotNull(output.outputIdentity(intent.trainId))
            state.outputs = state.outputs.map { if (it.trainId == intent.trainId) identity else it }
        }
        return result
    }

    fun outputDetached(requester: Any, handle: FormationChangeHandle, trainId: String): Boolean =
        owned(requester, handle)?.outputAdmission?.isDetached(trainId) == true

    fun retainedOwnerAvailable(requester: Any, owner: InfrastructureOwner): Boolean = states.values.all { state ->
        state.retainedResources.none { (heldOwner, resources) -> resources.isNotEmpty() && heldOwner.ownerId == owner.ownerId &&
            (heldOwner != owner || state.requester !== requester) }
    }

    fun retainedResources(owner: InfrastructureOwner): Set<InfrastructureResource> =
        states.values.flatMap { it.retainedResources[owner].orEmpty() }.toSet()

    fun hasRetainedResources(): Boolean = states.values.any { it.retainedResources.values.any { resources -> resources.isNotEmpty() } }

    fun overlapsRetained(resources: Collection<InfrastructureResource>, exceptEventId: String? = null): Boolean =
        states.values.filter { it.intent.eventId != exceptEventId }.any { state ->
            state.retainedResources.values.flatten().any { held -> resources.any(held::overlaps) }
        }

    /** Retired/detached source containers need not exist for their original claims to stay active. */
    fun retainedActivity(requester: Any? = null, typeIds: Set<String>? = null): List<RouteAdmissionRequesterActivity> =
        states.values.filter { requester == null || it.requester === requester }.flatMap { state ->
            state.retainedResources.mapNotNull { (owner, resources) ->
                val matching = resources.filterIsInstance<StableInfrastructureResource>().map { it.typeId }.toSet()
                    .let { if (typeIds == null) it else it.intersect(typeIds) }
                if (resources.isEmpty() || typeIds != null && matching.isEmpty()) null else
                    RouteAdmissionRequesterActivity(owner, state.bindings.first { it.grant.owner == owner }.grant.trainId,
                        false, resources.size, matching)
            }
        }.groupBy { it.owner }.map { (_, activities) -> activities.first().copy(
            heldResourceCount = activities.sumOf { it.heldResourceCount },
            resourceTypeIds = activities.flatMap { it.resourceTypeIds }.toSet()) }

    private fun participates(state: State, trainIds: Set<String>): Boolean {
        if (state.phase == FormationChangePhase.COMPLETED || state.phase == FormationChangePhase.ABORTED) return false
        return if (state.outputAdmission != null) state.outputs.any {
            it.trainId in trainIds && state.outputAdmission?.isDetached(it.trainId) != true
        } else if (state.phase == FormationChangePhase.PHYSICAL_OBSERVED || state.phase == FormationChangePhase.OUTPUTS_HELD)
            state.outputs.any { it.trainId in trainIds }
        else state.sources.any { it.trainId in trainIds }
    }

    private fun participating(vararg trainIds: String): State? = states.values.firstOrNull { participates(it, trainIds.toSet()) }
    private fun participatingStates(vararg trainIds: String): List<State> = states.values.filter { participates(it, trainIds.toSet()) }

    private fun samePlacement(current: TrainFrameSnapshot, original: TrainFrameSnapshot): Boolean =
        identity(current) == identity(original) && current.head == original.head && current.tail == original.tail &&
            current.cars == original.cars

    private fun unchangedSourceIdentities(state: State): Boolean = state.sources.all { expected ->
        world?.getTrain(expected.trainId)?.publishedFrame?.let(::identity) == expected
    }

    /** Queued Core publication runs before the host refreshes its external observation. */
    private fun unchangedPublicationSources(state: State): Boolean {
        val publication = (world as? work.art1st.javarailways.core.physics.CoreWorld)?.latestWorldFrame() ?: return false
        if (state.bindings.any { it.isInvalidated() }) return false
        val claims = registry.claimSnapshots()
        if (state.retainedResources.any { (owner, resources) ->
                // A detached output may share its registry owner with the old HOLD peer's
                // suspended originals. Verify that exact remainder without inheriting it here.
                val otherOriginals = states.values.filter { it !== state && it.phase !in setOf(
                    FormationChangePhase.ABORTED, FormationChangePhase.COMPLETED) }
                    .flatMap { it.retainedResources[owner].orEmpty() }.toSet()
                claims.none { it.owner == owner && it.footprint.resources.toSet() == resources + otherOriginals }
            }) return false
        return state.frames.zip(state.bindings).all { (original, binding) ->
            val current = world.getTrain(original.trainId)?.publishedFrame ?: return false
            publication.trains.any { it === current } && samePlacement(current, original) &&
                current.bodyTraversal == original.bodyTraversal && current.occupiedIntervals == original.occupiedIntervals &&
                stopped(current) && bodyCovered(current, binding)
        }
    }

    private fun unchangedStoppedSources(state: State): Boolean {
        val observation = latestObservation() ?: return false
        return state.bindings.none { it.isInvalidated() } && state.frames.all { original ->
            val current = world?.getTrain(original.trainId)?.publishedFrame ?: return false
            identity(current) == identity(original) && current.head == original.head && current.tail == original.tail &&
                stopped(current) && matchesObservation(current, observation)
        }
    }

    private fun bodyCovered(frame: TrainFrameSnapshot, binding: FormationSourceGrant): Boolean {
        if (binding.resources.isEmpty() || frame.occupiedIntervals.isEmpty()) return false
        return frame.occupiedIntervals.all { interval ->
            val slices = binding.resources.filterIsInstance<RailIntervalResource>()
                .filter { it.railId == interval.railId }.sortedBy { it.startAbMeters }
            var through = interval.minArcLengthInAbMeters
            for (slice in slices) {
                if (slice.endAbMeters < through) continue
                if (slice.startAbMeters > through + 1e-8) return@all false
                through = maxOf(through, slice.endAbMeters)
                if (through >= interval.maxArcLengthInAbMeters - 1e-8) return@all true
            }
            false
        }
    }

    private fun matchesObservation(frame: TrainFrameSnapshot, observation: RailwayObservation): Boolean =
        observation.stamp.worldId == worldId && frame.physicsEpoch == observation.stamp.physicsEpoch &&
            observation.trains.any { it.trainId == frame.trainId && it.frameRevision == frame.revision }

    private fun stopped(frame: TrainFrameSnapshot): Boolean = !frame.derailed && frame.speedMetersPerSecond == 0.0 &&
        (frame.headTraversal + frame.tailTraversal).all { it.startArcLengthInAbMeters == it.endArcLengthInAbMeters } &&
        frame.cars.all { car -> (car.bogies + car.state).all {
            it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
        } }

    private fun identity(frame: TrainFrameSnapshot) =
        FormationTrainIdentity(frame.trainId, frame.cars.map { it.carId }, frame.reversed)

    private fun recovery(state: State, reason: String) {
        if (state.phase == FormationChangePhase.RECOVERY_REQUIRED) return
        state.phase = FormationChangePhase.RECOVERY_REQUIRED
        publishContactPermissions()
        failClosed(reason, IllegalStateException("fixed event ${state.intent.eventId}: $reason"))
    }

    private fun accepted(state: State) = FormationChangeResult.Accepted(state.snapshot())
    private fun reject(detail: String) = FormationChangeResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, detail))
}
