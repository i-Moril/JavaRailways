package work.art1st.mineRails.bukkit.extension.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Suppress("UNCHECKED_CAST")
    private fun CarExtension.pendingHeldScenesForTest(): Map<String, String> {
        val field = CarExtension::class.java.getDeclaredField("pendingHeldScenes")
        field.isAccessible = true
        return field.get(this) as Map<String, String>
    }
}
