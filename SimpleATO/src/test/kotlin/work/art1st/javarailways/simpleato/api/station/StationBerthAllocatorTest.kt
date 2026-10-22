package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationBerthAllocatorTest {
    @Test
    fun `markers share area capacity and release uses the acquired area`() {
        val station = StationId("central")
        var sharedArea = "platform-one"
        val allocator = StationBerthAllocator {
            StationStoppingAreaRef(it.stationId, if (it.berthId == "independent") "other" else sharedArea)
        }
        fun key(marker: String, train: String) = StationBerthAllocationKey(
            StationBerthOwner(train, train, "call"), StationBerthRef(station, marker), 1L
        )
        val short = key("short", "T1")
        val long = key("long", "T2")
        val independent = key("independent", "T3")
        assertEquals(StationBerthAllocationResult.Confirmed(short), allocator.allocate(short))
        assertEquals(StationBerthAllocationResult.Conflict(long.berthRef, short), allocator.allocate(long))
        assertEquals(StationBerthAllocationResult.Confirmed(independent), allocator.allocate(independent))
        assertFalse(allocator.release(short.copy(missionRevision = 2L)))
        sharedArea = "renamed"
        assertEquals(StationBerthAllocationResult.Conflict(short.berthRef, short),
            allocator.allocate(short.copy(owner = long.owner)))
        assertTrue(allocator.release(short))
        sharedArea = "platform-one"
        assertEquals(StationBerthAllocationResult.Confirmed(long), allocator.allocate(long))
    }

    @Test
    fun `berth allocation is non-preemptive idempotent and explicit release`() {
        val allocator = StationBerthAllocator { StationStoppingAreaRef(it.stationId, it.berthId) }
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
