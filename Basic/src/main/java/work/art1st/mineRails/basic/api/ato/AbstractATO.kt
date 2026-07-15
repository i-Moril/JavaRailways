package work.art1st.mineRails.basic.api.ato

import work.art1st.mineRails.basic.api.ato.constraint.AtoConstraint
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintOwner
import work.art1st.mineRails.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.mineRails.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainSnapshot
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing

/** Fixed ATO Tick boundary: plan, actuate, move, or freeze the world-local ATO subsystem. */
abstract class AbstractATO(
    train: Train,
    world: World,
    protected val actuator: TrainActuator,
    protected val safetyProfile: AtoSafetyProfile,
    protected val atoCoordinator: AtoWorldCoordinator
) : TrainController(train, world) {
    val world: World = world

    protected var lastTravelDistanceMeters: Double = 0.0
        private set

    protected var latestRouteSnapshot: RouteSnapshot? = null
    protected var latestPerceptionSnapshot: PerceptionSnapshot? = null

    @Volatile
    var currentTrainSnapshot: TrainSnapshot
        private set

    @Volatile
    var lifetimeTravelDistanceMeters: Double = 0.0
        private set

    private var atoTick: Long = 0L

    init {
        currentTrainSnapshot = captureTrainSnapshot(0L)
        atoCoordinator.recordInitialTrain(currentTrainSnapshot)
    }

    final override fun tick(deltaT: Double) {
        atoTick++
        if (atoCoordinator.isStalled) {
            freeze()
            return
        }

        try {
            val deltaTimeSeconds = deltaT * 0.001
            processPendingCommands()
            val demand = plan(deltaTimeSeconds)
            validateBeforeMovement()
            if (atoCoordinator.isStalled) {
                freeze()
                return
            }
            val step = actuator.step(activeSpeed, demand, deltaTimeSeconds)
            train.moveTrainForward(step.distanceMeters)
            speed = step.speedMetersPerSecond
            lastTravelDistanceMeters = step.distanceMeters
            lifetimeTravelDistanceMeters += step.distanceMeters
            currentTrainSnapshot = captureTrainSnapshot(atoTick, step.speedMetersPerSecond)
        } catch (failure: Exception) {
            atoCoordinator.stall(createStallReport(failure))
            freeze()
        }
    }

    protected open fun processPendingCommands() = Unit

    protected abstract fun plan(deltaTimeSeconds: Double): ControlDemand

    protected open fun validateBeforeMovement() = Unit

    protected open fun activeProviderId(): String? = null

    protected open fun constraintSnapshot(): Map<ConstraintOwner, List<AtoConstraint>> = emptyMap()

    private fun freeze() {
        val frozen = actuator.freeze()
        speed = frozen.speedMetersPerSecond
        lastTravelDistanceMeters = 0.0
        currentTrainSnapshot = runCatching {
            captureTrainSnapshot(atoTick, frozen.speedMetersPerSecond)
        }.getOrDefault(currentTrainSnapshot)
    }

    private fun createStallReport(failure: Exception): AtoStallReport = AtoStallReport(
        worldId = atoCoordinator.worldId,
        trainId = train.id,
        tick = atoTick,
        errorType = failure::class.qualifiedName ?: failure::class.java.name,
        message = failure.message,
        stackTrace = failure.stackTraceToString(),
        initialTrain = atoCoordinator.initialTrain(train.id),
        currentTrain = runCatching { captureTrainSnapshot(atoTick, activeSpeed) }.getOrNull(),
        route = latestRouteSnapshot,
        perception = latestPerceptionSnapshot,
        activeProviderId = activeProviderId(),
        constraints = constraintSnapshot()
    )

    private fun captureTrainSnapshot(
        revision: Long,
        speedMetersPerSecond: Double = train.speed
    ): TrainSnapshot {
        val facing = (train.headLocation as? RailLocationWithFacing)?.facing
            ?: error("ATO requires a facing-aware train head location")
        return TrainSnapshot.capture(train, revision, facing).copy(
            speedMetersPerSecond = speedMetersPerSecond
        )
    }
}
