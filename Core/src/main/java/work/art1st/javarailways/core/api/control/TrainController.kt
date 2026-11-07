package work.art1st.javarailways.core.api.control

import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickProfiled
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.Car
import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.util.CommitBuffer
import java.util.concurrent.CompletableFuture

/**
 * Base class for a controller evaluated by the owning world's physics Coordinator.
 *
 * [tick] receives elapsed milliseconds. Implementations should derive movement commands without
 * accessing Bukkit APIs or blocking the physics worker. Controller state becomes externally visible
 * only after the complete physics epoch is published.
 *
 * @param train controlled train; retained while this controller exists
 */
abstract class TrainController(
    train: Train
) : TickingObject, Synchronizable, TickProfiled {
    private val trainId: String = train.id
    val train: Train = train

    override val profileCategory: String = "train-controller"
    override val profileId: String get() = trainId
    /** Meters per second, >=0. Published only after the whole physics epoch finishes. */
    private val speedBuffer = CommitBuffer(0.0)
    private var speedDirty: Boolean = false

    /** Published speed in metres per second. The setter is available to controller subclasses. */
    var speed: Double
        get() = speedBuffer.get()
        protected set(value) {
            speedBuffer.set(value)
            speedDirty = true
        }

    protected val activeSpeed: Double
        get() = if (speedDirty) speedBuffer.getActive() else speedBuffer.get()
    private val reverseRequestLock = Any()
    private var pendingReverseRequests = 0
    private var pendingReverse: CompletableFuture<Unit>? = null

    /**
     * Prepare controller state for [cars] without mutating either source, cars or external state.
     * Called on the structural simulation owner with physics excluded. [counterpart] is the other
     * coupling controller, or null for a split or a controllerless incoming train. Failure rejects
     * the structural operation.
     * Return only Core-created reference updates; Core installs them after all preparation succeeds.
     * Default controllers have no consist-dependent state. No controller instance/input is replaced.
     */
    @ApiStatus.Experimental
    open fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> =
        emptyList()

    /**
     * Queue reversal on the world's simulation owner between complete physics frames.
     * Pairs of requests captured by the same queued operation cancel out. The future completes
     * after application (or cancellation by parity); a detached controller or unavailable world
     * fails it. Do not wait on the owner responsible for processing this queue. Cancelling an
     * individual returned future does not cancel other callers' requests. The next physics tick
     * runs normally from the resulting direction; no movement-free epoch is inserted.
     */
    open fun reverse(): CompletableFuture<Unit> = synchronized(reverseRequestLock) {
        val controlledTrain = train as? CoreTrain
            ?: return@synchronized CompletableFuture.failedFuture(
                IllegalStateException("Controller reversal requires a coordinated Core train")
            )
        pendingReverseRequests++
        val operation = pendingReverse ?: controlledTrain.world.physicsCoordinator.submitBetweenFrames {
            val requestCount = synchronized(reverseRequestLock) {
                pendingReverseRequests.also {
                    pendingReverseRequests = 0
                    pendingReverse = null
                }
            }
            check(controlledTrain.world.getTrain(controlledTrain.id) === controlledTrain &&
                controlledTrain.controller === this) {
                "Reversal controller no longer owns train '${controlledTrain.id}'"
            }
            if (requestCount % 2 != 0) controlledTrain.reverse()
        }.also { submitted ->
            pendingReverse = submitted
            submitted.whenComplete { _, _ ->
                synchronized(reverseRequestLock) {
                    if (pendingReverse === submitted) {
                        pendingReverse = null
                        pendingReverseRequests = 0
                    }
                }
            }
        }
        operation.copy()
    }

    /**
     * Prepare non-motion controller state under a formation fence on the physics worker.
     * Core calls this instead of [tick] only when no configuration quarantine, derailment or
     * collision is pending. Implementations may process commands and publish planning state;
     * they must not actuate, move, reverse or change physical structure. Core freezes motion
     * unconditionally afterward. The default controller has nothing to prepare.
     */
    open fun prepareWhileFormationHeld(deltaT: Double) = Unit

    /** Publishes a stopped motion state when collision arbitration freezes this controller. */
    internal fun freezeMotionForPhysicsEpoch() {
        speed = 0.0
        (train as? CoreTrain)?.clearActiveMotionFromPhysics()
    }

    /**
     * Commit the stopped result of coupling while the structural owner excludes physics.
     * Does not cancel/rewrite commands, handles or ATO demand, or invoke subclass callbacks.
     * A subsequent control tick may legitimately produce movement using those existing inputs.
     */
    internal fun commitStructuralStop() {
        speedBuffer.set(0.0)
        speedBuffer.commit()
        speedBuffer.set(0.0)
        speedDirty = false
    }

    override fun tick(deltaT: Double) {
        // Apply this tick's active speed to this tick's movement; the published value belongs to
        // the previous epoch until the coordinator commits it.
        val currentSpeed = activeSpeed
        moveControlledTrain(currentSpeed * deltaT * 0.001, currentSpeed)
    }

    /**
     * Applies one controller result using metres and metres per second from the same physics tick.
     *
     * Controller subclasses that override [tick] must use this seam instead of calling
     * [Train.moveTrainForward] directly. Core can then publish authoritative car/bogie velocity;
     * non-Core implementations retain the public distance-only fallback.
     */
    protected fun moveControlledTrain(deltaS: Double, speedMetersPerSecond: Double) {
        val controlledTrain = train
        if (controlledTrain is CoreTrain) {
            controlledTrain.moveTrainForwardFromController(deltaS, speedMetersPerSecond)
        } else {
            controlledTrain.moveTrainForward(deltaS)
        }
    }

    /** Core-only buffer flip, serialized with coordinator close; does not invoke extension hooks. */
    internal fun publishPhysicsBuffers() {
        if (speedDirty) {
            speedBuffer.commit()
            speedDirty = false
        }
        (train as? CoreTrain)?.publishPhysicsState()
    }

    /** Runs outside coordinator publication locks, after the complete train frame is published. */
    internal fun notifyPhysicsStatePublished() = onPhysicsStatePublished()

    /** Publishes controller-owned value state only after the complete physical frame is available. */
    protected open fun onPhysicsStatePublished() = Unit

    /** Explicit publication uses the same frame-before-notification order as a physics epoch. */
    override fun commit() {
        if (speedDirty) {
            speedBuffer.commit()
            speedDirty = false
        }
        train.commit()
        notifyPhysicsStatePublished()
    }
}
