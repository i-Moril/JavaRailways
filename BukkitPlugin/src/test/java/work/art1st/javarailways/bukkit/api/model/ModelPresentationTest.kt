package work.art1st.javarailways.bukkit.api.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelPresentationTest {
    @Test
    fun `presentation copies complete scene state and retains the public pose`() {
        val scenes = mutableMapOf("Lamp" to "Green")
        val state = ModelPresentation(17, ModelPose.IDENTITY, scenes)
        scenes.clear()
        assertEquals(mapOf("Lamp" to "Green"), state.animationScenes())
        assertSame(ModelPose.IDENTITY, state.transform())
        assertThrows(UnsupportedOperationException::class.java) {
            (state.animationScenes() as MutableMap)["Lamp"] = "Red"
        }
    }

    @Test
    fun `negative revision and blank scene identities fail before publication`() {
        assertThrows(IllegalArgumentException::class.java) { ModelPresentation(-1, ModelPose.IDENTITY, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ModelPresentation(0, ModelPose.IDENTITY, mapOf(" " to "Green")) }
        assertThrows(IllegalArgumentException::class.java) { ModelPresentation(0, ModelPose.IDENTITY, mapOf("Lamp" to " ")) }
    }
}
