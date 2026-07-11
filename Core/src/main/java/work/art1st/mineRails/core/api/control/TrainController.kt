package work.art1st.mineRails.core.api.control

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickProfiled
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.WeakReferenceWrapper
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.util.CommitBuffer
import java.util.concurrent.atomic.AtomicInteger

abstract class TrainController(
    train: Train,
    world: World
) : TickingObject, Synchronizable, TickProfiled {
    protected val train: Train by WeakReferenceWrapper(train)
    override val profileCategory: String = "train-controller"
    override val profileId: String get() = train.id
    /** Meters per second, >=0. Published only after the whole physics epoch finishes. */
    private val speedBuffer = CommitBuffer(0.0)
    private var speedDirty: Boolean = false

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

    open fun reverse() {
        pendingReverseRequests.incrementAndGet()
    }

    /** Applies control mutations on the owning physics worker, never on the Bukkit thread. */
    internal fun preparePhysicsTick() {
        if (pendingReverseRequests.getAndSet(0) % 2 != 0) {
            val controlledTrain = train
            if (controlledTrain is CoreTrain) {
                controlledTrain.reverseFromPhysics()
            } else {
                controlledTrain.reverse()
            }
        }
    }

    override fun tick(deltaT: Double) {
        if (pendingCommand == null) {
            train.moveTrainForward(speed * deltaT * 0.001)
        } else {
            val cmd = pendingCommand!!
            cmd.tick(deltaT)
            train.moveTrainForward(cmd.deltaS)
            speed = cmd.speed
            if (cmd.isFinished) {
                pendingCommand = null
            }
        }
    }

    /** Called by PhysicsCoordinator after every worker has finished reading the previous epoch. */
    internal fun publishPhysicsState() {
        if (speedDirty) {
            speedBuffer.commit()
            speedDirty = false
        }
        (train as? CoreTrain)?.publishPhysicsState()
    }

    /** Main-thread render commit retained for API compatibility; physics publication is separate. */
    override fun commit() {
        publishPhysicsState()
        train.commit()
    }
}
