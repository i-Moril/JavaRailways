package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.render.SynchronizedRenderable

class RenderableCommitEvent(
    override val renderable: SynchronizedRenderable,
    val lastState: SynchronizedRenderable.StateSnapshot
) : RenderableEvent {
}