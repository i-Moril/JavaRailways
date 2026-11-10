package work.art1st.javarailways.core.physics.rail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.rail.topology.CoreTopologyInputAdapter
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class IncompleteTurnoutJunctionTest {
    @ParameterizedTest
    @CsvSource("A,A", "A,B", "B,A", "B,B")
    fun `same side legs retain authored membership without a traversable route`(firstEnd: RailEndpoint, secondEnd: RailEndpoint) = withWorld { world ->
        val first = world.createRailForTest(leg(firstEnd, Vector3d(20.0, 2.0, 0.0)))
        val second = append(world, leg(secondEnd, Vector3d(20.0, 2.0, 4.0)), secondEnd, port(first, firstEnd))
        assertIncomplete(world, port(first, firstEnd), port(second, secondEnd))
        val arriving = incoming(first, firstEnd)
        assertNull(RailLocationWithFacing(arriving, arriving.curve.totalArcLength - 0.01).forward(0.02))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (world.topologyState.endpointGroupAt(port(first, firstEnd)) as MutableSet<RailEndpointRef>).clear()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `either incomplete member accepts the missing common leg and deleting it restores the incomplete group`(chooseSecond: Boolean) = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 2.0, 0.0)))
        val second = append(world, leg(RailEndpoint.B, Vector3d(20.0, 2.0, 4.0)), RailEndpoint.B, port(first, RailEndpoint.A))
        val selected = if (chooseSecond) port(second, RailEndpoint.B) else port(first, RailEndpoint.A)
        val common = append(world, leg(RailEndpoint.B, Vector3d(-20.0, -2.0, 0.0)), RailEndpoint.B, selected)
        val a = port(first, RailEndpoint.A)
        val b = port(second, RailEndpoint.B)
        val c = port(common, RailEndpoint.B)
        assertEquals(setOf(a, b, c), world.topologyState.endpointGroupAt(c))
        assertEquals(setOf(a, b), world.topologyState.continuationsAt(c))
        assertEquals(setOf(c), world.topologyState.continuationsAt(a))
        assertEquals(setOf(c), world.topologyState.continuationsAt(b))
        assertSame(first.viewAB, common.viewAB.activeRail)
        val revision = world.topologyRevision
        IsolatedSimulationThreadMarker.run { world.removeRail(common).toCompletableFuture().join() }
        assertEquals(revision + 1, world.topologyRevision)
        assertIncomplete(world, a, b)
    }

    @Test
    fun `common leg first produces the same legal turnout when a branch is selected to add the other branch`() = withWorld { world ->
        val common = world.createRailForTest(leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0)))
        val first = append(world, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)), RailEndpoint.A, port(common, RailEndpoint.B))
        val second = append(world, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)), RailEndpoint.A, port(first, RailEndpoint.A))
        val a = port(first, RailEndpoint.A)
        val b = port(second, RailEndpoint.A)
        val c = port(common, RailEndpoint.B)
        assertEquals(setOf(a, b), world.topologyState.continuationsAt(c))
        assertEquals(setOf(c), world.topologyState.continuationsAt(a))
        assertEquals(setOf(c), world.topologyState.continuationsAt(b))
        assertSame(first.viewAB, common.viewAB.activeRail)
        IsolatedSimulationThreadMarker.run { common.viewAB.activeRail = null }
        val thirdStandalone = world.prepareRailCreation(leg(RailEndpoint.A, Vector3d(0.0, 0.0, 20.0)))
        val plan = PreparedRailConnectionPlan.captureExplicit(world, thirdStandalone)
        val update = plan.prepareTopologyUpdate()
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(update) {
            val installed = world.installPreparedRails(thirdStandalone)
            plan.apply(world, installed)
        } }
        assertNull(common.viewAB.activeRail)
    }

    @Test
    fun `unselected colocated endpoint stays outside explicitly joined group`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val unrelated = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, -4.0)))
        val second = append(world, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)), RailEndpoint.A, port(first, RailEndpoint.A))
        assertIncomplete(world, port(first, RailEndpoint.A), port(second, RailEndpoint.A))
        assertEquals(setOf(port(unrelated, RailEndpoint.A)), world.topologyState.endpointGroupAt(port(unrelated, RailEndpoint.A)))
    }

    @Test
    fun `direct connect preserves explicit null and default null on an incomplete group becomes a default route`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        val common = world.createRailForTest(leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0)))
        IsolatedSimulationThreadMarker.run {
            RailView.connect(first.viewBA, second.viewBA)
            assertNull(first.viewBA.activeRail)
            RailView.connect(common.viewAB, first.viewBA)
            assertSame(common.viewBA, first.viewBA.activeRail)
            common.viewAB.activeRail = null
            RailView.connect(common.viewAB, first.viewBA)
            assertNull(common.viewAB.activeRail)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `restored or rolled back standalone endpoints use the same initial default for direct and prepared joins`(load: Boolean, prepared: Boolean) = withWorld { source ->
        val first = source.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val other = source.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        fun verify(world: CoreWorld) {
            val restoredFirst = requireNotNull(world.getRail(first.id))
            val common = if (prepared) {
                append(world, leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0)), RailEndpoint.B, port(restoredFirst, RailEndpoint.A))
            } else {
                world.createRailForTest(leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0))).also { common ->
                    IsolatedSimulationThreadMarker.run { RailView.connect(common.viewAB, restoredFirst.viewBA) }
                }
            }
            assertSame(common.viewBA, restoredFirst.viewBA.activeRail)
            assertSame(restoredFirst.viewAB, common.viewAB.activeRail)
            assertTrue(requireNotNull(world.getRail(other.id)).viewBA.joinedEndpoints.isEmpty())
        }
        if (load) {
            withLoadedWorld(source.allRails.map { (it as CoreRail).save() }, ::verify)
        } else {
            val root = source.topologyState
            assertThrows(IllegalStateException::class.java) {
                IsolatedSimulationThreadMarker.run { source.railTopologyTransaction {
                    RailView.connect(first.viewBA, other.viewBA)
                    first.viewBA.restoreConnections(emptyList(), null, emptySet())
                    other.viewBA.restoreConnections(emptyList(), null, emptySet())
                    error("rolled back")
                } }
            }
            assertSame(root, source.topologyState)
            verify(source)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `loaded explicit null on a usable common leg survives direct and prepared branch additions`(prepared: Boolean) = withWorld { source ->
        val common = source.createRailForTest(leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0)))
        val first = append(source, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)), RailEndpoint.A, port(common, RailEndpoint.B))
        IsolatedSimulationThreadMarker.run { common.viewAB.activeRail = null }
        withLoadedWorld(source.allRails.map { (it as CoreRail).save() }) { loaded ->
            val restoredFirst = requireNotNull(loaded.getRail(first.id))
            val restoredCommon = requireNotNull(loaded.getRail(common.id))
            if (prepared) {
                append(loaded, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)), RailEndpoint.A, port(restoredFirst, RailEndpoint.A))
            } else {
                val second = loaded.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
                IsolatedSimulationThreadMarker.run { RailView.connect(restoredFirst.viewBA, second.viewBA) }
            }
            assertEquals(2, restoredCommon.viewAB.nextRails.size)
            assertNull(restoredCommon.viewAB.activeRail)
            assertSame(restoredCommon.viewBA, restoredFirst.viewBA.activeRail)
        }
    }

    @Test
    fun `incomplete group survives strict JSON load and group only undo redo`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        val before = world.allRails.map { (it as CoreRail).save() }
        val previousRoot = world.topologyState
        IsolatedSimulationThreadMarker.run { RailView.connect(first.viewBA, second.viewBA) }
        assertEquals(previousRoot.revision + 1, world.topologyRevision)
        assertFalse(world.topologyState.preservesRails(previousRoot, listOf(first.id)))
        val after = world.allRails.map { (it as CoreRail).save() }
        val document = CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 7L, after)
        val decoded = WorldSnapshotCodec.decodeInfrastructure(WorldSnapshotCodec.encodeInfrastructure(document))
        withWorld { empty ->
            val restore = empty.prepareRailWorldSnapshotRestore(emptyList(), decoded.rails)
            IsolatedSimulationThreadMarker.run { empty.railTopologyTransaction(restore.topologyUpdate) { restore.apply(empty) } }
            assertIncomplete(empty, port(first, RailEndpoint.A), port(second, RailEndpoint.A))
        }
        restore(world, after, before)
        assertEquals(setOf(port(first, RailEndpoint.A)), world.topologyState.endpointGroupAt(port(first, RailEndpoint.A)))
        restore(world, before, after)
        assertIncomplete(world, port(first, RailEndpoint.A), port(second, RailEndpoint.A))
    }

    @Test
    fun `group only change makes captured explicit plan stale before mutation`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        val batch = world.prepareRailCreation(leg(RailEndpoint.B, Vector3d(-20.0, 0.0, 0.0)))
        val created = RailEndpointRef(batch.creations.single().railId, RailEndpoint.B)
        val plan = PreparedRailConnectionPlan.captureExplicit(world, batch, addedConnections = listOf(port(first, RailEndpoint.A) to created))
        val update = plan.prepareTopologyUpdate()
        IsolatedSimulationThreadMarker.run { RailView.connect(first.viewBA, second.viewBA) }
        val root = world.topologyState
        assertFalse(plan.isCurrent(world))
        assertThrows(IllegalArgumentException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(update) { fail<Unit>("stale edit entered action") } }
        }
        assertSame(root, world.topologyState)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `prepared replacement transfers incomplete endpoint membership`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = append(world, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)), RailEndpoint.A, port(first, RailEndpoint.A))
        val batch = world.prepareRailCreation(leg(RailEndpoint.B, Vector3d(30.0, 0.0, 0.0)), setOf(first.id))
        val replacementPort = RailEndpointRef(batch.creations.single().railId, RailEndpoint.B)
        val plan = PreparedRailConnectionPlan.captureExplicit(world, batch,
            replacementEndpoints = mapOf(port(first, RailEndpoint.A) to replacementPort))
        val update = plan.prepareTopologyUpdate()
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(update) {
            val installed = world.installPreparedRails(batch)
            plan.apply(world, installed)
            world.removeRail(first)
        } }
        assertIncomplete(world, replacementPort, port(second, RailEndpoint.A))
        assertEquals(update.targetRailSnapshot.toSet(), world.allRails.map { (it as CoreRail).save() }.toSet())
    }

    @Test
    fun `group only rollback restores fingerprint and leaves world usable`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        val root = world.topologyState
        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                RailView.connect(first.viewBA, second.viewBA)
                first.viewBA.restoreConnections(emptyList(), null, emptySet())
                second.viewBA.restoreConnections(emptyList(), null, emptySet())
                error("rolled back")
            } }
        }
        assertSame(root, world.topologyState)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `strict reader rejects missing independent membership and illegal same side routes`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val second = append(world, leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)), RailEndpoint.A, port(first, RailEndpoint.A))
        val saved = world.allRails.map { (it as CoreRail).save() }
        val broken = saved.map { rail -> if (rail.id == first.id.toString()) rail.copy(
            connectionsAtA = listOf(SavedRailEndpointRef(second.id.toString(), RailEndpoint.A))) else rail }
        assertThrows(IllegalArgumentException::class.java) { world.prepareRailWorldSnapshotRestore(saved, broken) }
        val document = CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 1L, saved)
        val encoded = Json.parseToJsonElement(WorldSnapshotCodec.encodeInfrastructure(document)).jsonObject
        val missingMembership = JsonObject(encoded + ("rails" to JsonArray(encoded.getValue("rails").jsonArray.map { value ->
            JsonObject(value.jsonObject - "joinedEndpointsAtA")
        })))
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure(missingMembership.toString())
        }
    }

    @Test
    fun `live topology capture rejects sparse membership instead of publishing a group its save cannot restore`() = withWorld { world ->
        val first = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 0.0)))
        val middle = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 4.0)))
        val last = world.createRailForTest(leg(RailEndpoint.A, Vector3d(20.0, 0.0, 8.0)))
        first.viewBA.mutableJoinedEndpoints.add(port(middle, RailEndpoint.A))
        middle.viewBA.mutableJoinedEndpoints.addAll(listOf(port(first, RailEndpoint.A), port(last, RailEndpoint.A)))
        last.viewBA.mutableJoinedEndpoints.add(port(middle, RailEndpoint.A))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CoreTopologyInputAdapter.capture(world.topologyRevision + 1, listOf(first, middle, last))
        }
        assertTrue(failure.message.orEmpty().contains("complete and symmetric"))
        listOf(first, middle, last).forEach { it.viewBA.mutableJoinedEndpoints.clear() }
    }

    private fun assertIncomplete(world: CoreWorld, first: RailEndpointRef, second: RailEndpointRef) {
        val group = setOf(first, second)
        assertEquals(group, world.topologyState.endpointGroupAt(first))
        assertEquals(group, world.topologyState.endpointGroupAt(second))
        assertTrue(world.topologyState.continuationsAt(first).isEmpty())
        assertTrue(world.topologyState.continuationsAt(second).isEmpty())
        assertTrue(world.topologyState.structuralNodeAt(first) is TopologyNode.Turnout)
        listOf(first, second).forEach { port ->
            val view = incoming(requireNotNull(world.getRail(port.railId)), port.endpoint)
            assertTrue(view.nextRails.isEmpty())
            assertNull(view.activeRail)
            assertEquals(group - port, view.joinedEndpoints)
        }
    }

    private fun append(world: CoreWorld, curve: PrimeCurve, endpoint: RailEndpoint, selected: RailEndpointRef): CoreRail {
        val batch = world.prepareRailCreation(curve)
        val created = RailEndpointRef(batch.creations.single().railId, endpoint)
        val plan = PreparedRailConnectionPlan.captureExplicit(world, batch, addedConnections = listOf(selected to created))
        val update = plan.prepareTopologyUpdate()
        lateinit var rail: CoreRail
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(update) {
            rail = world.installPreparedRails(batch).single()
            plan.apply(world, listOf(rail))
        } }
        assertEquals(update.targetRailSnapshot.toSet(), world.allRails.map { (it as CoreRail).save() }.toSet())
        return rail
    }

    private fun restore(world: CoreWorld, current: List<CoreRail.SavedRailV1>, target: List<CoreRail.SavedRailV1>) {
        val plan = world.prepareRailWorldSnapshotRestore(current, target)
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) } }
    }

    private fun port(rail: CoreRail, endpoint: RailEndpoint) = RailEndpointRef(rail.id, endpoint)
    private fun incoming(rail: CoreRail, endpoint: RailEndpoint) = if (endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
    private fun leg(endpoint: RailEndpoint, other: Vector3d): PrimeCurve {
        val from = if (endpoint == RailEndpoint.A) Vector3d() else other
        val to = if (endpoint == RailEndpoint.A) other else Vector3d()
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0).sub(Vector3d(tangent).mul(tangent.y)).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(to, Vector3d(tangent), Vector3d(normal), from.distance(to))
        ))
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        TestWorldFixture.create().use { block(it.world) }
    }

    private fun withLoadedWorld(saved: List<CoreRail.SavedRailV1>, block: (CoreWorld) -> Unit) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                val loaded = IsolatedSimulationThreadMarker.run {
                    CoreWorld.load(CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 1L, saved),
                        null, CoreWorld.SnapshotDependency(tick, physics))
                }
                block(loaded)
            }
        }
    }
}
