package work.art1st.mineRails.core.api.physics

import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.tick.TickManager
import java.util.UUID

/**
 * Platform-independent collection of rails, trains, Tick services, and module extension data.
 *
 * A platform adapter owns creation and destruction. The interface does not promise that returned
 * collections are immutable snapshots or safe for arbitrary concurrent iteration. Bukkit extensions
 * should perform structural mutations on the server main thread and observe physics publication rules.
 */
interface World : Extendable<Any> {
    /** General-purpose Tick manager; train physics uses a separate coordinator. */
    val tickManager: TickManager

    /** Currently loaded trains. Treat the collection as a live view unless an implementation says otherwise. */
    val allTrains: Collection<Train>

    /** Find a train by its world-unique player-assigned name; implementations may also accept an internal ID. */
    fun getTrain(name: String): Train?

    /** Remove [train] from this world. Platform adapters may impose a main-thread requirement. */
    fun removeTrain(train: Train)

    /** Find a rail by persistent UUID. */
    fun getRail(id: UUID): Rail?

    /** Remove [rail] from this world. Callers must first ensure no train or route still depends on it. */
    fun removeRail(rail: Rail)
}
