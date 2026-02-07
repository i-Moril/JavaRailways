package work.art1st.mineRails.core.asset

import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.core.util.Vector3D

interface Renderable : Destroyable {
    val position: Vector3D
    val direction: Vector3D
    val normal: Vector3D
    val velocity: Vector3D
    val world: World
    var asset: Asset

    val chunk: RenderChunk
        get() = RenderChunk(position)

    override fun destroy() = world.removeAsset(this)
}
