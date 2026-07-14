package work.art1st.mineRails.basic.api.ato.provider

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** World-local registry for Basic and plugin operational RailObjects. */
class RailObjectStore {
    private val byRef = ConcurrentHashMap<RailObjectRef, RailObject>()
    private val byRail = ConcurrentHashMap<UUID, CopyOnWriteArrayList<RailObject>>()

    fun register(railObject: RailObject) {
        require(railObject.lifecycleState == RailObjectLifecycleState.ACTIVE)
        check(byRef.putIfAbsent(railObject.ref, railObject) == null) {
            "RailObjectRef already registered: ${railObject.ref}"
        }
        byRail.computeIfAbsent(railObject.location.rail.id) { CopyOnWriteArrayList() }.add(railObject)
    }

    fun unregister(ref: RailObjectRef): RailObject? {
        val removed = byRef.remove(ref) ?: return null
        byRail[removed.location.rail.id]?.remove(removed)
        if (removed is AtoProviderBoundary) removed.markRemoved()
        return removed
    }

    fun get(ref: RailObjectRef): RailObject? = byRef[ref]

    fun objectsOnRail(railId: UUID): List<RailObject> =
        byRail[railId].orEmpty().filter { it.lifecycleState == RailObjectLifecycleState.ACTIVE }
}
