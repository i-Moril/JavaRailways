package work.art1st.mineRails.bukkit.render.model

import org.bukkit.util.Transformation
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.mineRails.core.api.render.Renderable

data class CompactTransform(
    val position: Vector3dc = Vector3d(1.0 , 0.0, 0.0),
    val rotation: Quaterniondc = Quaterniond(1.0 , 0.0, 0.0,  0.0),
) {
    constructor(transform: CompactTransform) : this(Vector3d(transform.position), Quaterniond(transform.rotation))

    val eulerAngle: Vector3dc by lazy {
        rotation.getEulerAnglesXYZ(Vector3d())
    }
    val pitch: Double
        get() = eulerAngle.x()
    val yaw: Double
        get() = eulerAngle.y()
    val roll: Double
        get() = eulerAngle.z()

    /**
     * 左结合。localTransform.then(baseTransform)
     */
    fun applyTo(baseTransform: CompactTransform): CompactTransform {
        val localRotation = Quaterniond(rotation)
        val offset = Vector3d(position)

        // 应用平移变换
        // 将局部坐标系下的位移向量旋转到世界坐标系的方向
        val rotation = Quaterniond(baseTransform.rotation)
        rotation.transform(offset)
        offset.add(baseTransform.position)

        // 进行世界旋转
        // 局部旋转左乘世界旋转
        rotation.mul(localRotation)

        return CompactTransform(
            offset,
            rotation
        )
    }
}

fun Renderable.getTransform(): CompactTransform {
    // 1. 构建正交基 (Orthogonal Basis)
    // 在 MC 右手系中：Right = Up x Forward
    val right = Vector3d()
    this.normal.cross(this.direction, right).normalize()

    // 2. 构建当前旋转矩阵
    // JOML Matrix3f 构造函数按列主序填充：
    // 第一列: Right (+X), 第二列: Up (+Y), 第三列: Forward (+Z)
    val currentRotationMatrix = Matrix3d(
        right.x(), right.y(), right.z(),
        this.normal.x(), this.normal.y(), this.normal.z(),
        this.direction.x(), this.direction.y(), this.direction.z()
    )

    return CompactTransform(
        position = this.position,
        rotation = Quaterniond().setFromNormalized(currentRotationMatrix)
    )
}

fun CompactTransform.toBukkitTransformation(): Transformation {
    return Transformation(
        this.position.get(Vector3f()),
        Quaternionf(1.0f, 0.0f, 0.0f, 0.0f),
        Vector3f(1.0f, 1.0f, 1.0f),
        this.rotation.get(Quaternionf()),
    )
}