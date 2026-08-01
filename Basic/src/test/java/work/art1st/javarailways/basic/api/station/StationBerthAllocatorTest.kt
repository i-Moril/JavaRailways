package work.art1st.javarailways.basic.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationBerthAllocatorTest {
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

        assertTrue(allocator.release(first))
        assertFalse(allocator.release(first))
        assertEquals(StationBerthAllocationResult.Confirmed(second), allocator.allocate(second))
    }
}
