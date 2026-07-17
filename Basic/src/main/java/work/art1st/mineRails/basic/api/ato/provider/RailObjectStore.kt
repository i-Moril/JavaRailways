package work.art1st.mineRails.basic.api.ato.provider

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * World-local registry for Basic and plugin operational RailObjects.
 *
 * Generic object owners must publish [RailObjectLifecycleState.REMOVED] before unregistering.
 * [AtoProviderBoundary] is owned by this store and is transitioned during unregister.
 */
class RailObjectStore {
    private val byRef = ConcurrentHashMap<RailObjectRef, RailObject>()
    private val byRail = ConcurrentHashMap<UUID, CopyOnWriteArrayList<RailObject>>()
    private val mutationLock = Any()

    fun register(railObject: RailObject) = synchronized(mutationLock) {
        require(railObject.lifecycleState == RailObjectLifecycleState.ACTIVE)
        check(byRef.putIfAbsent(railObject.ref, railObject) == null) {
            "RailObjectRef already registered: ${railObject.ref}"
        }
        byRail.computeIfAbsent(railObject.location.rail.id) { CopyOnWriteArrayList() }.add(railObject)
        Unit
    }

    fun unregister(ref: RailObjectRef): RailObject? = synchronized(mutationLock) {
        val registered = byRef[ref] ?: return@synchronized null
        if (registered is AtoProviderBoundary) registered.markRemoved()
        check(registered.lifecycleState == RailObjectLifecycleState.REMOVED) {
            "RailObject owner must publish REMOVED before unregistering: $ref"
        }
        check(byRef.remove(ref, registered)) { "RailObject changed during unregister: $ref" }
        byRail[registered.location.rail.id]?.remove(registered)
        registered
    }

    fun get(ref: RailObjectRef): RailObject? = byRef[ref]

    fun objectsOnRail(railId: UUID): List<RailObject> =
        byRail[railId].orEmpty().filter { it.lifecycleState == RailObjectLifecycleState.ACTIVE }
}
