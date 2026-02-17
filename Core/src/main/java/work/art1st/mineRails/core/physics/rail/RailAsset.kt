package work.art1st.mineRails.core.physics.rail

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.physics.World

class RailAsset(
    override val position: Vector3dc,
    override val direction: Vector3dc,
    override val normal: Vector3dc,
    override val world: World,
    override var asset: Asset,
) : Renderable {
    override val velocity: Vector3dc
        get() = Vector3d(0.0, 0.0, 0.0)
}