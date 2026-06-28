package work.art1st.mineRails.core.api.control

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.WeakReferenceWrapper

abstract class TrainController(
    train: Train,
    world: World
) : TickingObject, Synchronizable {
    protected val train: Train by WeakReferenceWrapper(train)
    /**
     * Meters per second, >=0
     */
    var speed: Double = 0.0
        protected set
    protected var pendingCommand: ControlCommand? = null
        private set

    protected fun setCommand(command: ControlCommand) {
        if (command.speed < 0 || command.deltaS < 0) {
            //TODO: Warning
            return
        }
        pendingCommand = command
    }

    open fun reverse() {
        train.reverse()
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

    override fun commit() {
        train.commit()
    }
}
