package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.UUID
import java.util.concurrent.Executors

class SavedRailConnectionsTest {
    enum class InvalidConnection {
        MALFORMED_NEXT,
        MISSING_NEXT,
        MALFORMED_ACTIVE,
        UNCONNECTED_ACTIVE,
        ACTIVE_WITHOUT_CONNECTION,
        REMOTE_ENDPOINTS,
        DUPLICATE_NEXT,
        TOO_MANY_NEXT
    }

    @ParameterizedTest
    @EnumSource(InvalidConnection::class)
    fun `world load rejects invalid declared endpoint state`(invalid: InvalidConnection) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            withLoadedWorld(invalidRails(invalid)) { }
        }
        assertFailureContext(invalid, failure)
    }

    @ParameterizedTest
    @EnumSource(InvalidConnection::class)
    fun `prepared restore rejects the same invalid declared endpoint state`(invalid: InvalidConnection) {
        val world = createTestWorld()
        try {
            val root = world.topologyState
            val failure = assertThrows(IllegalArgumentException::class.java) {
                world.prepareRailWorldSnapshotRestore(emptyList(), invalidRails(invalid))
            }
            assertFailureContext(invalid, failure)
            assertTrue(world.allRails.isEmpty())
            assertSame(root, world.topologyState)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @ParameterizedTest
    @CsvSource("A,A", "A,B", "B,A", "B,B")
    fun `world load resolves the declared connection at either endpoint`(
        sourceEndpoint: RailEndpoint,
        targetEndpoint: RailEndpoint
    ) {
        val anchor = Vector3d()
        val sourceOther = Vector3d(-10.0, 0.0, 0.0)
        val targetOther = Vector3d(10.0, 0.0, 0.0)
        val source = savedRail(
            SOURCE_ID,
            if (sourceEndpoint == RailEndpoint.A) anchor else sourceOther,
            if (sourceEndpoint == RailEndpoint.A) sourceOther else anchor,
            atA = if (sourceEndpoint == RailEndpoint.A) listOf(FIRST_ID) else emptyList(),
            atB = if (sourceEndpoint == RailEndpoint.B) listOf(FIRST_ID) else emptyList(),
            activeA = if (sourceEndpoint == RailEndpoint.A) FIRST_ID else null,
            activeB = if (sourceEndpoint == RailEndpoint.B) FIRST_ID else null
        )
        val target = savedRail(
            FIRST_ID,
            if (targetEndpoint == RailEndpoint.A) anchor else targetOther,
            if (targetEndpoint == RailEndpoint.A) targetOther else anchor,
            atA = if (targetEndpoint == RailEndpoint.A) listOf(SOURCE_ID) else emptyList(),
            atB = if (targetEndpoint == RailEndpoint.B) listOf(SOURCE_ID) else emptyList(),
            activeA = if (targetEndpoint == RailEndpoint.A) SOURCE_ID else null,
            activeB = if (targetEndpoint == RailEndpoint.B) SOURCE_ID else null
        )

        withLoadedWorld(listOf(source, target)) { world ->
            val sourceRail = requireNotNull(world.getRail(UUID.fromString(SOURCE_ID)))
            val targetRail = requireNotNull(world.getRail(UUID.fromString(FIRST_ID)))
            val arriving = if (sourceEndpoint == RailEndpoint.A) sourceRail.viewBA else sourceRail.viewAB
            val away = if (targetEndpoint == RailEndpoint.A) targetRail.viewAB else targetRail.viewBA
            assertEquals(listOf(away), arriving.nextRails)
            assertSame(away, arriving.activeRail)
            assertEquals(listOf(arriving.reversed), away.reversed.nextRails)
            assertNull(arriving.reversed.activeRail)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `load and prepared restore preserve connection order and nullable active selection`(selectSecond: Boolean) {
        val rails = branchRails(selectSecond)
        fun verify(world: CoreWorld) {
            val source = requireNotNull(world.getRail(UUID.fromString(SOURCE_ID)))
            val first = requireNotNull(world.getRail(UUID.fromString(FIRST_ID)))
            assertEquals(listOf(SECOND_ID, FIRST_ID), source.viewAB.nextRails.map { it.rail.id.toString() })
            assertSame(if (selectSecond) first.viewAB else null, source.viewAB.activeRail)
            assertNull(source.viewBA.activeRail)
            assertEquals(if (selectSecond) FIRST_ID else null, source.save().activeTrackAtB)
        }
        withLoadedWorld(rails, ::verify)

        val world = createTestWorld()
        try {
            val plan = world.prepareRailWorldSnapshotRestore(emptyList(), rails)
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            verify(world)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `invalid second endpoint is rejected before either endpoint is changed`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(), Vector3d(10.0, 0.0, 0.0)))
            val before = world.createRailForTest(line(Vector3d(-10.0, 0.0, 0.0), Vector3d()))
            val after = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            CoreRail.connect(before, source)
            CoreRail.connect(source, after)
            val original = source.save()
            val root = world.topologyState
            val invalid = original.copy(
                nextRailAtA = emptyList(),
                activeTrackAtA = null,
                nextRailAtB = listOf(after.id.toString(), MISSING_ID)
            )

            val failure = assertThrows(IllegalArgumentException::class.java) {
                invalid.restoreConnections(source, CoreRail.SavedRailV1.Dependency(world))
            }

            assertTrue(failure.message.orEmpty().contains(MISSING_ID))
            assertEquals(original, source.save())
            assertSame(before.viewBA, source.viewBA.activeRail)
            assertSame(after.viewAB, source.viewAB.activeRail)
            assertSame(root, world.topologyState)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun invalidRails(invalid: InvalidConnection): List<CoreRail.SavedRailV1> {
        val source = savedRail(
            SOURCE_ID, Vector3d(), Vector3d(10.0, 0.0, 0.0),
            atB = listOf(FIRST_ID), activeB = FIRST_ID
        )
        val first = savedRail(
            FIRST_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0),
            atA = listOf(SOURCE_ID), activeA = SOURCE_ID
        )
        return when (invalid) {
            InvalidConnection.MALFORMED_NEXT -> listOf(source.copy(nextRailAtB = listOf("invalid-uuid"), activeTrackAtB = null))
            InvalidConnection.MISSING_NEXT -> listOf(source.copy(nextRailAtB = listOf(MISSING_ID), activeTrackAtB = null))
            InvalidConnection.MALFORMED_ACTIVE -> listOf(source.copy(activeTrackAtB = "invalid-uuid"), first)
            InvalidConnection.UNCONNECTED_ACTIVE -> listOf(
                source.copy(activeTrackAtB = SECOND_ID), first,
                savedRail(SECOND_ID, Vector3d(30.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0))
            )
            InvalidConnection.ACTIVE_WITHOUT_CONNECTION -> listOf(source.copy(nextRailAtB = emptyList()))
            InvalidConnection.REMOTE_ENDPOINTS -> listOf(
                source,
                savedRail(
                    FIRST_ID, Vector3d(100.0, 0.0, 0.0), Vector3d(110.0, 0.0, 0.0),
                    atA = listOf(SOURCE_ID), activeA = SOURCE_ID
                )
            )
            InvalidConnection.DUPLICATE_NEXT -> listOf(source.copy(nextRailAtB = listOf(FIRST_ID, FIRST_ID)), first)
            InvalidConnection.TOO_MANY_NEXT -> branchRails(true).map { rail ->
                if (rail.id == SOURCE_ID) rail.copy(nextRailAtB = listOf(FIRST_ID, SECOND_ID, THIRD_ID)) else rail
            } + savedRail(
                THIRD_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, -5.0),
                atA = listOf(SOURCE_ID), activeA = SOURCE_ID
            )
        }
    }

    private fun assertFailureContext(invalid: InvalidConnection, failure: IllegalArgumentException) {
        assertTrue(failure.message.orEmpty().contains(SOURCE_ID))
        if (invalid == InvalidConnection.MALFORMED_NEXT || invalid == InvalidConnection.MALFORMED_ACTIVE) {
            assertTrue(failure.message.orEmpty().contains("invalid-uuid"))
            assertTrue(failure.cause is IllegalArgumentException)
        }
    }

    private fun branchRails(selectSecond: Boolean): List<CoreRail.SavedRailV1> = listOf(
        savedRail(
            SOURCE_ID, Vector3d(), Vector3d(10.0, 0.0, 0.0),
            atB = listOf(SECOND_ID, FIRST_ID), activeB = if (selectSecond) FIRST_ID else null
        ),
        savedRail(
            FIRST_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0),
            atA = listOf(SOURCE_ID), activeA = SOURCE_ID
        ),
        savedRail(
            SECOND_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0),
            atA = listOf(SOURCE_ID), activeA = SOURCE_ID
        )
    )

    private fun <T> withLoadedWorld(rails: List<CoreRail.SavedRailV1>, action: (CoreWorld) -> T): T {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val tickManager = TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), System.currentTimeMillis())
        try {
            val world = CoreWorld.load(
                CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 7L, rails),
                null,
                CoreWorld.SnapshotDependency(tickManager, NoOpWorldParentContext, coordinator)
            )
            return action(world)
        } finally {
            coordinator.close()
            tickManager.close()
        }
    }

    private fun savedRail(
        id: String,
        from: Vector3d,
        to: Vector3d,
        atA: List<String> = emptyList(),
        atB: List<String> = emptyList(),
        activeA: String? = null,
        activeB: String? = null
    ) = CoreRail.SavedRailV1(
        id, line(from, to).save() as PrimeCurveImpl.SavedPrimeCurveV1,
        atA, atB, activeA, activeB
    )

    private fun line(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), tangent, Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(to), tangent, Vector3d(0.0, 1.0, 0.0), from.distance(to), Curve.SegmentType.LINE_END)
        ))
    }

    companion object {
        private const val SOURCE_ID = "00000000-0000-0000-0000-000000000001"
        private const val FIRST_ID = "00000000-0000-0000-0000-000000000002"
        private const val SECOND_ID = "00000000-0000-0000-0000-000000000003"
        private const val THIRD_ID = "00000000-0000-0000-0000-000000000004"
        private const val MISSING_ID = "00000000-0000-0000-0000-000000000099"
    }
}
