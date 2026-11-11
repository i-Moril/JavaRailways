package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import java.util.UUID

class FixedBlockSignalEntranceResolverTest {
    private val rail = UUID(0, 1)
    private val topology = lineTopologyState(1, rail)

    @Test fun `forward and reverse entrances resolve by authored travel direction`() {
        val snapshot = snapshot(block("test:block"))
        val forward = signal("test:forward", FixedBlockEntryDirection.FORWARD)
        val reverse = signal("test:reverse", FixedBlockEntryDirection.REVERSE)

        assertEquals(forward, FixedBlockSignalEntranceResolver.resolve(snapshot, listOf(forward, reverse),
            RailPositionSnapshot(rail, ViewDirection.AB, 2.1)).signal)
        assertEquals(reverse, FixedBlockSignalEntranceResolver.resolve(snapshot, listOf(forward, reverse),
            RailPositionSnapshot(rail, ViewDirection.BA, 7.9)).signal)
    }

    @Test fun `reverse entrance can be selected before a primary is configured`() {
        val resolved = FixedBlockSignalEntranceResolver.resolve(snapshot(block("test:block")),
            listOf(signal("test:forward", FixedBlockEntryDirection.FORWARD)),
            RailPositionSnapshot(rail, ViewDirection.BA, 8.0))
        assertEquals("test:block", resolved.blockId)
        assertEquals(FixedBlockEntryDirection.REVERSE, resolved.entryDirection)
        assertEquals(null, resolved.signal)
    }

    @Test fun `shared boundary ambiguity is rejected instead of selecting by collection order`() {
        val first = block("test:first")
        val second = block("test:second")
        val snapshot = snapshot(first, second)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FixedBlockSignalEntranceResolver.resolve(snapshot, listOf(
                signal("test:first-signal", blockId = first.blockId),
                signal("test:second-signal", blockId = second.blockId)),
                RailPositionSnapshot(rail, ViewDirection.AB, 2.0))
        }
        assertEquals("The selected boundary is shared by multiple fixed-block entrances", failure.message)
    }

    @Test fun `block body and wrong-facing boundary are not treated as entrances`() {
        val snapshot = snapshot(block("test:block"))
        val configured = listOf(signal("test:forward"), signal("test:reverse", FixedBlockEntryDirection.REVERSE))
        listOf(
            RailPositionSnapshot(rail, ViewDirection.AB, 5.0),
            RailPositionSnapshot(rail, ViewDirection.BA, 2.0)
        ).forEach { point ->
            assertThrows(IllegalArgumentException::class.java) {
                FixedBlockSignalEntranceResolver.resolve(snapshot, configured, point)
            }
        }
    }

    @Test fun `entrance without existing primary signal still resolves its stable binding`() {
        val resolved = FixedBlockSignalEntranceResolver.resolve(snapshot(block("test:block")), emptyList(),
            RailPositionSnapshot(rail, ViewDirection.AB, 2.0))
        assertEquals("test:block", resolved.blockId)
        assertEquals(FixedBlockEntryDirection.FORWARD, resolved.entryDirection)
        assertEquals(null, resolved.signal)
    }

    @Test fun `stored binding retains reverse entry and rejects a removed block`() {
        val configured = signal("test:reverse", FixedBlockEntryDirection.REVERSE)
        val binding = FixedBlockSignalWandBinding(UUID(9, 9), "test:block", FixedBlockEntryDirection.REVERSE)
        assertEquals(configured, FixedBlockSignalEntranceResolver.resolve(
            snapshot(block("test:block")), listOf(configured), binding).signal)
        assertEquals("The bound fixed block no longer exists", assertThrows(
            IllegalArgumentException::class.java
        ) {
            FixedBlockSignalEntranceResolver.resolve(snapshot(), listOf(configured), binding)
        }.message)
    }

    private fun snapshot(vararg blocks: FixedBlockDefinition) = FixedBlockCatalog().also {
        it.publish(topology, 1, blocks.toList())
    }.snapshot(topology)

    private fun block(id: String) = FixedBlockDefinition(id, 1,
        listOf(FixedBlockTraversal(rail, ViewDirection.AB, 2.0, 8.0)))

    private fun signal(
        id: String,
        direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD,
        blockId: String = "test:block"
    ) = FixedBlockSignalDefinition(id, 1, blockId, 2.0, "automatic:block", direction)
}
