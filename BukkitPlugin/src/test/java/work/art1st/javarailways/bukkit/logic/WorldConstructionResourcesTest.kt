package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorldConstructionResourcesTest {
    @Test
    fun `construction failure closes acquired resources in reverse order`() {
        val resources = WorldConstructionResources()
        val events = mutableListOf<String>()
        val primary = IllegalStateException("restore failed")
        resources.own { events += "tick" }
        resources.own { events += "physics" }
        resources.own { events += "render" }

        val thrown = assertThrows(IllegalStateException::class.java) {
            resources.fail(primary)
        }

        assertSame(primary, thrown)
        assertEquals(listOf("render", "physics", "tick"), events)
    }

    @Test
    fun `cleanup failures are suppressed without replacing construction failure`() {
        val resources = WorldConstructionResources()
        val primary = IllegalArgumentException("bad snapshot")
        val physicsFailure = IllegalStateException("physics close failed")
        val tickFailure = UnsupportedOperationException("tick close failed")
        resources.own { throw tickFailure }
        resources.own { throw physicsFailure }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            resources.fail(primary)
        }

        assertSame(primary, thrown)
        assertEquals(listOf(physicsFailure, tickFailure), thrown.suppressed.toList())
    }

    @Test
    fun `released resources remain owned by the completed world`() {
        val resources = WorldConstructionResources()
        var closeCalls = 0
        resources.own { closeCalls++ }
        resources.release()

        assertThrows(IllegalStateException::class.java) {
            resources.fail(IllegalStateException("later caller failure"))
        }

        assertEquals(0, closeCalls)
    }
}
