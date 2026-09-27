package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.Collections
import java.util.UUID

/**
 * SimpleATO-owned operating policy for physical rails.
 *
 * An absent rail permits both directions. A stored entry permits only that one direction. Mutations
 * are serialized so authoring transactions can replace UUIDs without exposing a partially migrated
 * policy to route planners.
 */
class OperatingDirectionStore {
    private val lock = Any()
    private val values = linkedMapOf<UUID, ViewDirection>()

    operator fun get(railId: UUID): ViewDirection? = synchronized(lock) { values[railId] }

    fun allows(railId: UUID, direction: ViewDirection): Boolean =
        synchronized(lock) { values[railId]?.let { it == direction } ?: true }

    fun allows(view: RailView): Boolean = allows(view.rail.id, view.viewDirection)

    /** Null removes the restriction and therefore permits both directions. */
    fun put(railId: UUID, permittedDirection: ViewDirection?) {
        synchronized(lock) {
            if (permittedDirection == null) values.remove(railId) else values[railId] = permittedDirection
        }
    }

    fun entries(): Map<UUID, ViewDirection> = synchronized(lock) {
        Collections.unmodifiableMap(LinkedHashMap(values))
    }

    /** Restores one fresh world. Runtime compatibility belongs at the persistence boundary. */
    fun restore(restored: Map<UUID, ViewDirection>) {
        synchronized(lock) {
            check(values.isEmpty()) { "OperatingDirectionStore is not empty" }
            values.putAll(restored)
        }
    }

    /** Atomically transfers policy from removed rail UUIDs to their replacement UUIDs. */
    fun replaceRails(
        removedRailIds: Collection<UUID>,
        replacementDirections: Map<UUID, ViewDirection>
    ) {
        synchronized(lock) {
            val removed = removedRailIds.toSet()
            val collisions = replacementDirections.keys.filter { it !in removed && it in values }
            require(collisions.isEmpty()) {
                "replacement operating directions collide with existing rails: ${collisions.sorted()}"
            }
            removed.forEach(values::remove)
            values.putAll(replacementDirections)
        }
    }

    internal fun overlay(topology: TopologyState): OperatingDirectionOverlay = synchronized(lock) {
        OperatingDirectionOverlay(topology.revision, values)
    }
}

object DirectionRestrictionResolver {
    fun fromArcPoints(
        playerArcLength: Double,
        clickedArcLength: Double,
        minimumDifference: Double = 0.25
    ): ViewDirection? {
        val delta = clickedArcLength - playerArcLength
        if (kotlin.math.abs(delta) < minimumDifference) return null
        return if (delta > 0.0) ViewDirection.AB else ViewDirection.BA
    }
}
