package work.art1st.javarailways.core.api.physics.train

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.render.DynamicRenderable
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.plugin.Extendable

/**
 * TODO: Add more APIs
 */
interface Car : DynamicRenderable, Extendable<RenderableExtension<Car>> {
    /**
     * Published unit direction followed when the owning train advances by a positive distance.
     *
     * This is live Core physics state. It is deliberately separate from [direction], which is the
     * car model/render orientation and therefore does not flip when the operating ends are swapped.
     */
    val operatingDirection: Vector3dc
    val bogies: List<Bogie>
    val length: Double
    val floorHeight: Double
    val bogieAOffset: Double
    val bogieBOffset: Double
    val seats: List<Seat>
}
