package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.WorldElement
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.tick.Synchronizable

/**
 * Ordered set of rigid cars moving along connected rails.
 *
 * Position and speed are published physics state. A platform renderer may commit the corresponding
 * visible entities later on its main thread.
 */
interface Train : WorldElement, Destroyable, Synchronizable, Extendable<TrainExtension> {
    /** Stable runtime and persistence identity. */
    val id: String

    /** World-unique, player-assigned name used by timetable selectors. */
    val name: String

    /** Cars from head to tail in the current operating direction. */
    val cars: List<Car>

    /** Published speed in metres per second; non-negative. */
    val speed: Double

    /** Whether physics has marked the train as derailed. */
    val isDerailed: Boolean

    /** First immutable reason that caused derailment, when available. */
    val derailReason: String?

    /** Number of cars. */
    val length: Int

    /** Published location at the current head end. */
    val headLocation: RailLocation

    /** Published location at the current tail end. */
    val tailLocation: RailLocation

    /** Direction-independent physical intervals occupied during the latest published physics step. */
    val occupiedRailIntervals: List<RailInterval>

    /** Latest complete immutable physical frame; null means aligned geometry is unavailable. */
    val publishedFrame: TrainFrameSnapshot? get() = null

    /** Move along the selected rail path by [deltaS] metres. Called by the owning physics worker. */
    fun moveTrainForward(deltaS: Double)

    /** Swap the operating head and tail. Structural effects are implementation-defined. */
    @MainThread
    fun reverse()

    /** Enter a derailed state with a diagnostic [reason]. */
    @MainThread
    fun derail(reason: String)
}
