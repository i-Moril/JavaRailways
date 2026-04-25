package work.art1st.mineRails.bukkit.render.persistence

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.joml.Vector3d
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.configLib.persistence.Loads
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.render.config.AnimationConfig

@Serializable
class AnimationConfigV1(
    val commands: List<AnimationCommandConfig> = listOf()
) : AnimationConfig {
    override fun load(dependency: Renderable): Animation {
        return Animation(
            commandList = commands.map { it.load(dependency) }
        )
    }

    @Serializable
    data class AnimationCommandConfig(
        val position: Vector3d = Vector3d(),
        val rotation: Vector3d = Vector3d(),
        val deltaT: Double = 0.0
    ) : Loads<Animation.AnimationCommand, Renderable> {
        override fun load(dependency: Renderable): Animation.AnimationCommand {
            return Animation.AnimationCommand(
                transform = work.art1st.mineRails.bukkit.render.model.CompactTransform(
                    position = position,
                    rotation = org.joml.Quaterniond().rotateXYZ(
                        Math.toRadians(rotation.x),
                        Math.toRadians(rotation.y),
                        Math.toRadians(rotation.z)
                    )
                ),
                deltaT = deltaT
            )
        }
    }
}