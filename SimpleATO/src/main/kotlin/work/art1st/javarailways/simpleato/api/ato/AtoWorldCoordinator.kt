package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner
import java.util.concurrent.ConcurrentHashMap
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
    val initialTrain: TrainSnapshot?,
    val currentTrain: TrainSnapshot?,
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
        appendLine("initialTrain=$initialTrain")
        appendLine("currentTrain=$currentTrain")
        appendLine("route=$route")
        appendLine("perception=$perception")
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
    private val stateRef = AtomicReference(AtoWorldState.RUNNING)
    private val initialTrains = ConcurrentHashMap<String, TrainSnapshot>()

    @Volatile
    var lastStall: AtoStallReport? = null
        private set

    val state: AtoWorldState
        get() = stateRef.get()

    val isStalled: Boolean
        get() = state == AtoWorldState.STALLED || safetyInterlock.isEngaged

    fun recordInitialTrain(snapshot: TrainSnapshot) {
        initialTrains[snapshot.trainId] = snapshot
    }

    fun initialTrain(trainId: String): TrainSnapshot? = initialTrains[trainId]

    /** The first failure wins; later failures observe the same world Stall. */
    fun stall(report: AtoStallReport): Boolean {
        if (!stateRef.compareAndSet(AtoWorldState.RUNNING, AtoWorldState.STALLED)) return false
        safetyInterlock.engage()
        lastStall = report
        reportSink(report)
        return true
    }

    /** Admin-only reset after trains have been restored manually. */
    fun reset() {
        lastStall = null
        stateRef.set(AtoWorldState.RUNNING)
        safetyInterlock.reset()
    }
}
