package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ScreenPartDefinitionTest {
    private fun screen(overrides: Map<String, Any> = emptyMap()) = mapOf<String, Any>(
        "category" to "custom_lcd", "interior" to true, "width" to 0.8, "height" to 0.45
    ) + overrides

    private fun part(config: Map<String, Any> = screen()) = mapOf<String, Any>(
        "config-version" to 1, "name" to "lcd", "type" to "screen", "screen" to config
    )

    @Test
    fun `screen configuration requires all fields and rejects invalid or unknown values`() {
        val valid = screen()
        for (key in valid.keys) {
            assertThrows<IllegalArgumentException>(key) { PartRegistry.parsePartDefinition(part(valid - key), "test") }
        }
        val invalid = listOf(
            screen(mapOf("category" to "")), screen(mapOf("category" to "cabin lcd")),
            screen(mapOf("category" to " lcd")), screen(mapOf("category" to "lcd\t")),
            screen(mapOf("interior" to "true")), screen(mapOf("unexpected" to true)),
            screen(mapOf("width" to "0.8"))
        ) + listOf("width", "height").flatMap { key ->
            listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                .map { screen(mapOf(key to it)) }
        }
        invalid.forEach { config ->
            assertThrows<IllegalArgumentException>(config.toString()) { PartRegistry.parsePartDefinition(part(config), "test") }
        }
        assertThrows<IllegalArgumentException> { PartRegistry.parsePartDefinition(part() - "screen", "test") }
        assertThrows<IllegalArgumentException> { PartRegistry.parsePartDefinition(part() + ("type" to "dummy"), "test") }
        assertThrows<IllegalArgumentException> { PartRegistry.parsePartDefinition(part() + ("typo" to true), "test") }
        assertThrows<IllegalArgumentException> { PartDefinition("test/lcd", PartType.SCREEN) }
        assertThrows<IllegalArgumentException> { PartDefinition("test/dummy", PartType.DUMMY, screen = PartScreenConfig("lcd", true, 1.0, 1.0)) }
        assertEquals(false, PartRegistry.parsePartDefinition(part(screen(mapOf("interior" to false))), "test").screen!!.interior)
    }

    @Test
    fun `file imports inline nested screens and copied transforms preserve metadata`(@TempDir directory: Path) {
        val namespace = directory.resolve("test").toFile().apply { mkdirs() }
        namespace.resolve("lcd.yml").writeText("""
            config-version: 1
            name: lcd
            type: screen
            offset: {x: 1}
            screen: {category: custom_lcd, interior: true, width: 0.8, height: 0.45}
            parts:
              - config-version: 1
                name: child
                type: dummy
        """.trimIndent())
        namespace.resolve("body.yml").writeText("""
            config-version: 1
            name: body
            type: dummy
            parts:
              - import: lcd
                offset: {y: 2}
              - config-version: 1
                name: group
                type: dummy
                parts:
                  - config-version: 1
                    name: inline_lcd
                    type: screen
                    offset: {x: 4, z: 2}
                    rotation: {y: 90}
                    parts:
                      - import: test/lcd
                    screen: {category: HeadLED, interior: false, width: 1, height: 0.2}
        """.trimIndent())
        val registry = PartRegistry(directory.toFile()).apply { load() }
        val tree = registry["test/body"]!!.resolveTree(registry)
        val imported = tree.children[0]
        assertEquals(PartScreenConfig("custom_lcd", true, 0.8, 0.45), imported.definition.screen)
        assertEquals(Vec3Config(x = 1.0, y = 2.0), imported.definition.offset)
        assertEquals("test/child", imported.children.single().definition.name)
        val inline = tree.children[1].children.single()
        assertEquals("HeadLED", inline.definition.screen!!.category)
        assertEquals(Vec3Config(x = 4.0, z = 2.0), inline.definition.offset)
        assertEquals(Vec3Config(y = 90.0), inline.definition.rotation)
        assertEquals("test", inline.definition.namespace)
        assertEquals("test/lcd", inline.children.single().definition.name)
        val car = CarDefinition("test/car", bodyParts = listOf(PartRefEntry.import("body")))
        val snapshot = SavedCarModelTree.build(car, registry)
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val restored = json.decodeFromString<SavedCarModelTree>(json.encodeToString(snapshot))
        assertEquals(snapshot, restored)
        assertEquals(imported.definition.screen, restored.parts["test/lcd"]!!.screen)
        assertEquals(snapshot.hash, SavedCarModelTree.computeHash(restored.car, restored.parts))
        val changed = restored.parts + ("test/lcd" to restored.parts["test/lcd"]!!.copy(screen = imported.definition.screen!!.copy(interior = false)))
        assertNotEquals(snapshot.hash, SavedCarModelTree.computeHash(restored.car, changed))
    }

    @Test
    fun `nonfinite YAML dimensions fail with source file context`(@TempDir directory: Path) {
        val file = directory.resolve("lcd.yml").toFile()
        for (value in listOf(".nan", ".inf", "-.inf")) {
            file.writeText("""
                config-version: 1
                name: lcd
                type: screen
                screen: {category: lcd, interior: true, width: $value, height: 1}
            """.trimIndent())
            val failure = assertThrows<IllegalStateException> { PartRegistry(directory.toFile()).load() }
            assertTrue(failure.message!!.contains(file.path))
            assertTrue(failure.message!!.contains("finite"))
        }
    }

    @Test
    fun `screen free snapshot retains canonical pre screen hash`() {
        val part = PartDefinition("test/body", PartType.DUMMY)
        val car = CarDefinition("test/car", bodyParts = listOf(PartRefEntry.import("body")))
        val snapshot = SavedCarModelTree.build(car, SnapshotPartRegistry(mapOf(part.name to part)))
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(snapshot)
        assertFalse(encoded.contains("screen"))
        assertEquals("0d53618f5ed083e98b27e477e4b7af284a589189ee8a2f3c441ee0952d990b84", snapshot.hash)
        val fixture = requireNotNull(javaClass.getResource("/model/screen-free-car-tree.json")).readText()
        assertEquals(snapshot, json.decodeFromString<SavedCarModelTree>(fixture))
        assertEquals(snapshot, json.decodeFromString<SavedCarModelTree>(encoded))
    }
}
