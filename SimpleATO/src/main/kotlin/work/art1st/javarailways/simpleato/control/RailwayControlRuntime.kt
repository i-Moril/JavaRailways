package work.art1st.javarailways.simpleato.control

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import work.art1st.javarailways.basic.api.ato.*
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
    fun prepare(train: Train, capabilities: VehicleControlCapabilities) {
        // A replacement controller must not reconfigure the active physics worker's executor.
        entries.compute(train.id) { _, previous ->
            previous?.takeIf { it.train === train } ?: createEntry(train).also { it.executor.prepare(capabilities) }
        }
    }

    fun automatic(train: Train, state: VehicleControlState, capabilities: VehicleControlCapabilities,
        frame: TrainControlFrame, deltaSeconds: Double, missionRevision: Long? = null): VehicleControlCommand =
        decide(train, state, capabilities, frame, deltaSeconds,
            if (simulation) VehicleExecutionMode.SIMULATION else VehicleExecutionMode.AUTO, null, missionRevision)

    override fun supervise(train: Train, state: VehicleControlState, capabilities: VehicleControlCapabilities,
        command: VehicleControlCommand, deltaTimeSeconds: Double): VehicleControlCommand {
        val frame = try {
            entry(train).routedSource?.invoke(deltaTimeSeconds) ?: manualProtectionFrame(train, capabilities)
        } catch (failure: Exception) {
            TrainControlFrame(emptyList(), emptyList(), capabilities.worstCurrentGrade,
                "${failure.javaClass.simpleName}: ${failure.message}")
        }
        return decide(train, state, capabilities, frame, deltaTimeSeconds,
            if (simulation) VehicleExecutionMode.SIMULATION else VehicleExecutionMode.MANUAL, command, null)
    }
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
