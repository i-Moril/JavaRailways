package work.art1st.javarailways.simpleato.coupling

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.*
import work.art1st.javarailways.simpleato.admission.RouteAuthorityAdmissionClient
import work.art1st.javarailways.simpleato.api.coupling.*
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.routing.*
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import java.util.UUID
import kotlin.math.abs

/** Ordinary unassigned trains use Basic's real contact and resource lifecycle directly. */
internal class CouplingModeRuntime(private val world: World, private val settings: CouplingModeSettings,
    private val contextProvider: TimetableRouteAdmissionContextProvider,
    private val controlReleased: (Train) -> Unit = {}) : CouplingModeService {
    private class Source(val train: Train, val controller: TrainController?, val owner: InfrastructureOwner,
        val route: RouteSnapshot, var hold: AutoCloseable? = null) {
        var grant: RouteGrantSnapshot? = null
        var pending: RouteAdmissionHandle? = null
    }
    private class Session(val id: String, val sources: List<Source>, val admission: RouteResourceAdmissionService) {
        val moving get() = sources[0]
        val target get() = sources[1]
        var phase = CouplingModePhase.PREPARING
        var detail: String? = null
        var handle: FormationChangeHandle? = null
        var proof: CouplingApproachProof? = null
        var contactRoute: RouteSnapshot? = null
        var contactPending: RouteAdmissionHandle? = null
        var contactReady = false
        var outputGrant: RouteGrantSnapshot? = null
        fun snapshot() = CouplingModeSnapshot(id, moving.train.id, target.train.id, phase, detail)
    }
    private val sessions = linkedMapOf<String, Session>()
    private val data get() = world.simpleAtoWorldData

    override fun status(trainId: String): CouplingModeSnapshot? =
        sessions.values.firstOrNull { active(it) && it.sources.any { source -> source.train.id == trainId } }?.snapshot()
            ?: sessions[trainId]?.snapshot()

    override fun enter(approachingTrainId: String, stationaryTrainId: String): CouplingModeEntryResult {
        val inspected = world.inspectCouplingModeEntry(approachingTrainId, stationaryTrainId, settings.maximumEntryGapMeters)
        if (inspected is CouplingApproachInspection.Rejected) return CouplingModeEntryResult.Rejected(inspected.reason)
        val context = contextProvider.current() ?: return CouplingModeEntryResult.Rejected("no coherent railway observation is available")
        if (data.atoCoordinator.isStalled) return CouplingModeEntryResult.Rejected("the railway world is stalled")
        val ids = setOf(approachingTrainId, stationaryTrainId)
        if (sessions.values.any { active(it) && it.sources.any { source -> source.train.id in ids } })
            return CouplingModeEntryResult.Rejected("a train already participates in a coupling operation")
        val depots = data.depots
        if (depots.allFixedUnitAssignments().any { it.trainId in ids } ||
            depots.heldFormationOutputs().any { it.owner.trainId in ids } ||
            depots.unresolvedFormationChanges().any { change ->
                (change.sources + change.outputs.map { it.owner }).any { it.trainId in ids }
            }) return CouplingModeEntryResult.Rejected("a train belongs to another operation; use its timetable coupling action")
        val trains = listOf(checkNotNull(world.getTrain(approachingTrainId)), checkNotNull(world.getTrain(stationaryTrainId)))
        if (trains.any { (it.controller as? SimpleATO)?.currentStationCall != null })
            return CouplingModeEntryResult.Rejected("a train still has an active station call")
        val id = "simpleato:coupling/${UUID.randomUUID()}"
        val sources = trains.mapIndexed { index, train -> Source(train, train.controller,
            InfrastructureOwner("$id/source/$index", 0), bodyRoute(train, "$id/body/$index")) }
        val session = Session(id, sources, context.service)
        sessions[approachingTrainId] = session
        try {
            sources.forEach { it.hold = data.controlRuntime.acquireCouplingHold(it.train) }
            progress(session, context)
        } catch (failure: Exception) { stop(session, failure.message ?: failure.javaClass.simpleName) }
        return if (session.phase == CouplingModePhase.REJECTED) CouplingModeEntryResult.Rejected(checkNotNull(session.detail))
            else CouplingModeEntryResult.Accepted(session.snapshot())
    }

    fun tick() {
        val context = contextProvider.current() ?: return
        sessions.values.filter(::active).toList().forEach { session ->
            try {
                if (session.detail == null) progress(session, context) else cleanRejected(session)
            } catch (failure: Exception) { stop(session, failure.message ?: failure.javaClass.simpleName) }
        }
        sessions.entries.removeIf { (_, session) -> !active(session) && world.getTrain(session.moving.train.id) == null }
    }

    private fun progress(session: Session, context: TimetableRouteAdmissionContext) {
        check(context.service === session.admission) { "coupling admission binding changed" }
        if (session.handle == null) {
            for (source in session.sources) {
                check(world.getTrain(source.train.id) === source.train && source.train.controller === source.controller &&
                    checkNotNull(source.train.publishedFrame).speedMetersPerSecond == 0.0) { "coupling source or driver changed during preparation" }
                if (source.grant != null) continue
                val result = source.pending?.let(session.admission::progress) ?: session.admission.request(
                    RouteResourceIntent("${source.owner.ownerId}/request", source.owner, source.train.id,
                        context.observation.stamp, source.route, RouteBoundary(source.route.totalLengthMeters)))
                when (result) {
                    is RouteAdmissionResult.Granted -> { source.grant = result.grant; source.pending = null }
                    is RouteAdmissionResult.Pending -> source.pending = result.handle
                    is RouteAdmissionResult.Rejected -> { stop(session, "source body admission rejected: ${result.failure.detail}"); return }
                }
            }
            if (session.sources.any { it.grant == null }) return
            prepareContact(session, context)
            if (session.detail != null) return
        }
        session.contactPending?.let { consumeContact(session, context, session.admission.progress(it)) }
        if (session.detail != null) return
        val handle = checkNotNull(session.handle)
        when (val state = session.admission.formationChangeState(handle)) {
            is FormationChangeResult.Rejected -> stop(session, state.failure.detail)
            is FormationChangeResult.Accepted -> when (state.snapshot.phase) {
                FormationChangePhase.PUBLICATION_SEALED -> {
                    val proof = session.proof
                    if (session.sources.any { world.getTrain(it.train.id) !== it.train } ||
                        proof == null || !world.validatePublishedCouplingApproach(proof))
                        stop(session, "coupling contact geometry or participant changed")
                }
                FormationChangePhase.PHYSICAL_OBSERVED -> Unit
                FormationChangePhase.OUTPUTS_HELD -> finishContact(session, context)
                FormationChangePhase.RECOVERY_REQUIRED -> stop(session, "coupling requires recovery")
                else -> stop(session, "unexpected ordinary coupling phase ${state.snapshot.phase}")
            }
        }
    }

    private fun prepareContact(session: Session, context: TimetableRouteAdmissionContext) {
        val inspected = world.inspectCouplingModeEntry(session.moving.train.id, session.target.train.id, settings.maximumEntryGapMeters)
        check(inspected is CouplingApproachInspection.Accepted) { "coupling entry changed: $inspected" }
        val proof = inspected.proof
        session.proof = proof
        val prepared = session.admission.prepareFormationChange(FixedFormationChangeIntent.Couple(session.id,
            checkNotNull(session.moving.grant).grantId, checkNotNull(session.target.grant).grantId, proof,
            FormationTrainIdentity(session.moving.train.id, coupledCars(proof), proof.approachingFrame.reversed),
            settings.maximumApproachSpeedMetersPerSecond))
        if (prepared is FormationChangeResult.Rejected) { stop(session, prepared.failure.detail); return }
        session.handle = (prepared as FormationChangeResult.Accepted).snapshot.handle
        val sealed = session.admission.sealFormationPublication(prepared.snapshot.handle)
        if (sealed is FormationChangeResult.Rejected) { stop(session, sealed.failure.detail); return }
        val route = RouteSnapshot("${session.id}/contact", 1, world.topologyState.revision,
            segments(proof.approachingFrame.bodyTraversal + proof.headTraversal))
        session.contactRoute = route
        consumeContact(session, context, session.admission.requestCouplingApproach(prepared.snapshot.handle,
            RouteResourceIntent("${session.moving.owner.ownerId}/request", session.moving.owner, session.moving.train.id,
                context.observation.stamp, route, RouteBoundary(route.totalLengthMeters))))
    }

    private fun consumeContact(session: Session, context: TimetableRouteAdmissionContext, result: RouteAdmissionResult) {
        when (result) {
            is RouteAdmissionResult.Pending -> session.contactPending = result.handle
            is RouteAdmissionResult.Rejected -> stop(session, "contact admission rejected: ${result.failure.detail}")
            is RouteAdmissionResult.Granted -> {
                session.contactPending = null
                session.contactReady = true
                val contact = session.admission.completeCouplingContact(checkNotNull(session.handle))
                check(contact is FormationChangeResult.Accepted) { "ready contact completion rejected: $contact" }
                if (contact.snapshot.phase != FormationChangePhase.PUBLICATION_SEALED) {
                    session.phase = CouplingModePhase.APPROACHING
                    return
                }
                val train = session.moving.train
                check(train.controller === session.moving.controller) { "coupling driver changed during admission" }
                (train.controller as? SimpleATO)?.let { installContactMission(session, context, result.grant, it) }
                data.controlRuntime.registerCouplingAuthority(train, checkNotNull(result.grant.couplingContactAuthority))
                session.moving.hold?.close()
                session.moving.hold = null
                session.phase = CouplingModePhase.APPROACHING
            }
        }
    }

    private fun installContactMission(session: Session, context: TimetableRouteAdmissionContext,
        grant: RouteGrantSnapshot, ato: SimpleATO) {
        val proof = checkNotNull(session.proof)
        val route = checkNotNull(session.contactRoute)
        val revision = ato.nextMissionRevision()
        val origin = AtoMissionOrigin.FormationContact(session.id, proof.approachingFrame.head)
        val target = "${session.id}/contact-point"
        val mission = AtoMission(session.id, revision, origin, listOf(AtoMissionLeg(0,
            AtoMissionPoint.Stop(WayPointRef(target)), PlannedRoute("${session.id}/movement", revision,
                ExpandedRoute(origin.id, target, segments(proof.headTraversal).map {
                    RouteSegment(RailViewRef(it.railId, it.facing), it.startArcLengthMeters, it.endArcLengthMeters)
                })))))
        check(ato.submitMission(mission) is AtoMissionSubmissionResult.Accepted) { "contact mission revision was rejected" }
        val limit = RouteOperatingDirectionLimit.calculate(context.observation.stamp.worldId, route,
            world.topologyState, world.operatingDirections(world.topologyState, route.segments.map { it.railId }))
        limit.requireLegalThrough(route.totalLengthMeters)
        val selection = FrozenRouteSelectionResult.Selected(ato.train.id, context.observation.stamp, route, limit)
        ato.bindTrainRestrictionRoute(context.observation.stamp,
            FrozenMissionRoute(selection, route.totalLengthMeters - proof.distanceToContactMeters, 0.0), revision, data.signalRestrictions)
        RouteAuthorityAdmissionClient(ato.train.id, "${session.moving.owner.ownerId}/request", session.moving.owner,
            session.admission, ato.trainRestrictionPublisher).adoptFormationGrant(selection, grant)
        ato.setMovementAuthority(revision, proof.distanceToContactMeters)
    }

    private fun finishContact(session: Session, context: TimetableRouteAdmissionContext) {
        val train = checkNotNull(world.getTrain(session.moving.train.id))
        val handle = checkNotNull(session.handle)
        if (session.outputGrant == null) {
            val route = bodyRoute(train, "${session.id}/output-body")
            val owner = InfrastructureOwner("${session.id}/output", 0)
            val bound = session.admission.bindFormationOutputs(handle, listOf(RouteResourceIntent("${owner.ownerId}/request", owner,
                train.id, context.observation.stamp, route, RouteBoundary(route.totalLengthMeters))))
            check(bound is FormationChangeResult.Accepted) { "actual coupled body binding rejected: $bound" }
            session.outputGrant = bound.snapshot.outputGrants.single()
        }
        session.sources.forEach { controlReleased(it.train) }
        train.setController(null)
        session.sources.forEach {
            data.controlRuntime.detachCouplingAuthority(it.train)
            data.controlRuntime.detachProtectionSource(it.train)
        }
        val finished = session.admission.finishFormationForManual(handle, checkNotNull(session.outputGrant).grantId)
        check(finished is FormationChangeResult.Accepted) { "stopped coupled output handoff rejected: $finished" }
        session.sources.forEach { it.hold?.close(); it.hold = null; data.inspectionRuntime.removeTrain(it.train.id) }
        session.phase = CouplingModePhase.COMPLETED
    }

    private fun stop(session: Session, detail: String) {
        session.detail = detail
        session.phase = CouplingModePhase.RECOVERY_REQUIRED
        session.sources.forEach { source -> if (world.getTrain(source.train.id) === source.train) {
            data.controlRuntime.detachCouplingAuthority(source.train)
            if (source.hold == null) source.hold = data.controlRuntime.acquireCouplingHold(source.train)
        } }
        cleanRejected(session)
    }

    /** Only unchanged, safely aborted sources return to their original controls automatically. */
    private fun cleanRejected(session: Session) {
        if (session.sources.any { world.getTrain(it.train.id) !== it.train || checkNotNull(it.train.publishedFrame).speedMetersPerSecond != 0.0 }) return
        session.handle?.let { handle ->
            val result = session.admission.abortFormationChange(handle)
            if (result !is FormationChangeResult.Accepted || result.snapshot.phase != FormationChangePhase.ABORTED) return
            session.handle = null
        }
        var released = true
        session.sources.forEach { source -> if (!session.admission.forceEndRoute(source.owner)) released = false }
        if (!released) return
        session.sources.forEach { it.hold?.close(); it.hold = null }
        session.phase = CouplingModePhase.REJECTED
    }

    fun close() {
        sessions.values.filter(::active).forEach { session ->
            session.sources.forEach { data.controlRuntime.detachCouplingAuthority(it.train) }
        }
    }

    private fun active(session: Session) = session.phase != CouplingModePhase.COMPLETED && session.phase != CouplingModePhase.REJECTED
    private fun bodyRoute(train: Train, id: String) = RouteSnapshot(id, 0, world.topologyState.revision,
        segments(checkNotNull(train.publishedFrame).bodyTraversal))
    private fun segments(spans: List<RailTraversalSegmentSnapshot>) = spans.map { span ->
        val rail = world.topologyState.railProjection.getValue(span.railId).single()
        val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
        RouteSegmentSnapshot(span.railId, span.facing,
            if (span.facing == ViewDirection.AB) span.startArcLengthInAbMeters else length - span.startArcLengthInAbMeters,
            if (span.facing == ViewDirection.AB) span.endArcLengthInAbMeters else length - span.endArcLengthInAbMeters)
    }
    private fun coupledCars(proof: CouplingApproachProof): List<String> {
        val moving = proof.approachingFrame.cars.map { it.carId }
        val target = proof.stationaryFrame.cars.map { it.carId }
        val incoming = if (proof.request.approachingEnd == proof.request.stationaryEnd) target.asReversed() else target
        return if (proof.request.approachingEnd == TrainEndSide.B) moving + incoming else incoming + moving
    }
}
