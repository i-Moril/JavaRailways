package work.art1st.mineRails.core.physics.train

import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.unaryMinus
import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.physics.ReversibleRailObject
import work.art1st.mineRails.core.api.render.SynchronizedRenderable
import work.art1st.mineRails.core.util.CommitBuffer
import work.art1st.mineRails.core.api.util.SynchronizedObject

/**
 * 转向架类
 * 转向架位于铁轨上，即转向架坐标位于曲线上，方向为曲线的切线方向
 */
open class Bogie internal constructor(
    location: RailLocationWithFacing,
    /* isSameDirectionAsFacing: direction是否与location的facing一致. */
    private var isSameDirectionAsFacing: Boolean = true,
) : ReversibleRailObject, SynchronizedRenderable() {
    init {
        lastState = takeSnapshot()
    }

    private val _location = CommitBuffer(location)
    override var location: RailLocationWithFacing by _location
    internal val activeLocation
        get() = _location.getActive()

    override val position
        get() = location.position
    /* direction: Asset显示的方向 */
    override val direction
        get() = if (isSameDirectionAsFacing) location.tangent else -location.tangent
    override val normal
        get() = location.normal
    private val _velocity: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 0.0, 0.0))
    /* velocity: 速度的大小和方向 */
    override var velocity: Vector3dc by _velocity
    internal val activeVelocity
        get() = _velocity.getActive()

    override val world
        get() = location.rail.world

    fun peekCurve(distance: Double) = location.peekCurve(distance)

    @Throws(BogieException::class)
    fun moveForward(distance: Double) {
        location = location.forward(distance) ?: throw BogieException(this)
    }

    override fun commit() {
        _location.commit()
        _velocity.commit()
        super.commit()
    }

    override fun reverse() {
        super.reverse()
        isSameDirectionAsFacing = !isSameDirectionAsFacing
    }
}

