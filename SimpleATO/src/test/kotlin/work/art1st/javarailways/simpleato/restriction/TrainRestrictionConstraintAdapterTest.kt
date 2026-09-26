package work.art1st.javarailways.simpleato.restriction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintManager
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintOwnerType
import work.art1st.javarailways.basic.api.ato.constraint.DepartureHoldConstraint
import work.art1st.javarailways.basic.api.ato.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.basic.api.ato.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.basic.api.ato.constraint.StopConstraint
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
