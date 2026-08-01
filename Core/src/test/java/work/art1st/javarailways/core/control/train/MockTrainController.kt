package work.art1st.javarailways.core.control.train

import work.art1st.javarailways.core.api.control.ControlCommand
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.train.CoreTrain

/**
 * 测试用：恒定速度的 TrainController。
 * 通过一直持有一个”未完成”的 ControlCommand，使每 tick 移动 distance = speed * deltaT * 0.001。
 */
class MockTrainController(
    train: CoreTrain,
    world: World,
    val constantSpeed: Double
) : TrainController(train, world) {

    init {
        setCommand(ConstantSpeedCommand(constantSpeed))
    }

    private class ConstantSpeedCommand(private val constantSpeed: Double) : ControlCommand {
        private var lastDeltaT: Double = 0.0

        override fun tick(deltaT: Double) {
            lastDeltaT = deltaT
        }

        override val speed: Double get() = constantSpeed
        override val deltaS: Double get() = constantSpeed * lastDeltaT * 0.001
        override val isFinished: Boolean get() = false
    }
}
