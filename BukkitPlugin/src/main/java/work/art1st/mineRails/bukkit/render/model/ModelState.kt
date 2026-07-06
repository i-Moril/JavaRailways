package work.art1st.mineRails.bukkit.render.model

import org.joml.Vector3d
import org.joml.*

class ModelState(
    val localTransform: CompactTransform,
) {
    // 相对根元素的变换
    var rootRelativeTransform: CompactTransform = CompactTransform(localTransform)
        private set
    var worldTransform: CompactTransform = CompactTransform()
        private set

    val position: Vector3dc
        get() = worldTransform.position
    val x: Double
        get() = worldTransform.position.x()
    val y: Double
        get() = worldTransform.position.y()
    val z: Double
        get() = worldTransform.position.z()
    val pitch: Double
        get() = worldTransform.pitch
    val yaw: Double
        get() = worldTransform.yaw

    /**
     * @param baseRelative 父元素相对根元素的transform
     */
    fun applyRotation(baseRelative: CompactTransform, root: CompactTransform, animationTransformOverlay: CompactTransform? = null) {
        val localRotation = Quaterniond(localTransform.rotation)
        val offset = Vector3d(localTransform.position)
        val localScale = Vector3d(localTransform.scale)

        // 先apply animation transformation
        animationTransformOverlay?.let {
            // 偏移相加
            offset.add(it.position)

            // 局部旋转右乘相对旋转
            localRotation.mul(it.rotation)

            // 缩放叠加
            localScale.mul(it.scale)
        }

        rootRelativeTransform = CompactTransform(offset, localRotation, localScale).applyTo(baseRelative)
        worldTransform = rootRelativeTransform.applyTo(root)
    }
}