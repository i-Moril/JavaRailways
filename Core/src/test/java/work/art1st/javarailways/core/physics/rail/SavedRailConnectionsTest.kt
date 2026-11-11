package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import kotlinx.serialization.json.*
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
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
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
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
        TOO_MANY_NEXT,
        WRONG_DECLARED_ENDPOINT,
        ACTIVE_OTHER_ENDPOINT,
        ASYMMETRIC,
        SELF_ENDPOINT,
        DUPLICATE_RAIL_ID
    }

    @ParameterizedTest
    @EnumSource(InvalidConnection::class)
    fun `world load rejects invalid declared endpoint state`(invalid: InvalidConnection) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            withLoadedWorld(invalidRails(invalid)) { }
        }
        assertFailureContext(invalid, failure)
        withWorld { healthy ->
            assertEquals(PhysicsCoordinatorStatus.RUNNING, healthy.physicsCoordinator.status)
            assertTrue(healthy.allRails.isEmpty())
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidConnection::class)
    fun `prepared restore rejects the same invalid declared endpoint state`(invalid: InvalidConnection) {
        withWorld { world ->
            val root = world.topologyState
            val failure = assertThrows(IllegalArgumentException::class.java) {
                world.prepareRailWorldSnapshotRestore(emptyList(), invalidRails(invalid))
            }
            assertFailureContext(invalid, failure)
            assertTrue(world.allRails.isEmpty())
            assertSame(root, world.topologyState)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
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
            atA = if (sourceEndpoint == RailEndpoint.A) listOf(ref(FIRST_ID, targetEndpoint)) else emptyList(),
            atB = if (sourceEndpoint == RailEndpoint.B) listOf(ref(FIRST_ID, targetEndpoint)) else emptyList(),
            activeA = if (sourceEndpoint == RailEndpoint.A) ref(FIRST_ID, targetEndpoint) else null,
            activeB = if (sourceEndpoint == RailEndpoint.B) ref(FIRST_ID, targetEndpoint) else null
        )
        val target = savedRail(
            FIRST_ID,
            if (targetEndpoint == RailEndpoint.A) anchor else targetOther,
            if (targetEndpoint == RailEndpoint.A) targetOther else anchor,
            atA = if (targetEndpoint == RailEndpoint.A) listOf(ref(SOURCE_ID, sourceEndpoint)) else emptyList(),
            atB = if (targetEndpoint == RailEndpoint.B) listOf(ref(SOURCE_ID, sourceEndpoint)) else emptyList(),
            activeA = if (targetEndpoint == RailEndpoint.A) ref(SOURCE_ID, sourceEndpoint) else null,
            activeB = if (targetEndpoint == RailEndpoint.B) ref(SOURCE_ID, sourceEndpoint) else null
        )

        fun verify(world: CoreWorld) {
            val sourceRail = requireNotNull(world.getRail(UUID.fromString(SOURCE_ID)))
            val targetRail = requireNotNull(world.getRail(UUID.fromString(FIRST_ID)))
            val arriving = if (sourceEndpoint == RailEndpoint.A) sourceRail.viewBA else sourceRail.viewAB
            val away = if (targetEndpoint == RailEndpoint.A) targetRail.viewAB else targetRail.viewBA
            assertEquals(listOf(away), arriving.nextRails)
            assertSame(away, arriving.activeRail)
            assertEquals(listOf(arriving.reversed), away.reversed.nextRails)
            assertNull(arriving.reversed.activeRail)
        }
        val rails = listOf(source, target)
        withLoadedWorld(rails, action = ::verify)
        withWorld { world ->
            val plan = world.prepareRailWorldSnapshotRestore(emptyList(), rails)
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) } }
            verify(world)
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
            assertEquals(if (selectSecond) ref(FIRST_ID, RailEndpoint.A) else null, source.save().activeConnectionAtB)
        }
        withLoadedWorld(rails, action = ::verify)

        withWorld { world ->
            val plan = world.prepareRailWorldSnapshotRestore(emptyList(), rails)
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) } }
            verify(world)
        }
    }

    @Test
    fun `invalid second endpoint is rejected before either endpoint is changed`() {
        withWorld { world ->
            val source = world.createRailForTest(line(Vector3d(), Vector3d(10.0, 0.0, 0.0)))
            val before = world.createRailForTest(line(Vector3d(-10.0, 0.0, 0.0), Vector3d()))
            val after = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(before, source) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(source, after) }
            val original = source.save()
            val root = world.topologyState
            val invalid = original.copy(
                connectionsAtA = emptyList(),
                joinedEndpointsAtA = emptyList(),
                activeConnectionAtA = null,
                connectionsAtB = listOf(ref(after.id.toString(), RailEndpoint.A), ref(MISSING_ID, RailEndpoint.A))
            )

            val failure = assertThrows(IllegalArgumentException::class.java) {
                IsolatedSimulationThreadMarker.run { invalid.restoreConnections(source, CoreRail.SavedRailV1.Dependency(world)) }
            }

            assertTrue(failure.message.orEmpty().contains(MISSING_ID))
            assertEquals(original, source.save())
            assertSame(before.viewBA, source.viewBA.activeRail)
            assertSame(after.viewAB, source.viewAB.activeRail)
            assertSame(root, world.topologyState)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        }
    }

    @Test
    fun `fixed current JSON contains exact endpoint identity and rejects unknown tokens and old shape`() {
        val target = Json.decodeFromString(SavedRailEndpointRef.serializer(),
            """{"railId":"00000000-0000-0000-0000-000000000002","endpoint":"B"}""")
        assertEquals(ref(FIRST_ID, RailEndpoint.B), target)
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString(SavedRailEndpointRef.serializer(),
                """{"railId":"00000000-0000-0000-0000-000000000002","endpoint":"C"}""")
        }
        val fixed = """{
          "formatVersion":2,"infrastructureRevision":7,"rails":[{
            "id":"00000000-0000-0000-0000-000000000001",
            "curveAB":{"pointList":[
              {"position":{"x":0.0,"y":0.0,"z":0.0},"tangent":{"x":1.0,"y":0.0,"z":0.0},"normal":{"x":0.0,"y":1.0,"z":0.0},"arcLength":0.0,"segmentType":"DISCRETE"},
              {"position":{"x":10.0,"y":0.0,"z":0.0},"tangent":{"x":1.0,"y":0.0,"z":0.0},"normal":{"x":0.0,"y":1.0,"z":0.0},"arcLength":10.0,"segmentType":"DISCRETE"}
            ]},
            "connectionsAtA":[],"connectionsAtB":[{"railId":"00000000-0000-0000-0000-000000000002","endpoint":"B"}],
            "activeConnectionAtA":null,"activeConnectionAtB":{"railId":"00000000-0000-0000-0000-000000000002","endpoint":"B"},
            "joinedEndpointsAtA":[],"joinedEndpointsAtB":[{"railId":"00000000-0000-0000-0000-000000000002","endpoint":"B"}]
          }]
        }"""
        val decoded = WorldSnapshotCodec.decodeInfrastructure(fixed)
        assertEquals(listOf(target), decoded.rails.single().connectionsAtB)
        assertEquals(target, decoded.rails.single().activeConnectionAtB)
        val encoded = Json.parseToJsonElement(WorldSnapshotCodec.encodeInfrastructure(decoded)).jsonObject
        val rail = encoded.getValue("rails").jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("""{"railId":"$FIRST_ID","endpoint":"B"}"""),
            rail.getValue("connectionsAtB").jsonArray.single())
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure(fixed.replace("connectionsAtA", "nextRailAtA"))
        }
    }

    @Test
    fun `same target rail different endpoints retain distinct identities and selected second endpoint`() {
        val anchor = Vector3d(10.0, 0.0, 0.0)
        // Both ends satisfy the existing tolerance. Geometry cannot identify the selected end.
        val rails = listOf(
            savedRail(SOURCE_ID, Vector3d(), anchor,
                atB = listOf(ref(FIRST_ID, RailEndpoint.A), ref(FIRST_ID, RailEndpoint.B)),
                activeB = ref(FIRST_ID, RailEndpoint.B)),
            savedRail(FIRST_ID, anchor, Vector3d(10.001, 0.0, 0.0),
                atA = listOf(ref(SOURCE_ID, RailEndpoint.B)),
                atB = listOf(ref(SOURCE_ID, RailEndpoint.B)),
                joinedA = listOf(ref(SOURCE_ID, RailEndpoint.B), ref(FIRST_ID, RailEndpoint.B)),
                joinedB = listOf(ref(SOURCE_ID, RailEndpoint.B), ref(FIRST_ID, RailEndpoint.A)))
                .copy(curveAB = PrimeCurve.fromPoints(listOf(
                    Curve.RawPoint(anchor, Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                    Curve.RawPoint(Vector3d(20.0, 0.0, 5.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 12.0),
                    Curve.RawPoint(Vector3d(10.001, 0.0, 0.0), Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 24.0)
                )).save() as PrimeCurveImpl.SavedPrimeCurveV1)
        )
        fun verify(world: CoreWorld) {
            val source = requireNotNull(world.getRail(UUID.fromString(SOURCE_ID)))
            val target = requireNotNull(world.getRail(UUID.fromString(FIRST_ID)))
            assertEquals(listOf(target.viewAB, target.viewBA), source.viewAB.nextRails)
            assertSame(target.viewBA, source.viewAB.activeRail)
            assertEquals(rails.first().connectionsAtB, source.save().connectionsAtB)
            assertEquals(rails.first().activeConnectionAtB, source.save().activeConnectionAtB)
            val batch = world.prepareRailCreation(line(Vector3d(30.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0)))
            val update = world.capturePreparedRailConnectionPlan(batch, emptyList()).prepareTopologyUpdate()
            val saved = update.targetRailSnapshot.single { it.id == SOURCE_ID }
            assertEquals(rails.first().connectionsAtB, saved.connectionsAtB)
            assertEquals(rails.first().activeConnectionAtB, saved.activeConnectionAtB)
        }
        withLoadedWorld(rails, action = ::verify)
        withWorld { world ->
            val plan = world.prepareRailWorldSnapshotRestore(emptyList(), rails)
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) } }
            verify(world)
        }
    }

    private fun invalidRails(invalid: InvalidConnection): List<CoreRail.SavedRailV1> {
        val source = savedRail(
            SOURCE_ID, Vector3d(), Vector3d(10.0, 0.0, 0.0),
            atB = listOf(ref(FIRST_ID, RailEndpoint.A)), activeB = ref(FIRST_ID, RailEndpoint.A)
        )
        val first = savedRail(
            FIRST_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0),
            atA = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeA = ref(SOURCE_ID, RailEndpoint.B)
        )
        return when (invalid) {
            InvalidConnection.MALFORMED_NEXT -> listOf(source.copy(connectionsAtB = listOf(ref("invalid-uuid", RailEndpoint.A)), activeConnectionAtB = null))
            InvalidConnection.MISSING_NEXT -> listOf(source.copy(connectionsAtB = listOf(ref(MISSING_ID, RailEndpoint.A)), activeConnectionAtB = null))
            InvalidConnection.MALFORMED_ACTIVE -> listOf(source.copy(activeConnectionAtB = ref("invalid-uuid", RailEndpoint.A)), first)
            InvalidConnection.UNCONNECTED_ACTIVE -> listOf(
                source.copy(activeConnectionAtB = ref(SECOND_ID, RailEndpoint.A)), first,
                savedRail(SECOND_ID, Vector3d(30.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0))
            )
            InvalidConnection.ACTIVE_WITHOUT_CONNECTION -> listOf(source.copy(connectionsAtB = emptyList()))
            InvalidConnection.REMOTE_ENDPOINTS -> listOf(
                source,
                savedRail(
                    FIRST_ID, Vector3d(100.0, 0.0, 0.0), Vector3d(110.0, 0.0, 0.0),
                    atA = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeA = ref(SOURCE_ID, RailEndpoint.B)
                )
            )
            InvalidConnection.ACTIVE_OTHER_ENDPOINT -> listOf(
                source.copy(activeConnectionAtB = ref(FIRST_ID, RailEndpoint.B)), first
            )
            InvalidConnection.WRONG_DECLARED_ENDPOINT -> listOf(
                source.copy(connectionsAtB = listOf(ref(FIRST_ID, RailEndpoint.B)), activeConnectionAtB = null), first
            )
            InvalidConnection.ASYMMETRIC -> listOf(source, first.copy(connectionsAtA = emptyList(), activeConnectionAtA = null))
            InvalidConnection.SELF_ENDPOINT -> listOf(source.copy(
                connectionsAtB = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeConnectionAtB = null
            ))
            InvalidConnection.DUPLICATE_RAIL_ID -> listOf(source, source, first)
            InvalidConnection.DUPLICATE_NEXT -> listOf(source.copy(connectionsAtB = listOf(ref(FIRST_ID, RailEndpoint.A), ref(FIRST_ID, RailEndpoint.A))), first)
            InvalidConnection.TOO_MANY_NEXT -> branchRails(true).map { rail ->
                if (rail.id == SOURCE_ID) rail.copy(connectionsAtB = listOf(ref(FIRST_ID, RailEndpoint.A), ref(SECOND_ID, RailEndpoint.A), ref(THIRD_ID, RailEndpoint.A))) else rail
            } + savedRail(
                THIRD_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, -5.0),
                atA = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeA = ref(SOURCE_ID, RailEndpoint.B)
            )
        }
    }

    private fun assertFailureContext(invalid: InvalidConnection, failure: IllegalArgumentException) {
        if (invalid == InvalidConnection.DUPLICATE_RAIL_ID) {
            assertTrue(failure.message.orEmpty().contains("duplicate"))
        } else {
            assertTrue(failure.message.orEmpty().contains(SOURCE_ID))
        }
        if (invalid == InvalidConnection.MALFORMED_NEXT || invalid == InvalidConnection.MALFORMED_ACTIVE) {
            assertTrue(failure.message.orEmpty().contains("invalid-uuid"))
            assertTrue(failure.cause is IllegalArgumentException)
        }
    }

    private fun branchRails(selectSecond: Boolean): List<CoreRail.SavedRailV1> = listOf(
        savedRail(
            SOURCE_ID, Vector3d(), Vector3d(10.0, 0.0, 0.0),
            atB = listOf(ref(SECOND_ID, RailEndpoint.A), ref(FIRST_ID, RailEndpoint.A)), activeB = if (selectSecond) ref(FIRST_ID, RailEndpoint.A) else null
        ),
        savedRail(
            FIRST_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0),
            atA = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeA = ref(SOURCE_ID, RailEndpoint.B),
            joinedA = listOf(ref(SOURCE_ID, RailEndpoint.B), ref(SECOND_ID, RailEndpoint.A))
        ),
        savedRail(
            SECOND_ID, Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0),
            atA = listOf(ref(SOURCE_ID, RailEndpoint.B)), activeA = ref(SOURCE_ID, RailEndpoint.B),
            joinedA = listOf(ref(SOURCE_ID, RailEndpoint.B), ref(FIRST_ID, RailEndpoint.A))
        )
    )

    private fun <T> withLoadedWorld(
        rails: List<CoreRail.SavedRailV1>,
        action: (CoreWorld) -> T
    ): T {
        return withWorld(load = { tick, physics ->
            IsolatedSimulationThreadMarker.run { CoreWorld.load(
                CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 7L, rails),
                null,
                CoreWorld.SnapshotDependency(tick, physics)
            ) }
        }, action = action)
    }

    private fun <T> withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, physics)
        },
        action: (CoreWorld) -> T
    ): T {
        return TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            run {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    action(created)
                }
            }
        }
    }

    private fun ref(id: String, endpoint: RailEndpoint) = SavedRailEndpointRef(id, endpoint)

    private fun savedRail(
        id: String,
        from: Vector3d,
        to: Vector3d,
        atA: List<SavedRailEndpointRef> = emptyList(),
        atB: List<SavedRailEndpointRef> = emptyList(),
        activeA: SavedRailEndpointRef? = null,
        activeB: SavedRailEndpointRef? = null,
        joinedA: List<SavedRailEndpointRef> = atA,
        joinedB: List<SavedRailEndpointRef> = atB
    ) = CoreRail.SavedRailV1(
        id, line(from, to).save() as PrimeCurveImpl.SavedPrimeCurveV1,
        atA, atB, activeA, activeB, joinedA, joinedB
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
