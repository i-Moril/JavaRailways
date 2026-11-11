package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PartDocumentFormatTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `standalone requires version while recursive inline content forbids it`() {
        val content = mapOf<String, Any>("name" to "group", "type" to "dummy")
        assertThrows(IllegalArgumentException::class.java) {
            PartRegistry.parsePartDefinition(content, "fleet")
        }
        val nested = content + ("parts" to listOf(content + ("name" to "child")))
        val standalone = PartRegistry.parsePartDefinition(nested + ("config-version" to 1), "fleet")
        val inline = PartRegistry.parsePartList(listOf(nested), "fleet").single().inline!!
        assertEquals(standalone, inline)
        for (version in listOf(1, 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                PartRegistry.parsePartList(listOf(content + ("config-version" to version)), "fleet")
            }
            assertThrows(IllegalArgumentException::class.java) {
                PartRegistry.parsePartDefinition(
                    content + mapOf("config-version" to 1, "parts" to listOf(content + ("config-version" to version))),
                    "fleet"
                )
            }
        }
    }

    @Test
    fun `named animation identity and order survive internal list conversion and repeated imports`() {
        val part = PartRegistry.parsePartDefinition(mapOf(
            "config-version" to 1,
            "name" to "group",
            "type" to "dummy",
            "animations" to linkedMapOf(
                "z_open" to mapOf("commands" to listOf(mapOf("duration" to 2.0), mapOf("duration" to 1.0))),
                "a_close" to emptyMap<String, Any>()
            ),
            "parts" to listOf(mapOf("import" to "door"), mapOf("import" to "door"))
        ), "fleet")
        assertEquals(listOf("z_open", "a_close"), part.animations.map { it.name })
        assertEquals(listOf(2.0, 1.0), part.animations.first().commands.map { it.duration })
        assertEquals(listOf("door", "door"), part.parts.map { it.name })
    }

    @Test
    fun `animation definitions reject old lists inner identities blank and nonstring keys`() {
        val base = mapOf<String, Any>("config-version" to 1, "name" to "group", "type" to "dummy")
        for (invalid in listOf(
            listOf(mapOf("name" to "doors")),
            mapOf("doors" to mapOf("name" to "doors")),
            mapOf(" " to emptyMap<String, Any>()),
            mapOf(1 to emptyMap<String, Any>()),
            mapOf("doors" to "invalid")
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                PartRegistry.parsePartDefinition(base + ("animations" to invalid), "fleet")
            }
        }
    }

    @Test
    fun `part loader rejects duplicate animation mapping keys before either entry can overwrite`() {
        File(directory, "group.yml").writeText("""
            config-version: 1
            name: group
            type: dummy
            animations:
              doors: {keep-final-state: true}
              doors: {keep-final-state: false}
        """.trimIndent())
        val failure = assertThrows(IllegalStateException::class.java) { PartRegistry(directory).load() }
        assertTrue(failure.message.orEmpty().contains("duplicate key doors"))
    }

    @Test
    fun `car loader rejects duplicate nested animation keys at actual YAML boundary`() {
        File(directory, "car.yml").writeText("""
            config-version: 1
            name: car
            body-parts:
              - name: lcd
                type: dummy
                animations:
                  blink: {}
                  blink: {}
        """.trimIndent())
        val failure = assertThrows(IllegalStateException::class.java) {
            CarLoader(directory, PartRegistry(File(directory, "absent"))).load()
        }
        assertTrue(failure.message.orEmpty().contains("duplicate key blink"))
    }
}
