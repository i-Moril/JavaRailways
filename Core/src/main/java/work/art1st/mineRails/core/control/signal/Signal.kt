package work.art1st.mineRails.core.control.signal

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.physics.rail.RailLocationWithoutFacing

/**
 * Runtime signal abstraction.
 *
 * Signals are intentionally not marked serializable until a versioned, polymorphic persistence
 * format exists for concrete signal types.
 */
abstract class Signal(
    final override val ref: RailObjectRef,
    override val location: RailLocationWithoutFacing
) : Renderable, RailObject {
    abstract override val lifecycleState: RailObjectLifecycleState
    override val position: Vector3dc
        get() = location.position
    override val direction: Vector3dc
        get() = location.tangent
    override val normal: Vector3dc
        get() = location.normal
    override val world: World
        get() = location.rail.world
}
