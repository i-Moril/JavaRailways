package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.asset.Asset
import work.art1st.mineRails.core.asset.Renderable
import work.art1st.mineRails.core.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.ReversibleRailObject
import work.art1st.mineRails.core.util.CommitBuffer

/**
 * 转向架类
 * 转向架位于铁轨上，即转向架坐标位于曲线上，方向为曲线的切线方向
 */
open class Bogie internal constructor(
    location: RailLocationWithFacing,
    override var asset: Asset<Any>,
) : ReversibleRailObject, MovingObject, Renderable {
    private var _location = CommitBuffer(location)
    override var location: RailLocationWithFacing
        get() = _location.get()
        set(value) = _location.set(value)

    val railView
        get() = location.railView

    override val world
        get() = location.rail.world

    fun peekCurve(distance: Double) = location.peekCurve(distance)

    @Throws(BogieException::class)
    fun moveForward(distance: Double) {
        location = location.forward(distance) ?: throw BogieException(this)
    }

    override fun commit() {
        world.updateAssetPosition(this, position, _location.getActive().position)
        _location.commit()
    }
}

