package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class RailInfrastructureMetadataTopologyTest {
    @Test
    fun `split moves metadata and records lineage outside Core`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val original = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0)
        )
        store.put(source, original)

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-1")

        assertNull(store[source])
        assertEquals(0, store.getValue(before).createdBySplit?.segmentIndex)
        assertEquals(1, store.getValue(after).createdBySplit?.segmentIndex)
        assertEquals("split-1", store.getValue(before).createdBySplit?.operationId)
    }

    @Test
    fun `merge restores parent lineage and rejects divergent metadata`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val merged = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        store.put(source, RailInfrastructureMetadata(authoring = RailAuthoringMetadata()))
        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-1")
        store.put(after, store.getValue(after).copy(gradeLockedAtB = true))

        assertThrows(IllegalArgumentException::class.java) {
            RailInfrastructureMetadataTopology.merge(store, before, after, merged)
        }

        store.put(after, store.getValue(after).copy(gradeLockedAtB = false))
        RailInfrastructureMetadataTopology.merge(store, before, after, merged)
        assertNull(store[before])
        assertNull(store[after])
        assertNull(store.getValue(merged).createdBySplit)
    }
}
