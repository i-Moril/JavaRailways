package work.art1st.mineRails.bukkit.asset.model

import org.bukkit.util.Transformation
import org.joml.Vector3d
import org.joml.*
import work.art1st.mineRails.bukkit.asset.Animation
import work.art1st.mineRails.core.api.render.Renderable

class ModelState(
    val transformation: Transformation,
    val model: Model,
    val animations: Map<String, Animation>
) {
    var animationTransformationOverlay: Transformation = Transformation(
        Vector3f(),
        Quaternionf(),
        Vector3f(),
        Quaternionf(),
    )

    fun applyAnimation(animation: Animation) {

    }

    fun applyRotation(renderable: Renderable): Transformation {
        // 1. 构建正交基 (Orthogonal Basis)
        // 在 MC 右手系中：Right = Up x Forward
        val right = Vector3d()
        renderable.normal.cross(renderable.direction, right).normalize()

        // 2. 构建当前旋转矩阵
        // JOML Matrix3f 构造函数按列主序填充：
        // 第一列: Right (+X), 第二列: Up (+Y), 第三列: Forward (+Z)
        val currentRotMat = Matrix3d(
            right.x(),        right.y(),        right.z(),
            renderable.normal.x(), renderable.normal.y(), renderable.normal.z(),
            renderable.direction.x(), renderable.direction.y(), renderable.direction.z()
        )

        val rotation = Quaternionf().setFromNormalized(currentRotMat)

        // 先apply animation transformation
        val relativeRotation = Quaternionf(animationTransformationOverlay.rightRotation)
        relativeRotation.mul(transformation.rightRotation, relativeRotation)

        val translation = Vector3f(transformation.translation)
        translation.add(animationTransformationOverlay.translation)
        rotation.transform(translation)

        // 3. 应用旋转变换
        // 局部旋转公式：WorldQuat = CurrentQuat * DeltaQuat
        rotation.mul(transformation.rightRotation)

        // 4. 应用平移变换
        // 将局部坐标系下的位移向量旋转到世界坐标系的方向
        rotation.transform(translation)

        return Transformation(
            translation,
            Quaternionf(),
            Vector3f(),
            rotation,
        )
    }
}