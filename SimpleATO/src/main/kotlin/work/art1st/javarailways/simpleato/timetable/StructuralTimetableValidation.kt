package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.basic.route.toSnapshot
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.StoppedBodyGeometry
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.DepotStablingPoint
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.routing.TerminalRouteMargin
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import kotlin.math.abs

internal data class StructuralSourceCandidateKey(val initialTrainId: String, val initialFrameRevision: Long,
    val originId: String, val lineage: String = "", val parents: StructuralCandidatePair? = null)
internal data class StructuralCandidatePair(val approaching: StructuralSourceCandidateKey, val stationary: StructuralSourceCandidateKey)
internal data class ValidatedStructuralCouplingKey(val eventId: String,
    val approachingCandidate: StructuralSourceCandidateKey, val stationaryCandidate: StructuralSourceCandidateKey,
    val approachingTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY,
    val stationaryTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY)
internal class ValidatedStructuralCoupling(val approachingSource: ValidatedStructuralSource,
    val stationarySource: ValidatedStructuralSource, val outputCandidateKey: StructuralSourceCandidateKey,
    val outputEnvelope: CertifiedStructuralCorridor, val protectedRoute: RouteSnapshot,
    val contactFeasibility: StructuralContactFeasibility) {
    val outputCarIds: List<String> get() = outputEnvelope.memberCarIds
    val outputReversed: Boolean get() = outputEnvelope.reversed
}
internal data class ValidatedStructuralRouteKey(val eventId: String, val outputId: String, val serviceId: String,
    val stepIndex: Int, val sourceCandidate: StructuralSourceCandidateKey, val startFacing: ViewDirection, val targetId: String,
    val address: TimetableStepAddress = TimetableStepAddress(stepIndex), val stoppingOffsetMeters: Double? = 0.0,
    val legIndex: Int = 0, val branchTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY)
/** Exact authored occurrence measured backwards from the aggregate retained end; repeated rails stay distinct. */
internal data class StructuralRouteMarker(val targetId: String, val distanceBeforeRetainedEndMeters: Double) {
    init { require(targetId.isNotBlank()); require(distanceBeforeRetainedEndMeters.isFinite() && distanceBeforeRetainedEndMeters > 0.0) }
}
internal class ValidatedStructuralOutputRoute(val proof: StructuralOutputCorridor, val targetWindow: StructuralEventWindow,
    val operatingTargetRoute: RouteSnapshot,
    val stoppingOffsetFromRetainedEndMeters: Double, carIds: Collection<String>, val expectedReversed: Boolean,
    private val bindings: StructuralRouteBindings,
    intermediateMarkers: Collection<StructuralRouteMarker> = emptyList()) {
    val outputCarIds: List<String> = java.util.List.copyOf(carIds)
    val intermediateMarkers: List<StructuralRouteMarker> = java.util.List.copyOf(intermediateMarkers)
    fun requireCurrent(world: World) = bindings.requireCurrent(world)
}
internal data class ValidatedStructuralSourceKey(val eventId: String, val sourceCandidate: StructuralSourceCandidateKey,
    val branchTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY)
internal class ValidatedStructuralSource(val envelope: CertifiedStructuralCorridor, val window: StructuralEventWindow,
    carIds: Collection<String>, val expectedReversed: Boolean,
    outputCandidateKeys: Map<String, StructuralSourceCandidateKey> = emptyMap()) {
    val sourceCarIds: List<String> = java.util.List.copyOf(carIds)
    val outputCandidateKeys: Map<String, StructuralSourceCandidateKey> = java.util.Map.copyOf(outputCandidateKeys)
}
internal data class ValidatedStructuralStorageKey(val eventId: String, val outputId: String, val serviceId: String,
    val stepIndex: Int, val sourceCandidate: StructuralSourceCandidateKey, val startFacing: ViewDirection,
    val stablingPointRef: StablingPointRef, val branchTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY)
internal class ValidatedStructuralStorageRoute(val movement: ValidatedStructuralOutputRoute,
    point: DepotStablingPoint, val metadata: FormationOutputMetadata) {
    val stablingPoint: DepotStablingPoint = point.copy(allowedTags = java.util.Set.copyOf(point.allowedTags))
}
internal class StructuralTimetableValidation(errors: Collection<String> = emptyList(),
    routes: Map<ValidatedStructuralRouteKey, ValidatedStructuralOutputRoute> = emptyMap(),
    sources: Map<ValidatedStructuralSourceKey, ValidatedStructuralSource> = emptyMap(),
    storageRoutes: Map<ValidatedStructuralStorageKey, ValidatedStructuralStorageRoute> = emptyMap(),
    couplings: Map<ValidatedStructuralCouplingKey, ValidatedStructuralCoupling> = emptyMap()) {
    val couplings: Map<ValidatedStructuralCouplingKey, ValidatedStructuralCoupling> = java.util.Map.copyOf(couplings)
    val errors: List<String> = java.util.List.copyOf(errors)
    val routes: Map<ValidatedStructuralRouteKey, ValidatedStructuralOutputRoute> = java.util.Map.copyOf(routes)
    val sources: Map<ValidatedStructuralSourceKey, ValidatedStructuralSource> = java.util.Map.copyOf(sources)
    val storageRoutes: Map<ValidatedStructuralStorageKey, ValidatedStructuralStorageRoute> = java.util.Map.copyOf(storageRoutes)
}

/** Snapshot-bound finite-candidate proof; no runtime search, hypothetical train frame or nominal output origin. */
internal class StructuralTimetableValidator(private val world: World,
    private val retained: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
    private val retainedSelections: Set<ValidatedStationSelectionKey>,
    private val depotOrigins: (DepotTrainSelector) -> List<String>) {
    private val topology = world.topologyState
    private val restrictions: RoutePlanningRestrictions = world.simpleAtoWorldData.routePlanningRestrictions.compile(
        topology, world.basicWorldData.operatingDirections.overlay(topology))
    private val capturedTrains = world.allTrains.filterIsInstance<CoreTrain>().toList()
    private val frames = capturedTrains.associate { it.id to it.publishedFrame }
    private val candidateFacts = capturedTrains.mapNotNull { train ->
        world.simpleAtoWorldData.candidateFacts(train)?.let { train.id to it }
    }.toMap()
    private val errors = arrayListOf<String>()
    private val routes = linkedMapOf<ValidatedStructuralRouteKey, ValidatedStructuralOutputRoute>()
    private val sources = linkedMapOf<ValidatedStructuralSourceKey, ValidatedStructuralSource>()
    private val storageRoutes = linkedMapOf<ValidatedStructuralStorageKey, ValidatedStructuralStorageRoute>()
    private val couplings = linkedMapOf<ValidatedStructuralCouplingKey, ValidatedStructuralCoupling>()
    private val completedCouplings = hashSetOf<String>()
    private val produced = linkedMapOf<Pair<String, String>, List<Candidate>>()
    private data class Candidate(val key: StructuralSourceCandidateKey, val envelope: CertifiedStructuralCorridor,
        val carIds: List<String>, val window: StructuralEventWindow?, val pointId: String?,
        val output: Pair<String, String>? = null, val classification: FormationOutputMetadata? = null,
        val exactInitialEligible: Boolean = true, val branchTrace: StructuralBranchTrace = StructuralBranchTrace.EMPTY,
        val pendingOffset: Double = 0.0, val pendingStation: TimetableAction.CallStation? = null,
        val pendingStopReverse: Boolean = false)
    private data class Cursor(val service: TimetableService, var index: Int = 0, var candidates: List<Candidate>? = null,
        var failed: Boolean = false)

    fun validate(timetable: Timetable): StructuralTimetableValidation {
        val relevant = timetable.formationEvents.flatMapTo(linkedSetOf()) { event -> event.participantDutyIds +
            event.outputs.mapNotNull { (it.disposition as? FormationOutputDisposition.Duty)?.dutyId } }
        val cursors = timetable.services.filter { it.id in relevant }.map(::Cursor)
        val events = timetable.formationEvents.associateBy { it.id }
        var progress: Boolean
        do {
            progress = false
            for (cursor in cursors) {
                if (cursor.failed || cursor.index >= cursor.service.steps.size) continue
                try {
                    val first = (cursor.service.steps.firstOrNull() as? TimetableStep.At)?.action
                    if (cursor.candidates == null) {
                        if (first is TimetableAction.TakeFromFormationOutput) {
                            val candidates = produced[first.eventId to first.outputId] ?: continue
                            cursor.candidates = candidates
                            cursor.index++
                        } else cursor.candidates = initial(cursor.service, first)
                    }
                    stepLoop@ while (cursor.index < cursor.service.steps.size && !cursor.failed) {
                        val index = cursor.index
                        val step = cursor.service.steps[index]
                        when (step) {
                            is TimetableStep.Check -> cursor.candidates = cursor.candidates!!.map { candidate ->
                                val window = window(step.wayPointId, end(candidate.envelope.retainedPath).facing)
                                require(position(candidate.envelope.retainedPath, window.forwardBoundary) != null) {
                                    "Check eligibility window is not covered by the complete retained source corridor" }
                                candidate.copy(window = window, pointId = step.wayPointId)
                            }
                            is TimetableStep.At -> when (val action = step.action) {
                                is TimetableAction.TakeFromDepot, is TimetableAction.TakeFromWaypoint -> Unit // initial boundary only; normal validator checks placement
                                is TimetableAction.TakeFromFormationOutput -> error("formation acquisition is not the first step")
                                is TimetableAction.DestinationOptions -> cursor.candidates = cursor.candidates!!.map {
                                    it.copy(pendingOffset = action.stoppingOffsetMetersBefore ?: 0.0, pendingStopReverse = action.stopPrograms?.reverseParity() == 1) }
                                is TimetableAction.CallStation -> {
                                    val berth = world.simpleAtoWorldData.stations.berth(action.berthRef)
                                        ?: error("structural station berth is missing")
                                    cursor.candidates = cursor.candidates!!.map { it.copy(pendingStation = action, pendingStopReverse = action.stopPrograms?.reverseParity() == 1) }
                                }
                                is TimetableAction.BeginStationStop -> if (action.stopPrograms.reverseParity() == 1) {
                                    cursor.candidates = cursor.candidates!!.map { it.copy(envelope = it.envelope.reoriented(true), window = null, exactInitialEligible = false) }
                                }
                                TimetableAction.Reverse -> cursor.candidates = cursor.candidates!!.map {
                                    it.copy(envelope = it.envelope.reoriented(true), window = null, exactInitialEligible = false) }
                                is TimetableAction.GoTo, is TimetableAction.GoVia -> {
                                    val targets = if (action is TimetableAction.GoTo) listOf(action.wayPointId) else (action as TimetableAction.GoVia).wayPointIds
                                    cursor.candidates = cursor.candidates!!.map { move(cursor, index, it, targets) }
                                }
                                is TimetableAction.Split -> {
                                    val event = events[action.eventId] as? TimetableFormationEvent.Split
                                        ?: error("split event declaration is missing")
                                    val children = event.outputs.associate { it.id to arrayListOf<Candidate>() }
                                    for (candidate in cursor.candidates!!) {
                                        val eligible = requireNotNull(candidate.window) { "split source has no explicit checked/stopped marker eligibility" }
                                        val outputKeys = linkedMapOf<String, StructuralSourceCandidateKey>()
                                        val units = world.simpleAtoWorldData.depots.fixedUnitsForFormation(candidate.carIds)
                                        require(event.firstUnitCount < units.size) { "HEAD partition must leave complete units in both outputs" }
                                        val cut = if (candidate.envelope.reversed) units.size - event.firstUnitCount else event.firstUnitCount
                                        val carCut = units.take(cut).sumOf { it.carIds.size }
                                        val stableFirst = candidate.carIds.take(carCut)
                                        val stableRest = candidate.carIds.drop(carCut)
                                        val firstCars = if (candidate.envelope.reversed) stableRest else stableFirst
                                        val restCars = if (candidate.envelope.reversed) stableFirst else stableRest
                                        for ((output, cars) in listOf(event.first to firstCars, event.rest to restCars)) {
                                            val childKey = candidate.key.copy(lineage = candidate.key.lineage + outputLineage(event.id, output.id, candidate.branchTrace))
                                            outputKeys[output.id] = childKey
                                            children.getValue(output.id) += candidate.copy(
                                                key = childKey,
                                                envelope = candidate.envelope.splitOutput(cars),
                                                carIds = java.util.List.copyOf(cars), window = null, pointId = null,
                                                output = event.id to output.id, exactInitialEligible = false,
                                                classification = candidate.classification?.let { FormationOutputMetadata.split(it,
                                                    candidate.carIds, cars, requireNotNull(candidate.envelope.geometry).configuredLengths(candidate.carIds)) })
                                        }
                                        sources[ValidatedStructuralSourceKey(event.id, candidate.key, candidate.branchTrace)] =
                                            ValidatedStructuralSource(candidate.envelope, eligible, candidate.carIds, candidate.envelope.reversed, outputKeys)
                                    }
                                    event.outputs.forEach { produced[event.id to it.id] = children.getValue(it.id) }
                                    cursor.candidates = event.outputs.singleOrNull {
                                        it.disposition == FormationOutputDisposition.Duty(cursor.service.id)
                                    }?.let { children.getValue(it.id) } ?: emptyList()
                                }
                                is TimetableAction.Conditional -> cursor.candidates = branch(cursor, cursor.candidates!!,
                                    TimetableStepAddress(index), action)
                                is TimetableAction.SelectStop -> cursor.candidates = selectStop(cursor, cursor.candidates!!,
                                    TimetableStepAddress(index), action)
                                is TimetableAction.Couple -> break@stepLoop
                                is TimetableAction.Extension -> cursor.candidates = cursor.candidates!!.map {
                                    it.copy(exactInitialEligible = false) }
                                is TimetableAction.StoreAtDepot -> {
                                    cursor.candidates!!.filter { it.output != null }.forEach { candidate ->
                                        store(cursor, index, candidate, action)
                                    }
                                }
                                else -> Unit
                            }
                        }
                        cursor.index++
                        progress = true
                    }
                } catch (failure: Exception) {
                    errors += "[${timetable.id}/${cursor.service.id}] step#${cursor.index} formation output has no retained onward-route proof: ${failure.message ?: failure.javaClass.simpleName}"
                    cursor.failed = true
                    progress = true
                }
            }
            for (event in timetable.formationEvents.filterIsInstance<TimetableFormationEvent.Couple>()) {
                if (event.id in completedCouplings) continue
                val participants = event.participantDutyIds.map { id -> cursors.single { it.service.id == id } }
                if (participants.any { it.failed || it.candidates == null ||
                    (it.service.steps.getOrNull(it.index) as? TimetableStep.At)?.action != TimetableAction.Couple(event.id) }) continue
                try {
                    val moving = participants.single { it.service.id == event.approachingDutyId }
                    val stationary = participants.single { it.service.id == event.stationaryDutyId }
                    require(moving.candidates!!.size.toLong() * stationary.candidates!!.size <= 4096) {
                        "finite coupling-pair proof exceeds 4096 states"
                    }
                    val outputs = moving.candidates!!.flatMap { a -> stationary.candidates!!.filter { b -> a.branchTrace.compatibleWith(b.branchTrace) }.map { b -> couple(event, a, b) } }
                    require(outputs.isNotEmpty()) { "no finite disjoint source pair" }
                    produced[event.id to event.output.id] = outputs
                    for (participant in participants) {
                        participant.candidates = if (event.output.disposition == FormationOutputDisposition.Duty(participant.service.id)) outputs else emptyList()
                        participant.index++
                    }
                } catch (failure: Exception) {
                    errors += "[${timetable.id}] formation event ${event.id} contact feasibility not certified: ${failure.message ?: failure.javaClass.simpleName}"
                    participants.forEach { it.failed = true }
                }
                completedCouplings += event.id
                progress = true
            }
        } while (progress)
        cursors.filter { !it.failed && it.index < it.service.steps.size }.forEach {
            errors += "[${timetable.id}/${it.service.id}] formation output has no producer corridor proof" }
        if (world.topologyState !== topology) errors += "structural validation topology changed"
        return StructuralTimetableValidation(errors, routes, sources, storageRoutes, couplings)
    }

    private fun couple(event: TimetableFormationEvent.Couple, a: Candidate, b: Candidate): Candidate {
        require(a.carIds.toSet().intersect(b.carIds.toSet()).isEmpty()) { "coupling participants share a physical asset" }
        require(a.pointId == event.rendezvous.approachDestinationId && b.pointId == event.rendezvous.stationaryDestinationId) {
            "participant stopped markers do not match the declared rendezvous" }
        val aw = requireNotNull(a.window) { "approaching source lacks stopped event eligibility" }
        val bw = requireNotNull(b.window) { "stationary source lacks stopped event eligibility" }
        val movingEnd = if (a.envelope.reversed) TrainEndSide.B else TrainEndSide.A
        val stationaryHead = if (b.envelope.reversed) TrainEndSide.B else TrainEndSide.A
        val stationaryEnd = if (event.stationaryEnd == FormationStationaryEnd.HEAD) stationaryHead
            else if (stationaryHead == TrainEndSide.A) TrainEndSide.B else TrainEndSide.A
        val incoming = if (movingEnd == stationaryEnd) b.carIds.asReversed() else b.carIds
        val cars = if (movingEnd == TrainEndSide.B) a.carIds + incoming else incoming + a.carIds
        world.simpleAtoWorldData.depots.fixedUnitsForFormation(cars)
        world.simpleAtoWorldData.depots.validateFixedUnitCoupling(cars)
        val asource = ValidatedStructuralSource(a.envelope, aw, a.carIds, a.envelope.reversed)
        val bsource = ValidatedStructuralSource(b.envelope, bw, b.carIds, b.envelope.reversed)
        val contact: StructuralContactFeasibility
        val envelope: CertifiedStructuralCorridor
        if (a.exactInitialEligible && b.exactInitialEligible) {
            val af = a.envelope.initialFrames.single()
            val bf = b.envelope.initialFrames.single()
            val inspected = world.inspectCouplingApproach(CouplingApproachRequest(af.trainId, movingEnd, bf.trainId, stationaryEnd))
            require(inspected is CouplingApproachInspection.Accepted) {
                (inspected as? CouplingApproachInspection.Rejected)?.reason ?: "host contact inspection rejected" }
            val proof = inspected.proof
            contact = ExactInitialStructuralContact.capture(world, topology, proof, asource, bsource)
            envelope = CertifiedStructuralCorridor.fromCoupling(a.envelope, b.envelope, proof, cars, restrictions)
        } else {
            val result = CertifiedStructuralCorridor.fromStraightCoupling(a.envelope, b.envelope, aw, bw,
                event.stationaryEnd == FormationStationaryEnd.HEAD, cars, movingEnd == stationaryEnd, restrictions)
            envelope = result.first
            contact = StraightWindowStructuralContact(world, topology, result.second, asource, bsource, movingEnd, stationaryEnd)
        }
        val mergedTrace = a.branchTrace.merge(b.branchTrace)
        val key = a.key.copy(lineage = a.key.lineage + outputLineage(event.id, event.output.id, mergedTrace),
            parents = StructuralCandidatePair(a.key, b.key))
        sources[ValidatedStructuralSourceKey(event.id, a.key, a.branchTrace)] = asource
        sources[ValidatedStructuralSourceKey(event.id, b.key, b.branchTrace)] = bsource
        couplings[ValidatedStructuralCouplingKey(event.id, a.key, b.key, a.branchTrace, b.branchTrace)] = ValidatedStructuralCoupling(
            asource, bsource, key, envelope, envelope.retainedPath, contact)
        val classification = if (a.classification != null && b.classification != null)
            FormationOutputMetadata.combine(listOf(a.classification, b.classification), listOf(a.carIds, b.carIds), cars,
                requireNotNull(a.envelope.geometry).configuredLengths(a.carIds) + requireNotNull(b.envelope.geometry).configuredLengths(b.carIds))
            else null
        return Candidate(key, envelope, cars, null, null, event.id to event.output.id, classification, false, mergedTrace)
    }

    private var branchProofStates = 0
    private fun branch(cursor: Cursor, incoming: List<Candidate>, address: TimetableStepAddress,
        action: TimetableAction.Conditional, depth: Int = 0): List<Candidate> {
        require(depth <= 32) { "structural branch depth exceeds 32" }
        val outputs = arrayListOf<Candidate>()
        for ((arm, steps) in listOf(TimetableBranchArm.THEN to action.thenSteps, TimetableBranchArm.ELSE to action.elseSteps)) {
            var candidates = incoming.map { it.copy(branchTrace = it.branchTrace.record(cursor.service.id, address, arm)) }
            for ((index, step) in steps.withIndex()) {
                val childAddress = address.child(arm, index)
                branchProofStates += candidates.size
                require(branchProofStates <= 4096) { "finite structural branch proof exceeds 4096 states" }
                candidates = when (val child = step.action) {
                    is TimetableAction.Conditional -> branch(cursor, candidates, childAddress, child, depth + 1)
                    is TimetableAction.GoTo -> candidates.map { move(cursor, address.topLevelIndex, it, listOf(child.wayPointId), childAddress) }
                    is TimetableAction.GoVia -> candidates.map { move(cursor, address.topLevelIndex, it, child.wayPointIds, childAddress) }
                    TimetableAction.Reverse -> candidates.map { it.copy(envelope = it.envelope.reoriented(true),
                        window = null, exactInitialEligible = false) }
                    is TimetableAction.DestinationOptions -> candidates.map { it.copy(pendingOffset = child.stoppingOffsetMetersBefore ?: 0.0, pendingStopReverse = child.stopPrograms?.reverseParity() == 1) }
                    is TimetableAction.CallStation -> candidates.map { it.copy(pendingStation = child, pendingStopReverse = child.stopPrograms?.reverseParity() == 1) }
                    is TimetableAction.SelectStop -> selectStop(cursor, candidates, childAddress, child)
                    is TimetableAction.Extension -> candidates.map { it.copy(exactInitialEligible = false) }
                    is TimetableAction.OpenDoors, TimetableAction.CloseDoors, is TimetableAction.SetDisplay, is TimetableAction.ResetDisplay, is TimetableAction.Stop, TimetableAction.WaitUntil -> candidates
                    else -> error("unsupported structural branch action at $childAddress: ${child.javaClass.simpleName}")
                }
            }
            outputs += candidates
            require(outputs.size <= 4096) { "finite structural branch successor set exceeds 4096 states" }
        }
        return outputs
    }

    private fun selectStop(cursor: Cursor, incoming: List<Candidate>, address: TimetableStepAddress,
        action: TimetableAction.SelectStop): List<Candidate> {
        require(incoming.size.toLong() * action.selection.candidates.size <= 4096) {
            "finite structural selected-stop proof exceeds 4096 candidates"
        }
        return incoming.flatMap { candidate ->
            require(candidate.pendingStation == null && candidate.pendingOffset == 0.0) {
                "selected stop cannot interrupt structural destination navigation"
            }
            val choices = action.selection.candidates.filter { selected ->
                ValidatedStationSelectionKey(cursor.service.id, address, candidate.pointId, selected.berthId) in retainedSelections
            }
            require(choices.isNotEmpty()) { "source has no selected-stop candidate with a validated continuation" }
            choices.map { selected ->
                val call = TimetableAction.CallStation(action.selection.stationId.value, selected.berthId,
                    action.minimumDwellTicks, action.stoppingOffsetMetersBefore, action.doorSideOverride)
                val berth = requireNotNull(world.simpleAtoWorldData.stations.berth(call.berthRef)) { "selected berth is missing" }
                require(berth.lifecycleState == work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE) {
                    "selected berth is removed"
                }
                val prospective = candidate.copy(pendingStation = call,
                    branchTrace = candidate.branchTrace.recordStop(cursor.service.id, address, selected.berthId))
                val target = work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds.stationBerth(
                    action.selection.stationId, selected.berthId)
                move(cursor, address.topLevelIndex, prospective, listOf(target), address)
            }
        }
    }

    private fun outputLineage(eventId: String, outputId: String, trace: StructuralBranchTrace): String {
        val decisions = trace.lineage()
        val base = "${eventId.length}:$eventId${outputId.length}:$outputId;"
        return if (decisions.isEmpty()) base else base + "${decisions.length}:$decisions;"
    }

    private fun initial(service: TimetableService, action: TimetableAction?): List<Candidate> {
        val origins = when (action) {
            is TimetableAction.TakeFromWaypoint -> action.wayPointIds
            is TimetableAction.TakeFromDepot -> depotOrigins(action.selector)
            else -> listOfNotNull(service.steps.filterIsInstance<TimetableStep.Check>().firstOrNull()?.wayPointId)
        }
        require(origins.isNotEmpty()) { "source has no finite initial acquisition/check origins" }
        return origins.flatMap { origin ->
            val point = requireNotNull(world.basicWorldData.wayPoints[origin]) { "source origin does not exist: $origin" }
            val possible = when (val selector = service.trainSelector) {
                is TrainSelector.TrainId -> {
                    // Match Dispatcher/World lookup: the authored selector is a train name,
                    // while captured frames are keyed by the resolved physical container ID.
                    val selected = world.getTrain(selector.trainId)
                    listOf(requireNotNull(selected?.let { frames[it.id] }) {
                        "initial source frame is missing: ${selector.trainId}" })
                }
                TrainSelector.AnyTrain -> frames.values.filter { frame ->
                    action !is TimetableAction.TakeFromDepot || action.selector.matches(candidateFacts[frame.trainId], frame.cars.size)
                }
            }.filter { frame ->
                frame.head.railId == point.railId && abs(frame.head.arcLengthInAbMeters - point.arcLength) <= TIMETABLE_ORIGIN_TOLERANCE_METERS &&
                    world.simpleAtoWorldData.destinations[origin]?.approachDirection?.let { it == frame.head.facing } != false
            }
            require(possible.isNotEmpty()) { "finite source origin $origin has no full initial-body witness; future assets require revalidation" }
            possible.map { frame ->
                val eligibility = window(origin, frame.head.facing)
                require(eligibility.acceptsSource(frame, topology)) { "source must be stopped and aligned at $origin" }
                val to = eligibility.forwardBoundary
                val headArc = viewArc(frame.head); val endArc = viewArc(to)
                require(endArc >= headArc)
                val incoming = RouteSnapshot("structural-initial:${service.id}:$origin", 0, topology.revision,
                    listOf(RouteSegmentSnapshot(to.railId, to.facing, headArc, endArc)))
                Candidate(StructuralSourceCandidateKey(frame.trainId, frame.revision, origin),
                    CertifiedStructuralCorridor.fromInitial(frame, incoming, topology,
                        StoppedBodyGeometry.capture(requireNotNull(world.getTrain(frame.trainId)))), frame.cars.map { it.carId }, eligibility, origin,
                    classification = candidateFacts[frame.trainId]?.let {
                        FormationOutputMetadata.source(it, frame.cars.map { car -> car.carId },
                            frame.cars.associate { car -> car.carId to car.lengthMeters }) })
            }
        }
    }

    private fun move(cursor: Cursor, index: Int, candidate: Candidate, targets: List<String>,
        address: TimetableStepAddress = TimetableStepAddress(index)): Candidate {
        require(targets.isNotEmpty())
        val base = candidate.envelope.retainedPath
        var effectiveOffset = candidate.pendingOffset
        val finalTarget = requireNotNull(world.basicWorldData.wayPoints[targets.last()])
        val stationAction = candidate.pendingStation
        val stationBerth = stationAction?.let { requireNotNull(world.simpleAtoWorldData.stations.berth(it.berthRef)) }
        val finalBerth = stationBerth?.takeIf {
            it.location.rail.id == finalTarget.railId && abs(it.location.arcLengthIn(ViewDirection.AB) - finalTarget.arcLength) <= 1e-7 }
        if (finalBerth != null) effectiveOffset = stationAction.stoppingOffsetMetersBefore ?: finalBerth.stoppingOffsetMetersBefore
        require(!candidate.pendingStopReverse || stationAction == null || finalBerth != null) {
            "station reversal must terminate its incoming movement before an onward mission"
        }
        val intermediatePositions = arrayListOf<Pair<String, Double>>()
        val selected = if (candidate.output == null) {
            val origin = requireNotNull(candidate.pointId) { "source movement lost its checked incoming origin" }
            var direction = end(base).facing
            val legs = targets.mapIndexed { legIndex, target ->
                val alternatives = retained.filterKeys { it.serviceId == cursor.service.id && it.address == address &&
                    it.originId == origin && it.legIndex == legIndex && it.targetId == target &&
                    it.stoppingOffsetMeters == if (legIndex == targets.lastIndex) effectiveOffset else null }
                val choices = alternatives.values.filter { it.route.segments.first().railViewRef.direction == direction }
                    .distinctBy { it.route to it.stoppingOffsetMeters }
                require(choices.size == 1) { "incoming origin $origin lacks one exact retained directional leg to $target" }
                if (legIndex == targets.lastIndex) effectiveOffset = choices.single().stoppingOffsetMeters ?: effectiveOffset
                choices.single().route.toSnapshot("structural-incoming:${cursor.service.id}:$index:$legIndex", 0, topology.revision)
                    .also { direction = end(it).facing }
            }
            val mission = route("structural-incoming:${cursor.service.id}:$index", legs.flatMap { it.segments })
            val outer = end(base)
            val at = position(mission, outer)
            require(at != null) { "selected incoming mission does not preserve the entire source corridor endpoint" }
            val missionStartInBase = position(base, start(mission))
            require(missionStartInBase != null && samePath(slice(base, missionStartInBase, base.totalLengthMeters), slice(mission, 0.0, at))) {
                "selected incoming mission differs from mandatory source prefix" }
            route(mission.routeId, base.segments + slice(mission, at, mission.totalLengthMeters).segments)
        } else {
            var result = base
            for ((targetIndex, target) in targets.withIndex()) {
                val point = requireNotNull(world.basicWorldData.wayPoints[target]) { "onward target is missing: $target" }
                val last = end(result)
                val planned = TopologyRoutePlanner.plan(TopologyRoutePlanningInput(topology, restrictions,
                    OperationalRouteLocation(last.railId, last.arcLengthInAbMeters), OperationalRouteLocation(point.railId, point.arcLength),
                    "structural-onward:${cursor.service.id}:$index:$target", 0, last.facing,
                    world.simpleAtoWorldData.destinations[target]?.approachDirection,
                    requireUniquePath = true, blockedLocations = OperationalRoutePlanner(world).waypointBlockers(
                        targets.getOrNull(targetIndex - 1) ?: candidate.pointId, target
                    )))
                require(planned is TopologyRoutePlanningResult.Planned) {
                    (planned as? TopologyRoutePlanningResult.Rejected)?.detail ?: "onward route was not planned" }
                result = route(planned.route.routeId, result.segments + planned.route.segments)
                if (targetIndex < targets.lastIndex) intermediatePositions += target to result.totalLengthMeters
            }
            result
        }
        if (stationBerth != null) {
            require(stationBerth.lifecycleState == work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE) {
                "structural station berth is removed"
            }
            val facing = stationBerth.approachDirection
            val occurrences = (facing?.let(::listOf) ?: ViewDirection.entries).mapNotNull { direction ->
                position(selected, RailPositionSnapshot(stationBerth.location.rail.id, direction,
                    stationBerth.location.arcLengthIn(ViewDirection.AB)))
            }.distinct()
            require(occurrences.size == 1) { "station berth has no unique directed occurrence on the retained structural route" }
            val berthStop = occurrences.single() - (stationAction.stoppingOffsetMetersBefore ?: stationBerth.stoppingOffsetMetersBefore)
            require(berthStop - stationBerth.positionToleranceMeters > base.totalLengthMeters) {
                "station stopping window is not beyond the whole structural source interval"
            }
            if (finalBerth == null) require(berthStop + stationBerth.positionToleranceMeters < selected.totalLengthMeters - effectiveOffset) {
                "interior station stopping window overlaps the final destination"
            }
        }
        val stop = pointAt(selected, selected.totalLengthMeters - effectiveOffset)
        val eligible = StructuralEventWindow.at(stop, topology, finalBerth?.positionToleranceMeters)
        val ahead = abs(eligible.forwardBoundary.arcLengthInAbMeters - stop.arcLengthInAbMeters)
        val bound = selected.totalLengthMeters - effectiveOffset + ahead
        val bounded = if (bound <= selected.totalLengthMeters) slice(selected, 0.0, bound) else {
            val extended = TerminalRouteMargin.propose(topology, restrictions, selected, bound)
            require(extended.failure == null) { extended.failure.orEmpty() }
            extended.route
        }
        require(end(bounded).let { it.railId == eligible.forwardBoundary.railId && it.facing == eligible.forwardBoundary.facing &&
            abs(it.arcLengthInAbMeters - eligible.forwardBoundary.arcLengthInAbMeters) <= 1e-7 }) { "selected stop bound differs from same-rail eligibility window" }
        val proof = StructuralOutputCorridor.prove(candidate.envelope, bounded, restrictions, false, ahead)
        if (candidate.output != null) {
            var previousMarker = base.totalLengthMeters
            for ((_, marker) in intermediatePositions) {
                require(marker > previousMarker + 1e-7) { "intermediate marker must follow the whole source corridor and preceding marker" }
                require(marker < selected.totalLengthMeters - effectiveOffset - ahead) {
                    "intermediate marker must precede the entire final stopping window"
                }
                previousMarker = marker
            }
            val markers = intermediatePositions.map { (target, distance) ->
                StructuralRouteMarker(target, bounded.totalLengthMeters - distance)
            }
            val key = ValidatedStructuralRouteKey(candidate.output.first, candidate.output.second, cursor.service.id,
                index, candidate.key, start(base).facing, targets.last(), address, effectiveOffset, branchTrace = candidate.branchTrace)
            require(routes.put(key, ValidatedStructuralOutputRoute(proof, eligible, selected, ahead, candidate.carIds,
                candidate.envelope.reversed, StructuralRouteBindings.capture(world,
                    listOfNotNull(candidate.pointId) + targets, stationAction?.berthRef), markers)) == null) { "duplicate structural route proof" }
        }
        val arrived = proof.outputEnvelope(candidate.carIds).narrowedAt(eligible)
        return candidate.copy(envelope = if (candidate.pendingStopReverse) arrived.reoriented(true) else arrived,
            window = if (candidate.pendingStopReverse) null else eligible, pointId = targets.last(),
            exactInitialEligible = false, pendingOffset = 0.0, pendingStation = null, pendingStopReverse = false)
    }

    private fun store(cursor: Cursor, index: Int, candidate: Candidate, action: TimetableAction.StoreAtDepot) {
        val output = requireNotNull(candidate.output)
        val metadata = requireNotNull(candidate.classification) {
            "formation output storage requires registered source classification; physical-only storage is unsupported" }
        val allPoints = world.simpleAtoWorldData.depots.stablingPoints(action.depotId)
        require(allPoints.isNotEmpty()) { "storage Depot has no StablingPoint: ${action.depotId}" }
        val missing = action.stablingPointIds - allPoints.map { it.point.ref.pointId }.toSet()
        require(missing.isEmpty()) { "storage StablingPoint does not exist: ${missing.sorted()}" }
        // Availability is not a static route filter: any compatible point may become free before execution.
        val points = allPoints.filter { (action.stablingPointIds.isEmpty() || it.point.ref.pointId in action.stablingPointIds) &&
            metadata.accepts(it.point) }.sortedBy { it.point.ref.pointId }
        require(points.isNotEmpty()) { "storage Depot has no classification/length-compatible StablingPoint: ${action.depotId}" }
        val byTarget = points.groupBy { it.point.destinationId }
        for ((target, targetPoints) in byTarget) {
            move(cursor, index, candidate, listOf(target))
            val key = ValidatedStructuralRouteKey(output.first, output.second, cursor.service.id, index,
                candidate.key, start(candidate.envelope.retainedPath).facing, target, branchTrace = candidate.branchTrace)
            val movement = routes.getValue(key)
            for (point in targetPoints) {
                val storageKey = ValidatedStructuralStorageKey(output.first, output.second, cursor.service.id, index,
                    candidate.key, key.startFacing, point.point.ref, candidate.branchTrace)
                require(storageRoutes.put(storageKey, ValidatedStructuralStorageRoute(movement, point.point, metadata)) == null) {
                    "duplicate structural storage route proof" }
            }
        }
    }

    private fun window(pointId: String, facing: ViewDirection): StructuralEventWindow {
        val point = requireNotNull(world.basicWorldData.wayPoints[pointId]) { "checked structural marker is missing: $pointId" }
        return StructuralEventWindow.at(RailPositionSnapshot(point.railId, facing, point.arcLength), topology)
    }
    private fun length(rail: java.util.UUID): Double = topology.railProjection[rail]?.singleOrNull()?.let {
        abs(it.railEndAbMeters - it.railStartAbMeters) } ?: error("structural rail has no unique physical projection")
    private fun viewArc(point: RailPositionSnapshot) = if (point.facing == ViewDirection.AB) point.arcLengthInAbMeters else length(point.railId) - point.arcLengthInAbMeters
    private fun start(route: RouteSnapshot) = route.segments.first().let { RailPositionSnapshot(it.railId, it.facing,
        if (it.facing == ViewDirection.AB) it.startArcLengthMeters else length(it.railId) - it.startArcLengthMeters) }
    private fun end(route: RouteSnapshot) = route.segments.last().let { RailPositionSnapshot(it.railId, it.facing,
        if (it.facing == ViewDirection.AB) it.endArcLengthMeters else length(it.railId) - it.endArcLengthMeters) }
    private fun route(id: String, segments: List<RouteSegmentSnapshot>) = RouteSnapshot(id, 0, topology.revision, segments)
    private fun pointAt(route: RouteSnapshot, distance: Double): RailPositionSnapshot {
        require(distance >= 0.0 && distance <= route.totalLengthMeters) { "stop offset is outside selected route" }
        if (distance == route.totalLengthMeters) return end(route)
        var before = 0.0
        for (segment in route.segments) {
            if (before + segment.lengthMeters >= distance) {
                val arc = if (distance == before + segment.lengthMeters) segment.endArcLengthMeters else segment.startArcLengthMeters + (distance - before)
                return RailPositionSnapshot(segment.railId, segment.facing, if (segment.facing == ViewDirection.AB) arc else length(segment.railId) - arc)
            }
            before += segment.lengthMeters
        }
        return end(route)
    }
    private fun position(route: RouteSnapshot, point: RailPositionSnapshot): Double? {
        var before = 0.0
        val found = arrayListOf<Double>()
        for (segment in route.segments) {
            if (segment.railId == point.railId && segment.facing == point.facing) {
                val arc = viewArc(point)
                if (arc >= segment.startArcLengthMeters && arc <= segment.endArcLengthMeters) found += before + arc - segment.startArcLengthMeters
            }
            before += segment.lengthMeters
        }
        return found.firstOrNull()?.takeIf { first -> found.all { abs(it - first) <= 1e-7 } }
    }
    private fun slice(route: RouteSnapshot, from: Double, to: Double): RouteSnapshot {
        require(from >= 0.0 && to >= from && to <= route.totalLengthMeters)
        var before = 0.0
        return this.route(route.routeId, route.segments.mapNotNull { segment ->
            val lo = maxOf(from, before); val hi = minOf(to, before + segment.lengthMeters)
            val result = if (hi > lo || segment.lengthMeters == 0.0 && before in from..to)
                segment.copy(startArcLengthMeters = segment.startArcLengthMeters + (lo - before),
                    endArcLengthMeters = if (hi == before + segment.lengthMeters) segment.endArcLengthMeters else segment.startArcLengthMeters + (hi - before)) else null
            before += segment.lengthMeters
            result
        })
    }
    private fun samePath(a: RouteSnapshot, b: RouteSnapshot): Boolean {
        fun normalized(route: RouteSnapshot): List<RouteSegmentSnapshot> = buildList {
            for (segment in route.segments.filter { it.lengthMeters > 0.0 }) {
                val last = lastOrNull()
                if (last != null && last.railId == segment.railId && last.facing == segment.facing &&
                    abs(last.endArcLengthMeters - segment.startArcLengthMeters) <= 1e-7) {
                    removeAt(lastIndex); add(last.copy(endArcLengthMeters = segment.endArcLengthMeters))
                } else add(segment)
            }
        }
        val x = normalized(a); val y = normalized(b)
        return x.size == y.size && x.zip(y).all { (first, second) -> first.railId == second.railId && first.facing == second.facing &&
            abs(first.startArcLengthMeters - second.startArcLengthMeters) <= 1e-7 && abs(first.endArcLengthMeters - second.endArcLengthMeters) <= 1e-7 }
    }
}
