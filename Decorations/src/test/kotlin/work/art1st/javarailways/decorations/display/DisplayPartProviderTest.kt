package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy
import java.util.UUID

class DisplayPartProviderTest {
    private val config = Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0.8,"height":0.45}""").jsonObject
    // Explicit test stub: metadata-only tests do not install a runtime bridge.
    private fun metadataProvider(validate: (String, DisplayResources) -> Unit = { _, _ -> }) =
        DisplayPartProvider(openTemplate = { _, _, _, _ -> null }, validateTemplates = validate)
    private fun part(version: Int = 1) = PartExtensionPart("cabin-lcd", version, config)

    @Test fun `explicit installation template wins within exact captured resources and retains interior policy`() {
        val resources = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}, point: {file: point.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "category default", "point.xml" to "pinned point source",
        ))
        val installation = PartExtensionPart("point-lcd", 1, JsonObject(config + ("template" to JsonPrimitive("point"))))
        val context = PartExtensionRenderContext("owning-car-package", "captured-hash", installation, resources)
        val policy = PartExtensionAudiencePolicy { 7.0 }
        val calls = mutableListOf<String>()
        val provider = DisplayPartProvider(openTemplate = { actual, parsed, id, source ->
            assertSame(context, actual)
            assertEquals("point", parsed.template)
            assertEquals(DisplayTemplateSource("point.xml", "pinned point source"), source)
            calls += id
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() {}
            }
        }, interiorAudiencePolicy = policy)
        provider.validate(PartExtensionPackage("owning-car-package", listOf(installation), resources))
        assertSame(policy, provider.open(context)!!.audiencePolicy)
        assertEquals(listOf("point"), calls)

        // A part-level template also works when its category has no package default.
        val noCategoryDefault = PartExtensionResources(files = resources.files + (
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}, point: {file: point.xml}}\n"
        ))
        assertDoesNotThrow { provider.validate(PartExtensionPackage("owning-car-package", listOf(installation), noCategoryDefault)) }
        // Metadata-only bridge is intentionally inactive after successful reference resolution.
        assertNull(metadataProvider().open(PartExtensionRenderContext(
            "owning-car-package", "captured-hash", installation, noCategoryDefault,
        )))
    }

    @Test fun `unknown explicit template cannot silently fall back and requires captured resources`() {
        val installation = PartExtensionPart("point-lcd", 1, JsonObject(config + ("template" to JsonPrimitive("unknown"))))
        val withFallback = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "fallback source",
        ))
        var opens = 0
        val provider = DisplayPartProvider(openTemplate = { _, _, _, _ -> opens++; error("Invalid reference must fail before open") })
        listOf(null, PartExtensionResources(), withFallback).forEach { resources ->
            val validation = assertThrows<IllegalArgumentException> {
                provider.validate(PartExtensionPackage("car-source", listOf(installation), resources))
            }
            assertTrue(validation.message.orEmpty().contains("point-lcd"))
            assertTrue(validation.message.orEmpty().contains("unknown"))
            assertThrows<IllegalArgumentException> {
                provider.open(PartExtensionRenderContext("car-source", "hash", installation, resources))
            }
        }
        assertEquals(0, opens)
    }

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
        assertEquals(Component.text("prepared"), provider.open(context)!!.draw(PartExtensionRenderInput()).texts.single().text)
        assertEquals(1, calls)
    }

    @Test fun `interior policy is injected per viewer exterior is unrestricted and delegate still closes`() {
        val resources = PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "source",
        ))
        val settings = DisplayDistanceSettings()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        settings.set(first, 0.0)
        settings.set(second, 8.0)
        var closes = 0
        val frame = PartExtensionFrame(listOf(PartExtensionText(Component.text("same rendered content"))))
        val provider = DisplayPartProvider(
            openTemplate = { _, _, _, _ -> object : PartExtensionRenderSession {
                override val audiencePolicy = PartExtensionAudiencePolicy { 1.0 }
                override fun draw(input: PartExtensionRenderInput) = frame
                override fun close() { closes++ }
            } },
            interiorAudiencePolicy = PartExtensionAudiencePolicy(settings::distanceFor),
        )
        val interior = provider.open(PartExtensionRenderContext("fleet", "hash", part(), resources))!!
        assertEquals(0.0, interior.audiencePolicy.maximumDistanceBlocks(first))
        assertEquals(8.0, interior.audiencePolicy.maximumDistanceBlocks(second))
        settings.reset(first)
        assertEquals(32.0, interior.audiencePolicy.maximumDistanceBlocks(first))
        assertSame(frame, interior.draw(PartExtensionRenderInput()))
        val exteriorPart = PartExtensionPart("outside", 1, Json.parseToJsonElement("""{"category":"cabin","interior":false,"width":1,"height":1}""").jsonObject)
        val exterior = provider.open(PartExtensionRenderContext("fleet", "hash", exteriorPart, resources))!!
        assertNull(exterior.audiencePolicy.maximumDistanceBlocks(second))
        interior.close()
        exterior.close()
        assertEquals(2, closes)
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
