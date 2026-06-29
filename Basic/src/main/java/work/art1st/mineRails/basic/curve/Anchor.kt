package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * 锚点：一个空间参考点，带有位置和切线方向。
 */
data class Anchor(
    val id: String,
    val position: Vector3dc,
    val tangent: Vector3dc,
    val worldId: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /** 复制并更新位置 */
    fun withPosition(pos: Vector3dc): Anchor = copy(position = Vector3d(pos))

    /** 复制并更新切线 */
    fun withTangent(tan: Vector3dc): Anchor = copy(tangent = Vector3d(tan))
}
