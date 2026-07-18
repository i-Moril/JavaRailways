package work.art1st.mineRails.core.util.trait.render

import work.art1st.mineRails.core.api.render.RenderableStateSnapshot
internal interface DynamicRenderableTrait<T> : ExtendableRenderableTrait<T> {
    /**
     * 如果在该Tick内需要重新渲染则设置dirty，否则不会触发commitEvent
     */
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

}