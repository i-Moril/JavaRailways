package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartRegistryDriverSeatTest {
    @Test
    fun `parses driver metadata on seat interaction config`() {
        val part = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "driver_seat",
                "type" to "seat",
                "interaction" to mapOf("driver" to true, "control-end" to "tail")
            ),
            "s100"
        )

        assertTrue(part.interaction.driver)
        assertEquals(DriverControlEnd.TAIL, part.interaction.controlEnd)
    }
}
