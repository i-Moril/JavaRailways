package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.util.Destroyable

interface Renderable : Destroyable {
    val position: Vector3dc
    val direction: Vector3dc
    val normal: Vector3dc
    val velocity: Vector3dc
    val world: World
    var asset: Asset

    override fun destroy() = asset.destroy()
}
