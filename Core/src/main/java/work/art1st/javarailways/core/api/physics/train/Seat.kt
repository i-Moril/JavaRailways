package work.art1st.javarailways.core.api.physics.train

import org.joml.Vector3dc

/**
 * 座位接口。
 * 座位的世界坐标由车厢的位置和朝向计算得出。
 */
interface Seat {
    val car: Car
    /** 车厢内的座位编号，从 0 开始 */
    val seatIndex: Int
    /** 座位所在位置（世界坐标） */
    val position: Vector3dc
    /** 座位朝向（乘客面朝方向） */
    val direction: Vector3dc
}
