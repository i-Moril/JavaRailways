package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalDevicePickingTest {
    @Test
    fun `block ray stays within owned chunks without limiting vertical sight`() {
        val clipped = ownedSignalRayDistance(8.0, 8.0, 1.0, 0.0, 64.0) { x, z -> x == 0 && z == 0 }
        assertTrue(clipped > 7.99 && clipped < 8.0, "stop before reading the next chunk")
        assertEquals(64.0, ownedSignalRayDistance(8.0, 8.0, -0.0, 0.0, 64.0) { x, z -> x == 0 && z == 0 })
        assertEquals(0.0, ownedSignalRayDistance(8.0, 8.0, 1.0, 0.0, 64.0) { _, _ -> false })
    }
}
