package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class ProtectionSignalEntranceResolverTest {
    private val rail = UUID(0, 1)
    private val topology = lineTopologyState(1, rail)
    private val entry = ProtectionBoundaryDefinition("test:entry",
        RailPositionSnapshot(rail, ViewDirection.AB, 2.0))
    private val exit = ProtectionBoundaryDefinition("test:exit",
        RailPositionSnapshot(rail, ViewDirection.BA, 8.0))

    @Test fun `indexed lookup requires same rail tolerance and inward direction`() {
        val snapshot = snapshot(region("test:region", entry, exit))
        assertEquals(entry, ProtectionSignalEntranceResolver.resolve(snapshot,
            RailPositionSnapshot(rail, ViewDirection.AB, 2.7)).boundary)
        listOf(
            RailPositionSnapshot(rail, ViewDirection.AB, 2.751),
            RailPositionSnapshot(rail, ViewDirection.BA, 2.0),
            RailPositionSnapshot(UUID(0, 9), ViewDirection.AB, 2.0)
        ).forEach { point -> assertThrows(IllegalArgumentException::class.java) {
            ProtectionSignalEntranceResolver.resolve(snapshot, point)
        } }
    }

    @Test fun `shared directed entrance is rejected rather than chosen by map order`() {
        val second = ProtectionBoundaryDefinition("test:second-entry", entry.position)
        val secondExit = ProtectionBoundaryDefinition("test:second-exit", exit.position)
        val snapshot = snapshot(region("test:first", entry, exit), region("test:second", second, secondExit))
        assertEquals("The selected boundary is shared by multiple protection-region entrances",
            assertThrows(IllegalArgumentException::class.java) {
                ProtectionSignalEntranceResolver.resolve(snapshot, entry.position)
            }.message)
    }

    @Test fun `boundary identity index is immutable and rejects duplicate publication identities`() {
        val snapshot = snapshot(region("test:region", entry, exit))
        assertEquals(entry, snapshot.boundariesById.getValue(entry.boundaryId))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.boundariesById as MutableMap<String, ProtectionBoundaryDefinition>)["test:other"] = exit
        }

        val duplicate = ProtectionBoundaryDefinition(entry.boundaryId,
            RailPositionSnapshot(rail, ViewDirection.AB, 3.0))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            snapshot(region("test:first", entry, exit), region("test:second", duplicate, exit))
        }
        assertTrue(failure.message!!.contains("boundary IDs must be unique"), failure.message)
    }

    private fun region(id: String, vararg boundaries: ProtectionBoundaryDefinition) =
        ResolvedProtectionRegion(ProtectionRegionDefinition(id, 1, boundaries.toList()),
            listOf(ProtectionRegionInteriorSlice(rail, 2.0, 8.0)))

    private fun snapshot(vararg regions: ResolvedProtectionRegion): ProtectionRegionCompilationSnapshot {
        val byId = regions.associateBy { it.definition.regionId }
        return ProtectionRegionCompilationSnapshot(topology, 1, ProtectionRegionTopology.direct(topology), byId,
            mapOf(rail to byId.keys))
    }
}
