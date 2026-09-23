package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource

class CompiledTailReleaseQueueTest {
    private val firstResource = StableInfrastructureResource("test:release", "first")
    private val secondResource = StableInfrastructureResource("test:release", "second")

    @Test
    fun `a later threshold cannot be ordered before an uncleared head`() {
        assertThrows(IllegalArgumentException::class.java) {
            queue(entry(firstResource, 30.0), entry(secondResource, 20.0))
        }
    }

    @Test
    fun `equal thresholds use stable resource identity order`() {
        assertThrows(IllegalArgumentException::class.java) {
            queue(entry(secondResource, 20.0), entry(firstResource, 20.0))
        }

        val queue = queue(entry(firstResource, 20.0), entry(secondResource, 20.0))
        assertEquals(listOf(firstResource, secondResource), queue.entries.map { it.resource })
    }

    @Test
    fun `duplicate resources are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            queue(entry(firstResource, 20.0), entry(firstResource, 30.0))
        }
    }

    @Test
    fun `queue and admission release metadata are immutable and cover footprint exactly once`() {
        val mutableEntries = mutableListOf(entry(firstResource, 20.0))
        val queue = CompiledTailReleaseQueue(mutableEntries)
        mutableEntries += entry(secondResource, 30.0)
        assertEquals(listOf(firstResource), queue.entries.map(ResolvedTailReleaseEntry::resource))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (queue.entries as MutableList<ResolvedTailReleaseEntry>).clear()
        }

        val admissionEntries = mutableListOf(entry(firstResource, 20.0))
        val admission = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(firstResource)),
            emptyList(),
            OccupancyQueryGeometry.create(emptyList()),
            admissionEntries,
            testRouteProgressPath()
        )
        admissionEntries.clear()
        assertEquals(listOf(firstResource), admission.tailReleaseEntries.map { it.resource })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (admission.tailReleaseEntries as MutableList<ResolvedTailReleaseEntry>).clear()
        }

        assertThrows(IllegalArgumentException::class.java) {
            CompiledRouteAdmission.create(
                InfrastructureFootprint(listOf(firstResource, secondResource)),
                emptyList(),
                OccupancyQueryGeometry.create(emptyList()),
                listOf(entry(firstResource, 20.0)),
                testRouteProgressPath()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledRouteAdmission.create(
                InfrastructureFootprint(listOf(firstResource)),
                emptyList(),
                OccupancyQueryGeometry.create(emptyList()),
                listOf(entry(firstResource, 20.0), entry(firstResource, 30.0)),
                testRouteProgressPath()
            )
        }
    }

    private fun queue(vararg entries: ResolvedTailReleaseEntry) =
        CompiledTailReleaseQueue(entries.asList())

    private fun entry(resource: InfrastructureResource, tailClearRouteMeters: Double) =
        ResolvedTailReleaseEntry(resource, tailClearRouteMeters)
}
