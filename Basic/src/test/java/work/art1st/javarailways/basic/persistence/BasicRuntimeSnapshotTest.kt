package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BasicRuntimeSnapshotTest {
    @Test
    fun `current Basic runtime contains only pairing metadata`() {
        val snapshot = BasicRuntimeSnapshotV3(
            formatVersion = BasicRuntimeSnapshotV3.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 11L
        )

        assertEquals(snapshot, BasicRuntimeSnapshotCodec.decode(BasicRuntimeSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `current Basic runtime rejects removed rolling stock ledger`() {
        assertThrows(Exception::class.java) {
            BasicRuntimeSnapshotCodec.decode(
                """
                {"formatVersion":3,"infrastructureRevision":7,"physicsEpoch":11,"rollingStock":[]}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `current Basic runtime rejects unsupported versions`() {
        val encoded = BasicRuntimeSnapshotCodec.encode(
            BasicRuntimeSnapshotV3(
                formatVersion = BasicRuntimeSnapshotV3.CURRENT_FORMAT_VERSION + 1,
                infrastructureRevision = 7L,
                physicsEpoch = 11L
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            BasicRuntimeSnapshotCodec.decode(encoded)
        }
    }
}
