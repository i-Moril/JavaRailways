package work.art1st.mineRails.core.control.train

import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.control.command.ControlCommand
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.util.WeakReferenceWrapper

abstract class TrainController(
    train: CoreTrain,
    world: CoreWorld
) : TickingObject, Synchronizable {
    protected val train: CoreTrain by WeakReferenceWrapper(train)
    /**
     * Meters per second, >=0
     */
    var speed: Double = 0.0
        private set
    protected var pendingCommand: ControlCommand? = null
        private set

    protected fun setCommand(command: ControlCommand) {
        if (command.speed < 0 || command.deltaS < 0) {
            //TODO: Warning
            return
        }
        pendingCommand = command
    }

    protected fun reverse() {
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