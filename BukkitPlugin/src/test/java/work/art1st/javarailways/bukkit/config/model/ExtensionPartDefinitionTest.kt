package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExtensionPartDefinitionTest {
    private fun payload() = PartExtensionPayload("decorations:display", config = buildJsonObject {
        put("category", "custom_lcd"); put("interior", true); put("width", 0.8); put("height", 0.45)
    })
    private fun part() = mapOf<String, Any>("config-version" to 1, "name" to "lcd", "type" to "decorations:display", "config" to mapOf("unknown" to listOf("raw", true)))

    @Test fun `unknown namespaced parts preserve raw configuration but short unknown types and native payloads reject`() {
        val parsed = PartRegistry.parsePartDefinition(part(), "test")
        assertEquals(PartType.EXTENSION, parsed.type)
        assertEquals("decorations:display", parsed.extension!!.typeId)
        assertEquals(1, parsed.extension!!.schemaVersion)
        assertEquals("[\"raw\",true]", parsed.extension!!.config.getValue("unknown").toString())
        for (change in listOf(mapOf("type" to "screen"), mapOf("type" to "UNKNOWN:type"), mapOf("type" to "dummy"),
            mapOf("schema-version" to 0), mapOf("schema-version" to "1"), mapOf("config" to emptyList<Any>()),
            mapOf("item" to emptyMap<String, Any>()), mapOf("typo" to true))) {
            assertThrows<IllegalArgumentException> { PartRegistry.parsePartDefinition(part() + change, "test") }
        }
        assertThrows<IllegalArgumentException> { PartRegistry.parsePartDefinition(part() - "config", "test") }
        assertThrows<IllegalArgumentException> { PartDefinition("test/lcd", PartType.EXTENSION) }
        assertThrows<IllegalArgumentException> { PartDefinition("test/dummy", PartType.DUMMY, extension = payload()) }
    }

    @Test fun `opaque payload is deeply immutable canonical and rejects unsupported or recursive YAML values`() {
        val child = mutableMapOf<String, JsonElement>("z" to JsonPrimitive(1), "a" to JsonPrimitive(2))
        val array = mutableListOf<JsonElement>(JsonObject(child))
        val payload = PartExtensionPayload("unknown:part", config = JsonObject(mapOf("nested" to JsonArray(array))))
        child.clear(); array.clear()
        assertEquals(1, payload.config.getValue("nested").jsonArray.size)
        assertEquals(listOf("a", "z"), payload.config.getValue("nested").jsonArray[0].jsonObject.keys.toList())
        assertThrows<UnsupportedOperationException> { (payload.config.getValue("nested").jsonArray as java.util.List<*>).clear() }
        assertThrows<UnsupportedOperationException> { (payload.config.getValue("nested").jsonArray[0].jsonObject as java.util.Map<*, *>).clear() }
        for (raw in listOf(mapOf("bad" to Double.NaN), mapOf(1 to "bad"), mapOf("bad" to java.util.Date()))) {
            assertThrows<IllegalArgumentException> { extensionYamlObject(raw) }
        }
        val cyclic = mutableMapOf<String, Any>(); cyclic["self"] = cyclic
        assertThrows<IllegalArgumentException> { extensionYamlObject(cyclic) }
    }

    @Test
    fun `file imports inline nested extensions and copied transforms preserve metadata`(@TempDir directory: Path) {
        val namespace = directory.resolve("test").toFile().apply { mkdirs() }
        namespace.resolve("lcd.yml").writeText("""
            config-version: 1
            name: lcd
            type: decorations:display
            offset: {x: 1}
            config: {category: custom_lcd, interior: true, width: 0.8, height: 0.45}
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
                    type: decorations:display
                    offset: {x: 4, z: 2}
                    rotation: {y: 90}
                    parts:
                      - import: test/lcd
                    config: {category: HeadLED, interior: false, width: 1, height: 0.2}
        """.trimIndent())
        val registry = PartRegistry(directory.toFile()).apply { load() }
        val tree = registry["test/body"]!!.resolveTree(registry)
        val imported = tree.children[0]
        assertEquals(payload(), imported.definition.extension)
        assertEquals(Vec3Config(x = 1.0, y = 2.0), imported.definition.offset)
        assertEquals("test/child", imported.children.single().definition.name)
        val inline = tree.children[1].children.single()
        assertEquals("HeadLED", inline.definition.extension!!.config.getValue("category").jsonPrimitive.content)
        assertEquals(Vec3Config(x = 4.0, z = 2.0), inline.definition.offset)
        assertEquals(Vec3Config(y = 90.0), inline.definition.rotation)
        assertEquals("test", inline.definition.namespace)
        assertEquals("test/lcd", inline.children.single().definition.name)
        val car = CarDefinition("test/car", bodyParts = listOf(PartRefEntry.import("body")))
        val snapshot = SavedCarModelTree.build(car, registry)
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val restored = json.decodeFromString<SavedCarModelTree>(json.encodeToString(snapshot))
        assertEquals(snapshot, restored)
        assertEquals(imported.definition.extension, restored.parts["test/lcd"]!!.extension)
        assertEquals(snapshot.hash, SavedCarModelTree.computeHash(restored.car, restored.parts))
        val changed = restored.parts + ("test/lcd" to restored.parts["test/lcd"]!!.copy(extension = imported.definition.extension!!.let { PartExtensionPayload(it.typeId, it.schemaVersion, JsonObject(it.config + ("interior" to JsonPrimitive(false)))) }))
        assertNotEquals(snapshot.hash, SavedCarModelTree.computeHash(restored.car, changed))
    }

    @Test
    fun `extension free snapshot retains canonical pre screen hash`() {
        val part = PartDefinition("test/body", PartType.DUMMY)
        val car = CarDefinition("test/car", bodyParts = listOf(PartRefEntry.import("body")))
        val snapshot = SavedCarModelTree.build(car, SnapshotPartRegistry(mapOf(part.name to part)))
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(snapshot)
        assertFalse(encoded.contains("extension"))
        assertEquals("0d53618f5ed083e98b27e477e4b7af284a589189ee8a2f3c441ee0952d990b84", snapshot.hash)
        val fixture = requireNotNull(javaClass.getResource("/model/screen-free-car-tree.json")).readText()
        assertEquals(snapshot, json.decodeFromString<SavedCarModelTree>(fixture))
        assertEquals(snapshot, json.decodeFromString<SavedCarModelTree>(encoded))
    }
}
