package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LatestTargetGateTest {
    @Test
    fun `only the latest target survives while an operation is in flight`() {
        val gate = LatestTargetGate<String>()

        assertEquals("A", gate.offer("A"))
        assertNull(gate.offer("B"))
        assertNull(gate.offer("C"))
        assertEquals("C", gate.complete())
        assertNull(gate.complete())
    }

    @Test
    fun `cancel drops pending targets and prevents later launches`() {
        val gate = LatestTargetGate<String>()

        assertEquals("A", gate.offer("A"))
        assertNull(gate.offer("B"))
        gate.cancel()

        assertNull(gate.complete())
        assertNull(gate.offer("C"))
    }
}
