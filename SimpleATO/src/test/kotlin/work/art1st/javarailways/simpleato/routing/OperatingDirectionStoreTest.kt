package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class OperatingDirectionStoreTest {
    @Test
    fun `missing entry permits both directions and stored entry permits one`() {
        val store = OperatingDirectionStore()
        val railId = UUID.randomUUID()

        assertTrue(store.allows(railId, ViewDirection.AB))
        assertTrue(store.allows(railId, ViewDirection.BA))

        store.put(railId, ViewDirection.BA)
        assertFalse(store.allows(railId, ViewDirection.AB))
        assertTrue(store.allows(railId, ViewDirection.BA))

        store.put(railId, null)
        assertNull(store[railId])
    }

    @Test
    fun `rail replacement transfers one owned policy atomically`() {
        val store = OperatingDirectionStore()
        val old = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.put(old, ViewDirection.AB)

        store.replaceRails(
            removedRailIds = listOf(old),
            replacementDirections = mapOf(first to ViewDirection.AB, second to ViewDirection.AB)
        )

        assertNull(store[old])
        assertEquals(ViewDirection.AB, store[first])
        assertEquals(ViewDirection.AB, store[second])
    }

    @Test
    fun `arc authoring resolver rejects an ambiguous gesture`() {
        assertEquals(ViewDirection.AB, DirectionRestrictionResolver.fromArcPoints(10.0, 20.0))
        assertEquals(ViewDirection.BA, DirectionRestrictionResolver.fromArcPoints(20.0, 10.0))
        assertNull(DirectionRestrictionResolver.fromArcPoints(10.0, 10.1))
    }
}
