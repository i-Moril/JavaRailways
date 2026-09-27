package work.art1st.javarailways.basic.api.safety

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldSafetyInterlockTest {
    @Test
    fun `interlock latches until an administrative reset`() {
        val interlock = WorldSafetyInterlock()

        assertTrue(interlock.engage())
        assertFalse(interlock.engage())
        assertTrue(interlock.isEngaged)

        interlock.reset()
        assertFalse(interlock.isEngaged)
        assertTrue(interlock.engage())
    }
}
