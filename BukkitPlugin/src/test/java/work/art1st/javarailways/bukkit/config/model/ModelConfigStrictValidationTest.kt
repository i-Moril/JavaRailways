package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModelConfigStrictValidationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `rejects unknown material names before model construction`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parseItemConfig(mapOf("type" to "NOT_A_MATERIAL", "custom-model-data" to 1))
        }
        assertTrue(failure.message!!.contains("item.type"))
        assertEquals("stone",
            PartRegistry.parseItemConfig(mapOf("type" to "stone", "custom-model-data" to 1))!!.type)

        for ((field, time) in listOf("commands" to "duration", "keyframes" to "at")) {
            val animationFailure = assertThrows(IllegalArgumentException::class.java) {
                PartRegistry.parseAnimations(listOf(mapOf("name" to "doors", field to listOf(
                    mapOf(time to 0.0, "item-type" to "NOT_A_MATERIAL")
                ))))
            }
            assertTrue(animationFailure.message!!.contains("$field[0].item-type"))
        }
    }

    @Test
    fun `rejects unknown part fields and wrong optional types`() {
        assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parsePartDefinition(
                mapOf(
                    "config-version" to 1,
                    "name" to "marker",
                    "type" to "dummy",
                    "postion" to mapOf("x" to 1.0)
                ),
                "test"
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parsePartDefinition(
                mapOf(
                    "config-version" to 1,
                    "name" to "marker",
                    "type" to "dummy",
                    "offset" to mapOf("x" to "left")
                ),
                "test"
            )
        }
    }

    @Test
    fun `schema rejection fixtures match strict part loader`() {
        assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parsePartDefinition(
                mapOf(
                    "config-version" to 1,
                    "name" to "invalid-item-animation",
                    "type" to "item_display",
                    "item" to mapOf(
                        "type" to "IRON_NUGGET",
                        "item-model" to "example/model",
                        "animations" to emptyList<Any>()
                    )
                ),
                "test"
            )
        }

        listOf("spacingX", "spacingZ").forEach { unsupportedKey ->
            assertThrows(IllegalArgumentException::class.java) {
                PartRegistry.parsePartDefinition(
                    mapOf(
                        "config-version" to 1,
                        "name" to "invalid-$unsupportedKey",
                        "type" to "platform",
                        "platform" to mapOf(unsupportedKey to 1.0)
                    ),
                    "test"
                )
            }
        }
    }

    @Test
    fun `schema rejection fixture matches strict action loader`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainActionConfig.parseActions(
                mapOf(
                    "config-version" to 1,
                    "actions" to mapOf(
                        "open" to mapOf("animations" to listOf("doors"))
                    )
                )
            )
        }
    }

    @Test
    fun `rejects unsupported manifest versions and fields`() {
        val unsupported = File(tempDir, "unsupported").apply { mkdirs() }
        File(unsupported, "manifest.yml").writeText(
            """
            config-version: 1
            name: unsupported
            type: train
            composition: []
            """.trimIndent()
        )
        assertThrows(IllegalArgumentException::class.java) {
            PackageLoader(unsupported).loadTrainPackage()
        }

        val unknown = File(tempDir, "unknown").apply { mkdirs() }
        File(unknown, "manifest.yml").writeText(
            """
            config-version: 2
            name: unknown
            type: train
            composition: []
            composiiton: []
            """.trimIndent()
        )
        assertThrows(IllegalArgumentException::class.java) {
            PackageLoader(unknown).loadTrainPackage()
        }
    }

    @Test
    fun `requires versioned actions and rejects unknown action fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainActionConfig.parseActions(mapOf("actions" to emptyMap<String, Any>()))
        }

        assertThrows(IllegalArgumentException::class.java) {
            TrainActionConfig.parseActions(
                mapOf(
                    "config-version" to 1,
                    "actions" to mapOf(
                        "horn" to mapOf("sound" to "minecraft:entity.minecart.riding")
                    )
                )
            )
        }
    }

    @Test
    fun `rejects unknown nested control fields`() {
        val validRoot = mapOf<String, Any>(
            "config-version" to 1,
            "max-speed" to 30.0,
            "controller" to mapOf(
                "power-notches" to listOf("P1"),
                "brake-notches" to listOf("B1")
            ),
            "traction" to mapOf("notch-scales" to mapOf("P1" to 1.0)),
            "brake" to mapOf(
                "notch-scales" to mapOf("B1" to 1.0),
                "apply-time" to 1.0,
                "release-time" to 1.0,
                "typo" to true
            ),
            "resistance" to mapOf("a" to 1.0, "b" to 1.0, "c" to 1.0),
            "adhesion" to mapOf("coefficient" to 0.2),
            "jerk" to mapOf("traction" to 1.0, "brake" to 1.0)
        )

        assertThrows(IllegalArgumentException::class.java) {
            TrainControlConfig.parse(validRoot)
        }
    }
}
