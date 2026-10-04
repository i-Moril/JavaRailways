package work.art1st.javarailways.simpleato.restriction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager
import work.art1st.javarailways.simpleato.api.constraint.ConstraintOwnerType
import work.art1st.javarailways.simpleato.api.constraint.DepartureHoldConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.simpleato.api.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.StopConstraint
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.StopTrainRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import java.util.UUID

class TrainRestrictionConstraintAdapterTest {
    @Test
    fun `repeated ranges end independently and reversal consumes its own complete replacement`() {
        adapter.publish(snapshot("test:ranges", 1L, listOf(
            RouteSpeedRestriction("test:block/visit-0", 0.0, 5.0, 30.0),
            RouteSpeedRestriction("test:block/visit-1", 60.0, 5.0, 90.0))))
        adapter.apply(stamp, routeBinding, 40.0)
        val future = manager.snapshot().values.flatten().single() as RouteSpeedLimitConstraint
        assertEquals(20.0, future.distanceToStartMeters)
        adapter.apply(stamp, routeBinding, 70.0)
        assertEquals(5.0, permittedSpeed())
        adapter.apply(stamp, routeBinding, 90.001)
        assertTrue(manager.snapshot().values.flatten().isEmpty())
        val reverse = RouteSnapshot(route.routeId, 3L, route.topologyRevision,
            route.segments.map { RouteSegmentSnapshot(it.railId, ViewDirection.BA, 0.0, 100.0) })
        val reverseBinding = FrozenRouteBinding.from(reverse)
        adapter.apply(stamp, reverseBinding, 10.0)
        assertTrue(manager.snapshot().values.flatten().single() is DepartureHoldConstraint)
        adapter.publish(TrainRestrictionSnapshot("test:ranges", 2L, "train", stamp, reverseBinding,
            listOf(RouteSpeedRestriction("test:block/visit-0", 0.0, 5.0, 30.0))))
        adapter.apply(stamp, reverseBinding, 10.0)
        assertEquals(5.0, permittedSpeed())
        adapter.apply(stamp, reverseBinding, 30.001)
        assertTrue(manager.snapshot().values.flatten().isEmpty())
    }

    @Test
    fun `bounded cap brakes toward entry remains through head exit then expires without publication`() {
        adapter.publish(snapshot("test:ranges", 1L, listOf(RouteSpeedRestriction("test:range", 20.0, 5.0, 60.0))))
        adapter.apply(stamp, routeBinding, 10.0)
        assertEquals(10.0, (manager.snapshot().values.flatten().single() as RouteSpeedLimitConstraint).distanceToStartMeters)
        listOf(20.0, 30.0, 60.0).forEach { head ->
            adapter.apply(stamp.copy(physicsEpoch = 11L), routeBinding, head)
            assertEquals(5.0, permittedSpeed())
        }
        adapter.apply(stamp.copy(physicsEpoch = 12L), routeBinding, 60.0001)
        assertTrue(manager.snapshot().values.flatten().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { RouteSpeedRestriction("test:range", 20.0, 5.0, 19.0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot("test:ranges", 2L,
            listOf(RouteSpeedRestriction("test:range", 20.0, 5.0, 101.0))) }
    }

    private val stamp = RailwayStateStamp("world", 10L, 4L)
    private val route = RouteSnapshot(
        "test:route",
        2L,
        4L,
        listOf(RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.AB, 0.0, 100.0))
    )
    private val routeBinding = FrozenRouteBinding.from(route)
    private val manager = ConstraintManager()
    private val adapter = TrainRestrictionConstraintAdapter("train", manager)

    @Test
    fun `newer snapshots replace one source and stale revisions cannot restore old limits`() {
        assertTrue(adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 8.0))
        )).accepted)
        adapter.apply(stamp, routeBinding, 0.0)
        assertEquals(8.0, permittedSpeed())

        assertTrue(adapter.publish(snapshot(
            "signal:first", 2L, listOf(CurrentSpeedRestriction("signal:limit", 5.0))
        )).accepted)
        val stale = adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 12.0))
        ))
        assertEquals(TrainRestrictionPublicationStatus.STALE_REVISION, stale.status)
        assertEquals(2L, stale.acceptedRevision)

        adapter.apply(stamp.copy(physicsEpoch = 11L), routeBinding, 0.0)
        assertEquals(5.0, permittedSpeed())
    }

    @Test
    fun `different signalling sources reduce through the existing minimum constraint pipeline`() {
        adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("shared:limit", 8.0))
        ))
        adapter.publish(snapshot(
            "signal:second", 1L, listOf(CurrentSpeedRestriction("shared:limit", 5.0))
        ))

        val applied = adapter.apply(stamp, routeBinding, 0.0)

        assertTrue(applied.all { it.status == TrainRestrictionEnforcementStatus.APPLIED })
        assertEquals(5.0, permittedSpeed())
        assertEquals(1, manager.snapshot().keys.count { it.type == ConstraintOwnerType.SIGNALLING })
    }

    @Test
    fun `route targets are converted from absolute route metres using current progress`() {
        adapter.publish(snapshot("signal:first", 1L, listOf(
            RouteSpeedRestriction("signal:speed", 70.0, 6.0),
            StopTrainRestriction("signal:stop", 80.0, 0.0),
            MovementAuthorityRestriction("signal:authority", 90.0)
        )))

        adapter.apply(stamp.copy(physicsEpoch = 12L), routeBinding, 20.0)

        val values = manager.snapshot().values.single()
        assertEquals(50.0, values.filterIsInstance<RouteSpeedLimitConstraint>().single().distanceToStartMeters)
        assertEquals(60.0, values.filterIsInstance<StopConstraint>().single().remainingDistanceMeters)
        assertEquals(70.0, values.filterIsInstance<MovementAuthorityConstraint>().single().remainingDistanceMeters)
    }

    @Test
    fun `explicit newer empty snapshot clears while stale nonempty snapshot stays rejected`() {
        adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 5.0))
        ))
        adapter.apply(stamp, routeBinding, 0.0)
        assertEquals(5.0, permittedSpeed())

        adapter.publish(snapshot("signal:first", 2L, emptyList()))
        adapter.apply(stamp.copy(physicsEpoch = 11L), routeBinding, 1.0)
        assertEquals(30.0, permittedSpeed())
        assertFalse(adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 1.0))
        )).accepted)
        adapter.apply(stamp.copy(physicsEpoch = 12L), routeBinding, 2.0)
        assertEquals(30.0, permittedSpeed())
    }

    @Test
    fun `route mismatch and forced source loss fail closed instead of removing the source`() {
        adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 8.0))
        ))
        val changedRoute = routeBinding.copy(routeRevision = routeBinding.routeRevision + 1L)

        val mismatch = adapter.apply(stamp, changedRoute, 0.0)

        assertEquals(TrainRestrictionEnforcementStatus.FAILED_CLOSED, mismatch.single().status)
        assertTrue(manager.snapshot().values.single().single() is DepartureHoldConstraint)
        assertEquals(0.0, permittedSpeed())

        adapter.markSourceUnavailable("signal:first")
        assertEquals(
            TrainRestrictionPublicationStatus.SOURCE_UNAVAILABLE,
            adapter.publish(snapshot("signal:first", 2L, emptyList())).status
        )
        val unavailable = adapter.apply(stamp, routeBinding, 0.0)
        assertEquals(TrainRestrictionEnforcementStatus.FAILED_CLOSED, unavailable.single().status)
        assertEquals(0.0, permittedSpeed())
    }

    @Test
    fun `snapshot copies input and rejects duplicate or out of route restrictions`() {
        val mutable = mutableListOf(CurrentSpeedRestriction("signal:limit", 8.0))
        val copied = snapshot("signal:first", 1L, mutable)
        mutable.clear()
        assertEquals(1, copied.restrictions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (copied.restrictions as MutableList).clear()
        }

        assertThrows(IllegalArgumentException::class.java) {
            snapshot("signal:first", 1L, listOf(
                CurrentSpeedRestriction("signal:same", 8.0),
                CurrentSpeedRestriction("signal:same", 7.0)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot("signal:first", 1L, listOf(
                StopTrainRestriction("signal:outside", 101.0)
            ))
        }
    }

    @Test
    fun `closing adapter removes only the signalling owners it installed`() {
        adapter.publish(snapshot(
            "signal:first", 1L, listOf(CurrentSpeedRestriction("signal:limit", 8.0))
        ))
        adapter.apply(stamp, routeBinding, 0.0)

        adapter.close()

        assertTrue(manager.snapshot().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            adapter.apply(stamp, routeBinding, 0.0)
        }
    }

    private fun snapshot(
        sourceId: String,
        revision: Long,
        restrictions: Collection<work.art1st.javarailways.simpleato.api.restriction.TrainRestriction>
    ) = TrainRestrictionSnapshot(sourceId, revision, "train", stamp, routeBinding, restrictions)

    private fun permittedSpeed(): Double = manager.permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond = 0.0,
        maximumSpeedMetersPerSecond = 30.0,
        profile = AtoSafetyProfile()
    )
}
