package work.art1st.javarailways.bukkit.render.chunk

import java.util.concurrent.CompletionStage
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.render.model.OrdinaryModel
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.render.Renderable

/** Ordinary presentation adapter in the existing spatial audience index, never a railway object. */
internal class OrdinaryModelRenderable(
    override val world: World,
    private val closeHandle: () -> CompletionStage<Void>
) : Renderable {
    @Volatile private var pose = ModelPose.IDENTITY
    @Volatile private var model: OrdinaryModel? = null
    val isPublished: Boolean get() = model != null
    override val position: Vector3dc get() = Vector3d(pose.x(), pose.y(), pose.z())
    override val direction: Vector3dc get() = Vector3d(0.0, 0.0, 1.0)
    override val normal: Vector3dc get() = Vector3d(0.0, 1.0, 0.0)
    override val velocity: Vector3dc get() = Vector3d()

    fun publish(pose: ModelPose, model: OrdinaryModel) { this.pose = pose; this.model = model }
    fun withdraw() { model = null }
    fun showTo(player: Player) { model?.showTo(player) }
    fun hideFrom(player: Player) { model?.hideFrom(player) }
    override fun destroy() = closeHandle()
}
