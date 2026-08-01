package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PacketMotionTraceTest {
    @Test
    fun `trace preserves packet order and evicts oldest entries`() {
        val trace = PacketMotionTrace(capacity = 2)

        trace.record("first")
        trace.record("second")
        trace.record("third")

        assertEquals(listOf("#2 second", "#3 third"), trace.snapshot())
    }

    @Test
    fun `clear removes captured history without resetting order sequence`() {
        val trace = PacketMotionTrace(capacity = 2)
        trace.record("first")

        trace.clear()
        trace.record("second")

        assertEquals(listOf("#2 second"), trace.snapshot())
    }
}
