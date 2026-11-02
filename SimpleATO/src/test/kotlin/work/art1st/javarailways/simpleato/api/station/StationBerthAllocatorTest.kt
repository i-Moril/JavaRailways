package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationBerthAllocatorTest {
    @Test
    fun `different berths in one station have independent logical ownership`() {
        val allocator = StationBerthAllocator()
        val station = StationId("central")
        val first = StationBerthAllocationKey(
            StationBerthOwner("first", "T1", "call"), StationBerthRef(station, "short"), 1L
        )
        val second = StationBerthAllocationKey(
            StationBerthOwner("second", "T2", "call"), StationBerthRef(station, "long"), 1L
        )
        assertEquals(StationBerthAllocationResult.Confirmed(first), allocator.allocate(first))
        assertEquals(StationBerthAllocationResult.Confirmed(second), allocator.allocate(second))
        assertTrue(allocator.release(first))
        assertTrue(allocator.isAllocated(second.berthRef))
        assertEquals(listOf(second), allocator.allocations())
    }

    @Test
    fun `berth allocation is non-preemptive idempotent and explicit release`() {
        val allocator = StationBerthAllocator()
        val berth = StationBerthRef(StationId("central"), "one")
        val first = StationBerthAllocationKey(
            StationBerthOwner("tt/first", "T1", "call-1"),
            berth,
            1L
        )
        val second = StationBerthAllocationKey(
            StationBerthOwner("tt/second", "T2", "call-2"),
            berth,
            2L
        )

        assertEquals(StationBerthAllocationResult.Confirmed(first), allocator.allocate(first))
        assertEquals(StationBerthAllocationResult.Confirmed(first), allocator.allocate(first))
        assertEquals(
            StationBerthAllocationResult.Conflict(berth, first),
            allocator.allocate(second)
        )
        assertEquals(listOf(first), allocator.allocations())
        assertTrue(allocator.isAllocated(berth))
        assertFalse(allocator.release(first.copy(missionRevision = 3L)))
        assertTrue(allocator.isAllocated(berth))

        assertTrue(allocator.release(first))
        assertFalse(allocator.isAllocated(berth))
        assertFalse(allocator.release(first))
        assertEquals(StationBerthAllocationResult.Confirmed(second), allocator.allocate(second))
    }
}
