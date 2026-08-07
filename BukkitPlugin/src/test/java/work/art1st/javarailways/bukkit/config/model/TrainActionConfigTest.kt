package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrainActionConfigTest {
    @Test
    fun `parses actions with animations and sounds`() {
        val actions = TrainActionConfig.parseActions(
            mapOf(
                "config-version" to 1,
                "actions" to mapOf(
                    "door-left-open" to mapOf(
                        "display-name" to "Open left doors",
                        "executable" to true,
                        "requires-control" to true,
                        "animations" to listOf(
                            mapOf("name" to "doors_l", "scene" to "open", "at" to 0.6),
                            mapOf("name" to "door_warning", "at" to 0.0)
                        ),
                        "sounds" to listOf(
                            mapOf(
                                "key" to "minecraft:block.iron_door.open",
                                "volume" to 0.8,
                                "pitch" to 1.2,
                                "at" to 0.6
                            )
                        )
                    )
                )
            )
        )

        val action = actions.getValue("door-left-open")
        assertEquals("door-left-open", action.name)
        assertEquals("Open left doors", action.displayName)
        assertEquals(true, action.executable)
        assertEquals(true, action.requiresControl)
        assertEquals(2, action.animations.size)
        assertEquals("doors_l", action.animations[0].name)
        assertEquals("open", action.animations[0].scene)
        assertEquals(0.6, action.animations[0].at)
        assertEquals("door_warning", action.animations[1].name)
        assertNull(action.animations[1].scene)
        assertEquals("minecraft:block.iron_door.open", action.sounds.single().key)
        assertEquals(0.8, action.sounds.single().volume)
        assertEquals(1.2, action.sounds.single().pitch)
        assertEquals(0.6, action.sounds.single().at)
    }

    @Test
    fun `rejects string animation references`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            TrainActionConfig.parseActions(
                mapOf(
                    "config-version" to 1,
                    "actions" to mapOf(
                        "door-open" to mapOf("animations" to listOf("old-animation"))
                    )
                )
            )
        }
    }
}
