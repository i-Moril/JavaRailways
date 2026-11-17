package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World

/**
 * First-party access to the world's single Basic-owned collection of route anchors.
 *
 * Reads observe current values, not a topology/revision-pinned snapshot. [all] returns a detached
 * list sorted by ID; it does not promise one atomic capture across concurrent writes. Authoring
 * mutations and multi-store destination transactions must run in the world's serialized authoring
 * context. The caller owns validation of physical rail references and rollback across other stores;
 * this collection does not create destinations, register rail objects, or publish topology changes.
 */
interface WayPointAccess {
    /** Current immutable value, or `null` when [id] is absent. */
    operator fun get(id: String): WayPoint?

    /** Current values sorted by ID, copied into a new list on each call. */
    fun all(): List<WayPoint>

    /**
     * Inserts or replaces the anchor with the same ID.
     * @throws IllegalArgumentException if its ID is blank; the collection remains unchanged.
     */
    fun add(wayPoint: WayPoint)

    /**
     * Changes only the ID, preserving rail and AB coordinate; equal old/new IDs are allowed.
     * @throws IllegalArgumentException if [newId] is blank or belongs to a different anchor.
     * @throws NoSuchElementException if [oldId] is absent.
     * Validation failures leave the collection unchanged in the required serialized context.
     */
    fun rename(oldId: String, newId: String): WayPoint

    /** Removes and returns the current anchor, or `null` if absent. */
    fun remove(id: String): WayPoint?
}

/**
 * Accesses the existing world-local route-anchor collection without creating another store.
 * @throws IllegalStateException if Basic data has not been explicitly attached to this world.
 */
val World.wayPoints: WayPointAccess
    get() = basicWorldData.wayPoints
