package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedBlockSignalCompilerTest {
    @Test
    fun `configured zero lead time remains distinct from an unconfigured block`() {
        val compiled = CompiledFixedBlockSignals.compile(
            listOf(definition("test:signal", "test:configured-block", inspectionLeadTime = 0.0)),
            listOf("test:configured-block", "test:resource-only-block")
        )

        assertTrue(compiled.hasSignalForBlock("test:configured-block"))
        assertFalse(compiled.hasSignalForBlock("test:resource-only-block"))
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
                listOf("test:block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition(
                        "test:signal",
                        "test:block",
                        listOf(FixedBlockSignalDependency("test:missing-signal", 10.0))
                    )
                ),
                listOf("test:block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition(
                        "test:first-signal",
                        "test:first-block",
                        listOf(FixedBlockSignalDependency("test:second-signal", 10.0))
                    ),
                    definition(
                        "test:second-signal",
                        "test:second-block",
                        listOf(FixedBlockSignalDependency("test:first-signal", 10.0))
                    )
                ),
                listOf("test:first-block", "test:second-block")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(
                listOf(
                    definition("test:first-signal", "test:block"),
                    definition("test:second-signal", "test:block")
                ),
                listOf("test:block")
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

}
