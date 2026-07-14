package work.art1st.mineRails.core.api.event

import work.art1st.mineRails.core.api.render.Renderable

/** Main-thread event concerning a platform renderable. */
interface RenderableEvent : MainThreadRailEvent {
    val renderable: Renderable
}
