package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationCallRegistryTest {
    @Test
    fun `different berths in one station have independent logical ownership`() {
        val registry = StationCallRegistry()
        val station = StationId("central")
        val first = StationBerthRegistrationKey(
            StationBerthOwner("first", "T1", "call"), StationBerthRef(station, "short"), 1L
        )
        val second = StationBerthRegistrationKey(
            StationBerthOwner("second", "T2", "call"), StationBerthRef(station, "long"), 1L
        )
        assertEquals(first, registry.register(first))
        assertEquals(second, registry.register(second))
        assertTrue(registry.release(first))
        assertTrue(registry.isRegistered(second.berthRef))
        assertEquals(listOf(second), registry.registrations())
    }

    @Test
    fun `same berth permits independent calls and remains protected until last exact release`() {
        val registry = StationCallRegistry()
        val berth = StationBerthRef(StationId("central"), "one")
        val first = StationBerthRegistrationKey(
            StationBerthOwner("tt/first", "T1", "call-1"),
            berth,
            1L
        )
        val second = StationBerthRegistrationKey(
            StationBerthOwner("tt/second", "T2", "call-2"),
            berth,
            2L
        )

        assertEquals(first, registry.register(first))
        assertEquals(first, registry.register(first))
        assertEquals(second, registry.register(second))
        assertEquals(listOf(first, second), registry.registrations())
        assertTrue(registry.isRegistered(berth))
        assertFalse(registry.release(first.copy(missionRevision = 3L)))
        assertTrue(registry.isRegistered(berth))

        assertTrue(registry.release(first))
        assertTrue(registry.isRegistered(berth))
        assertFalse(registry.release(first))
        assertEquals(listOf(second), registry.registrations())
        assertTrue(registry.release(second))
        assertFalse(registry.isRegistered(berth))
    }
}
