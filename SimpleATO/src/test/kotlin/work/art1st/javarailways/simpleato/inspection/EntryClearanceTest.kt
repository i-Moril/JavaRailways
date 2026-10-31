package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.*
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearance
import work.art1st.javarailways.simpleato.api.inspection.InspectionRestrictionIdentity
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

class EntryClearanceTest {
    private val own = ConstraintKey("test:signals", "test:entrance")
    private val requirement = InspectionEntryClearance("test:clearance", 10.0, 25.0,
        listOf(InspectionRestrictionIdentity(own.namespace, own.value)))
    private val profile = AtoSafetyProfile(safetyDistanceMeters = 5.0, stopToleranceMeters = 0.1)
    private fun state(vararg constraints: AtoConstraint, length: Double? = 10.0, routeLength: Double = 100.0,
        observed: Double = 80.0) = entryClearanceState(requirement, length, 0.0, routeLength, observed,
        profile, constraints.toList())

    @Test fun `station authority uses its carried margin while an independent safety bound remains restrictive`() {
        val station = MovementAuthorityConstraint(ConstraintKey("mission", "station-authority"), 35.3, 0.3)
        val ready = state(station)
        assertTrue(ready.movementReady)
        assertEquals(40.0, ready.requiredAuthorityEndRouteDistanceMeters)
        assertFalse(state(station, routeLength = 35.3).movementReady, "entry request floor retains its independent margin")
        assertFalse(state(station.copy(remainingDistanceMeters = 35.2)).movementReady)
        assertFalse(state(station, MovementAuthorityConstraint(ConstraintKey("signals", "red"), 35.3)).movementReady)
        assertFalse(state(station, FollowConstraint(ConstraintKey("basic", "follow"), 35.3, 0.0)).movementReady)
    }

    @Test fun `grant margin and operation stop must leave the full tail clear`() {
        assertFalse(state(MovementAuthorityConstraint(ConstraintKey("mission", "authority/1"), 39.0)).movementReady)
        val ready = state(MovementAuthorityConstraint(ConstraintKey("mission", "authority/1"), 40.0))
        assertTrue(ready.movementReady)
        assertEquals(35.0, ready.minimumHeadClearRouteDistanceMeters)
        assertEquals(40.0, ready.requiredAuthorityEndRouteDistanceMeters)
        assertFalse(state(StopConstraint(ConstraintKey("mission", "stop"), 35.05)).movementReady)
        assertTrue(state(StopConstraint(ConstraintKey("mission", "stop"), 38.0)).movementReady)
        assertFalse(state(StopConstraint(ConstraintKey("station", "stop"), 36.0, 2.0)).movementReady)
        assertFalse(state(routeLength = 39.0).movementReady)
        assertFalse(state(observed = 35.0).movementReady)
    }

    @Test fun `exclude only named self red at exact occurrence not foreign same-position or own later red`() {
        assertTrue(state(RouteSpeedLimitConstraint(own, 10.0, 0.0)).movementReady)
        assertFalse(state(RouteSpeedLimitConstraint(ConstraintKey("foreign:signals", "test:entrance"), 10.0, 0.0)).movementReady)
        assertFalse(state(RouteSpeedLimitConstraint(own, 30.0, 0.0)).movementReady)
        assertFalse(state(StopConstraint(ConstraintKey("foreign", "stop"), 10.1)).movementReady)
        assertTrue(state(RouteSpeedLimitConstraint(ConstraintKey("foreign:signals", "test:entrance"), 30.0, 1.0)).movementReady)
    }

    @Test fun `following uses conservative current space not predicted leading train motion`() {
        assertFalse(state(FollowConstraint(ConstraintKey("basic", "follow"), 39.0, 50.0)).movementReady)
        assertTrue(state(FollowConstraint(ConstraintKey("basic", "follow"), 41.0, 0.0)).movementReady)
        assertFalse(state(CurrentSpeedLimitConstraint(ConstraintKey("operations", "hold"), 0.0)).movementReady)
        assertFalse(state(DepartureHoldConstraint(ConstraintKey("station", "dwell"))).movementReady)
    }

    @Test fun `preceding stop is not downstream proof blocker but exact entrance is`() {
        assertTrue(state(RouteSpeedLimitConstraint(ConstraintKey("test:signals", "test:preceding"), 9.0, 0.0)).movementReady)
        assertTrue(state(StopConstraint(ConstraintKey("mission", "earlier"), 9.0)).movementReady)
        assertFalse(state(FollowConstraint(ConstraintKey("basic", "follow"), 15.0, 0.0)).movementReady)
    }

    @Test fun `missing geometry and overflow fail closed and repeated BA spans retain length`() {
        assertFalse(state(length = null).movementReady)
        assertFalse(state(length = Double.MAX_VALUE).movementReady)
        assertFalse(entryClearanceState(requirement, 1.0e308, 0.0, 100.0, 80.0,
            profile.copy(safetyDistanceMeters = 1.0e308), emptyList()).movementReady)
        assertNull(conservativeTrainLengthMeters(emptyList()))
        val rail = UUID(0, 1)
        assertEquals(30.0, conservativeTrainLengthMeters(listOf(
            RailTraversalSegmentSnapshot(rail, ViewDirection.AB, 0.0, 10.0),
            RailTraversalSegmentSnapshot(rail, ViewDirection.BA, 10.0, 0.0),
            RailTraversalSegmentSnapshot(rail, ViewDirection.AB, 0.0, 10.0))))
    }

    @Test fun `scan expands monotonically through newly encountered clearance entrance`() {
        val a = BoundEntryClearance("test:source", requirement)
        val b = BoundEntryClearance("test:source", InspectionEntryClearance("test:b", 30.0, 50.0))
        val c = BoundEntryClearance("test:source", InspectionEntryClearance("test:c", 80.0, 90.0))
        val compiled = CompiledEntryClearances(listOf(c, b, a))
        assertEquals(listOf(a, b), compiled.between(0.0, 20.0, 10.0, 5.0))
        assertEquals(listOf(b), compiled.between(20.0, 31.0, 10.0, 5.0))
        assertTrue(compiled.between(60.0, 70.0, 10.0, 5.0).isEmpty())
    }

    @Test fun `current physical geometry rejects wrong train stale frame derail and absent frame`() {
        val rail = UUID(0, 502)
        val head = RailPositionSnapshot(rail, ViewDirection.AB, 20.0)
        val tail = RailPositionSnapshot(rail, ViewDirection.AB, 0.0)
        fun frame(id: String = "train", epoch: Long = 4, derailed: Boolean = false) = TrainFrameSnapshot.create(
            id, "Train", epoch, epoch, 0.0, derailed, false, emptyList(), head, tail, emptyList(),
            listOf(RailTraversalSegmentSnapshot(rail, ViewDirection.AB, 0.0, 20.0)), emptyList(), emptyList(), emptyList())
        assertEquals(20.0, currentTrainLengthMeters(frame(), "train", 4))
        assertNull(currentTrainLengthMeters(frame(id = "wrong"), "train", 4))
        assertNull(currentTrainLengthMeters(frame(epoch = 3), "train", 4))
        assertNull(currentTrainLengthMeters(frame(derailed = true), "train", 4))
        assertNull(currentTrainLengthMeters(frame(), "train", 4, tail))
        assertNull(currentTrainLengthMeters(null, "train", 4))
    }

    @Test fun `length envelope absorbs solver jitter grows immediately and shrinks with hysteresis`() {
        val cache = ConservativeTrainLengthEnvelope()
        val initial = requireNotNull(cache.observe(21.999296875, 1))
        listOf(22.0051953125, 22.0025390625, 21.997109375, 22.00546875).forEach {
            assertEquals(initial, cache.observe(it, 2))
            assertTrue(initial >= it)
        }
        val grown = requireNotNull(cache.observe(22.1, 3))
        assertTrue(grown >= 22.1 && grown > initial)
        assertEquals(grown, cache.observe(21.0, 2), "older observation cannot shrink newer controller geometry")
        val shrunk = requireNotNull(cache.observe(21.0, 4))
        assertTrue(shrunk >= 21.0 && shrunk < grown)
        assertNull(cache.observe(null, 5))
        assertNull(cache.observe(Double.POSITIVE_INFINITY, 5))
        assertEquals(shrunk, cache.observe(21.0, 5))
    }
}
