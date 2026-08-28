package work.art1st.javarailways.bukkit.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudCommandRegistrarTest {
    @Test
    fun `tokenizer preserves the empty token required for trailing-space suggestions`() {
        assertEquals(listOf("train", "create", ""), CloudCommandRegistrar.tokenize("train create "))
        assertEquals(listOf(""), CloudCommandRegistrar.tokenize(""))
    }

    @Test
    fun `cloud tree includes resource reload and representative nested commands`() {
        val routes = CloudCommandRegistrar.registeredRoutePaths()
        assertTrue("resources reload" in routes)
        assertTrue("train create" in routes)
        assertTrue("timetable replan" in routes)
        assertTrue("wand rail" in routes)
        assertFalse(routes.any { it == "rail" || it.startsWith("rail ") })
    }
}
