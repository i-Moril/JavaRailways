package work.art1st.javarailways.bukkit.extension.train

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot

/** Canonical car/bogie pose shared by model rendering and passenger surface projection. */
internal fun RenderableFrameSnapshot.modelTransform(floorHeight: Double = 0.0) = CompactTransform(
    positionVector().add(normalVector().mul(floorHeight)), modelRotation(directionVector(), normalVector())
)

internal fun modelRotation(direction: Vector3dc, normal: Vector3dc): Quaterniond {
    val right = normal.cross(direction, Vector3d()).normalize()
    return Quaterniond().setFromNormalized(Matrix3d(
        right.x(), right.y(), right.z(), normal.x(), normal.y(), normal.z(),
        direction.x(), direction.y(), direction.z()
    ))
}
