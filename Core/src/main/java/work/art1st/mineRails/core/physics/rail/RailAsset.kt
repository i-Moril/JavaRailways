package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.asset.Asset
import work.art1st.mineRails.core.asset.Renderable
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.util.Vector3D

class RailAsset(
    override val position: Vector3D,
    override val direction: Vector3D,
    override val normal: Vector3D,
    override val world: World,
    override var asset: Asset<Any>
) : Renderable {
}