package work.art1st.javarailways.core.api.physics.train

import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.physics.WorldElement
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import java.util.concurrent.Future

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

    /** Cars in stable consist and persistence order. Reversing the train does not reorder this list. */
    val cars: List<Car>

    /** Physical fixed-unit partition captured with the same immutable published frame. */
    val composition: TrainComposition
        get() = requireNotNull(publishedFrame) { "Train composition requires a published frame" }.composition

    /** Whether the operating direction is opposite to the stable [cars] order. */
    val isReversed: Boolean

    /** Cars from the current operating head to tail, derived without introducing mutable state. */
    val operatingCars: List<Car>
        get() = if (isReversed) cars.asReversed() else cars

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

    /** Direction-independent conservative placement at the latest published epoch end, not its swept envelope. */
    val occupiedRailIntervals: List<RailInterval>

    /**
     * Latest immutable physical frame, or null when an implementation does not provide one.
     * Core publishes a non-null frame even before alignment; its body traversal can initially be empty.
     */
    val publishedFrame: TrainFrameSnapshot? get() = null

    /** Move along the selected rail path by [deltaS] metres. Called by the owning physics worker. */
    fun moveTrainForward(deltaS: Double)

    /**
     * Synchronously swap the operating head and tail on the simulation owner, with physics
     * excluded. May wait for the current complete physics frame. Core publishes coherent train,
     * occupancy and world observations before notifying direction listeners. Does not advance
     * the physics epoch or change controller commands; controllers use their queued reverse API.
     */
    @MainThread
    fun reverse()

    /**
     * Schedule a split before [carId] in stable [cars] order, retaining this train's prefix.
     *
     * Non-blocking submission to the world's serialized main-thread structural queue. The returned
     * future yields the new suffix train after both results are fully published. Unknown/ambiguous
     * IDs, an empty prefix, conflicting world identity, rejected decisions and preparation failures
     * complete it exceptionally without changing the original structure. Plugins receive a
     * [work.art1st.javarailways.core.api.event.TrainSplitEvent] before preparation and a
     * [work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent] only after success.
     * Never wait for this future on the thread that must drain the structural queue.
     * This supported API is experimental: signatures and semantics may change without source or
     * binary compatibility guarantees, including for Java callers.
     */
    @ApiStatus.Experimental
    fun splitBefore(carId: String, newId: String): Future<Train>

    /** Enter a derailed state with a diagnostic [reason]. */
    @MainThread
    fun derail(reason: String)
}
