package work.art1st.javarailways.basic.controller

import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.control.TrainController
import java.util.concurrent.atomic.AtomicReference

/**
 * 手动控制器：玩家通过加速/减速手柄控制列车。
 * 速度变化遵循线性加减速，每 tick 应用固定加速度。
 *
 * @param train 控制的列车
 * @param acceleration 加速度 (m/s²)，默认 1.0
 * @param deceleration 减速度 (m/s²)，默认 2.0（制动比加速更快）
 * @param maxSpeed 最大速度 (m/s)，默认 10.0
 */
class ManualController(
    train: Train,
    private val acceleration: Double = 1.0,
    private val deceleration: Double = 2.0,
    private val maxSpeed: Double = 10.0
) : TrainController(train) {

    private data class ManualInput(val throttle: Double, val targetSpeed: Double)

    private val input = AtomicReference(ManualInput(0.0, 0.0))

    /** 目标速度 (m/s)，由玩家通过手柄设置，始终 >= 0。 */
    val targetSpeed: Double get() = input.get().targetSpeed

    /** 加速手柄位置，范围 [-1, 1]；正值加速，负值制动。 */
    val throttle: Double get() = input.get().throttle

    /**
     * 设置加速手柄位置。
     * @param value 范围 [-1, 1]，正值加速，负值减速/制动
     */
    fun setThrottle(value: Double) {
        val throttle = value.coerceIn(-1.0, 1.0)
        input.set(ManualInput(throttle, if (throttle >= 0.0) throttle * maxSpeed else 0.0))
    }

    /**
     * 紧急制动：立即将速度设为 0 并阻止加速
     */
    fun emergencyBrake() {
        input.set(ManualInput(-1.0, 0.0))
    }

    /**
     * 惰行（滑行）：解除制动但目标速度归零
     */
    fun coast() {
        input.set(ManualInput(0.0, 0.0))
    }

    val controlledTrain: Train
        get() = train

    override fun tick(deltaT: Double) {
        // deltaT 单位为毫秒，转换为秒
        val deltaS = deltaT * 0.001
        val targetSpeed = input.get().targetSpeed

        if (speed < targetSpeed) {
            // 加速
            speed = minOf(speed + acceleration * deltaS, targetSpeed)
        } else if (speed > targetSpeed) {
            // 减速/制动
            speed = maxOf(speed - deceleration * deltaS, targetSpeed)
        }

        // 速度不能为负
        if (speed < 0) speed = 0.0

        super.tick(deltaT)
    }
}
