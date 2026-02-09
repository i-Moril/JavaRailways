package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.math.Vector3D

class RailAsset(
    override val position: Vector3D,
    override val direction: Vector3D,
    override val normal: Vector3D,
    override val world: World,
    override var asset: Asset,
) : Renderable {
    override val velocity: Vector3D
        get() = Vector3D.ZERO
}