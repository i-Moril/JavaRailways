package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import java.util.UUID

class CompiledRouteReleaseTrackerTest {
    private val firstRail = UUID(0L, 101L)
    private val secondRail = UUID(0L, 102L)
    private val firstResource = StableInfrastructureResource("test:release", "first")
    private val secondResource = StableInfrastructureResource("test:release", "second")

    @Test
    fun `no release occurs before final occurrence is entered`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(slice(firstResource, firstRail, 40.0, 50.0))
        )

        assertReleased(emptyList(), tracker.update(39.0, emptyList()))
        assertReleased(emptyList(), tracker.update(39.0, listOf(interval(firstRail))))
        assertReleased(emptyList(), tracker.update(40.0, emptyList()))
    }

    @Test
    fun `progress past the gate cannot release while the tail still overlaps`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(slice(firstResource, firstRail, 10.0, 20.0))
        )

        assertReleased(emptyList(), tracker.update(25.0, listOf(interval(firstRail))))
        assertReleased(emptyList(), tracker.update(30.0, listOf(interval(firstRail))))
        assertReleased(listOf(firstResource), tracker.update(31.0, emptyList()))
        assertReleased(emptyList(), tracker.update(32.0, emptyList()))
    }

    @Test
    fun `later cleared slice cannot skip an uncleared prefix`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(
                slice(firstResource, firstRail, 5.0, 10.0),
                slice(secondResource, secondRail, 15.0, 20.0)
            )
        )

        assertReleased(emptyList(), tracker.update(20.0, listOf(interval(secondRail))))
        assertReleased(emptyList(), tracker.update(21.0, emptyList()))
        assertReleased(emptyList(), tracker.update(22.0, listOf(interval(firstRail))))
        assertReleased(
            listOf(firstResource, secondResource),
            tracker.update(23.0, emptyList())
        )
    }

    @Test
    fun `first visit to repeated rail cannot arm release for final occurrence`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(slice(firstResource, firstRail, 50.0, 60.0))
        )

        assertReleased(emptyList(), tracker.update(10.0, listOf(interval(firstRail))))
        assertReleased(emptyList(), tracker.update(20.0, emptyList()))
        assertReleased(emptyList(), tracker.update(50.0, listOf(interval(firstRail))))
        assertReleased(emptyList(), tracker.update(60.0, listOf(interval(firstRail))))
        assertReleased(listOf(firstResource), tracker.update(61.0, emptyList()))
    }

    @Test
    fun `non monotonic progress permanently rejects without releasing`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(slice(firstResource, firstRail, 10.0, 20.0))
        )
        assertReleased(emptyList(), tracker.update(20.0, listOf(interval(firstRail))))

        assertInstanceOf(
            RouteReleaseTrackingResult.Rejected::class.java,
            tracker.update(19.0, emptyList())
        )
        assertInstanceOf(
            RouteReleaseTrackingResult.Rejected::class.java,
            tracker.update(21.0, emptyList())
        )
    }

    @Test
    fun `non finite progress permanently rejects without releasing`() {
        val tracker = CompiledRouteReleaseTracker(
            listOf(slice(firstResource, firstRail, 10.0, 20.0))
        )

        assertInstanceOf(
            RouteReleaseTrackingResult.Rejected::class.java,
            tracker.update(Double.NaN, listOf(interval(firstRail)))
        )
        assertInstanceOf(
            RouteReleaseTrackingResult.Rejected::class.java,
            tracker.update(21.0, emptyList())
        )
    }

    @Test
    fun `release metadata is immutable and covers the footprint exactly once`() {
        val mutableResources = mutableListOf<InfrastructureResource>(firstResource)
        val first = ResolvedReleaseSlice.create(
            mutableResources,
            OccupancyQueryGeometry.create(listOf(interval(firstRail))),
            10.0,
            20.0
        )
        mutableResources += secondResource
        assertEquals(listOf(firstResource), first.resources)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first.resources as MutableList<InfrastructureResource>).clear()
        }

        val mutableSlices = mutableListOf(first)
        val admission = CompiledRouteAdmission.create(
            InfrastructureFootprint(listOf(firstResource)),
            emptyList(),
            OccupancyQueryGeometry.create(listOf(interval(firstRail))),
            mutableSlices
        )
        mutableSlices.clear()
        assertEquals(listOf(first), admission.releaseSlices)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (admission.releaseSlices as MutableList<ResolvedReleaseSlice>).clear()
        }

        val geometry = OccupancyQueryGeometry.create(listOf(interval(firstRail), interval(secondRail)))
        assertThrows(IllegalArgumentException::class.java) {
            CompiledRouteAdmission.create(
                InfrastructureFootprint(listOf(firstResource, secondResource)),
                emptyList(),
                geometry,
                listOf(first)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledRouteAdmission.create(
                InfrastructureFootprint(listOf(firstResource)),
                emptyList(),
                geometry,
                listOf(first, first)
            )
        }
    }

    private fun slice(
        resource: InfrastructureResource,
        railId: UUID,
        observeFrom: Double,
        releaseAfter: Double
    ) = ResolvedReleaseSlice.create(
        listOf(resource),
        OccupancyQueryGeometry.create(listOf(interval(railId))),
        observeFrom,
        releaseAfter
    )

    private fun interval(railId: UUID) = RailInterval(railId, 4.0, 6.0)

    private fun assertReleased(
        expected: List<InfrastructureResource>,
        result: RouteReleaseTrackingResult
    ) {
        val advanced = assertInstanceOf(RouteReleaseTrackingResult.Advanced::class.java, result)
        assertEquals(expected, advanced.newlyClearedResources)
    }
}
