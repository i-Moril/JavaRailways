package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPart
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText
import java.util.UUID

class RoutingDisplaySessionTest {
    private val world = UUID.randomUUID()
    private fun input(unit: String?) = PartExtensionRenderInput(Json.parseToJsonElement(
        if (unit == null) """{"unit":null,"operational":{"currentRun":{"runId":"run"}}}"""
        else """{"unit":{"id":"$unit"},"operational":{"currentRun":{"runId":"run"}}}"""
    ).jsonObject)

    private fun context(defaults: String = "defaults: {cabin: main}", template: String? = null) = PartExtensionRenderContext(
        "captured-car", "exact-hash", PartExtensionPart("screen", 1, Json.parseToJsonElement(
            """{"category":"cabin","interior":true,"width":1,"height":1${if (template == null) "" else ",\"template\":\"$template\""}}"""
        ).jsonObject), PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}, other: {file: other.xml}}\n$defaults\n",
            "main.xml" to "exact main", "other.xml" to "exact other",
        )), worldId = world,
    )

    @Test fun `hidden update binds without compile and selection reuses swaps and restores defaults`() {
        val styles = RuntimeDisplayStyles()
        val opens = mutableListOf<String>()
        val closes = mutableListOf<String>()
        val policy = PartExtensionAudiencePolicy { 12.0 }
        val provider = DisplayPartProvider(openTemplate = { actual, _, id, source ->
            assertEquals("exact-hash", actual.packageHash)
            assertEquals("exact $id", source.source)
            opens += id
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame(listOf(PartExtensionText(Component.text(id))))
                override fun close() { closes += id }
            }
        }, interiorAudiencePolicy = policy, runtimeStyles = styles)
        val session = provider.open(context(template = "other"))!!
        assertSame(policy, session.audiencePolicy)
        session.update(input("unit-a"))
        assertTrue(opens.isEmpty())
        assertEquals(1, styles.inspect(world, "unit-a", "cabin").screenCount)
        assertEquals(Component.text("other"), session.draw(input("unit-a")).texts.single().text)
        session.draw(input("unit-a"))
        assertEquals(listOf("other"), opens)
        val initialRevision = session.renderRevision
        styles.set(world, "unit-a", "cabin", "main")
        assertTrue(session.renderRevision > initialRevision)
        assertEquals(listOf("other"), opens) // Selection notifications do not evaluate scripts.
        assertEquals(Component.text("main"), session.draw(input("unit-a")).texts.single().text)
        assertEquals(listOf("other"), closes)
        styles.reset(world, "unit-a", "cabin")
        session.draw(input("unit-a"))
        assertEquals(listOf("other", "main", "other"), opens)
        session.close()
        session.close()
        assertEquals(listOf("other", "main", "other"), closes)
        assertEquals(0, styles.inspect(world, "unit-a", "cabin").screenCount)
    }

    @Test fun `override activates screen without defaults and follows real unit across session rebuild`() {
        val styles = RuntimeDisplayStyles()
        val provider = DisplayPartProvider(openTemplate = { _, _, id, _ ->
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame(listOf(PartExtensionText(Component.text(id))))
                override fun close() {}
            }
        }, runtimeStyles = styles)
        val session = provider.open(context(defaults = ""))!!
        session.update(input("unit-a"))
        assertTrue(session.draw(input("unit-a")).texts.isEmpty())
        styles.set(world, "unit-a", "cabin", "other")
        session.close()
        val rebuilt = provider.open(context(defaults = ""))!!
        rebuilt.update(input("unit-a"))
        assertEquals(Component.text("other"), rebuilt.draw(input("unit-a")).texts.single().text)
        rebuilt.update(input(null))
        assertTrue(rebuilt.draw(input(null)).texts.isEmpty())
        rebuilt.update(input("unit-a"))
        assertEquals(Component.text("other"), rebuilt.draw(input("unit-a")).texts.single().text)
        styles.clearWorld(world)
        assertTrue(rebuilt.draw(input("unit-a")).texts.isEmpty())
        assertTrue(styles.unitIds(world).isEmpty())
        rebuilt.close()
    }

    @Test fun `retained incompatible override is diagnostic and reset recovers without unbinding`() {
        val styles = RuntimeDisplayStyles()
        val old = styles.register(world, "cabin", setOf("gone"), "old")
        styles.bind(old, "unit-a")
        styles.set(world, "unit-a", "cabin", "gone")
        styles.unbind(old)
        val provider = DisplayPartProvider(openTemplate = { _, _, _, _ ->
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() {}
            }
        }, runtimeStyles = styles)
        val replacement = provider.open(context())!!
        replacement.update(input("unit-a"))
        val failure = assertThrows<IllegalArgumentException> { replacement.draw(input("unit-a")) }
        assertTrue(failure.message.orEmpty().contains("gone"))
        assertEquals(1, styles.inspect(world, "unit-a", "cabin").screenCount)
        styles.reset(world, "unit-a", "cabin")
        assertDoesNotThrow { replacement.draw(input("unit-a")) }
        replacement.close()
    }

    @Test fun `failed replacement closes old private session once and later retry recovers`() {
        val styles = RuntimeDisplayStyles()
        var reject = true
        val closed = mutableListOf<String>()
        val provider = DisplayPartProvider(openTemplate = { _, _, id, _ ->
            if (id == "other" && reject) throw IllegalArgumentException("compile failure")
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() { closed += id }
            }
        }, runtimeStyles = styles)
        val session = provider.open(context())!!
        session.draw(input("unit-a"))
        styles.set(world, "unit-a", "cabin", "other")
        assertThrows<IllegalArgumentException> { session.draw(input("unit-a")) }
        assertEquals(listOf("main"), closed)
        reject = false
        session.draw(input("unit-a"))
        session.close()
        assertEquals(listOf("main", "other"), closed)
    }

    @Test fun `private close failure still removes binding and is never closed twice`() {
        val styles = RuntimeDisplayStyles()
        var closes = 0
        val failure = IllegalStateException("private close failed")
        val provider = DisplayPartProvider(openTemplate = { _, _, _, _ ->
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() { closes++; throw failure }
            }
        }, runtimeStyles = styles)
        val session = provider.open(context())!!
        session.draw(input("unit-a"))
        assertSame(failure, assertThrows<IllegalStateException> { session.close() })
        assertEquals(0, styles.inspect(world, "unit-a", "cabin").screenCount)
        session.close()
        assertEquals(1, closes)
    }
}
