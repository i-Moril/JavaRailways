package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ProtectionRegionTest {
    @Test
    fun `definition defensively copies boundaries and exposes inside facing`() {
        val mutable = mutableListOf(boundary("test:boundary/a", UUID.randomUUID(), 5.0, ViewDirection.BA))

        val definition = ProtectionRegionDefinition(
            ProtectionRegionRef("test:region/main", 2L),
            7L,
            mutable
        )
        mutable.clear()

        assertEquals(1, definition.boundaries.size)
        assertEquals(ViewDirection.BA, definition.boundaries.single().insideFacing)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (definition.boundaries as MutableList<ProtectionBoundaryDefinition>).clear()
        }
    }

    @Test
    fun `definition rejects duplicate boundary identity`() {
        val railId = UUID.randomUUID()

        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRegionDefinition(
                ProtectionRegionRef("test:region/main", 1L),
                3L,
                listOf(
                    boundary("test:boundary/shared", railId, 4.0, ViewDirection.AB),
                    boundary("test:boundary/shared", railId, 6.0, ViewDirection.BA)
                )
            )
        }
    }

    private fun boundary(id: String, railId: UUID, arc: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(railId, facing, arc))
}
