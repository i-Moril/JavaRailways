package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.infrastructure.*
import work.art1st.javarailways.core.api.physics.train.FormationMovementHold
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest

private const val BODY_COORDINATE_EPSILON_METERS = 1e-8

/** An output retains its own coordinate/cursor; originals remain jointly owned until all clear. */
internal class FormationOutputAdmission(
    private val world: World,
    private val handle: FormationChangeHandle,
    private val registry: InfrastructureCoordinationRegistry,
    private val hostOwner: Any,
    private val originals: Map<InfrastructureOwner, MutableSet<InfrastructureResource>>,
    private val preparations: MutableMap<InfrastructureResource, InfrastructurePreparationRequirement>,
    private val context: (FormationRoutePurpose) -> FormationRouteContext,
    private val compile: (RouteResourceIntent, Double?) -> FormationRouteCompilation,
    private val compileOutput: (RouteResourceIntent, Double?, RouteProgressCursorState) -> FormationRouteCompilation,
    private val latestObservation: () -> RailwayObservation?,
    private val boundaries: (FormationRouteCompilation.Compiled, Collection<InfrastructureResource>) -> Map<InfrastructureResource, Double?>,
    private val authorize: (FormationRouteContext, RouteResourceIntent) -> FormationRoutePolicyResult,
    private val actuator: InfrastructurePreparationActuator,
    private val publish: (FormationOutputRoute) -> Unit,
    private val detach: (FormationOutputRoute) -> Unit,
    private val complete: (List<FormationOutputRoute>) -> Unit,
    private val recovery: (String) -> Unit
) {
    private val outputs = linkedMapOf<String, FormationOutputRoute>()
    private var pending: Pending? = null
    private var lastEpoch = -1L
    private val results = java.util.WeakHashMap<RouteAdmissionHandle, RouteAdmissionResult>()
    var promoted = false
        private set
    val grants get() = outputs.values.map { it.grant }
    val activeGrants get() = outputs.values.filterNot { it.detached }.map { it.grant }
    fun isDetached(trainId: String): Boolean = outputs[trainId]?.detached == true
    private class Pending(
        val output: FormationOutputRoute,
        val request: RouteResourceIntent,
        val compiled: FormationRouteCompilation.Compiled,
        val clearances: Map<InfrastructureResource, Double?>,
        val delta: List<InfrastructureResource>,
        val handle: RouteAdmissionHandle,
        val ticket: ResourcePreparationTicket,
        val finalize: () -> Boolean,
        val rollback: () -> Boolean
    )

    fun bind(intents: List<RouteResourceIntent>, holds: List<FormationMovementHold>): FormationChangeResult {
        if (outputs.isNotEmpty()) return rejected("outputs already bound")
        val expected = context(FormationRoutePurpose.OUTPUT_BODY).outputs
        if (intents.size != expected.size || intents.map { it.trainId }.toSet() != expected.map { it.trainId }.toSet() ||
            intents.map { it.owner }.distinct().size != intents.size) return rejected("every exact output needs one unique owner")
        val compiled = arrayListOf<FormationOutputRoute>()
        try {
            for (identity in expected) {
                val submitted = intents.single { it.trainId == identity.trainId }
                require(submitted.formationContext == null)
                val frame = checkNotNull(world.getTrain(identity.trainId)?.publishedFrame)
                require(frame.cars.map { it.carId } == identity.carIds && frame.reversed == identity.reversed &&
                    stopped(frame))
                val request = submitted.copy(formationContext = context(FormationRoutePurpose.OUTPUT_BODY))
                val route = checkCompiled(compile(request, null))
                require(kotlin.math.abs(route.observedHeadMeters - request.desiredAuthority.distanceFromRouteStartMeters) <= Curve.TOLERANCE)
                require(uncoveredResources(route.admission.resources.resources, originals.values.flatten()).isEmpty()) {
                    "output body requires resources outside retained source protection"
                }
                require(preparationsAgree(route.admission.preparations))
                val cursor = (route.admission.routeProgressPath.initializeCursor(frame.tail) as?
                    RouteProgressCursorInitialization.Initialized)?.cursor ?: error("output route must start at actual tail")
                require(sameBody(route, frame.bodyTraversal)) { "output route prefix differs from published body" }
                val clearance = clearances(route)
                val grant = grant(route)
                compiled += FormationOutputRoute(route, grant, cursor,
                    holds.single { it.trainId == identity.trainId }, frame, clearance)
            }
            // Validate the whole batch before publishing any ownership/authority change.
            outputs.putAll(compiled.associateBy { it.grant.trainId })
            lastEpoch = compiled.first().compiled.observation.stamp.physicsEpoch
            compiled.forEach(publish)
            return FormationChangeResult.Accepted(snapshot())
        } catch (failure: Exception) {
            if (outputs.isNotEmpty()) recovery("output publication failed: ${failure.message}")
            return rejected("output binding rejected: ${failure.message}")
        }
    }

    fun suspendReversal(grantId: RouteGrantId): RouteGrantReversalResult {
        val output = outputs.values.singleOrNull { it.grant.grantId === grantId }
            ?: return reversalRejected("unknown formation output grant")
        if (output.reversalFrom != null) return RouteGrantReversalResult.Accepted(output.grant)
        val train = world.getTrain(output.grant.trainId) as? work.art1st.javarailways.core.physics.train.CoreTrain
            ?: return reversalRejected("output train absent")
        val frame = train.publishedFrame
        if (output.resumed || output.detached || pending != null || output.exclusive.isNotEmpty() ||
            !train.hasFormationMovementHold || !stopped(frame) ||
            output.grant.committedAuthority.distanceFromRouteStartMeters > output.compiled.observedHeadMeters + Curve.TOLERANCE ||
            frame.reversed != output.heldFrame.reversed || frame.head != output.heldFrame.head ||
            frame.tail != output.heldFrame.tail || !sameCarPoses(frame, output.heldFrame) ||
            !sameHeldBody(frame.bodyTraversal, output.heldFrame.bodyTraversal))
            return reversalRejected("reversal requires an unchanged held body-only output with no pending work")
        output.reversalFrom = frame
        return RouteGrantReversalResult.Accepted(output.grant)
    }

    fun outputIdentity(trainId: String): FormationTrainIdentity? = outputs[trainId]?.heldFrame?.let {
        FormationTrainIdentity(it.trainId, it.cars.map { car -> car.carId }, it.reversed)
    }

    fun rebaseReversal(submitted: RouteResourceIntent): RouteGrantReversalResult {
        val output = outputs[submitted.trainId] ?: return reversalRejected("unknown formation output")
        val before = output.reversalFrom ?: return reversalRejected("output reversal was not suspended")
        if (submitted.owner != output.grant.owner || submitted.formationContext != null || pending != null)
            return reversalRejected("reversal must preserve the exact output owner without pending work")
        val physical = world.getTrain(submitted.trainId) as? work.art1st.javarailways.core.physics.train.CoreTrain
            ?: return reversalRejected("output train absent")
        val frame = physical.publishedFrame
        if (!physical.hasFormationMovementHold || !exactReversedPose(before, frame))
            return reversalRejected("output is not the exact stopped reversal of the held body")
        var mutationStarted = false
        try {
            val old = context(FormationRoutePurpose.OUTPUT_BODY)
            val changed = FormationTrainIdentity(frame.trainId, frame.cars.map { it.carId }, frame.reversed)
            val request = submitted.copy(formationContext = FormationRouteContext(old.eventId, old.purpose,
                old.sources, old.outputs.map { if (it.trainId == changed.trainId) changed else it },
                old.retainedStableResources))
            val route = checkCompiled(compile(request, null))
            require(kotlin.math.abs(route.observedHeadMeters - request.desiredAuthority.distanceFromRouteStartMeters) <= Curve.TOLERANCE &&
                kotlin.math.abs(request.route.totalLengthMeters - route.observedHeadMeters) <= Curve.TOLERANCE) {
                "reversed output must bind body-only authority and route"
            }
            require(uncoveredResources(route.admission.resources.resources, originals.values.flatten()).isEmpty()) {
                "reversal body requires unretained resources"
            }
            require(preparationsAgree(route.admission.preparations) && sameBody(route, frame.bodyTraversal))
            val cursor = (route.admission.routeProgressPath.initializeCursor(frame.tail) as?
                RouteProgressCursorInitialization.Initialized)?.cursor ?: error("reversed body must begin at actual tail")
            val clearance = clearances(route)
            mutationStarted = true
            output.compiled = route
            output.grant = grant(route, output.grant.grantId)
            output.cursor = cursor
            output.heldFrame = frame
            output.clearances = clearance
            output.reversalFrom = null
            publish(output)
            return RouteGrantReversalResult.Accepted(output.grant)
        } catch (failure: Exception) {
            if (mutationStarted) recovery("reversed output publication failed; protection retained: ${failure.message}")
            return reversalRejected("held output reversal rejected: ${failure.message}")
        }
    }

    private fun exactReversedPose(before: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot,
                                  after: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot): Boolean {
        fun flip(direction: work.art1st.javarailways.core.api.physics.rail.ViewDirection) =
            if (direction == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB)
                work.art1st.javarailways.core.api.physics.rail.ViewDirection.BA else work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
        val reversedBody = before.bodyTraversal.asReversed().map {
            it.copy(facing = flip(it.facing), startArcLengthInAbMeters = it.endArcLengthInAbMeters,
                endArcLengthInAbMeters = it.startArcLengthInAbMeters)
        }
        return stopped(after) && after.reversed != before.reversed &&
            after.head == before.tail.copy(facing = flip(before.tail.facing)) &&
            after.tail == before.head.copy(facing = flip(before.head.facing)) &&
            sameHeldBody(after.bodyTraversal, reversedBody) &&
            sameCarPoses(after, before)
    }

    private fun reversalRejected(detail: String) = RouteGrantReversalResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, detail))

    fun request(submitted: RouteResourceIntent): RouteAdmissionResult {
        val output = outputs[submitted.trainId] ?: return routeRejected("unknown output")
        if (output.detached) return routeRejected("output already uses ordinary admission")
        if (output.reversalFrom != null) return routeRejected("held output reversal is pending")
        val committedRoute = output.compiled.intent.route
        val appendsRoute = submitted.route != committedRoute &&
            isAppendOnlyContinuation(committedRoute, submitted.route) &&
            kotlin.math.abs(committedRoute.totalLengthMeters - output.grant.committedAuthority.distanceFromRouteStartMeters) <= Curve.TOLERANCE
        if (pending != null || submitted.formationContext != null || submitted.owner != output.grant.owner ||
            (submitted.route != committedRoute && !appendsRoute) ||
            submitted.desiredAuthority.distanceFromRouteStartMeters <= output.grant.committedAuthority.distanceFromRouteStartMeters)
            return routeRejected("output extension must preserve owner and complete frozen route")
        val request = submitted.copy(formationContext = context(FormationRoutePurpose.OUTPUT_DEPARTURE))
        var mutationStarted = false
        try {
            val compilation = compileOutput(request, output.grant.committedAuthority.distanceFromRouteStartMeters, output.cursor.state)
            if (compilation is FormationRouteCompilation.Rejected) return RouteAdmissionResult.Rejected(compilation.failure)
            val route = compilation as FormationRouteCompilation.Compiled
            val frame = checkNotNull(world.getTrain(submitted.trainId)?.publishedFrame)
            val expected = checkNotNull(request.formationContext).outputs.single { it.trainId == submitted.trainId }
            require(frame.cars.map { it.carId } == expected.carIds && frame.reversed == expected.reversed && !frame.derailed)
            require(preparationsAgree(route.admission.preparations))
            val clearance = clearances(route)
            // A suffix can extend last use of a still-retained original. Once this output
            // certified a resource clear, a moving sibling may rely on that clearance. Never
            // restore shared use while that sibling is still using its admitted corridor.
            require(originals.values.flatten().all { resource ->
                val previous = output.clearances[resource]
                previous == null || clearance[resource]?.let { it >= previous } == true
            }) { "output continuation shortened retained original release evidence" }
            val conflicts = originals.values.flatten().filter { resource ->
                clearance[resource] != output.clearances[resource] && clear(output, resource) &&
                    outputs.values.any { other -> other !== output && other.resumed && !clear(other, resource) }
            }
            if (conflicts.isNotEmpty()) return RouteAdmissionResult.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.RESOURCE_CONFLICT,
                "output continuation would re-enter protection in use by a moving sibling"))
            val heldBoundaries = boundaries(route, output.exclusive.keys)
            require(output.exclusive.all { (resource, oldBoundary) ->
                heldBoundaries[resource]?.let { it >= oldBoundary } == true
            }) { "output continuation shortened retained exclusive release evidence" }
            val inherited = originals.values.flatten() + output.exclusive.keys
            val delta = uncoveredResources(route.admission.resources.resources, inherited)
            if (delta.isEmpty()) return install(output, route, clearance, emptyList())
            val exists = registry.claimSnapshots().any { it.owner == request.owner }
            val reservation = if (exists) {
                val append = registry.beginAppendClaim(hostOwner, request.owner, delta)
                Triple(append.result, { registry.completeClaimAppend(append) }, { registry.rollbackClaimAppend(append).successful })
            } else {
                val claim = registry.beginAcquireClaim(hostOwner, InfrastructureClaimRequest(request.owner, InfrastructureFootprint(delta)))
                Triple(claim.result, { registry.completeClaimMutation(claim) }, { registry.rollbackClaimMutation(claim).successful })
            }
            if (!reservation.first.successful) return RouteAdmissionResult.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.RESOURCE_CONFLICT, reservation.first.detail.ifBlank { "output resource conflict" },
                reservation.first.blockers.flatMap { blocker -> blocker.overlappingResources.map {
                    RouteAdmissionBlocker.ConflictingResource(it, blocker.owner)
                } }))
            mutationStarted = true
            val start = registry.withClaim(hostOwner, request.owner) {
                actuator.start(route.admission.preparations.filter { it.resource in delta })
            }
            if (start is InfrastructurePreparationStart.Rejected) {
                check(reservation.third())
                return routeRejected("output preparation rejected")
            }
            val admissionHandle = RouteAdmissionHandle()
            pending = Pending(output, request, route, clearance, delta, admissionHandle,
                (start as InfrastructurePreparationStart.Accepted).ticket, reservation.second, reservation.third)
            return checkNotNull(progress(admissionHandle))
        } catch (failure: Exception) {
            if (mutationStarted) recovery("output admission failed with retained protection: ${failure.message}")
            return routeRejected("output admission rejected: ${failure.message}")
        }
    }

    /** Terminal evidence remains readable after recovery; this never advances preparation. */
    fun cachedResult(handle: RouteAdmissionHandle): RouteAdmissionResult? = results[handle]

    fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult? {
        results[handle]?.let { return it }
        val attempt = pending?.takeIf { it.handle === handle } ?: return null
        val result = try {
            when (attempt.ticket.snapshot().state) {
                ResourcePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, attempt.output.grant)
                ResourcePreparationState.READY -> {
                    val request = attempt.request.copy(observationStamp = checkNotNull(latestObservation()).stamp)
                    val route = checkCompiled(compileOutput(request, attempt.output.grant.committedAuthority.distanceFromRouteStartMeters, attempt.output.cursor.state))
                    // A moving tail can legitimately trim the already-protected body prefix.
                    // READY may not introduce any unreserved resource or change new-delta work /
                    // complete-route release evidence. Originals stay suspended while pending.
                    val held = originals.values.flatten() + attempt.output.exclusive.keys + attempt.delta
                    require(uncoveredResources(route.admission.resources.resources, held).isEmpty())
                    require(preparationsAgree(route.admission.preparations) &&
                        route.admission.preparations.filter { it.resource in attempt.delta } ==
                            attempt.compiled.admission.preparations.filter { it.resource in attempt.delta } &&
                        boundaries(route, attempt.delta) == boundaries(attempt.compiled, attempt.delta) &&
                        clearances(route) == attempt.clearances)
                    check(attempt.finalize())
                    install(attempt.output, route, attempt.clearances, attempt.delta)
                }
                ResourcePreparationState.ABORTED_SAFE -> {
                    check(attempt.rollback())
                    RouteAdmissionResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
                        "output preparation safely aborted"))
                }
                ResourcePreparationState.UNSAFE -> error("output preparation unsafe")
            }
        } catch (failure: Exception) {
            recovery("output preparation requires recovery: ${failure.message}")
            routeRejected("output preparation unsafe; resources retained")
        }
        if (result !is RouteAdmissionResult.Pending) {
            pending = null
            results[handle] = result
        }
        return result
    }

    fun resume(grantId: RouteGrantId): FormationChangeResult {
        val output = outputs.values.firstOrNull { it.grant.grantId == grantId } ?: return rejected("unknown output grant")
        if (output.resumed) return FormationChangeResult.Accepted(snapshot())
        if (output.reversalFrom != null) return busy("held output reversal is pending")
        if (pending != null || output.grant.committedAuthority.distanceFromRouteStartMeters <= output.compiled.observedHeadMeters + Curve.TOLERANCE)
            return busy("output has no prepared forward authority")
        // Only one output may initially consume inherited shared protection. The next output
        // waits until the previous output has proved clear on its complete frozen route.
        if (outputs.values.any { other -> other !== output && other.resumed && originals.values.flatten().any { !clear(other, it) } })
            return busy("another output is still using retained shared protection")
        val train = world.getTrain(output.grant.trainId) ?: return rejected("output absent")
        val frame = checkNotNull(train.publishedFrame)
        val held = output.heldFrame
        if (!stopped(frame) || frame.reversed != held.reversed || frame.head != held.head || frame.tail != held.tail ||
            !sameHeldBody(frame.bodyTraversal, held.bodyTraversal) || !sameCarPoses(frame, held))
            return rejected("output no longer matches stopped physical evidence: " +
                "stopped=${stopped(frame)}, direction=${frame.reversed == held.reversed}, " +
                "head=${frame.head == held.head}, tail=${frame.tail == held.tail}, " +
                "body=${sameHeldBody(frame.bodyTraversal, held.bodyTraversal)}, cars=${sameCarPoses(frame, held)}, " +
                "heldBody=${normalizedBody(held.bodyTraversal)}, currentBody=${normalizedBody(frame.bodyTraversal)}")
        if (!world.releaseFormationMovementHold(output.hold)) return rejected("output fence no longer belongs to this event")
        output.resumed = true
        return FormationChangeResult.Accepted(snapshot())
    }

    fun consume(observation: RailwayObservation) {
        if (promoted || observation.stamp.physicsEpoch == lastEpoch) return
        try {
            require(observation.stamp.physicsEpoch > lastEpoch &&
                observation.stamp.worldId == outputs.values.first().compiled.observation.stamp.worldId)
            for (output in outputs.values.filterNot { it.detached }) {
                val frame = checkNotNull(world.getTrain(output.grant.trainId)?.publishedFrame)
                val observed = observation.trains.single { it.trainId == output.grant.trainId }
                val expected = context(FormationRoutePurpose.OUTPUT_BODY).outputs.single { it.trainId == output.grant.trainId }
                output.reversalFrom?.let { before ->
                    require(observed.frameRevision == frame.revision &&
                        (exactReversedPose(before, frame) || stopped(frame) && frame.reversed == before.reversed &&
                            frame.head == before.head && frame.tail == before.tail && sameCarPoses(frame, before) &&
                            sameHeldBody(frame.bodyTraversal, before.bodyTraversal))) { "pending reversed output pose changed" }
                }
                if (output.reversalFrom != null) continue
                require(observed.frameRevision == frame.revision && frame.cars.map { it.carId } == expected.carIds &&
                    frame.reversed == expected.reversed && !frame.derailed && (output.resumed || frame.speedMetersPerSecond == 0.0))
                val progress = output.cursor.update(observed.tail)
                require(progress is RouteProgressCursorUpdate.Resolved) { "output tail left monotonic frozen route" }
                val head = output.cursor.project(observed.head)
                require(head is RouteProgressCursorUpdate.Resolved &&
                    head.state.routeDistanceMeters <= output.grant.committedAuthority.distanceFromRouteStartMeters + Curve.TOLERANCE)
            }
            lastEpoch = observation.stamp.physicsEpoch
            if (pending != null || outputs.values.any { it.reversalFrom != null }) return // Retain complete inherited coverage.
            for ((owner, resources) in originals) {
                val eligible = resources.filter { resource -> outputs.values.all { clear(it, resource) } }
                if (eligible.isNotEmpty()) {
                    check(registry.shrinkClaimWithEvidence(hostOwner, owner, eligible).mutation.successful)
                    resources.removeAll(eligible.toSet())
                }
            }
            for (output in outputs.values.filterNot { it.detached }) {
                val eligible = output.exclusive.filter { (resource, boundary) ->
                    output.cursor.state.routeDistanceMeters >= boundary && bodyClear(output, resource)
                }.keys.toList()
                if (eligible.isNotEmpty()) {
                    check(registry.shrinkClaimWithEvidence(hostOwner, output.grant.owner, eligible).mutation.successful)
                    eligible.forEach { output.exclusive.remove(it) }
                }
            }
            // Whole frozen-route last-use evidence permanently separates this output from
            // the group. Ordinary admission will forbid any return to remaining originals.
            for (output in outputs.values.filterNot { it.detached }) {
                if (output.resumed && originals.values.flatten().all { clear(output, it) }) {
                    detach(output)
                    output.detached = true
                    output.exclusive.clear() // Exact queue ownership moved to ordinary engine.
                }
            }
            if (originals.values.all { it.isEmpty() }) {
                complete(outputs.values.toList())
                promoted = true
            }
        } catch (failure: Exception) { recovery("aggregate output progress failed; remaining claims retained: ${failure.message}") }
    }

    private fun install(output: FormationOutputRoute, route: FormationRouteCompilation.Compiled,
                        clearances: Map<InfrastructureResource, Double?>, delta: Collection<InfrastructureResource>): RouteAdmissionResult {
        val retainedBoundaries = boundaries(route, output.exclusive.keys)
        output.exclusive.keys.toList().forEach { resource ->
            output.exclusive[resource] = checkNotNull(retainedBoundaries[resource])
        }
        val deltaBoundaries = boundaries(route, delta)
        delta.forEach { output.exclusive[it] = checkNotNull(deltaBoundaries[it]) { "new resource has no complete-route clearance" } }
        route.admission.preparations.forEach { preparations[it.resource] = it }
        if (route.intent.route != output.compiled.intent.route) {
            output.cursor.installExtension(route.admission.routeProgressPath)
        }
        output.compiled = route
        output.grant = grant(route, output.grant.grantId)
        output.clearances = clearances
        publish(output)
        return RouteAdmissionResult.Granted(output.grant)
    }

    private fun clear(output: FormationOutputRoute, resource: InfrastructureResource): Boolean {
        if (output.detached) return true
        if (!output.clearances.containsKey(resource)) return false
        val boundary = output.clearances[resource]
        return (boundary == null || output.cursor.state.routeDistanceMeters >= boundary) && bodyClear(output, resource)
    }
    private fun bodyClear(output: FormationOutputRoute, resource: InfrastructureResource): Boolean {
        if (resource !is RailIntervalResource) return true
        val frame = world.getTrain(output.grant.trainId)?.publishedFrame ?: return false
        return frame.bodyTraversal.none { span -> span.railId == resource.railId &&
            minOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters) <= resource.endAbMeters + Curve.TOLERANCE &&
            maxOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters) >= resource.startAbMeters - Curve.TOLERANCE }
    }
    private fun clearances(route: FormationRouteCompilation.Compiled): Map<InfrastructureResource, Double?> {
        val resources = originals.values.flatten()
        val policy = authorize(checkNotNull(route.intent.formationContext), route.intent)
        require(policy is FormationRoutePolicyResult.Accepted) { "provider did not authorize output" }
        val expected = checkNotNull(route.intent.formationContext).retainedStableResources.toSet()
        require(policy.clearances.map { it.resource }.toSet() == expected && policy.clearances.size == expected.size)
        val result = boundaries(route, resources).toMutableMap()
        policy.clearances.forEach { clearance ->
            val distance = clearance.releaseAfterTailPasses?.distanceFromRouteStartMeters
            require(distance == null || distance <= route.intent.route.totalLengthMeters)
            result[clearance.resource] = distance
        }
        require(resources.all { result.containsKey(it) })
        return result
    }
    private fun preparationsAgree(required: List<InfrastructurePreparationRequirement>) = required.all { item ->
        if (item.resource !in preparations) true else when (item) {
            is ResolvedTurnoutSelection -> (preparations[item.resource] as? ResolvedTurnoutSelection)?.let {
                setOf(it.fromPort, it.toPort) == setOf(item.fromPort, item.toPort)
            } == true
            is ResolvedContributedResourcePreparation -> preparations[item.resource] == item
        }
    }
    // Structural publication may split one rail occurrence at car/coupler boundaries; a later
    // stopped epoch compacts those pieces. Preserve order/facing and every non-contiguous revisit.
    private fun normalizedBody(parts: List<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot>) =
        buildList<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot> {
            for (part in parts) {
                if (part.startArcLengthInAbMeters == part.endArcLengthInAbMeters) continue
                val previous = lastOrNull()
                if (previous != null && previous.railId == part.railId && previous.facing == part.facing &&
                    kotlin.math.abs(previous.endArcLengthInAbMeters - part.startArcLengthInAbMeters) <= BODY_COORDINATE_EPSILON_METERS) {
                    removeAt(lastIndex); add(previous.copy(endArcLengthInAbMeters = part.endArcLengthInAbMeters))
                } else add(part)
            }
        }

    private fun sameHeldBody(
        first: List<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot>,
        second: List<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot>
    ): Boolean {
        val a = normalizedBody(first)
        val b = normalizedBody(second)
        // Coupling's cut path and the subsequent stopped placement can differ by floating-point
        // roundoff. Use the existing body-coordinate precision; exact train/car pose is checked separately.
        return a.size == b.size && a.indices.all { index ->
            a[index].railId == b[index].railId && a[index].facing == b[index].facing &&
                kotlin.math.abs(a[index].startArcLengthInAbMeters - b[index].startArcLengthInAbMeters) <= BODY_COORDINATE_EPSILON_METERS &&
                kotlin.math.abs(a[index].endArcLengthInAbMeters - b[index].endArcLengthInAbMeters) <= BODY_COORDINATE_EPSILON_METERS
        }
    }

    private fun sameBody(route: FormationRouteCompilation.Compiled, body: List<work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot>): Boolean {
        val prefix = route.projection.occurrences.mapNotNull { occurrence ->
            val end = minOf(route.observedHeadMeters, occurrence.routeEndMeters)
            if (end <= occurrence.routeStartMeters) null else work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot(
                occurrence.segment.railId, occurrence.segment.facing, occurrence.canonicalStartAbMeters,
                occurrence.canonicalStartAbMeters + (end - occurrence.routeStartMeters) *
                    if (occurrence.segment.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) 1.0 else -1.0)
        }
        val a = normalizedBody(prefix)
        val b = normalizedBody(body)
        return a.size == b.size && a.indices.all { index ->
            a[index].railId == b[index].railId && a[index].facing == b[index].facing &&
                kotlin.math.abs(a[index].startArcLengthInAbMeters - b[index].startArcLengthInAbMeters) <= Curve.TOLERANCE &&
                kotlin.math.abs(a[index].endArcLengthInAbMeters - b[index].endArcLengthInAbMeters) <= Curve.TOLERANCE
        }
    }

    /** Velocity is checked by stopped(), not pose identity: IEEE signed zero is still stopped. */
    private fun sameCarPoses(a: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot,
                             b: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot): Boolean {
        fun pose(x: work.art1st.javarailways.core.api.render.RenderableFrameSnapshot,
                 y: work.art1st.javarailways.core.api.render.RenderableFrameSnapshot): Boolean =
            x.positionX == y.positionX && x.positionY == y.positionY && x.positionZ == y.positionZ &&
                x.directionX == y.directionX && x.directionY == y.directionY && x.directionZ == y.directionZ &&
                x.normalX == y.normalX && x.normalY == y.normalY && x.normalZ == y.normalZ
        return a.cars.size == b.cars.size && a.cars.zip(b.cars).all { (x, y) ->
            x.carId == y.carId && x.lengthMeters == y.lengthMeters && pose(x.state, y.state) &&
                x.bogies.size == y.bogies.size && x.bogies.zip(y.bogies).all { (u, v) -> pose(u, v) }
        }
    }

    private fun stopped(frame: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot): Boolean =
        !frame.derailed && frame.speedMetersPerSecond == 0.0 && frame.cars.all { car ->
            (car.bogies + car.state).all { it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0 }
        }
    private fun grant(route: FormationRouteCompilation.Compiled, grantId: RouteGrantId = RouteGrantId()) = RouteGrantSnapshot(grantId, route.intent.owner,
        route.intent.trainId, route.intent.route.routeId, route.intent.route.revision, route.observation.stamp, route.intent.desiredAuthority)
    private fun snapshot() = FormationChangeSnapshot(handle, context(FormationRoutePurpose.OUTPUT_BODY).eventId,
        if (promoted) FormationChangePhase.COMPLETED else if (outputs.values.any { it.resumed }) FormationChangePhase.OUTPUTS_ACTIVE
        else FormationChangePhase.OUTPUTS_BOUND, context(FormationRoutePurpose.OUTPUT_BODY).sources,
        context(FormationRoutePurpose.OUTPUT_BODY).outputs, grants)
    private fun checkCompiled(result: FormationRouteCompilation) = when (result) {
        is FormationRouteCompilation.Compiled -> result
        is FormationRouteCompilation.Rejected -> error(result.failure.detail)
    }
    private fun busy(detail: String) = FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.REQUEST_BUSY, detail))
    private fun rejected(detail: String) = FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, detail))
    private fun routeRejected(detail: String) = RouteAdmissionResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH, detail))
}

internal class FormationOutputRoute(
    var compiled: FormationRouteCompilation.Compiled,
    var grant: RouteGrantSnapshot,
    var cursor: RouteProgressCursor,
    val hold: FormationMovementHold,
    var heldFrame: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot,
    var clearances: Map<InfrastructureResource, Double?>,
    var reversalFrom: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot? = null,
    var resumed: Boolean = false,
    var detached: Boolean = false,
    val exclusive: MutableMap<InfrastructureResource, Double> = linkedMapOf()
)
