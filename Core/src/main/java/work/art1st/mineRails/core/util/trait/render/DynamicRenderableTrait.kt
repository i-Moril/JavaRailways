package work.art1st.mineRails.core.util.trait.render

import work.art1st.mineRails.core.api.render.RenderableStateSnapshot
import work.art1st.mineRails.core.api.util.tick.Synchronizable

internal interface DynamicRenderableTrait<T> : ExtendableRenderableTrait<T>, Synchronizable {
    /**
     * 如果在该Tick内需要重新渲染则设置dirty，否则不会触发commitEvent
     */
    var dirty: Boolean
    override var lastState: RenderableStateSnapshot

    fun takeSnapshot(): RenderableStateSnapshot {
        return RenderableStateSnapshot(
            position = position,
            direction = direction,
            normal = normal,
            velocity = velocity,
            world = world
        )
    }

    override fun commit() {
        if (dirty) {
            forEachExtensionData {
                it.commit()
            }
            lastState = takeSnapshot()
            dirty = false
        }
    }
}