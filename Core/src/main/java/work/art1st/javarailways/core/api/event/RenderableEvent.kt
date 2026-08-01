package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.render.Renderable

/** Main-thread event concerning a platform renderable. */
interface RenderableEvent : MainThreadRailEvent {
    val renderable: Renderable
}
