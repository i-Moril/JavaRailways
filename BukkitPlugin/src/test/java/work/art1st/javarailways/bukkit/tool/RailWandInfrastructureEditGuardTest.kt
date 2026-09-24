package work.art1st.javarailways.bukkit.tool

import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureLeaseService
import work.art1st.javarailways.bukkit.api.internal.DefaultInfrastructureLeaseService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.UUID

class RailInfrastructureEditGuardTest {
    @Test
    fun `conflicting live claim prevents mutation callback`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(line(0.0, 10.0))
        val worldId = UUID.randomUUID()
        val service = DefaultInfrastructureLeaseService()
        val plugin = plugin("JavaRailways")
        registerWorld(service, worldId, world)
        val claim = service.acquireLiveClaim(
            plugin,
            worldId,
            InfrastructureClaimRequest(
                world.topologyRevision,
                InfrastructureOwner("test:route/main", 1L),
                InfrastructureFootprint(listOf(RailIntervalResource(rail.id, 0.0, rail.curve.totalArcLength)))
            )
        )
        assertTrue(claim.successful)
        var mutated = false

        val result = RailInfrastructureEditGuard(plugin, service).acquire(
            worldId,
            world,
            setOf(rail.id),
            "test:railwand/edit"
        )
        if (result is RailInfrastructureEditGuard.Result.Granted) {
            result.permit.use { mutated = true }
        }

        assertFalse(mutated)
        assertEquals(
            InfrastructureMutationStatus.RESOURCE_CONFLICT,
            (result as RailInfrastructureEditGuard.Result.Denied).reason.status
        )
    }

    @Test
    fun `granted permit is held during callback and released afterward`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(line(0.0, 10.0))
        val worldId = UUID.randomUUID()
        val service = DefaultInfrastructureLeaseService()
        val plugin = plugin("JavaRailways")
        registerWorld(service, worldId, world)
        val competingRequest = InfrastructureEditRequest(
            world.topologyRevision,
            InfrastructureOwner("test:editor/competing", 1L),
            InfrastructureFootprint(listOf(RailIntervalResource(rail.id, 0.0, rail.curve.totalArcLength)))
        )
        var statusDuringCallback: InfrastructureMutationStatus? = null

        val result = RailInfrastructureEditGuard(plugin, service).acquire(
            worldId,
            world,
            setOf(rail.id),
            "test:railwand/edit"
        )
        val granted = result as RailInfrastructureEditGuard.Result.Granted
        granted.permit.use {
            statusDuringCallback = service.acquireEditPermit(plugin, worldId, competingRequest).result().status
        }

        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, statusDuringCallback)
        val after = service.acquireEditPermit(plugin, worldId, competingRequest)
        assertTrue(after.result().successful)
        after.permit().orElseThrow().close()
    }

    @Test
    fun `new-only empty-world edit skips permit without constructing an empty footprint`() {
        val world = createTestWorld()
        val plugin = plugin("JavaRailways")
        var serviceCalled = false
        val service = Proxy.newProxyInstance(
            InfrastructureLeaseService::class.java.classLoader,
            arrayOf(InfrastructureLeaseService::class.java)
        ) { _, method, _ ->
            if (method.name == "acquireEditPermit") serviceCalled = true
            error("InfrastructureLeaseService must not be called for an empty existing-rail set")
        } as InfrastructureLeaseService
        var mutated = false

        val result = RailInfrastructureEditGuard(plugin, service).acquire(
            UUID.randomUUID(),
            world,
            emptySet(),
            "test:railwand/new-only"
        ) as RailInfrastructureEditGuard.Result.Granted
        result.permit.use { mutated = true }

        assertTrue(mutated)
        assertFalse(serviceCalled)
    }

    @Test
    fun `rail removed after preparation is reported as stale without calling the service`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(line(0.0, 10.0))
        rail.destroy()
        world.railTopologyTransaction { world.removeRail(rail) }
        var serviceCalled = false
        val guard = RailInfrastructureEditGuard { _, _ ->
            serviceCalled = true
            error("stale edit must not reach the lease service")
        }

        val result = guard.acquire(
            UUID.randomUUID(),
            world,
            setOf(rail.id),
            "test:railwand/stale"
        )

        assertFalse(serviceCalled)
        assertEquals(
            InfrastructureMutationStatus.STALE_TOPOLOGY,
            (result as RailInfrastructureEditGuard.Result.Denied).reason.status
        )
    }

    @Test
    fun `topology change after permit acquisition closes the permit and denies the edit`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(line(0.0, 10.0))
        val worldId = UUID.randomUUID()
        val service = DefaultInfrastructureLeaseService()
        val plugin = plugin("JavaRailways")
        registerWorld(service, worldId, world)
        val guard = RailInfrastructureEditGuard { requestedWorldId, request ->
            service.acquireEditPermit(plugin, requestedWorldId, request).also {
                world.createRailForTest(line(20.0, 30.0))
            }
        }

        val result = guard.acquire(worldId, world, setOf(rail.id), "test:railwand/stale")

        assertEquals(
            InfrastructureMutationStatus.STALE_TOPOLOGY,
            (result as RailInfrastructureEditGuard.Result.Denied).reason.status
        )
        val retry = service.acquireEditPermit(
            plugin,
            worldId,
            InfrastructureEditRequest(
                world.topologyRevision,
                InfrastructureOwner("test:railwand/retry", 2L),
                InfrastructureFootprint(listOf(
                    RailIntervalResource(rail.id, 0.0, rail.curve.totalArcLength)
                ))
            )
        )
        assertTrue(retry.result().successful)
        retry.permit().orElseThrow().close()
    }

    private fun registerWorld(
        service: DefaultInfrastructureLeaseService,
        worldId: UUID,
        world: work.art1st.javarailways.core.physics.CoreWorld
    ) {
        service.registerWorld(worldId, { world.topologyRevision }) { resource: InfrastructureResource ->
            resource is RailIntervalResource && world.getRail(resource.railId)?.let { rail ->
                resource.endAbMeters <= rail.curve.totalArcLength
            } == true
        }
    }

    private fun line(fromX: Double, toX: Double): PrimeCurve {
        val direction = Vector3d(1.0, 0.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), direction, Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), direction, Vector3d(0.0, 1.0, 0.0), toX - fromX)
        ))
    }

    private fun plugin(name: String): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName", "toString" -> name
                "isEnabled" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
