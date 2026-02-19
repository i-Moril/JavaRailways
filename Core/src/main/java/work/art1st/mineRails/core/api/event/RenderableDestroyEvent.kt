package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.render.Renderable

class RenderableDestroyEvent(
    override val renderable: Renderable,
) : RenderableEvent {
}