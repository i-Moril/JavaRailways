package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.bukkit.api.model.extension.*
import java.util.UUID

class ActualRunDisplayGateTest {
    private val world = UUID.randomUUID()
    private fun context() = PartExtensionRenderContext(
        "car-source", "pinned-hash", PartExtensionPart("screen", 1, Json.parseToJsonElement(
            """{"category":"cabin","interior":true,"width":1,"height":1}"""
        ).jsonObject), PartExtensionResources(files = mapOf(
            "displays.yml" to "config-version: 1\ntemplates: {main: {file: main.xml}, other: {file: other.xml}}\ndefaults: {cabin: main}\n",
            "main.xml" to "literal XML and document JS", "other.xml" to "alternate style",
        )), worldId = world,
    )

    private fun input(operational: String, tick: Long = 0) = PartExtensionRenderInput(
        Json.parseToJsonElement("""{"unit":{"id":"unit-a"},"operational":$operational}""").jsonObject, tick
    )
    private val activeTrip = """{"operationalTripId":"trip","currentRun":{"runId":"run-a"}}"""
    private val outsideRun = """{"operationalTripId":"trip","currentRun":null}"""

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `direct and routed displays suppress literal and script output outside run and resume same session`(routed: Boolean) {
        val styles = if (routed) RuntimeDisplayStyles() else null
        val policy = PartExtensionAudiencePolicy { 9.0 }
        var opens = 0
        var draws = 0
        var closes = 0
        val updated = mutableListOf<PartExtensionRenderInput>()
        val provider = DisplayPartProvider(openTemplate = { _, _, _, _ ->
            opens++
            object : PartExtensionRenderSession {
                override fun update(input: PartExtensionRenderInput) { updated += input }
                override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
                    draws++
                    // Both a literal label and script-produced content would remain visible
                    // if the provider merely cleared the destination binding.
                    return PartExtensionFrame(listOf(PartExtensionText(Component.text("fixed label"), key = "fixed-label"),
                        PartExtensionText(Component.text("script:${input.elapsedTicks}"), key = "script-label")))
                }
                override fun close() { closes++ }
            }
        }, interiorAudiencePolicy = policy, runtimeStyles = styles)
        val session = provider.open(context())!!
        assertSame(policy, session.audiencePolicy)
        val first = session.draw(input(activeTrip))
        assertEquals(listOf(Component.text("fixed label"), Component.text("script:0")), first.texts.map { it.text })
        assertEquals(1, opens)

        val off = input(outsideRun, 10)
        session.update(off) // Hidden mounts still deliver metadata without drawing.
        assertSame(PartExtensionFrame.EMPTY, session.draw(off))
        assertSame(PartExtensionFrame.EMPTY, session.draw(PartExtensionRenderInput()))
        assertSame(PartExtensionFrame.EMPTY, session.draw(input("null")))
        assertSame(PartExtensionFrame.EMPTY, session.draw(input("""{"operationalTripId":"trip"}""")))
        assertSame(PartExtensionFrame.EMPTY, session.draw(input("""{"currentRun":"run-a"}""")))
        assertEquals(1, draws, "Neither literal XML nor document JS is evaluated outside actual RUN")
        assertEquals(0, closes, "Leaving RUN does not retire the reusable template")
        if (!routed) assertTrue(updated.any { it === off }, "Direct metadata updates must be delegated")

        // Actual RUN is sufficient, independently of an authored trip identifier.
        val resumed = session.draw(input("""{"currentRun":{"runId":"run-b"}}""", 20))
        assertEquals(Component.text("script:20"), resumed.texts.last().text)
        assertEquals(2, draws)
        assertEquals(1, opens, "Resuming an unchanged style reuses its private session")
        session.close()
        assertEquals(1, closes)
        if (styles != null) {
            assertEquals(0, styles.inspect(world, "unit-a", "cabin").screenCount)
            styles.close()
        }
    }

    @Test fun `off and hidden routing keeps style binding revisions and applies override on resumption`() {
        val styles = RuntimeDisplayStyles()
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val provider = DisplayPartProvider(openTemplate = { _, _, id, _ ->
            opened += id
            object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) =
                    PartExtensionFrame(listOf(PartExtensionText(Component.text(id), key = "fixture-" + id)))
                override fun close() { closed += id }
            }
        }, runtimeStyles = styles)
        val session = provider.open(context())!!
        session.update(input(outsideRun))
        assertEquals(1, styles.inspect(world, "unit-a", "cabin").screenCount)
        val revision = session.renderRevision
        styles.set(world, "unit-a", "cabin", "other")
        assertTrue(session.renderRevision > revision)
        assertSame(PartExtensionFrame.EMPTY, session.draw(input(outsideRun)))
        assertTrue(opened.isEmpty(), "An override may bind while off without compiling or drawing")
        assertEquals(Component.text("other"), session.draw(input(activeTrip)).texts.single().text)
        assertSame(PartExtensionFrame.EMPTY, session.draw(input(outsideRun)))
        assertTrue(closed.isEmpty())
        styles.reset(world, "unit-a", "cabin")
        assertEquals(listOf("other"), opened)
        assertEquals(Component.text("main"), session.draw(input(activeTrip)).texts.single().text)
        assertEquals(listOf("other"), closed)
        session.close()
        assertEquals(listOf("other", "main"), closed)
        assertEquals(0, styles.inspect(world, "unit-a", "cabin").screenCount)
        styles.close()
    }
}
