package work.art1st.javarailways.core.api.control

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickProfiled
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.WeakReferenceWrapper
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.util.CommitBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for a controller evaluated by the owning world's physics Coordinator.
 *
 * [tick] receives elapsed milliseconds. Implementations should derive movement commands without
 * accessing Bukkit APIs or blocking the physics worker. Controller state becomes externally visible
 * only after the complete physics epoch is published.
 *
 * @param train controlled train; retained through a weak reference
 * @param world world that owns the train
 */
abstract class TrainController(
    train: Train,
    world: World
) : TickingObject, Synchronizable, TickProfiled {
    /** Controlled train. The weak reference fails fast after the train is no longer live. */
    val train: Train by WeakReferenceWrapper(train)
    override val profileCategory: String = "train-controller"
    override val profileId: String get() = train.id
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
    protected var pendingCommand: ControlCommand? = null
        private set
    private val pendingReverseRequests = AtomicInteger()

    protected fun setCommand(command: ControlCommand) {
        if (command.speed < 0 || command.deltaS < 0) {
            //TODO: Warning
            return
        }
        pendingCommand = command
    }

    /** Queue a direction reversal for the next physics tick; pairs of pending requests cancel out. */
    open fun reverse() {
        pendingReverseRequests.incrementAndGet()
    }

    /** Applies control mutations on the owning physics worker, never on the Bukkit thread. */
    internal fun preparePhysicsTick(): Boolean {
        if (pendingReverseRequests.getAndSet(0) % 2 == 0) return true
        val controlledTrain = train
        if (controlledTrain is CoreTrain) {
            controlledTrain.reverseFromPhysics()
        } else {
            controlledTrain.reverse()
        }
        // Publish the direction change as its own epoch. Movement resumes from that published state.
        return false
    }

    /** Publishes a stopped motion state when collision arbitration freezes this controller. */
    internal fun freezeMotionForPhysicsEpoch() {
        (train as? CoreTrain)?.clearActiveMotionFromPhysics()
    }

    override fun tick(deltaT: Double) {
        if (pendingCommand == null) {
            moveControlledTrain(speed * deltaT * 0.001, speed)
        } else {
            val cmd = pendingCommand!!
            cmd.tick(deltaT)
            speed = cmd.speed
            moveControlledTrain(cmd.deltaS, cmd.speed)
            if (cmd.isFinished) {
                pendingCommand = null
            }
        }
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

    /** Called by PhysicsCoordinator after every worker has finished reading the previous epoch. */
    internal fun publishPhysicsState() {
        if (speedDirty) {
            speedBuffer.commit()
            speedDirty = false
        }
        (train as? CoreTrain)?.publishPhysicsState()
        onPhysicsStatePublished()
    }

    /** Publishes controller-owned value state only after speed and train geometry are committed. */
    protected open fun onPhysicsStatePublished() = Unit

    /** Main-thread render commit retained for API compatibility; physics publication is separate. */
    override fun commit() {
        publishPhysicsState()
        train.commit()
    }
}
