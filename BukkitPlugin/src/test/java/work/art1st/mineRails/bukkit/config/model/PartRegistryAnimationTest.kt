package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartRegistryAnimationTest {
    @Test
    fun `parses top-level keyframe animations with normalized scenes`() {
        val part = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "door_a",
                "type" to "item_display",
                "item" to mapOf(
                    "type" to "IRON_NUGGET",
                    "item-model" to "art1st/tc/s100/door_a"
                ),
                "animations" to listOf(
                    mapOf(
                        "name" to "doors_l",
                        "keep-final-state" to false,
                        "keyframes" to listOf(
                            mapOf("at" to 1.3, "scene" to "o", "offset" to mapOf("z" to 0.625)),
                            mapOf("at" to 0.0, "scene" to "c")
                        )
                    )
                )
            ),
            "s100"
        )

        assertEquals("s100/door_a", part.name)
        assertEquals(1, part.animations.size)
        val animation = part.animations.single()
        assertEquals("doors_l", animation.name)
        assertFalse(animation.keepFinalState)
        assertEquals(listOf(0.0, 1.3), animation.keyframes.map { it.at })
        assertEquals(listOf("closed", "open"), animation.keyframes.map { it.scene })
        assertEquals(0.625, animation.keyframes[1].offset.z)
    }

    @Test
    fun `keeps compatibility with item-level command animations`() {
        val part = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "door_b",
                "type" to "item_display",
                "item" to mapOf(
                    "type" to "IRON_NUGGET",
                    "animations" to listOf(
                        mapOf(
                            "name" to "door_left_open",
                            "commands" to listOf(
                                mapOf("duration" to 0.3, "offset" to mapOf("z" to 0.1)),
                                mapOf("duration" to 1.0, "scene" to "open", "offset" to mapOf("z" to 0.625))
                            )
                        )
                    )
                )
            ),
            "s100"
        )

        val animation = part.item!!.animations.single()
        assertTrue(animation.keepFinalState)
        assertEquals("door_left_open", animation.name)
        assertEquals(listOf(0.3, 1.0), animation.commands.map { it.duration })
        assertEquals("open", animation.commands[1].scene)
        assertEquals(0.625, animation.commands[1].offset.z)
    }

    @Test
    fun `parses display state animation fields`() {
        val part = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "door_light",
                "type" to "item_display",
                "item" to mapOf("type" to "IRON_NUGGET"),
                "animations" to listOf(
                    mapOf(
                        "name" to "door_light_warn",
                        "keyframes" to listOf(
                            mapOf(
                                "at" to 0.0,
                                "item-type" to "IRON_NUGGET",
                                "item-model" to "art1st/tc/s100/left_door_light_warning",
                                "custom-model-data" to 10015,
                                "brightness" to mapOf("block" to 5, "sky" to 0)
                            )
                        )
                    )
                )
            ),
            "s100"
        )

        val keyframe = part.animations.single().keyframes.single()
        val brightness = keyframe.brightness ?: error("brightness should be parsed")
        assertEquals("IRON_NUGGET", keyframe.itemType)
        assertEquals("art1st/tc/s100/left_door_light_warning", keyframe.itemModel)
        assertEquals(10015, keyframe.customModelData)
        assertEquals(5, brightness.block)
        assertEquals(0, brightness.sky)
    }

    @Test
    fun `parses interaction and platform part types`() {
        val interact = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "door_click_area",
                "type" to "interact",
                "interaction" to mapOf("width" to 0.8, "height" to 2.0)
            ),
            "s100"
        )
        val platform = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "floor_collision",
                "type" to "platform",
                "platform" to mapOf(
                    "rows" to 3,
                    "columns" to 18,
                    "spacing" to mapOf("x" to 1.0, "z" to 1.0)
                )
            ),
            "s100"
        )
        val seat = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "seat_marker",
                "type" to "seat"
            ),
            "s100"
        )

        assertEquals(PartType.INTERACT, interact.type)
        assertEquals(0.8, interact.interaction.width)
        assertEquals(2.0, interact.interaction.height)
        assertEquals(PartType.PLATFORM, platform.type)
        assertEquals(3, platform.platform.rows)
        assertEquals(18, platform.platform.columns)
        assertEquals(-1.0, platform.platform.cellOffsets().first().x)
        assertEquals(-8.5, platform.platform.cellOffsets().first().z)
        assertEquals(1.0, platform.platform.cellOffsets().last().x)
        assertEquals(8.5, platform.platform.cellOffsets().last().z)
        assertEquals(PartType.SEAT, seat.type)
    }

    @Test
    fun `defaults missing offset rotation and scale components in car part config`() {
        val part = PartRegistry.parsePartDefinition(
            mapOf(
                "config-version" to 1,
                "name" to "door_marker",
                "type" to "item_display",
                "offset" to mapOf("z" to 0.4),
                "rotation" to mapOf("y" to 90.0),
                "item" to mapOf(
                    "type" to "IRON_NUGGET",
                    "scale" to mapOf("z" to 1.5)
                )
            ),
            "s100"
        )

        val itemScale = part.item?.scale ?: error("item scale should be parsed")
        assertEquals(0.0, part.offset.x)
        assertEquals(0.0, part.offset.y)
        assertEquals(0.4, part.offset.z)
        assertEquals(0.0, part.rotation.x)
        assertEquals(90.0, part.rotation.y)
        assertEquals(0.0, part.rotation.z)
        assertEquals(1.0, itemScale.x)
        assertEquals(1.0, itemScale.y)
        assertEquals(1.5, itemScale.z)
    }

    @Test
    fun `rejects duplicate scenes in one animation`() {
        assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parsePartDefinition(
                mapOf(
                    "config-version" to 1,
                    "name" to "door_group",
                    "type" to "dummy",
                    "animations" to listOf(
                        mapOf(
                            "name" to "doors_l",
                            "keyframes" to listOf(
                                mapOf("at" to 0.0, "scene" to "closed"),
                                mapOf("at" to 1.0, "scene" to "c")
                            )
                        )
                    )
                ),
                "s100"
            )
        }
    }
}
