package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.topologyState
import java.util.UUID

class FixedBlockSignalCompilerTest {
    @Test
    fun `configured zero lead time remains distinct from an unconfigured block`() {
        val compiled = CompiledFixedBlockSignals.compile(
            listOf(definition("test:signal", "test:configured-block", inspectionLeadTime = 0.0)),
            blocks("test:configured-block", "test:resource-only-block")
        )

        assertTrue(compiled.hasSignalForEntry(FixedBlockSignalEntry("test:configured-block")))
        assertFalse(compiled.hasSignalForEntry(FixedBlockSignalEntry("test:resource-only-block")))
    }

    @Test
    fun `definition rejects incomplete clear tables and invalid inspection lead time`() {
        assertThrows(IllegalArgumentException::class.java) {
            FixedBlockSignalDefinition(
                "test:signal",
                1L,
                "test:block",
                100.0,
                ConfiguredSignalOutput(0.0, "occupied/private"),
                listOf(
                    FixedBlockSignalClearLevel(
                        10.0,
                        ConfiguredSignalOutput(20.0, "clear/private")
                    )
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixedBlockSignalDefinition(
                "test:signal",
                1L,
                "test:block",
                -1.0,
                ConfiguredSignalOutput(0.0, "occupied/private"),
                listOf(
                    FixedBlockSignalClearLevel(
                        0.0,
                        ConfiguredSignalOutput(20.0, "clear/private")
                    )
                )
            )
        }
    }

    @Test
    fun `compiler rejects unknown blocks downstream references and cycles`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(definition("test:signal", "test:missing-block")),
                blocks("test:block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition(
                        "test:signal",
                        "test:block",
                        listOf(FixedBlockSignalDependency("test:missing-signal"))
                    )
                ),
                blocks("test:block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition(
                        "test:first-signal",
                        "test:first-block",
                        listOf(FixedBlockSignalDependency("test:second-signal"))
                    ),
                    definition(
                        "test:second-signal",
                        "test:second-block",
                        listOf(FixedBlockSignalDependency("test:first-signal"))
                    )
                ),
                blocks("test:first-block", "test:second-block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition("test:first-signal", "test:block"),
                    definition("test:second-signal", "test:block")
                ),
                blocks("test:block")
            )
        }
    }

    @Test
    fun `compiler requires explicit legal turnout choices and rejects unreachable paths`() {
        val sourceRail = UUID.nameUUIDFromBytes("signal-path-source".toByteArray())
        val firstMiddle = UUID.nameUUIDFromBytes("signal-path-first-middle".toByteArray())
        val secondMiddle = UUID.nameUUIDFromBytes("signal-path-second-middle".toByteArray())
        val targetRail = UUID.nameUUIDFromBytes("signal-path-target".toByteArray())
        val ambiguousTopology = topologyState(
            2L,
            listOf(sourceRail, firstMiddle, secondMiddle, targetRail),
            listOf(
                endpoint(sourceRail, RailEndpoint.B) to endpoint(firstMiddle, RailEndpoint.A),
                endpoint(sourceRail, RailEndpoint.B) to endpoint(secondMiddle, RailEndpoint.A),
                endpoint(firstMiddle, RailEndpoint.B) to endpoint(targetRail, RailEndpoint.A),
                endpoint(secondMiddle, RailEndpoint.B) to endpoint(targetRail, RailEndpoint.A)
            )
        )
        val sourceBlock = fixedBlock("test:path-source-block", sourceRail)
        val targetBlock = fixedBlock("test:path-target-block", targetRail)
        val targetSignal = definition("test:path-target-signal", targetBlock.blockId)

        assertThrows(IllegalArgumentException::class.java) {
            compile(
                ambiguousTopology,
                listOf(sourceBlock, targetBlock),
                listOf(
                    definition(
                        "test:path-source-signal",
                        sourceBlock.blockId,
                        listOf(FixedBlockSignalDependency(targetSignal.signalId))
                    ),
                    targetSignal
                )
            )
        }

        val directTopology = topologyState(
            4L,
            listOf(sourceRail, targetRail),
            listOf(endpoint(sourceRail, RailEndpoint.B) to endpoint(targetRail, RailEndpoint.A))
        )
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                directTopology,
                listOf(sourceBlock, targetBlock),
                listOf(
                    definition(
                        "test:path-source-signal",
                        sourceBlock.blockId,
                        listOf(
                            FixedBlockSignalDependency(
                                targetSignal.signalId,
                                listOf(
                                    FixedBlockSignalContinuationRequirement(
                                        endpoint(sourceRail, RailEndpoint.B),
                                        endpoint(targetRail, RailEndpoint.A)
                                    )
                                )
                            )
                        )
                    ),
                    targetSignal
                )
            )
        }

        val selectedFirstPath = FixedBlockSignalDependency(
            targetSignal.signalId,
            listOf(
                FixedBlockSignalContinuationRequirement(
                    endpoint(sourceRail, RailEndpoint.B),
                    endpoint(firstMiddle, RailEndpoint.A)
                )
            )
        )
        val compiled = compile(
            ambiguousTopology,
            listOf(sourceBlock, targetBlock),
            listOf(
                definition(
                    "test:path-source-signal",
                    sourceBlock.blockId,
                    listOf(selectedFirstPath)
                ),
                targetSignal
            )
        )
        assertTrue(
            compiled.ruleRuntime!!.watchedContinuationEntries.contains(
                endpoint(sourceRail, RailEndpoint.B)
            )
        )

        val disconnectedTopology = topologyState(
            3L,
            listOf(sourceRail, targetRail),
            emptyList()
        )
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                disconnectedTopology,
                listOf(sourceBlock, targetBlock),
                listOf(
                    definition(
                        "test:path-source-signal",
                        sourceBlock.blockId,
                        listOf(FixedBlockSignalDependency(targetSignal.signalId))
                    ),
                    targetSignal
                )
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            compile(
                ambiguousTopology,
                listOf(sourceBlock, targetBlock),
                listOf(
                    definition(
                        "test:path-source-signal",
                        sourceBlock.blockId,
                        listOf(
                            FixedBlockSignalDependency(
                                targetSignal.signalId,
                                listOf(
                                    FixedBlockSignalContinuationRequirement(
                                        endpoint(sourceRail, RailEndpoint.B),
                                        endpoint(targetRail, RailEndpoint.A)
                                    )
                                )
                            )
                        )
                    ),
                    targetSignal
                )
            )
        }
    }

    private fun definition(
        signalId: String,
        blockId: String,
        downstream: List<FixedBlockSignalDependency> = emptyList(),
        inspectionLeadTime: Double = 450.0
    ) = FixedBlockSignalDefinition(
        signalId,
        1L,
        blockId,
        inspectionLeadTime,
        ConfiguredSignalOutput(0.0, "occupied/private"),
        listOf(
            FixedBlockSignalClearLevel(
                0.0,
                ConfiguredSignalOutput(9.0, "clear/limited")
            ),
            FixedBlockSignalClearLevel(
                20.0,
                ConfiguredSignalOutput(28.0, "clear/open")
            )
        ),
        downstream
    )

    private fun blocks(vararg blockIds: String): work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot {
        val railId = UUID.nameUUIDFromBytes("signal-compiler-rail".toByteArray())
        val topology = lineTopologyState(1L, railId)
        val catalog = FixedBlockCatalog()
        catalog.publish(
            topology,
            1L,
            blockIds.mapIndexed { index, blockId ->
                FixedBlockDefinition(
                    blockId,
                    1L,
                    listOf(
                        FixedBlockTraversal(
                            railId,
                            ViewDirection.AB,
                            index * 2.0,
                            index * 2.0 + 1.0
                        )
                    )
                )
            }
        )
        return catalog.snapshot(topology)
    }

    private fun fixedBlock(blockId: String, railId: UUID) = FixedBlockDefinition(
        blockId,
        1L,
        listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 10.0))
    )

    private fun compile(
        topology: TopologyState,
        blocks: List<FixedBlockDefinition>,
        signals: List<FixedBlockSignalDefinition>
    ): CompiledFixedBlockSignals {
        val catalog = FixedBlockCatalog()
        catalog.publish(topology, 1L, blocks)
        return CompiledFixedBlockSignals.compile(signals, catalog.snapshot(topology))
    }

}
