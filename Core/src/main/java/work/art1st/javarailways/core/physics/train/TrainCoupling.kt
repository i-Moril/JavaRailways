package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import work.art1st.javarailways.core.api.event.TrainLinkEvent
import work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
import work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.rail.RailSegments
import work.art1st.javarailways.core.physics.train.CoreTrain.PreparedTrainStructure
import work.art1st.javarailways.core.api.physics.train.TrainComposition

internal class CouplingRejectedException(message: String) : RuntimeException(message)

/**
 * Collision coupling decisions, preparation and commit ordering.
 *
 * This owner has no persistent state. CoreTrain owns structure/publication writes and CoreCar
 * owns its geometry. Moving participants are corrected only on the recorded collision-step
 * path. CollisionEnvelope owns the finite coupler candidate footprint and captured route proof.
 */
internal object TrainCoupling {
    private class ApproachEvidence(
        val world: CoreWorld,
        val approaching: CoreTrain,
        val stationary: CoreTrain,
        val approachingEnvelope: CollisionEnvelope,
        val stationaryEnvelope: CollisionEnvelope,
        val path: CollisionPath,
        val leadingBogie: CoreBogie,
        val contactBogieDistanceMeters: Double,
        val approachingGeometryIdentity: Any = approaching.formationGeometryIdentity,
        val stationaryGeometryIdentity: Any = stationary.formationGeometryIdentity
    )

    /** Rear-coupling entry policy over the same current physical observation and contact solver. */
    fun inspectModeEntry(world: CoreWorld, approachingTrainId: String, stationaryTrainId: String,
                         maximumGapMeters: Double): CouplingApproachInspection {
        require(maximumGapMeters.isFinite() && maximumGapMeters >= 0.0) {
            "maximum coupling gap must be finite and non-negative"
        }
        fun rejected(reason: String) = CouplingApproachInspection.Rejected(reason)
        if (approachingTrainId == stationaryTrainId) return rejected("coupling requires two different trains")
        val moving = world.getTrain(approachingTrainId) ?: return rejected("approaching train is absent")
        val fixed = world.getTrain(stationaryTrainId) ?: return rejected("stationary train is absent")
        if (!moving.publishedFrame.isStationary() || !fixed.publishedFrame.isStationary())
            return rejected("both trains must be stopped before entering coupling mode")
        val request = CouplingApproachRequest(moving.id,
            if (moving.isReversed) TrainEndSide.B else TrainEndSide.A,
            fixed.id, if (fixed.isReversed) TrainEndSide.A else TrainEndSide.B)
        return when (val inspection = inspectApproach(world, request)) {
            is CouplingApproachInspection.Accepted -> if (inspection.proof.couplerGapMeters > maximumGapMeters)
                rejected("coupler gap exceeds $maximumGapMeters metres") else inspection
            is CouplingApproachInspection.Rejected -> inspection
        }
    }

    /** Same finite route support and rigid leading-car solve as collision preparation. No writes. */
    fun inspectApproach(world: CoreWorld, request: CouplingApproachRequest): CouplingApproachInspection {
        fun rejected(reason: String) = CouplingApproachInspection.Rejected(reason)
        val moving = world.getTrain(request.approachingTrainId) ?: return rejected("approaching train is absent")
        val fixed = world.getTrain(request.stationaryTrainId) ?: return rejected("stationary train is absent")
        if (moving.movementHeld || fixed.movementHeld || moving.isDerailed || fixed.isDerailed)
            return rejected("coupling participant cannot move safely")
        if (request.approachingEnd != if (moving.isReversed) TrainEndSide.B else TrainEndSide.A)
            return rejected("approach must use the operating head")
        val own = world.collisionEnvelope(moving.id) ?: return rejected("approach envelope is unavailable")
        val incoming = world.collisionEnvelope(fixed.id) ?: return rejected("stationary envelope is unavailable")
        if (own.frame !== moving.publishedFrame || incoming.frame !== fixed.publishedFrame ||
            own.frame.physicsEpoch != incoming.frame.physicsEpoch ||
            !own.dependencies.matches(moving) || !incoming.dependencies.matches(fixed))
            return rejected("coupling observation is stale")
        if (!incoming.frame.isStationary()) return rejected("target must be stationary")
        val bridge = own.bridge(request.approachingEnd, incoming, request.stationaryEnd)
            ?: return rejected("couplers have no unique selected continuation")
        if (!own.ends.getValue(request.approachingEnd).faces(incoming.ends.getValue(request.stationaryEnd)))
            return rejected("couplers do not face each other")
        return try {
            val evidence = CollisionPath(moving.resolve(own.frame.bodyTraversal + bridge))
            val approach = CollisionPath(moving.resolve(bridge))
            val contact = moving.prepareContactHead(evidence, approach, fixed.coupler(request.stationaryEnd),
                maximumTailDistance = evidence.length)
                ?: return rejected("contact is outside the captured approach")
            val path = approach.cut(moving.resolve(own.frame.head), contact.head).map { segment ->
                val length = segment.railView.curve.totalArcLength
                fun canonical(arc: Double) = if (segment.railView.viewDirection == ViewDirection.AB) arc else length - arc
                RailTraversalSegmentSnapshot(segment.railView.rail.id, segment.railView.viewDirection,
                    canonical(segment.startArcLength), canonical(segment.endArcLength))
            }
            val stationaryAtHead = (request.stationaryEnd == TrainEndSide.A) != incoming.frame.reversed
            val stationaryPath = moving.resolve(incoming.frame.bodyTraversal).let {
                if (stationaryAtHead) reversedSegments(it) else it
            }
            val protected = CollisionPath(moving.resolve(own.frame.bodyTraversal + bridge) + stationaryPath)
                .segments.map { segment ->
                    val length = segment.railView.curve.totalArcLength
                    fun canonical(arc: Double) = if (segment.railView.viewDirection == ViewDirection.AB) arc else length - arc
                    RailTraversalSegmentSnapshot(segment.railView.rail.id, segment.railView.viewDirection,
                        canonical(segment.startArcLength), canonical(segment.endArcLength))
                }
            CouplingApproachInspection.Accepted(CouplingApproachProof(request, own.frame, incoming.frame,
                RailPositionSnapshot.capture(contact.head, contact.head.facing),
                own.ends.getValue(request.approachingEnd).gap(incoming.ends.getValue(request.stationaryEnd)), path, protected,
                ApproachEvidence(world, moving, fixed, own, incoming, evidence, moving.headBogie,
                    checkNotNull(evidence.distanceTo(if (moving.headCar.isReversed)
                        contact.geometry.bogieBLocation else contact.geometry.bogieALocation)))))
        } catch (failure: BogieException) {
            rejected("contact geometry cannot be solved: ${failure.message}")
        } catch (failure: IllegalStateException) {
            rejected("contact path cannot be proved: ${failure.message}")
        }
    }

    fun validateApproach(world: CoreWorld, proof: CouplingApproachProof): Boolean {
        if (!validateApproachGeometry(world, proof)) return false
        val evidence = proof.hostEvidence as? ApproachEvidence ?: return false
        return evidence.approaching.publishedFrame === proof.approachingFrame &&
            evidence.stationary.publishedFrame === proof.stationaryFrame
    }

    fun validateApproachGeometry(world: CoreWorld, proof: CouplingApproachProof): Boolean {
        val evidence = proof.hostEvidence as? ApproachEvidence ?: return false
        val targetFrame = evidence.stationary.publishedFrame
        return evidence.world === world && world.getTrain(evidence.approaching.id) === evidence.approaching &&
            world.getTrain(evidence.stationary.id) === evidence.stationary &&
            targetFrame.head == proof.stationaryFrame.head && targetFrame.tail == proof.stationaryFrame.tail &&
            targetFrame.cars == proof.stationaryFrame.cars && targetFrame.isStationary() &&
            world.latestWorldFrame().activeContinuations === evidence.approachingEnvelope.activeContinuations &&
            !evidence.approaching.movementHeld && !evidence.stationary.movementHeld &&
            !evidence.approaching.isDerailed && !evidence.stationary.isDerailed &&
            evidence.approachingEnvelope.dependencies.matches(evidence.approaching) &&
            evidence.stationaryEnvelope.dependencies.matches(evidence.stationary)
    }

    fun validatePublishedApproach(world: CoreWorld, proof: CouplingApproachProof): Boolean {
        val evidence = proof.hostEvidence as? ApproachEvidence ?: return false
        if (evidence.world !== world ||
            evidence.approaching.formationGeometryIdentity !== evidence.approachingGeometryIdentity ||
            evidence.stationary.formationGeometryIdentity !== evidence.stationaryGeometryIdentity) return false
        val publication = world.latestWorldFrame()
        if (publication.activeContinuations !== evidence.approachingEnvelope.activeContinuations) return false
        val moving = publication.trains.firstOrNull { it.trainId == proof.request.approachingTrainId } ?: return false
        val fixed = publication.trains.firstOrNull { it.trainId == proof.request.stationaryTrainId } ?: return false
        return moving === evidence.approaching.publishedFrame && fixed === evidence.stationary.publishedFrame &&
            !moving.derailed && !fixed.derailed && !evidence.approaching.movementHeld && !evidence.stationary.movementHeld &&
            moving.reversed == proof.approachingFrame.reversed &&
            moving.cars.map { it.carId } == proof.approachingFrame.cars.map { it.carId } &&
            fixed.reversed == proof.stationaryFrame.reversed && fixed.cars == proof.stationaryFrame.cars &&
            fixed.head == proof.stationaryFrame.head && fixed.tail == proof.stationaryFrame.tail && fixed.isStationary()
    }

    fun clampApproachDistance(world: CoreWorld, proof: CouplingApproachProof, requested: Double): Double {
        require(requested.isFinite() && requested >= 0.0) { "contact distance must be finite and non-negative" }
        check(validatePublishedApproach(world, proof)) { "contact proof is no longer valid in the published world" }
        val evidence = proof.hostEvidence as ApproachEvidence
        val current = checkNotNull(evidence.path.distanceTo(evidence.leadingBogie.location)) {
            "approaching leading bogie left the host contact path"
        }
        val remaining = evidence.contactBogieDistanceMeters - current
        check(remaining >= -COUPLER_CONTACT_EPSILON_METERS) { "approaching bogie already passed the contact target" }
        return minOf(requested, remaining.coerceAtLeast(0.0))
    }

    /** No synthetic movement or collision: only current exact physical contact can enter commit. */
    fun completeContact(world: CoreWorld, proof: CouplingApproachProof):
        work.art1st.javarailways.core.api.physics.train.CouplingContactCompletion {
        fun rejected(reason: String) =
            work.art1st.javarailways.core.api.physics.train.CouplingContactCompletion.Rejected(reason)
        if (!validateApproachGeometry(world, proof)) return rejected("coupling contact geometry is stale")
        val evidence = proof.hostEvidence as ApproachEvidence
        val moving = evidence.approaching
        val fixed = evidence.stationary
        val own = world.collisionEnvelope(moving.id) ?: return rejected("approaching contact envelope is unavailable")
        val incoming = world.collisionEnvelope(fixed.id) ?: return rejected("stationary contact envelope is unavailable")
        if (own.frame !== moving.publishedFrame || incoming.frame !== fixed.publishedFrame ||
            own.frame.physicsEpoch != incoming.frame.physicsEpoch ||
            !own.dependencies.matches(moving) || !incoming.dependencies.matches(fixed) ||
            !own.frame.isStationary() || !incoming.frame.isStationary())
            return rejected("coupling contact requires two current stopped participants")
        if (!own.ends.getValue(proof.request.approachingEnd).touches(incoming.ends.getValue(proof.request.stationaryEnd)) ||
            own.bridge(proof.request.approachingEnd, incoming, proof.request.stationaryEnd) == null)
            return work.art1st.javarailways.core.api.physics.train.CouplingContactCompletion.NotInContact
        return try {
            work.art1st.javarailways.core.api.physics.train.CouplingContactCompletion.Completed(
                couple(moving, fixed, own.frame, incoming.frame, mapOf(moving to own, fixed to incoming)))
        } catch (failure: CouplingRejectedException) {
            rejected(checkNotNull(failure.message))
        }
    }

    /** Collision-only owner transaction. The two input frames precede collision stabilization. */
    fun linkFromCollisionOnOwner(
        first: CoreTrain,
        other: CoreTrain,
        beforeCollision: TrainFrameSnapshot,
        otherBeforeCollision: TrainFrameSnapshot,
        topologyRevision: Long?,
        firstEnvelope: CollisionEnvelope?, otherEnvelope: CollisionEnvelope?
    ): Train {
        first.world.physicsCoordinator.ensureBetweenFrames("TrainCoupling.linkFromCollisionOnOwner")
        check(topologyRevision != null) { "collision topology observation is missing" }
        val envelopes = listOfNotNull(firstEnvelope?.let { first to it }, otherEnvelope?.let { other to it }).toMap()
        check(envelopes.size == 2) { "collision dependency observation is missing" }
        admitCoupling(envelopes.all { (train, envelope) -> envelope.dependencies.matches(train) }) {
            "collision rail, connection or consist dependencies changed"
        }
        return couple(first, other, beforeCollision, otherBeforeCollision, envelopes)
    }

    @OptIn(ExtensionData::class)
    private fun couple(
        first: CoreTrain,
        other: CoreTrain,
        beforeCollision: TrainFrameSnapshot,
        otherBeforeCollision: TrainFrameSnapshot,
        envelopes: Map<CoreTrain, CollisionEnvelope>
    ): Train = with(first) {
        check(this !== other && world === other.world) { "coupling requires two trains in one world" }
        check(world.getTrain(id) === this && world.getTrain(other.id) === other) { "collision train has retired" }
        admitCoupling(!movementHeld && !other.movementHeld) { "cannot couple a train with unavailable configuration" }
        admitCoupling(!isDerailed && !other.isDerailed) { "cannot couple a derailed train" }
        check(beforeCollision.trainId == id && otherBeforeCollision.trainId == other.id &&
            beforeCollision.physicsEpoch == otherBeforeCollision.physicsEpoch) { "collision observations disagree" }
        val stationary = beforeCollision.isStationary()
        val otherStationary = otherBeforeCollision.isStationary()
        admitCoupling(stationary || otherStationary) { "coupling requires a stationary participant before collision" }
        check(publishedFrame.physicsEpoch == other.publishedFrame.physicsEpoch) { "coupling frames disagree" }
        check((cars + other.cars).map { it.id }.toSet().size == cars.size + other.cars.size) {
            "coupling would duplicate a stable car identity"
        }
        val contact = prepareContact(first, other, beforeCollision, otherBeforeCollision, stationary,
            otherStationary, envelopes) ?: throw CouplingRejectedException("collision has no unique contact on its recorded step")
        val firstEnd = contact.firstEnd
        val secondEnd = contact.secondEnd
        val decision = TrainLinkEvent(
            this, other, firstEnd, secondEnd,
            beforeCollision.speedMetersPerSecond, otherBeforeCollision.speedMetersPerSecond,
            RailPositionSnapshot.capture(contact.placements.getValue(this).head,
                contact.placements.getValue(this).head.facing),
            RailPositionSnapshot.capture(contact.placements.getValue(other).head,
                contact.placements.getValue(other).head.facing),
            if (stationary) this else other
        )
        val listenersSucceeded = try {
            MainRailEventBus.publishAndReportFailures(decision)
        } finally {
            decision.freeze()
        }
        admitCoupling(listenersSucceeded) { "Link decision listener failed: $id and ${other.id}" }
        admitCoupling(!decision.isRejected) { "Link rejected: ${decision.rejectionReason}" }
        val master = decision.masterTrain as CoreTrain
        val removed = if (master === this) other else this
        val masterEnd = if (master === this) firstEnd else secondEnd
        val removedEnd = if (master === this) secondEnd else firstEnd
        admitCoupling(envelopes.all { (train, envelope) -> envelope.dependencies.matches(train) }) {
            "collision dependencies changed during link decision"
        }
        val prepared = master.prepareCoupling(removed, masterEnd, removedEnd, decision.resultReversed, contact)
        val controllerUpdates = master.controller?.prepareConsistChange(
            prepared.structure.geometry.cars.toList(), removed.controller
        ).orEmpty().toList()

        // Prepare every union slot before replacing anything. Source state belongs to plugins;
        // this hook cannot promise rollback of arbitrary external effects.
        val masterSlots = master.extensionDataContainer.extensionData
        val removedSlots = removed.extensionDataContainer.extensionData
        val extensions = List(maxOf(masterSlots.size, removedSlots.size)) { slot ->
            val own = masterSlots.getOrNull(slot)
            val incoming = removedSlots.getOrNull(slot)
            val source = own ?: incoming ?: return@List null
            source.prepareLink(master, removed, if (own != null) incoming else own).also { replacement ->
                require(replacement !== own && replacement !== incoming) {
                    "link extension must be dedicated: master=${master.id} slot=$slot"
                }
                replacement.initialize(master, emptyMap())
            }
        }

        admitCoupling(envelopes.all { (train, envelope) -> envelope.dependencies.matches(train) }) {
            "collision rail, connection or consist dependencies changed during preparation"
        }
        val occupancy = world.prepareCouplingOccupancy(master, removed, prepared.structure.frame,
            prepared.structure.geometry.cars, prepared.reversals.map { it.first }.toSet())
        var movementHold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold? = null

        // Commit-only: no solver, plugin callback, Context initialization or car destruction.
        try {
            controllerUpdates.forEach { it.install() }
            prepared.corrections.forEach { (car, geometry) -> car.applyPreparedStructuralGeometry(geometry) }
            prepared.reversals.forEach { (car, locations) -> car.applyPreparedOperatingReversal(locations) }
            master.controller?.commitStructuralStop()
            removed.removeController()
            masterSlots.clear()
            masterSlots.addAll(extensions)
            prepared.structure.geometry.cars.forEach { it.transferPublicationOwnership(master) }
            removed.retireAfterCoupling().whenComplete { _, failure ->
                if (failure != null) world.physicsCoordinator.stall(
                    "Coupled train retirement failed: train=${removed.id}", failure
                )
            }
            master.applyPreparedStructure(prepared.structure)
            if (decision.holdResultMovement) movementHold = master.installFormationMovementHold()
            world.applyPreparedOccupancy(occupancy)
            world.publishWorldFrame(prepared.structure.frame.physicsEpoch)
        } catch (failure: Throwable) {
            throw world.physicsCoordinator.stall("Collision coupling publication failed: train=${master.id}", failure)
        }
        MainRailEventBus.publish(TrainLinkSuccessEvent(
            master, removed, this, other, firstEnd, secondEnd,
            decision.firstCarIds, decision.secondCarIds, decision.firstReversed, decision.secondReversed,
            movementHold
        ))
        master
    }

    private inline fun admitCoupling(accepted: Boolean, reason: () -> String) {
        if (!accepted) throw CouplingRejectedException(reason())
    }

    private fun TrainFrameSnapshot.isStationary(): Boolean = speedMetersPerSecond == 0.0 &&
        (headTraversal + tailTraversal).none { it.startArcLengthInAbMeters != it.endArcLengthInAbMeters } && cars.all { car ->
        listOf(car.state).plus(car.bogies).all { it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0 }
    }

    /** Physical preclaim check, deliberately independent of coupling's stationary admission. */
    internal fun hasConnectorContact(first: CoreTrain, second: CoreTrain,
                                     own: CollisionEnvelope, incoming: CollisionEnvelope,
                                     recordedTopologyValid: Boolean): Boolean {
        if (!recordedTopologyValid || own.frame !== first.publishedFrame || incoming.frame !== second.publishedFrame) return false
        val current = own.ends.any { (a, firstEnd) -> incoming.ends.any { (b, secondEnd) ->
            firstEnd.touches(secondEnd) && own.bridge(a, incoming, b) != null &&
                own.frame.displacementToward(a) + incoming.frame.displacementToward(b) > 0.0
        } }
        if (current) return true
        val firstStationary = own.frame.isStationary()
        val secondStationary = incoming.frame.isStationary()
        if (firstStationary == secondStationary) return false
        val moving = if (firstStationary) second else first
        val fixed = if (firstStationary) first else second
        val frame = if (firstStationary) incoming.frame else own.frame
        if (frame.headTraversal.isEmpty() || frame.tailTraversal.isEmpty()) return false
        val envelopes = mapOf(first to own, second to incoming)
        val movement = CollisionPath(moving.resolve(frame.headTraversal))
        return TrainEndSide.entries.any { fixedEnd ->
            val anchor = fixed.coupler(fixedEnd)
            if (anchor.outward.dot(moving.headCar.operatingDirection) >= 0.0) return@any false
            val path = moving.contactEvidence(frame, fixed, fixedEnd, envelopes) ?: return@any false
            try {
                moving.prepareContactHead(path, movement, anchor) != null
            } catch (_: BogieException) {
                false
            }
        }
    }

    /**
     * Stable A is the operating head exactly when the consist is not reversed. Actual traversal
     * toward that end closes a contact; traversal at the operating tail opens it. Maintenance
     * frames have no traversal, regardless of retained controller speed or future commands.
     */
    private fun TrainFrameSnapshot.displacementToward(end: TrainEndSide): Double {
        val atHead = (end == TrainEndSide.A) != reversed
        val path = if (atHead) headTraversal else tailTraversal
        val distance = path.sumOf { kotlin.math.abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters) }
        return if (atHead) distance else -distance
    }

    private data class Coupler(val position: Vector3d, val outward: Vector3d, val length: Double)

    private fun CoreTrain.coupler(end: TrainEndSide): Coupler {
        val car = if (end == TrainEndSide.A) cars.first() else cars.last()
        val physicalA = (end == TrainEndSide.A) != (car.isReversed xor isReversed)
        val outward = Vector3d(car.direction).mul(if (physicalA) 1.0 else -1.0)
        return Coupler(
            Vector3d(car.position).add(Vector3d(outward).mul(car.length / 2.0)), outward,
            if (physicalA) car.connectorLengthAtA else car.connectorLengthAtB
        )
    }

    private fun CoreTrain.outwardLocation(end: TrainEndSide): RailLocationWithFacing =
        if ((end == TrainEndSide.A) != isReversed) head.location else tail.location.reversed

    private fun CoreTrain.couplersContact(other: CoreTrain, ownEnd: TrainEndSide, otherEnd: TrainEndSide): Boolean {
        val own = coupler(ownEnd)
        val incoming = other.coupler(otherEnd)
        if (kotlin.math.abs(own.position.distance(incoming.position) - own.length - incoming.length) > Curve.TOLERANCE) return false
        if (own.outward.dot(incoming.outward) >= 0.0) return false
        if (Vector3d(incoming.position).sub(own.position).dot(own.outward) < -Curve.TOLERANCE) return false
        return try {
            placementSegments(outwardLocation(ownEnd), other.outwardLocation(otherEnd).reversed)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private data class PreparedCoupling(
        val structure: PreparedTrainStructure,
        val reversals: List<Pair<CoreCar, List<RailLocationWithFacing>>>,
        val corrections: Map<CoreCar, CoreCar.PreparedCarGeometry>
    )

    private data class Placement(
        val head: RailLocationWithFacing,
        val tail: RailLocationWithFacing,
        val body: List<RailSegment>,
        val corrections: Map<CoreCar, CoreCar.PreparedCarGeometry> = emptyMap()
    )

    private data class Contact(
        val firstEnd: TrainEndSide,
        val secondEnd: TrainEndSide,
        val placements: Map<CoreTrain, Placement>,
        val correctionPath: CollisionPath? = null
    )

    /** Finite collision evidence, with occurrences kept in traversal order. No branch selection. */
    private class CollisionPath(input: List<RailSegment>) {
        val segments = buildList<RailSegment> {
            input.forEach { next ->
                check(next.totalArcLength >= 0.0) { "collision path runs backwards" }
                val previous = lastOrNull()
                if (previous != null && previous.railView === next.railView &&
                    kotlin.math.abs(previous.endArcLength - next.startArcLength) <= Curve.TOLERANCE) {
                    removeAt(lastIndex)
                    add(previous.copy(endArcLength = next.endArcLength))
                } else {
                    if (previous != null) check(
                        kotlin.math.abs(previous.endArcLength - previous.railView.curve.totalArcLength) <= Curve.TOLERANCE &&
                            next.startArcLength <= Curve.TOLERANCE && next.railView in previous.railView.nextRails
                    ) { "collision path is disconnected" }
                    add(next)
                }
            }
        }
        val length = segments.sumOf { it.totalArcLength }

        fun distanceTo(location: RailLocationWithFacing): Double? {
            var preceding = 0.0
            var result: Double? = null
            segments.forEach { segment ->
                if (segment.railView === location.railView &&
                    location.arcLength >= segment.startArcLength - Curve.TOLERANCE &&
                    location.arcLength <= segment.endArcLength + Curve.TOLERANCE) {
                    val candidate = preceding + (location.arcLength - segment.startArcLength)
                        .coerceIn(0.0, segment.totalArcLength)
                    check(result == null || kotlin.math.abs(result - candidate) <= Curve.TOLERANCE) {
                        "collision position has ambiguous path occurrences"
                    }
                    result = candidate
                }
                preceding += segment.totalArcLength
            }
            return result
        }

        fun locationAt(distance: Double): RailLocationWithFacing {
            check(distance >= -Curve.TOLERANCE && distance <= length + Curve.TOLERANCE) {
                "corrected position is outside collision path"
            }
            var remaining = distance.coerceIn(0.0, length)
            segments.forEachIndexed { index, segment ->
                if (remaining <= segment.totalArcLength || index == segments.lastIndex)
                    return segment.railView.getRailLocationAt(segment.startArcLength + remaining)
                remaining -= segment.totalArcLength
            }
            error("empty collision path")
        }

        fun cut(from: RailLocationWithFacing, to: RailLocationWithFacing): List<RailSegment> {
            val start = distanceTo(from) ?: error("collision path lacks start")
            val end = distanceTo(to) ?: error("collision path lacks end")
            check(end + Curve.TOLERANCE >= start) { "collision path endpoints are reversed" }
            var preceding = 0.0
            return segments.mapNotNull { segment ->
                val lo = maxOf(start, preceding)
                val hi = minOf(end, preceding + segment.totalArcLength)
                val result = if (hi >= lo) RailSegment(segment.railView,
                    segment.startArcLength + lo - preceding, segment.startArcLength + hi - preceding) else null
                preceding += segment.totalArcLength
                result
            }
        }
    }

    private fun CoreTrain.resolve(position: RailPositionSnapshot): RailLocationWithFacing {
        val rail = world.getRail(position.railId) ?: error("collision rail has retired: ${position.railId}")
        val view = if (position.facing == ViewDirection.AB) rail.viewAB else rail.viewBA
        return view.getRailLocationAt(if (position.facing == ViewDirection.AB) position.arcLengthInAbMeters
            else rail.curve.totalArcLength - position.arcLengthInAbMeters)
    }

    private fun CoreTrain.resolve(segments: List<RailTraversalSegmentSnapshot>): List<RailSegment> =
        segments.map { segment ->
            val start = resolve(RailPositionSnapshot(segment.railId, segment.facing, segment.startArcLengthInAbMeters))
            val end = resolve(RailPositionSnapshot(segment.railId, segment.facing, segment.endArcLengthInAbMeters))
            RailSegment(start.railView, start.arcLength, end.arcLength)
        }

    private fun CoreTrain.placement(frame: TrainFrameSnapshot): Placement {
        check(RailPositionSnapshot.capture(head.location, head.location.facing) == frame.head &&
            RailPositionSnapshot.capture(tail.location, tail.location.facing) == frame.tail &&
            frame.reversed == isReversed && frame.cars.map { it.carId } == cars.map { it.id }) {
            "collision placement has changed: $id"
        }
        return Placement(resolve(frame.head), resolve(frame.tail), CollisionPath(resolve(frame.bodyTraversal)).segments)
    }

    private fun CoreTrain.outwardLocation(end: TrainEndSide, placement: Placement): RailLocationWithFacing =
        if ((end == TrainEndSide.A) != isReversed) placement.head else placement.tail.reversed

    private fun CoreTrain.contactEvidence(frame: TrainFrameSnapshot, fixed: CoreTrain, fixedEnd: TrainEndSide,
                                         envelopes: Map<CoreTrain, CollisionEnvelope>): CollisionPath? {
        val movingEnd = if (isReversed) TrainEndSide.B else TrainEndSide.A
        val ownEnvelope = envelopes[this]
        val incomingEnvelope = envelopes[fixed]
        val bridge = if (ownEnvelope != null && incomingEnvelope != null)
            ownEnvelope.bridge(movingEnd, incomingEnvelope, fixedEnd).orEmpty() else emptyList()
        val evidence = CollisionPath(resolve(frame.tailTraversal + frame.bodyTraversal + bridge))
        return evidence.takeIf { it.distanceTo(fixed.outwardLocation(fixedEnd).reversed) != null }
    }

    private data class PreparedContactHead(val geometry: CoreCar.PreparedCarGeometry, val head: RailLocationWithFacing)

    /** Preclaim needs only the leading car, never an all-consist prediction for each neighbour. */
    private fun CoreTrain.prepareContactHead(path: CollisionPath, movement: CollisionPath, anchor: Coupler,
                                           maximumTailDistance: Double? = null): PreparedContactHead? {
        val car = headCar
        val tail = path.distanceTo(car.tailBogie.location) ?: error("collision path lacks leading tail bogie")
        val head = path.distanceTo(car.headBogie.location) ?: error("collision path lacks leading head bogie")
        val geometry = car.prepareFollowingCarOnPath(RailSegments(path.segments), car.isReversed,
            anchor.position, anchor.length, maximumTailDistance ?: tail, tail, head - tail)
        val bogie = if (car.isReversed) geometry.bogieBLocation else geometry.bogieALocation
        val endpoint = path.locationAt((path.distanceTo(bogie) ?: error("collision path lacks prepared leading bogie")) +
            car.headBogieToEndPointOffset)
        return if (movement.distanceTo(endpoint) == null) null else PreparedContactHead(geometry, endpoint)
    }

    private fun prepareContact(
        first: CoreTrain, second: CoreTrain,
        firstFrame: TrainFrameSnapshot, secondFrame: TrainFrameSnapshot,
        firstStationary: Boolean, secondStationary: Boolean,
        envelopes: Map<CoreTrain, CollisionEnvelope>
    ): Contact? {
        val placements = mapOf(first to first.placement(firstFrame), second to second.placement(secondFrame))
        if (firstStationary && secondStationary) {
            val contacts = TrainEndSide.entries.flatMap { a -> TrainEndSide.entries.mapNotNull { b ->
                if (first.couplersContact(second, a, b)) a to b else null
            } }
            check(contacts.size == 1) { "collision has no unique connected coupler contact" }
            return Contact(contacts.single().first, contacts.single().second, placements)
        }
        val moving = if (firstStationary) second else first
        val fixed = if (firstStationary) first else second
        val frame = if (firstStationary) secondFrame else firstFrame
        if (frame.headTraversal.isEmpty() || frame.tailTraversal.isEmpty()) return null
        val headMovement = CollisionPath(moving.resolve(frame.headTraversal))
        val movingEnd = if (moving.isReversed) TrainEndSide.B else TrainEndSide.A
        val contacts = TrainEndSide.entries.mapNotNull { fixedEnd ->
            val anchor = fixed.coupler(fixedEnd)
            if (anchor.outward.dot(moving.headCar.operatingDirection) >= 0.0) return@mapNotNull null
            val evidence = moving.contactEvidence(frame, fixed, fixedEnd, envelopes) ?: return@mapNotNull null
            val corrected = try {
                moving.correctPlacement(evidence, headMovement, anchor) ?: return@mapNotNull null
            } catch (_: BogieException) {
                return@mapNotNull null
            }
            val firstGeometry = corrected.corrections.getValue(moving.headCar)
            val direction = firstGeometry.direction.toVector3d().mul(if (moving.headCar.isReversed) -1.0 else 1.0)
            if (direction.dot(anchor.outward) >= 0.0) return@mapNotNull null
            Contact(if (moving === first) movingEnd else fixedEnd,
                if (moving === first) fixedEnd else movingEnd,
                placements + (moving to corrected), evidence)
        }
        return contacts.singleOrNull()
    }

    private fun CoreTrain.correctPlacement(path: CollisionPath, headMovement: CollisionPath, anchor: Coupler): Placement? {
        val leading = prepareContactHead(path, headMovement, anchor) ?: return null
        val prepared = linkedMapOf<CoreCar, CoreCar.PreparedCarGeometry>()
        var predecessor = anchor.position
        var connector = anchor.length
        val ordered = if (isReversed) cars.asReversed() else cars
        ordered.forEachIndexed { index, car ->
            val tailDistance = path.distanceTo(car.tailBogie.location) ?: error("collision path lacks tail bogie")
            val headDistance = path.distanceTo(car.headBogie.location) ?: error("collision path lacks head bogie")
            val geometry = if (index == 0) leading.geometry else
                car.prepareFollowingCarOnPath(RailSegments(path.segments), car.isReversed,
                    predecessor, connector, tailDistance, tailDistance, headDistance - tailDistance)
            prepared[car] = geometry
            val direction = geometry.direction.toVector3d().mul(if (car.isReversed) -1.0 else 1.0)
            predecessor = geometry.position.toVector3d().sub(direction.mul(car.length / 2.0))
            connector = car.tailConnectorLength
        }
        val first = ordered.first()
        val last = ordered.last()
        val lastGeometry = prepared.getValue(last)
        val tailBogie = if (last.isReversed) lastGeometry.bogieALocation else lastGeometry.bogieBLocation
        val correctedHead = leading.head
        val correctedTail = path.locationAt((path.distanceTo(tailBogie) ?: error("missing corrected tail bogie")) -
            last.tailBogieToEndPointOffset)
        return Placement(correctedHead, correctedTail, path.cut(correctedTail, correctedHead), prepared)
    }

    /** Reverse a captured occupied path by representation only, never reverse turnout selection. */
    private fun reversedSegments(path: List<RailSegment>): List<RailSegment> = path.asReversed().map { segment ->
        val length = segment.railView.curve.totalArcLength
        RailSegment(segment.railView.reversed, length - segment.endArcLength, length - segment.startArcLength)
    }

    private fun CoreTrain.prepareCoupling(
        other: CoreTrain,
        ownEnd: TrainEndSide,
        otherEnd: TrainEndSide,
        reversed: Boolean,
        contact: Contact
    ): PreparedCoupling {
        val flipOther = ownEnd == otherEnd
        val ownPlacement = contact.placements.getValue(this)
        val otherPlacement = contact.placements.getValue(other)
        val ownPath = ownPlacement.body.let { if (isReversed) reversedSegments(it) else it }
        val otherPath = otherPlacement.body.let { if (other.isReversed xor flipOther) reversedSegments(it) else it }
        val ownA = outwardLocation(TrainEndSide.A, ownPlacement)
        val ownB = outwardLocation(TrainEndSide.B, ownPlacement).reversed
        val otherA = if (flipOther) other.outwardLocation(TrainEndSide.B, otherPlacement)
            else other.outwardLocation(TrainEndSide.A, otherPlacement)
        val otherB = if (flipOther) other.outwardLocation(TrainEndSide.A, otherPlacement).reversed
            else other.outwardLocation(TrainEndSide.B, otherPlacement).reversed
        fun bridge(from: RailLocationWithFacing, to: RailLocationWithFacing): List<RailSegment> {
            val evidence = contact.correctionPath ?: return placementSegments(from, to)
            return if (evidence.distanceTo(from) != null) evidence.cut(from, to)
                else CollisionPath(reversedSegments(evidence.segments)).cut(from, to)
        }
        val incomingCars = if (flipOther) other.cars.asReversed() else other.cars
        val joinedCars: List<CoreCar>
        val stablePath: List<RailSegment>
        val stableHead: RailLocationWithFacing
        val stableTail: RailLocationWithFacing
        if (ownEnd == TrainEndSide.B) {
            joinedCars = cars + incomingCars
            stablePath = otherPath + bridge(otherA, ownB) + ownPath
            stableHead = ownA
            stableTail = otherB
        } else {
            joinedCars = incomingCars + cars
            stablePath = ownPath + bridge(ownA, otherB) + otherPath
            stableHead = otherA
            stableTail = ownB
        }
        val resultPath = CollisionPath(if (reversed) reversedSegments(stablePath) else stablePath).segments
        val geometry = prepareStructureGeometry(
            joinedCars,
            if (reversed) stableTail.reversed else stableHead,
            if (reversed) stableHead.reversed else stableTail,
            resultPath
        )
        val corrections = ownPlacement.corrections + otherPlacement.corrections
        val reversals = joinedCars.mapNotNull { car ->
            val source = if (car.train === this) this else other
            val desired = car.isReversed xor source.isReversed xor (source === other && flipOther) xor reversed
            val corrected = corrections[car]
            val locations = if (corrected == null) car.bogies.map { it.location }
                else listOf(corrected.bogieALocation, corrected.bogieBLocation)
            if (desired != car.isReversed) car to locations.map { it.reversed } else null
        }
        val composition = TrainComposition.create(this.composition.units + other.composition.units, joinedCars.map { it.id })
        val frame = prepareStructureFrame(geometry, reversed, resultSpeed = 0.0, clearMotion = true,
            composition = composition,
            preparedCars = corrections.mapValues { (car, placement) -> car.prepareStationaryFrame(placement) })
        return PreparedCoupling(PreparedTrainStructure(geometry, frame, emptyList(), true), reversals, corrections)
    }
}
