package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import java.util.UUID

class FixedBlockSignalCompilerTest {
    private val rail = UUID(0, 1)
    private val topology = lineTopologyState(1, rail)

    private fun snapshot() = FixedBlockCatalog().also {
        it.publish(topology, 1, listOf(FixedBlockDefinition("test:block", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 10.0)))))
    }.snapshot(topology)

    private fun signal(
        id: String = "test:signal",
        blockId: String = "test:block",
        direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD,
        lead: Double = 0.0
    ) = FixedBlockSignalDefinition(id, 1, blockId, lead, "automatic:block", direction)

    @Test
    fun `compiler retains independent directional entries and configured zero lead time`() {
        val forward = signal(lead = 0.0)
        val reverse = signal("test:reverse", direction = FixedBlockEntryDirection.REVERSE, lead = 4.0)
        val compiled = CompiledFixedBlockSignals.compile(listOf(reverse, forward), snapshot())

        assertTrue(compiled.hasSignalForEntry(requireNotNull(forward.entry)))
        assertTrue(compiled.hasSignalForEntry(requireNotNull(reverse.entry)))
        assertEquals(0.0, compiled.inspectionLeadTimeSeconds(requireNotNull(forward.entry)))
        assertEquals(4.0, compiled.inspectionLeadTimeSeconds(requireNotNull(reverse.entry)))
        assertFalse(compiled.hasSignalForEntry(FixedBlockSignalEntry("test:other")))
    }

    @Test
    fun `compiler rejects unknown blocks duplicate devices and duplicate entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(signal(blockId = "test:missing")), snapshot())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(signal(), signal()), snapshot())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(signal(), signal("test:other")), snapshot())
        }
    }

    @Test
    fun `definition requires stable namespaced type and finite scheduling lead time`() {
        assertThrows(IllegalArgumentException::class.java) {
            FixedBlockSignalDefinition("test:signal", 1, "test:block", 0.0, "not-namespaced")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedBlockSignalDefinition("test:signal", 1, "test:block", Double.NaN, "automatic:block")
        }
    }
}
