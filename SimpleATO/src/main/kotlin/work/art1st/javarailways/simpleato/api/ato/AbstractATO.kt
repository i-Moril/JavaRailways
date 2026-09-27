package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwner

/** Fixed ATO Tick boundary: plan, actuate, move, or freeze the world-local ATO subsystem. */
abstract class AbstractATO(
    train: Train,
    world: World,
    protected val actuator: TrainActuator,
    protected val safetyProfile: AtoSafetyProfile,
    protected val atoCoordinator: AtoWorldCoordinator
) : TrainController(train, world) {
    protected data class PublishedAtoState(
        val trainSnapshot: TrainSnapshot,
        val lifetimeTravelDistanceMeters: Double,
        val controllerState: Any?
    )

    val world: World = world

    protected var lastTravelDistanceMeters: Double = 0.0
        private set

    protected var latestRouteSnapshot: RouteSnapshot? = null
    protected var latestPerceptionSnapshot: PerceptionSnapshot? = null

    private var activeLifetimeTravelDistanceMeters: Double = 0.0
    private var atoTick: Long = 0L

    @Volatile
    private var publishedState = PublishedAtoState(
        trainSnapshot = captureTrainSnapshot(0L),
        lifetimeTravelDistanceMeters = 0.0,
        controllerState = null
    )

    val currentTrainSnapshot: TrainSnapshot
        get() = publishedState.trainSnapshot

    val lifetimeTravelDistanceMeters: Double
        get() = publishedState.lifetimeTravelDistanceMeters

    protected val activeLifetimeTravelDistance: Double
        get() = activeLifetimeTravelDistanceMeters

    init {
        atoCoordinator.recordInitialTrain(publishedState.trainSnapshot)
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
            activeLifetimeTravelDistanceMeters += step.distanceMeters
        } catch (failure: Exception) {
            atoCoordinator.stall(createStallReport(failure))
            freeze()
        }
    }

    protected open fun processPendingCommands() = Unit

    protected abstract fun plan(deltaTimeSeconds: Double): ControlDemand

    protected open fun validateBeforeMovement() = Unit

    protected open fun constraintSnapshot(): Map<ConstraintOwner, List<AtoConstraint>> = emptyMap()

    private fun freeze() {
        val frozen = actuator.freeze()
        speed = frozen.speedMetersPerSecond
        lastTravelDistanceMeters = 0.0
    }

    final override fun onPhysicsStatePublished() {
        publishedState = PublishedAtoState(
            trainSnapshot = captureTrainSnapshot(atoTick),
            lifetimeTravelDistanceMeters = activeLifetimeTravelDistanceMeters,
            controllerState = capturePublishedControllerState()
        )
        afterPhysicsStatePublished()
    }

    protected open fun capturePublishedControllerState(): Any? = null

    protected open fun afterPhysicsStatePublished() = Unit

    protected fun publishedAtoState(): PublishedAtoState = publishedState

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
