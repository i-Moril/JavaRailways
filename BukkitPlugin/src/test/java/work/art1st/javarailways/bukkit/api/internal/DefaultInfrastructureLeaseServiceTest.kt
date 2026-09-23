package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.AdditionalResourceRequirement
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import java.lang.reflect.Proxy
import java.util.UUID

class DefaultInfrastructureLeaseServiceTest {
    @Test
    fun `route additions require resource owner authorization beyond lease existence`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val allowed = plugin("allowed-signal")
        val denied = plugin("other-signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val addition = AdditionalResourceRequirement(resource, RouteBoundary(10.0))
        val preparation = ResourcePreparationDescriptor("example.signal:prepare", emptyMap())
        registerWorld(service, worldId)
        service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == "home"

            override fun validateRouteAddition(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertTrue(BukkitThreadGuardBridge.isMainThread())
                assertEquals(worldId, candidateWorld)
                assertEquals("home", resourceId)
                return when {
                    requester !== allowed -> RouteAdmissionFailureCode.INVALID_ADDITION
                    descriptor != null && descriptor.kindId != preparation.kindId ->
                        RouteAdmissionFailureCode.INVALID_PREPARATION
                    else -> null
                }
            }
        })

        assertEquals(null, service.validateRouteAddition(allowed, worldId, addition))
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteAddition(allowed, worldId, addition.copy(preparation = preparation))?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(denied, worldId, addition)?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(
                allowed, worldId,
                AdditionalResourceRequirement(resource.copy(resourceId = "missing"), RouteBoundary(10.0))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteAddition(
                allowed, worldId,
                addition.copy(preparation = ResourcePreparationDescriptor("example.signal:wrong", emptyMap()))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            service.validateRouteAddition(allowed, UUID.randomUUID(), addition)?.code
        )
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `explicit route denial and unregister prevent authorization`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val requester = plugin("signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val addition = AdditionalResourceRequirement(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val registration = service.registerResourceProvider(owner, resource.typeId, rejectingRouteProvider { _, _ -> true })

        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(requester, worldId, addition)?.code)
        registration.close()
        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(requester, worldId, addition)?.code)
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `provider failure or removal during validation cannot authorize an addition`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val requester = plugin("signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val addition = AdditionalResourceRequirement(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val failing = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteAddition(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? = error("provider failed")
        })
        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(requester, worldId, addition)?.code)
        failing.close()

        lateinit var registration: InfrastructureResourceProviderRegistration
        registration = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteAddition(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                registration.close()
                return null
            }
        })
        assertEquals(RouteAdmissionFailureCode.INVALID_ADDITION,
            service.validateRouteAddition(requester, worldId, addition)?.code)
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `authoritative provider accepts existing add-on resource and rejects missing or unknown refs`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        var providerRanInSimulationContext = false
        val provider = service.registerResourceProvider(plugin, "example.signal:signal", rejectingRouteProvider { candidateWorld, id ->
            providerRanInSimulationContext = BukkitThreadGuardBridge.isMainThread()
            candidateWorld == worldId && id == "home"
        })
        registerWorld(service, worldId)

        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/main", "example.signal:signal", "home")).status
        )
        assertTrue(providerRanInSimulationContext)
        assertEquals(
            InfrastructureMutationStatus.INVALID_RESOURCE,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/missing", "example.signal:signal", "missing")).status
        )
        assertEquals(
            InfrastructureMutationStatus.INVALID_RESOURCE,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/unknown", "other.plugin:signal", "home")).status
        )

        provider.close()
        provider.close()
        assertEquals(
            InfrastructureMutationStatus.INVALID_RESOURCE,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/after-close", "example.signal:signal", "home")).status
        )
    }

    @Test
    fun `plugin cleanup removes providers leases and held edit permits by identity`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val first = plugin("same-name")
        val second = plugin("same-name")
        service.registerResourceProvider(first, "example.signal:signal", rejectingRouteProvider { _, id -> id == "home" })
        registerWorld(service, worldId)
        service.acquireLease(first, worldId, lease("example.signal:plan/main", "example.signal:signal", "home"))
        val edit = service.acquireEditPermit(
            first,
            worldId,
            InfrastructureEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                footprint("example.signal:other", "edit-target")
            )
        )
        // Register a second provider so the edit target has an authoritative identity.
        assertFalse(edit.result().successful)
        service.registerResourceProvider(first, "example.signal:other", rejectingRouteProvider { _, id -> id == "edit-target" })
        val heldEdit = service.acquireEditPermit(
            first,
            worldId,
            InfrastructureEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                footprint("example.signal:other", "edit-target")
            )
        )
        assertTrue(heldEdit.result().successful)

        service.unregisterOwner(second)
        assertEquals(1, service.leases(worldId).size)
        service.unregisterOwner(first)
        assertTrue(service.leases(worldId).isEmpty())
        assertEquals(
            InfrastructureMutationStatus.INVALID_RESOURCE,
            service.acquireLease(second, worldId, lease("example.signal:plan/new", "example.signal:signal", "home")).status
        )
        heldEdit.permit().orElseThrow().close()
    }

    @Test
    fun `world removal and close invalidate service state and permits are idempotent`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        service.registerResourceProvider(plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> true })
        registerWorld(service, worldId)
        val decision = service.acquireEditPermit(
            plugin,
            worldId,
            InfrastructureEditRequest(
                7L,
                InfrastructureOwner("example.signal:editor/main", 1L),
                footprint("example.signal:signal", "home")
            )
        )
        val permit = decision.permit().orElseThrow()

        service.removeWorld(worldId)
        permit.close()
        permit.close()
        assertEquals(
            InfrastructureMutationStatus.WORLD_UNAVAILABLE,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/main", "example.signal:signal", "home")).status
        )

        service.close()
        service.close()
        assertThrows(IllegalStateException::class.java) {
            service.registerResourceProvider(plugin, "example.signal:late", rejectingRouteProvider { _, _ -> true })
        }
    }

    @Test
    fun `topology revision is read from world at mutation time`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        var currentRevision = 7L
        service.registerResourceProvider(plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> true })
        service.registerWorld(worldId, { currentRevision }) { resource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }

        currentRevision = 8L
        assertEquals(
            InfrastructureMutationStatus.STALE_TOPOLOGY,
            service.acquireLease(plugin, worldId, lease("example.signal:plan/main", "example.signal:signal", "home")).status
        )
        assertTrue(service.leases(worldId).isEmpty())
    }

    @Test
    fun `resource provider failure is typed and cannot partially acquire`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val plugin = plugin("signal")
        service.registerResourceProvider(plugin, "example.signal:signal", rejectingRouteProvider { _, _ -> error("provider offline") })
        registerWorld(service, worldId)

        val result = service.acquireLease(
            plugin,
            worldId,
            lease("example.signal:plan/main", "example.signal:signal", "home")
        )

        assertEquals(InfrastructureMutationStatus.RESOURCE_PROVIDER_FAILED, result.status)
        assertTrue(result.detail.contains("provider offline"))
        assertTrue(service.leases(worldId).isEmpty())
    }

    private fun registerWorld(service: DefaultInfrastructureLeaseService, worldId: UUID) {
        service.registerWorld(worldId, { 7L }) { resource: InfrastructureResource ->
            resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
    }

    private fun rejectingRouteProvider(exists: (UUID, String) -> Boolean) = object : InfrastructureResourceProvider {
        override fun exists(worldId: UUID, resourceId: String) = exists.invoke(worldId, resourceId)

        override fun validateRouteAddition(
            requester: Plugin,
            worldId: UUID,
            resourceId: String,
            descriptor: ResourcePreparationDescriptor?
        ) = RouteAdmissionFailureCode.INVALID_ADDITION
    }

    private fun lease(ownerId: String, typeId: String, resourceId: String) = InfrastructureLeaseRequest(
        7L,
        InfrastructureOwner(ownerId, 1L),
        footprint(typeId, resourceId)
    )

    private fun footprint(typeId: String, resourceId: String) =
        InfrastructureFootprint(listOf(StableInfrastructureResource(typeId, resourceId)))

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
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
