package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.render.DynamicRenderable
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable

/**
 * TODO: Add more APIs
 */
interface Car : DynamicRenderable, Extendable<RenderableExtension<Car>> {
    val bogies: List<Bogie>
    val length: Double
    val floorHeight: Double
    val bogieAOffset: Double
    val bogieBOffset: Double
    val seats: List<Seat>
}