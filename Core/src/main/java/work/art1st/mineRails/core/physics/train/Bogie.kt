package work.art1st.mineRails.core.physics.train

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.physics.ReversibleRailObject
import work.art1st.mineRails.core.util.CommitBuffer
import work.art1st.mineRails.core.util.PersistentObject
import work.art1st.mineRails.core.api.math.Vector3D

/**
 * 转向架类
 * 转向架位于铁轨上，即转向架坐标位于曲线上，方向为曲线的切线方向
 */
open class Bogie internal constructor(
    location: RailLocationWithFacing,
    // direction是否与location的facing一致
    private var isSameDirectionAsFacing: Boolean,
    override var asset: Asset,
) : ReversibleRailObject, SynchronizedObject, Renderable, PersistentObject<Bogie.SavedBogieV1> {
    internal var _location = CommitBuffer(location)
    override var location: RailLocationWithFacing
        get() = _location.get()
        set(value) = _location.set(value)

    override val position
        get() = location.position
    override val direction
        get() = if (isSameDirectionAsFacing) location.tangent else -location.tangent
    override val normal
        get() = location.normal
    internal var _velocity: CommitBuffer<Vector3D> = CommitBuffer(Vector3D(0.0, 0.0, 0.0))
    override var velocity: Vector3D
        get() = _velocity.get()
        set(value) = _velocity.set(value)

    override val world
        get() = location.rail.world

    fun peekCurve(distance: Double) = location.peekCurve(distance)

    @Throws(BogieException::class)
    fun moveForward(distance: Double) {
        location = location.forward(distance) ?: throw BogieException(this)
    }

    override fun commit() {
        world.updateRenderablePosition(this, position, _location.getActive().position)
        _location.commit()
        _velocity.commit()
    }

    override fun reverse() {
        super.reverse()
        isSameDirectionAsFacing = !isSameDirectionAsFacing
    }

    override fun save(): SavedBogieV1 {

    }

    @Serializable
    data class SavedBogieV1(
        val assetId: String,
        val locationWithFacing: RailLocationWithFacing,
    )
}

