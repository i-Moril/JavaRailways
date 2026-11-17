package work.art1st.javarailways.simpleato.control

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.api.route.CouplingContactAuthority
import work.art1st.javarailways.simpleato.api.atp.AtpTarget
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Captured before intervention; no mutable controller or dynamics workspace escapes. */
data class AtpDiagnosticContext(val worldId: String, val train: TrainFrameSnapshot,
    val topologyRevision: Long, val missionRevision: Long?, val frame: TrainControlFrame,
    val capabilities: VehicleControlCapabilities) {
    override fun toString(): String = "AtpDiagnosticContext(worldId=$worldId, trainId=${train.trainId}, " +
        "physicsEpoch=${train.physicsEpoch}, head=${train.head}, tail=${train.tail}, " +
        "speed=${train.speedMetersPerSecond}, topologyRevision=$topologyRevision, " +
        "missionRevision=$missionRevision, frame=$frame, capabilities=$capabilities)"
}
data class AtpIncident(val sequence: Long, val event: AtpControlEvent)

/** One latch per physical train, retained when its driver/controller changes. */
class RailwayControlRuntime(private val worldId: String) : VehicleCommandSupervisor {
    private class Entry(val train: Train, val executor: VehicleControlExecutor) {
        @Volatile var routedSource: ((Double) -> TrainControlFrame)? = null
        @Volatile var couplingAuthority: CouplingContactAuthority? = null
        val couplingHolds = ConcurrentHashMap.newKeySet<Any>()
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val history = ConcurrentLinkedDeque<AtpIncident>()
    private val sequence = AtomicLong()
    @Volatile var simulation = false
    val incidents: List<AtpIncident> get() = history.toList()
    val latestIntervention: AtpIncident? get() = history.lastOrNull { it.event.kind == AtpEventKind.TRIGGERED }

    private fun createEntry(train: Train): Entry = Entry(train, VehicleControlExecutor { event ->
        val incident = AtpIncident(sequence.incrementAndGet(), event)
        history.addLast(incident)
        while (history.size > 256) history.pollFirst()
        LOGGER.warning("ATP world=$worldId sequence=${incident.sequence} event=$event")
    })
    private fun entry(train: Train): Entry = entries.compute(train.id) { _, previous ->
        previous?.takeIf { it.train === train } ?: createEntry(train)
    }!!

    fun latestDecision(train: Train): ReferenceControlDecision? =
        entries[train.id]?.takeIf { it.train === train }?.executor?.lastDecision

    fun registerProtectionSource(train: Train, source: (Double) -> TrainControlFrame) {
        entry(train).routedSource = source
    }
    fun detachProtectionSource(train: Train) {
        entries[train.id]?.takeIf { it.train === train }?.routedSource = null
    }
    /** Owner-side registration after the exact contact route has passed Basic admission. */
    fun registerCouplingAuthority(train: Train, authority: CouplingContactAuthority) {
        require(authority.proof.request.approachingTrainId == train.id) {
            "coupling authority belongs to another approaching train"
        }
        check(train.world.validatePublishedCouplingApproach(authority.proof)) {
            "coupling authority has no current physical contact geometry"
        }
        entry(train).couplingAuthority = authority
    }
    fun detachCouplingAuthority(train: Train) {
        entries[train.id]?.takeIf { it.train === train }?.couplingAuthority = null
    }
    /** Exact physical-train capability; consumers must validate its current geometry and route. */
    fun couplingAuthority(train: Train): CouplingContactAuthority? =
        entries[train.id]?.takeIf { it.train === train }?.couplingAuthority

    /**
     * One coupling operation's ordinary stop request and subsequent stationary control hold.
     * Owner-side acquisition/close; worker reads remain nonblocking. A moving train uses the
     * existing ordinary stopping strategy and physical dynamics before the hold freezes motion.
     * The exact token cannot release another operation's hold or attach to a retired replacement.
     */
    fun acquireCouplingHold(train: Train): AutoCloseable {
        check(train.world.getTrain(train.id) === train) { "coupling hold requires the current physical train" }
        val current = entry(train)
        val token = Any()
        current.couplingHolds.add(token)
        return AutoCloseable { current.couplingHolds.remove(token) }
    }
    private fun hasCouplingHold(train: Train): Boolean =
        entries[train.id]?.takeIf { it.train === train }?.couplingHolds?.isNotEmpty() == true

    override fun isMovementHeld(train: Train): Boolean {
        if (!hasCouplingHold(train)) return false
        val frame = train.publishedFrame ?: return false
        return !frame.derailed && frame.speedMetersPerSecond == 0.0 &&
            (frame.headTraversal + frame.tailTraversal).all { it.startArcLengthInAbMeters == it.endArcLengthInAbMeters } &&
            frame.cars.all { car -> (car.bogies + car.state).all {
                it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
            } }
    }
    private fun withCouplingStop(train: Train, frame: TrainControlFrame): TrainControlFrame =
        if (!hasCouplingHold(train)) frame else frame.copy(referenceTargets = frame.referenceTargets +
            MotionReferenceConstraint("coupling/operating-stop", 0.0, 0.0))

    fun prepare(train: Train, capabilities: VehicleControlCapabilities) {
        // A replacement controller must not reconfigure the active physics worker's executor.
        entries.compute(train.id) { _, previous ->
            previous?.takeIf { it.train === train } ?: createEntry(train).also { it.executor.prepare(capabilities) }
        }
    }

    fun automatic(train: Train, state: VehicleControlState, capabilities: VehicleControlCapabilities,
        frame: TrainControlFrame, deltaSeconds: Double, missionRevision: Long? = null): VehicleControlCommand =
        decide(train, state, capabilities, withCouplingStop(train, withCouplingSpeedLimit(train, frame)), deltaSeconds,
            if (simulation) VehicleExecutionMode.SIMULATION else VehicleExecutionMode.AUTO, null, missionRevision)

    override fun supervise(train: Train, state: VehicleControlState, capabilities: VehicleControlCapabilities,
        command: VehicleControlCommand, deltaTimeSeconds: Double): VehicleControlCommand {
        val frame = try {
            val source = entry(train).routedSource
            if (source != null) withCouplingSpeedLimit(train, source(deltaTimeSeconds))
            else manualProtectionFrame(train, capabilities, couplingAuthority(train))
        } catch (failure: Exception) {
            TrainControlFrame(emptyList(), emptyList(), capabilities.worstCurrentGrade,
                "${failure.javaClass.simpleName}: ${failure.message}")
        }
        // The held branch still constructed the current protection frame, but it cannot move or
        // create a new ATP acknowledgement latch merely by waiting beside its coupling peer.
        if (isMovementHeld(train)) return VehicleControlCommand.COAST
        val stopping = hasCouplingHold(train)
        return decide(train, state, capabilities, withCouplingStop(train, frame), deltaTimeSeconds,
            if (simulation) VehicleExecutionMode.SIMULATION else if (stopping) VehicleExecutionMode.AUTO
                else VehicleExecutionMode.MANUAL, if (stopping) null else command, null)
    }
    private fun withCouplingSpeedLimit(train: Train, frame: TrainControlFrame): TrainControlFrame {
        val authority = couplingAuthority(train) ?: return frame
        check(train.world.validatePublishedCouplingApproach(authority.proof)) {
            "coupling contact geometry is no longer available"
        }
        val id = "coupling/${authority.eventId}/maximum-approach-speed"
        val limit = authority.maximumApproachSpeedMetersPerSecond
        return frame.copy(referenceTargets = frame.referenceTargets + MotionReferenceConstraint(id, 0.0, limit),
            protectionTargets = frame.protectionTargets + AtpTarget(id, 0.0, limit))
    }
    override fun constrainMovementDistance(train: Train, requestedDistanceMeters: Double): Double =
        couplingAuthority(train)?.let {
            train.world.clampCouplingApproachDistance(it.proof, requestedDistanceMeters)
        } ?: requestedDistanceMeters

    private fun decide(train: Train, state: VehicleControlState, capabilities: VehicleControlCapabilities,
        frame: TrainControlFrame, delta: Double, mode: VehicleExecutionMode,
        proposed: VehicleControlCommand?, missionRevision: Long?): VehicleControlCommand {
        val context = AtpDiagnosticContext(worldId, requireNotNull(train.publishedFrame) {
            "ATP requires a published frame for ${train.id}"
        }, train.world.topologyState.revision,
            missionRevision, frame, capabilities)
        val grade = minOf(frame.mostAdverseGrade, capabilities.worstCurrentGrade,
            RailGradeBounds.mostAdverseOccupiedGrade(train))
        return entry(train).executor.choose(MotionReferenceInput(state, capabilities, frame.referenceTargets,
            delta, maxOf(delta, train.world.maximumPhysicsDeltaSeconds)), frame.protectionTargets, grade,
            mode, proposed, context, frame.unavailableReason)
    }
    override fun isAcknowledgementRequired(train: Train): Boolean =
        entries[train.id]?.takeIf { it.train === train }?.executor?.intervention != null
    override fun acknowledge(train: Train): Boolean =
        entries[train.id]?.takeIf { it.train === train }?.executor?.acknowledgeManual() ?: false
    fun retireMissing(trains: Collection<Train>) {
        val live = trains.associateBy { it.id }
        entries.entries.removeIf { (id, value) -> live[id] !== value.train }
    }
    fun close() { entries.clear() }
    companion object { private val LOGGER = Logger.getLogger(RailwayControlRuntime::class.java.name) }
}
