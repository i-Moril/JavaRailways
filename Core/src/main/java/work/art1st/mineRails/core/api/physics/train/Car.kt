package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable

/**
 * TODO: Add more APIs
 */
interface Car : Renderable, Extendable<RenderableExtension<Car>> {
    val bogies: List<Bogie>
}