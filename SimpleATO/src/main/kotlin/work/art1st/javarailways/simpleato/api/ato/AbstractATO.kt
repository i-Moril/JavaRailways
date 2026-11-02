package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.simpleato.control.*
import work.art1st.javarailways.simpleato.api.atp.AtpTarget
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.ConsistDynamicsActuator
import work.art1st.javarailways.basic.control.DynamicsProfileSource
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
    safetyProfile: AtoSafetyProfile,
    protected val atoCoordinator: AtoWorldCoordinator
) : TrainController(train) {
    private val consistSafety = ControllerConsistState(safetyProfile)
    private val unboundControlRuntime by lazy { RailwayControlRuntime(atoCoordinator.worldId) }
    protected val railwayControlRuntime: RailwayControlRuntime
        get() = world.simpleAtoWorldDataOrNull?.controlRuntime ?: unboundControlRuntime
    protected val safetyProfile: AtoSafetyProfile get() = consistSafety.value

    /**
     * Prepare intrinsic actuator bindings and derived braking limits while physics is excluded.
     * All lookups/derivation may fail here without changing controller state. Core later installs
     * both reference updates before physics resumes; this is not a cross-thread multi-cell snapshot.
     * Preserve non-braking safety overrides and actuator history. Actuators without intrinsic
     * consist dynamics have no such bindings or limits to replace and return no updates.
     */
    override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> {
        val dynamics = actuator as? ConsistDynamicsActuator ?: return emptyList()
        val prepared = dynamics.prepareDynamicsChange(cars, counterpart as? DynamicsProfileSource)
        val safety = safetyProfile.copy(
            serviceDecelerationMetersPerSecondSquared = prepared.serviceDecelerationMetersPerSecondSquared,
            reactionTimeSeconds = prepared.reactionTimeSeconds
        )
        return listOf(prepared.update, consistSafety.prepare(safety))
    }
    fun prepareConfigurationChange(
        trainProfile: TrainDynamics.TrainProfile,
        carProfiles: List<TrainDynamics.CarProfile>
    ): List<PreparedControllerStateUpdate> {
        val handles = actuator as? HandleTrainActuator
            ?: error("ATO actuator does not support configuration replacement")
        val updates = handles.prepareConfigurationChange(trainProfile, carProfiles)
        val safety = safetyProfile.copy(
            serviceDecelerationMetersPerSecondSquared = TrainDynamics.flatServiceDeceleration(
                trainProfile, carProfiles, handles.gravityMetersPerSecondSquared),
            reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                trainProfile, carProfiles, handles.gravityMetersPerSecondSquared)
        )
        return updates + consistSafety.prepare(safety)
    }

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
        railwayControlRuntime.prepare(train, actuator.controlCapabilities(train.speed))
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
            if (deltaTimeSeconds <= 0.0) return
            val command = railwayControlRuntime.automatic(train, actuator.controlState(activeSpeed),
                actuator.controlCapabilities(activeSpeed), motionControlFrame(demand), deltaTimeSeconds, controlMissionRevision())
            val step = actuator.step(activeSpeed, command, deltaTimeSeconds)
            val distance = constrainMovementDistance(step.distanceMeters)
            check(distance.isFinite() && distance >= 0.0 && distance <= step.distanceMeters) {
                "controller movement boundary may only shorten the actuator step"
            }
            speed = step.speedMetersPerSecond
            moveControlledTrain(distance, step.speedMetersPerSecond)
            lastTravelDistanceMeters = distance
            activeLifetimeTravelDistanceMeters += distance
        } catch (failure: Exception) {
            atoCoordinator.stall(createStallReport(failure))
            freeze()
        }
    }

    /** Publish mission/inspection readiness without advancing actuator history or physical motion. */
    final override fun prepareWhileFormationHeld(deltaT: Double) {
        atoTick++
        speed = 0.0
        // The last movement belongs to the pre-publication formation, never the new mission.
        lastTravelDistanceMeters = 0.0
        if (atoCoordinator.isStalled) return
        try {
            processPendingCommands()
            plan(deltaT * 0.001)
            validateBeforeMovement()
        } catch (failure: Exception) {
            atoCoordinator.stall(createStallReport(failure))
        } finally {
            speed = 0.0
            lastTravelDistanceMeters = 0.0
        }
    }

    protected open fun processPendingCommands() = Unit

    protected abstract fun plan(deltaTimeSeconds: Double): ControlDemand

    /** Adapters with route semantics override this; the generic controller owns only its demand. */
    protected open fun motionControlFrame(demand: ControlDemand): TrainControlFrame = TrainControlFrame(
        listOf(MotionReferenceConstraint("planner/demand", 0.0, demand.targetSpeedMetersPerSecond)),
        listOf(AtpTarget("vehicle/maximum", 0.0, actuator.maximumSpeedMetersPerSecond)),
        actuator.controlCapabilities(activeSpeed).worstCurrentGrade)

    protected open fun controlMissionRevision(): Long? = null

    /** Refresh a routed manual transfer using current geometry, without integrating motion. */
    protected fun refreshTransferredProtection(deltaSeconds: Double): TrainControlFrame {
        speed = train.speed
        lastTravelDistanceMeters = 0.0
        processPendingCommands()
        val demand = plan(deltaSeconds)
        validateBeforeMovement()
        return motionControlFrame(demand)
    }

    protected open fun validateBeforeMovement() = Unit

    /** Exact host physical boundary; ordinary movement retains the complete actuator result. */
    protected open fun constrainMovementDistance(requestedDistanceMeters: Double): Double = requestedDistanceMeters

    protected open fun constraintSnapshot(): Map<ConstraintOwner, List<AtoConstraint>> = emptyMap()

    private fun freeze() {
        actuator.freeze()
        speed = 0.0
        moveControlledTrain(0.0, 0.0)
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
        return TrainSnapshot.capture(train, revision).copy(
            speedMetersPerSecond = speedMetersPerSecond
        )
    }
}
