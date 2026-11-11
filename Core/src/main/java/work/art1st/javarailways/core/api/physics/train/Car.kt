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
     * Stable persisted identity, unchanged by operating reversal or structural transfer.
     * Not a world-wide uniqueness guarantee; splitting requires one unique match in its source train.
     */
    val id: String

    /**
     * Current owning train instance, or null before attachment or after its ownership is retired.
     * Structural transfer updates this association without replacing the car.
     *
     * This non-blocking O(1) identity lookup is safe from any thread. The returned train is live,
     * not a snapshot: reading it grants no permission to access mutable train state off its owner
     * thread, and this association may change immediately after it is read.
     */
    val train: Train?

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
