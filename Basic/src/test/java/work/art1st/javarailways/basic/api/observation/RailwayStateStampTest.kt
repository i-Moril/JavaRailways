package work.art1st.javarailways.basic.api.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RailwayStateStampTest {
    @Test
    fun `keeps physics and topology revisions distinct`() {
        val stamp = RailwayStateStamp(
            worldId = "0ce805b8-f631-4a44-92f7-fec96af36468",
            physicsEpoch = 41L,
            topologyRevision = 7L
        )

        assertEquals(41L, stamp.physicsEpoch)
        assertEquals(7L, stamp.topologyRevision)
    }

    @Test
    fun `rejects invalid identity and revisions`() {
        assertThrows(IllegalArgumentException::class.java) {
            RailwayStateStamp(" ", 0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailwayStateStamp("world", -1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailwayStateStamp("world", 0L, -1L)
        }
    }
}
