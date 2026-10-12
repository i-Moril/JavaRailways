package work.art1st.javarailways.bukkit.extension.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CarExtensionHeldSceneTest {
    @Test
    fun `held scene updates survive while the render model is unloaded`() {
        val extension = CarExtension()

        assertTrue(extension.applyAnimation("rear_end_light", "tail"))
        assertTrue(extension.applyAnimation("rear_end_light", "head"))

        assertEquals(
            mapOf("rear_end_light" to "head"),
            extension.pendingHeldScenesForTest()
        )
    }

    @Test
    fun `closed car rejects late animation before querying host or changing held scenes`() {
        val extension = CarExtension()
        extension.applyAnimation("door-left", "open")
        extension.closeBehavior()
        val failure = assertThrows(IllegalStateException::class.java) {
            extension.executeAnimation("door-left", "closed")
        }
        assertEquals("car behavior is closed", failure.message)
        assertEquals(mapOf("door-left" to "open"), extension.pendingHeldScenesForTest())
    }

    @Suppress("UNCHECKED_CAST")
    private fun CarExtension.pendingHeldScenesForTest(): Map<String, String> {
        val field = CarExtension::class.java.getDeclaredField("pendingHeldScenes")
        field.isAccessible = true
        return field.get(this) as Map<String, String>
    }
}
