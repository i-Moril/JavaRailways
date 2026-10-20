package work.art1st.javarailways.basic.api.rail

import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * World-local registry for Basic and plugin operational RailObjects.
 *
 * Generic object owners must publish [RailObjectLifecycleState.REMOVED] before unregistering.
 * The store never knows concrete object types or performs owner-specific lifecycle transitions.
 */
class RailObjectStore {
    private val byRef = ConcurrentHashMap<RailObjectRef, RailObject>()
    private val byRail = ConcurrentHashMap<UUID, CopyOnWriteArrayList<RailObject>>()
    /** Rail identity captured at registration so unregister never re-invokes owner getters. */
    private val railIdByRef = ConcurrentHashMap<RailObjectRef, UUID>()
    private val mutationLock = Any()

    fun register(railObject: RailObject) = synchronized(mutationLock) {
        // Capture and validate every external input before touching either index.
        val ref = railObject.ref
        val railId = railObject.location.rail.id
        require(railObject.lifecycleState == RailObjectLifecycleState.ACTIVE)
        check(byRef.putIfAbsent(ref, railObject) == null) {
            "RailObjectRef already registered: $ref"
        }
        try {
            byRail.computeIfAbsent(railId) { CopyOnWriteArrayList() }.add(railObject)
            railIdByRef[ref] = railId
        } catch (failure: Throwable) {
            byRef.remove(ref, railObject)
            railIdByRef.remove(ref)
            throw failure
        }
        Unit
    }

    fun unregister(ref: RailObjectRef): RailObject? = synchronized(mutationLock) {
        val registered = byRef[ref] ?: return@synchronized null
        check(registered.lifecycleState == RailObjectLifecycleState.REMOVED) {
            "RailObject owner must publish REMOVED before unregistering: $ref"
        }
        val railId = checkNotNull(railIdByRef[ref]) { "RailObject rail registration missing: $ref" }
        val railObjects = checkNotNull(byRail[railId]) { "RailObject rail index missing: $ref" }
        check(railObjects.any { it === registered }) { "RailObject missing from rail index: $ref" }
        check(byRef.remove(ref, registered)) { "RailObject changed during unregister: $ref" }
        railObjects.remove(registered)
        railIdByRef.remove(ref)
        registered
    }

    fun get(ref: RailObjectRef): RailObject? = byRef[ref]

    fun objectsOnRail(railId: UUID): List<RailObject> =
        byRail[railId].orEmpty().filter { it.lifecycleState == RailObjectLifecycleState.ACTIVE }
}
