package work.art1st.javarailways.simplesignals.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import java.util.UUID

class DirectionalSignalPersistenceTest {
    private val world = UUID(0, 120)
    private val fixture get() = requireNotNull(javaClass.getResource("/directional-fixed-block-signals.json")).readText()

    @Test fun `fixed schema one fixture preserves both independent entries and explicit writer direction`() {
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(fixture, world)
        assertEquals(listOf(FixedBlockEntryDirection.FORWARD, FixedBlockEntryDirection.REVERSE),
            decoded.fixedBlockSignals.map { it.entryDirection })
        assertEquals(1, decoded.fixedBlocks.size)
        assertEquals(listOf(12.0, 4.0), decoded.fixedBlockSignals.map { it.inspectionLeadTimeSeconds })
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(decoded)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertFalse(encoded.contains("bidirectional"))
        assertEquals(listOf("test:forward", "test:reverse"), decoded.fixedBlockSignals.map { it.signalId })
        assertEquals(work.art1st.javarailways.core.api.physics.rail.ViewDirection.BA,
            decoded.fixedBlocks.single().traversals.single().facing)
        assertTrue(encoded.contains("\"entryDirection\": \"REVERSE\""))
        assertEquals(decoded, SimpleSignalsWorldDefinitionsCodec.decode(encoded, world))
        assertNotNull(decoded.fixedBlockSignals.last().placement)
        assertTrue(decoded.fixedBlockSignals.all { it.signalType == "automatic:block" })
    }

    @Test fun `current missing direction defaults forward but unsupported version numbers are rejected`() {
        val missing = fixture.replace("\"entryDirection\": \"FORWARD\", ", "")
        assertEquals(FixedBlockEntryDirection.FORWARD,
            SimpleSignalsWorldDefinitionsCodec.decode(missing, world).fixedBlockSignals.first().entryDirection)
        for (version in listOf(0, 2, 3, 99)) assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": $version"), world)
        }
    }

    @Test fun `unknown null and incorrectly typed directions reject rather than selecting an endpoint`() {
        for (value in listOf("\"BOTH\"", "\"forward\"", "null", "0", "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                SimpleSignalsWorldDefinitionsCodec.decode(fixture.replace("\"entryDirection\": \"FORWARD\"", "\"entryDirection\": $value"), world)
            }
        }
    }
}
