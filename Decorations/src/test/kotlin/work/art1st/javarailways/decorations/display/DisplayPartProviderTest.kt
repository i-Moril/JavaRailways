package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText
import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPart
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources

class DisplayPartProviderTest {
    private val config = Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0.8,"height":0.45}""").jsonObject
    // Explicit test stub: metadata-only tests do not install a runtime bridge.
    private fun metadataProvider(validate: (String, DisplayResources) -> Unit = { _, _ -> }) =
        DisplayPartProvider(openTemplate = { _, _, _, _ -> null }, validateTemplates = validate)
    private fun part(version: Int = 1) = PartExtensionPart("cabin-lcd", version, config)

    @Test fun `provider validates metadata without requiring templates or SimpleDisplays service`() {
        val provider = metadataProvider()
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
        val provider = metadataProvider { name, displays -> calls.add(name to displays) }
        provider.validate(PartExtensionPackage("fleet", emptyList(), resources))
        assertEquals("fleet", calls.single().first)
        assertEquals("captured XML source", calls.single().second.templates.getValue("main").source)
        provider.validate(PartExtensionPackage("without-resources", listOf(part()), null))
        assertEquals(1, calls.size)
        val failure = IllegalArgumentException("invalid embedded script")
        val failing = metadataProvider { _, _ -> throw failure }
        assertSame(failure, assertThrows<IllegalArgumentException> {
            failing.validate(PartExtensionPackage("fleet", listOf(part()), resources))
        })
    }

    @Test fun `provider reports unsupported part and resource versions with package context`() {
        val provider = metadataProvider()
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

    @Test fun `runtime resolves exact captured car category source and returns callback frame`() {
        val resources = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}, unused: {file: unused.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "pinned source", "unused.xml" to "other source",
        ))
        val context = PartExtensionRenderContext("car-source-package", "pinned-hash", part(), resources)
        val expected = object : PartExtensionRenderSession {
            override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame(listOf(PartExtensionText(Component.text("prepared"))))
            override fun close() {}
        }
        var calls = 0
        val provider = DisplayPartProvider(openTemplate = { actual, installation, id, source ->
            calls++
            assertSame(context, actual)
            assertEquals(DisplayPartConfig("cabin", true, 0.8, 0.45), installation)
            assertEquals("main", id)
            assertEquals(DisplayTemplateSource("main.xml", "pinned source"), source)
            expected
        })
        assertSame(expected, provider.open(context))
        assertEquals(1, calls)
    }

    @Test fun `runtime without captured resources category default or optional bridge is inactive`() {
        var calls = 0
        val provider = DisplayPartProvider(openTemplate = { _, _, _, _ -> calls++; error("Must not prepare without a category default") })
        assertNull(provider.open(PartExtensionRenderContext("fleet", "hash", part(), null)))
        assertNull(provider.open(PartExtensionRenderContext("fleet", "hash", part(), PartExtensionResources())))
        val withoutDefault = PartExtensionResources(files = mapOf("displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}", "main.xml" to "source"))
        assertNull(provider.open(PartExtensionRenderContext("fleet", "hash", part(), withoutDefault)))
        assertEquals(0, calls)
        val withDefault = PartExtensionResources(files = withoutDefault.files + ("displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}"))
        assertNull(metadataProvider().open(PartExtensionRenderContext("fleet", "hash", part(), withDefault)))
    }

    @Test fun `runtime validates payload versions before preparation and propagates worker failures`() {
        val provider = metadataProvider()
        assertThrows<IllegalArgumentException> { provider.open(PartExtensionRenderContext("fleet", "hash", part(2), null)) }
        assertThrows<IllegalArgumentException> { provider.open(PartExtensionRenderContext("fleet", "hash", part(), PartExtensionResources(schemaVersion = 2))) }
        val captured = PartExtensionResources(files = mapOf("displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}", "main.xml" to "source"))
        val failure = IllegalArgumentException("draw failed")
        val failing = DisplayPartProvider(openTemplate = { _, _, _, _ -> throw failure })
        assertSame(failure, assertThrows<IllegalArgumentException> { failing.open(PartExtensionRenderContext("fleet", "hash", part(), captured)) })
    }

    @Test fun `provider delegates field validation and captured local-reference validation`() {
        val provider = metadataProvider()
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
