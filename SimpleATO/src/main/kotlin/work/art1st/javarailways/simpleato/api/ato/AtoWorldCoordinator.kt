package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import java.util.concurrent.atomic.AtomicReference

enum class AtoWorldState {
    RUNNING,
    STALLED
}

data class AtoStallReport(
    val worldId: String,
    val trainId: String,
    val tick: Long,
    val errorType: String,
    val message: String?,
    val stackTrace: String,
    val initialTrain: TrainFrameSnapshot?,
    val currentTrain: TrainFrameSnapshot?,
    val route: RouteSnapshot?,
    val perception: PerceptionSnapshot?,
    val constraints: Map<ConstraintOwner, List<AtoConstraint>>,
    val occurredAtEpochMillis: Long = System.currentTimeMillis()
) {
    init {
        require(occurredAtEpochMillis >= 0L)
    }
    fun format(): String = buildString {
        appendLine("[JavaRailways] ATO world stalled")
        appendLine("world=$worldId train=$trainId tick=$tick occurredAtEpochMillis=$occurredAtEpochMillis")
        appendLine("error=$errorType: ${message.orEmpty()}")
        appendLine("initialTrain=${initialTrain?.let { "${it.trainId} head=${it.head} tail=${it.tail} speed=${it.speedMetersPerSecond}" }}")
        appendLine("currentTrain=${currentTrain?.let { "${it.trainId} head=${it.head} tail=${it.tail} speed=${it.speedMetersPerSecond}" }}")
        appendLine("route=$route")
        appendLine("perceptionEpoch=${perception?.epoch} observations=${perception?.observations}")
        appendLine("constraints=$constraints")
        append(stackTrace)
    }
}

/** World-local latch shared by all ATO controllers in that world. */
class AtoWorldCoordinator(
    val worldId: String = "unbound",
    val safetyInterlock: WorldSafetyInterlock = WorldSafetyInterlock(),
    private val reportSink: (AtoStallReport) -> Unit = { System.err.println(it.format()) }
) {
    private val stallReport = AtomicReference<AtoStallReport?>(null)

    val lastStall: AtoStallReport?
        get() = stallReport.get()

    val state: AtoWorldState
        get() = if (lastStall == null) AtoWorldState.RUNNING else AtoWorldState.STALLED

    val isStalled: Boolean
        get() = state == AtoWorldState.STALLED || safetyInterlock.isEngaged

    /** The first failure wins; later failures observe the same world Stall. */
    fun stall(report: AtoStallReport): Boolean {
        if (!stallReport.compareAndSet(null, report)) return false
        safetyInterlock.engage()
        reportSink(report)
        return true
    }

    /** Admin-only reset after trains have been restored manually. */
    fun reset() {
        stallReport.set(null)
        safetyInterlock.reset()
    }
}
