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
    val position: Vector3dc = Vector3d(0.0, 0.0, 0.0),
    val rotation: Quaterniondc = Quaterniond(1.0, 0.0, 0.0, 0.0),
    val scale: Vector3dc = Vector3d(1.0, 1.0, 1.0),
) {
    constructor(transform: CompactTransform) : this(
        Vector3d(transform.position),
        Quaterniond(transform.rotation),
        Vector3d(transform.scale)
    )

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

        // 缩放相乘
        val finalScale = Vector3d(baseTransform.scale.x() * scale.x(), baseTransform.scale.y() * scale.y(), baseTransform.scale.z() * scale.z())

        return CompactTransform(
            offset,
            rotation,
            finalScale
        )
    }
}

fun Renderable.getTransform(): CompactTransform {
    // 构建正交基 (Orthogonal Basis)
    // right = normal × direction（右手系）
    val right = Vector3d()
    this.normal.cross(this.direction, right).normalize()

    // Matrix3d(a,b,c,d,e,f,g,h,i) fills row-major: Row0=(a,b,c), Row1=(d,e,f), Row2=(g,h,i)
    // Col0 = world dir of model+X = right, Col1 = normal, Col2 = direction
    // 然后取共轭（取反 X 轴）：q' = (w, -x, y, z)，将绕 Y 轴旋转方向取反
    val currentRotationMatrix = Matrix3d(
        right.x(), this.normal.x(), this.direction.x(),
        right.y(), this.normal.y(), this.direction.y(),
        right.z(), this.normal.z(), this.direction.z()
    )
    val q = Quaterniond().setFromNormalized(currentRotationMatrix)
    val finalRotation = Quaterniond(q.w(), -q.x(), q.y(), q.z())

    return CompactTransform(
        position = this.position,
        rotation = finalRotation
    )
}

fun CompactTransform.toBukkitTransformation(): Transformation {
    return Transformation(
        this.position.get(Vector3f()),
        Quaternionf(1.0f, 0.0f, 0.0f, 0.0f),
        this.scale.get(Vector3f()),
        this.rotation.get(Quaternionf()),
    )
}