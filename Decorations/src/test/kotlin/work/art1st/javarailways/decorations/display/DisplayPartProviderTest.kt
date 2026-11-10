package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPart
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources

class DisplayPartProviderTest {
    private val config = Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0.8,"height":0.45}""").jsonObject
    private fun part(version: Int = 1) = PartExtensionPart("cabin-lcd", version, config)

    @Test fun `provider validates metadata without requiring templates or SimpleDisplays service`() {
        val provider = DisplayPartProvider()
        assertEquals("decorations:display", provider.typeId)
        assertDoesNotThrow { provider.validate(PartExtensionPackage("fleet", listOf(part()), null)) }
        assertDoesNotThrow { provider.validate(PartExtensionPackage("fleet", listOf(part()), PartExtensionResources())) }
        val resources = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "<screen><script>raw uncompiled source</script></screen>",
        ))
        assertDoesNotThrow { provider.validate(PartExtensionPackage("fleet", listOf(part()), resources)) }
        // Captured templates are independently valid even before any screen installation uses them.
        assertDoesNotThrow { provider.validate(PartExtensionPackage("fleet", emptyList(), resources)) }
    }

    @Test fun `provider forwards captured decoded templates and preserves compiler failures`() {
        val resources = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "captured XML source",
        ))
        val calls = mutableListOf<Pair<String, DisplayResources>>()
        val provider = DisplayPartProvider { name, displays -> calls.add(name to displays) }
        provider.validate(PartExtensionPackage("fleet", emptyList(), resources))
        assertEquals("fleet", calls.single().first)
        assertEquals("captured XML source", calls.single().second.templates.getValue("main").source)
        provider.validate(PartExtensionPackage("without-resources", listOf(part()), null))
        assertEquals(1, calls.size)
        val failure = IllegalArgumentException("invalid embedded script")
        val failing = DisplayPartProvider { _, _ -> throw failure }
        assertSame(failure, assertThrows<IllegalArgumentException> {
            failing.validate(PartExtensionPackage("fleet", listOf(part()), resources))
        })
    }

    @Test fun `provider reports unsupported part and resource versions with package context`() {
        val provider = DisplayPartProvider()
        val partFailure = assertThrows<IllegalArgumentException> {
            provider.validate(PartExtensionPackage("fleet", listOf(part(2)), null))
        }
        assertTrue(partFailure.message.orEmpty().contains("fleet"))
        assertTrue(partFailure.message.orEmpty().contains("cabin-lcd"))
        assertTrue(partFailure.message.orEmpty().contains("schema-version 2"))
        val resourceFailure = assertThrows<IllegalArgumentException> {
            provider.validate(PartExtensionPackage("other-fleet", emptyList(), PartExtensionResources(schemaVersion = 2)))
        }
        assertTrue(resourceFailure.message.orEmpty().contains("other-fleet"))
        assertTrue(resourceFailure.message.orEmpty().contains("resources unsupported schema-version 2"))
    }

    @Test fun `provider delegates field validation and captured local-reference validation`() {
        val provider = DisplayPartProvider()
        val invalidPart = PartExtensionPart("broken-lcd", 1, Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0,"height":1}""").jsonObject)
        val partFailure = assertThrows<IllegalArgumentException> {
            provider.validate(PartExtensionPackage("fleet", listOf(part(), invalidPart), null))
        }
        assertTrue(partFailure.message.orEmpty().contains("broken-lcd"))
        assertTrue(partFailure.message.orEmpty().contains("width"))
        val missingXml = PartExtensionResources(files = mapOf("displays.yml" to "config-version: 1\ntemplates: {main: {file: missing.xml}}"))
        val resourceFailure = assertThrows<IllegalArgumentException> {
            provider.validate(PartExtensionPackage("fleet", listOf(part()), missingXml))
        }
        assertTrue(resourceFailure.message.orEmpty().contains("templates.main.file 'missing.xml'"))
        assertTrue(resourceFailure.message.orEmpty().contains("fleet"))
    }
}
