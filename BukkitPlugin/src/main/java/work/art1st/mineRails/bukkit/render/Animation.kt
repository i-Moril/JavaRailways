package work.art1st.mineRails.bukkit.render

import org.joml.Quaternionf
import org.joml.Vector3f
import work.art1st.mineRails.bukkit.render.model.ModelState

// TODO: Implement animation
class Animation(
    val commandList: List<AnimationCommand>
) {
    data class AnimationCommand(
        val rotation: Quaternionf,
        val translation: Vector3f,
        val deltaT: Double
    )

    fun apply(state: ModelState) {

    }
}