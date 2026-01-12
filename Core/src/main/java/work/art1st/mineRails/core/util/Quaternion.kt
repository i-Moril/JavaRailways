package work.art1st.mineRails.core.util

import kotlin.math.abs
import kotlin.math.sqrt

class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        /**
         * 从方向向量和指向上方的法向量创建四元数
         * 确保 direction 和 normal 正交
         * 
         * @param direction 前向方向向量
         * @param normal 指向上方的法向量
         * @return 表示该旋转的四元数
         */
        fun fromDirectionAndNormal(direction: Vector3D, normal: Vector3D): Quaternion {
            // 归一化方向向量
            val forward = direction.copy().normalize()
            
            // 归一化法向量
            val up = normal.copy().normalize()
            
            // 确保 forward 和 up 正交
            // 如果它们不正交，将 up 投影到垂直于 forward 的平面上
            val dot = forward.dot(up)
            if (abs(dot) > 1e-6) {
                // up = up - (up · forward) * forward
                up.subtract(forward.copy().multiply(dot))
                up.normalize()
            }
            
            // 计算右向量：right = forward × up
            val right = forward.copy().crossProduct(up.copy())
            right.normalize()
            
            // 从旋转矩阵构建四元数
            // 旋转矩阵的列向量是 [right, up, forward]
            // 在 Minecraft 坐标系中：X=右，Y=上，Z=前
            val m00 = right.x
            val m01 = right.y
            val m02 = right.z
            val m10 = up.x
            val m11 = up.y
            val m12 = up.z
            val m20 = forward.x
            val m21 = forward.y
            val m22 = forward.z
            
            // 计算四元数
            val trace = m00 + m11 + m22
            val w: Double
            val x: Double
            val y: Double
            val z: Double
            
            if (trace > 0) {
                val s = sqrt(trace + 1.0) * 2.0 // s = 4 * w
                w = 0.25 * s
                x = (m21 - m12) / s
                y = (m02 - m20) / s
                z = (m10 - m01) / s
            } else if ((m00 > m11) && (m00 > m22)) {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2.0 // s = 4 * x
                w = (m21 - m12) / s
                x = 0.25 * s
                y = (m01 + m10) / s
                z = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2.0 // s = 4 * y
                w = (m02 - m20) / s
                x = (m01 + m10) / s
                y = 0.25 * s
                z = (m12 + m21) / s
            } else {
                val s = sqrt(1.0 + m22 - m00 - m11) * 2.0 // s = 4 * z
                w = (m10 - m01) / s
                x = (m02 + m20) / s
                y = (m12 + m21) / s
                z = 0.25 * s
            }
            
            return Quaternion(x, y, z, w)
        }
    }
}