package work.art1st.javarailways.core.control.train

import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.physics.train.CoreTrain

/**
 * 测试用：恒定速度的 TrainController。
 * 基类每 tick 以 active speed 移动 distance = speed * deltaT * 0.001。
 */
class MockTrainController(
    train: CoreTrain,
    val constantSpeed: Double
) : TrainController(train) {

    init {
        speed = constantSpeed
    }
}
