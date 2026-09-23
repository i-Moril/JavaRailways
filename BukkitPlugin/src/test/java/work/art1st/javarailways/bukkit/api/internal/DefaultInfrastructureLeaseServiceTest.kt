package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.util.plugin.Plugin as CorePlugin
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.lang.reflect.Proxy
import java.util.UUID

class DefaultInfrastructureLeaseServiceTest {
    @Test
    fun `normal unregister rejects active route resources and accepts an empty route state`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val requester = plugin("signal")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        var observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachRouteAdmission(worldId, TestWorld(topology), { observation }) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/main",
            InfrastructureOwner("test.signal:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )

        val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            admission.request(intent)).grant
        assertEquals(1, service.liveClaims(worldId).size)
        val active = assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(requester)
        }
        assertTrue(active.message.orEmpty().contains("route=${intent.owner.ownerId}"))
        assertEquals(1, service.liveClaims(worldId).size)

        observation = routeObservation(worldId, topology.revision, 2L, railId, 21.0, 22.0)
        service.consumeObservation(worldId, observation)
        assertTrue(service.liveClaims(worldId).isEmpty())
        service.unregisterOwner(requester)
        assertFalse(admission.endGrant(grant.grantId))

        var pluginEnabled = true
        val laterDisabledPlugin = plugin("later-disabled") { pluginEnabled }
        val disabledBinding = service.routeAdmission(laterDisabledPlugin, worldId)
        pluginEnabled = false
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                disabledBinding.request(intent)).failure.code
        )

        service.removeWorld(worldId)
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
        service.registerWorld(worldId, { topology.revision }) { false }
        service.attachRouteAdmission(worldId, TestWorld(topology), { observation }) { _, _ -> }
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
    }

    @Test
    fun `forced plugin disable retains active route claim and stalls its world`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var pluginEnabled = true
        val requester = plugin("signal") { pluginEnabled }
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachRouteAdmission(worldId, TestWorld(topology), { observation }) { reason, _ ->
            stalls += reason
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/main",
            InfrastructureOwner("test.signal:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.request(intent))

        pluginEnabled = false
        service.forcedDisableOwner(requester)

        assertEquals(1, service.liveClaims(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("Bukkit plugin signal"))
        assertTrue(stalls.single().contains(intent.owner.ownerId))
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                admission.request(intent)).failure.code
        )
    }

    @Test
    fun `forced contributor disable stalls even when it contributed no resource`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        val requester = plugin("ato")
        var contributorEnabled = true
        val contributor = plugin("signal-policy") { contributorEnabled }
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        service.registerRouteResourceContributor(contributor) { _, _ -> emptyList() }
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId
        }
        service.attachRouteAdmission(worldId, TestWorld(topology), { observation }) { reason, _ ->
            stalls += reason
        }
        val intent = RouteResourceIntent(
            "test.ato:request/main",
            InfrastructureOwner("test.ato:route/main", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.ato:route/main",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            service.routeAdmission(requester, worldId).request(intent))

        contributorEnabled = false
        service.forcedDisableOwner(contributor)

        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("route-resource contributor"))
        assertEquals(1, service.liveClaims(worldId).size)
    }

    @Test
    fun `resource provider cannot unregister while another plugin holds its route resource`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        var providerEnabled = true
        val providerOwner = plugin("block-provider") { providerEnabled }
        val requester = plugin("ato")
        val contributor = plugin("signal-policy")
        val block = StableInfrastructureResource("example.block:section", "main")
        val topology = buildTestTopology(7L, listOf(TestTopologyRail(railId, 100.0)), emptyMap())
        val observation = routeObservation(worldId, topology.revision, 1L, railId, 0.0, 1.0)
        val stalls = mutableListOf<String>()
        val registration = service.registerResourceProvider(providerOwner, block.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == block.resourceId

            override fun validateRouteContribution(
                actualContributor: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertSame(contributor, actualContributor)
                return null
            }
        })
        val contributorRegistration = service.registerRouteResourceContributor(contributor) { candidateWorld, _ ->
            if (candidateWorld == worldId) {
                listOf(ContributedRouteResource(block, RouteBoundary(20.0)))
            } else {
                emptyList()
            }
        }
        service.registerWorld(worldId, { topology.revision }) { resource ->
            resource is RailIntervalResource && resource.railId == railId ||
                resource is StableInfrastructureResource && service.stableResourceExists(worldId, resource)
        }
        service.attachRouteAdmission(worldId, TestWorld(topology), { observation }) { reason, _ ->
            stalls += reason
        }
        val admission = service.routeAdmission(requester, worldId)
        val intent = RouteResourceIntent(
            "test.signal:request/provider",
            InfrastructureOwner("test.signal:route/provider", 1L),
            "train",
            observation.stamp,
            RouteSnapshot(
                "test.signal:route/provider",
                1L,
                topology.revision,
                listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
            ),
            RouteBoundary(20.0)
        )
        assertInstanceOf(RouteAdmissionResult.Granted::class.java, admission.request(intent))

        val contributorActive = assertThrows(IllegalStateException::class.java) {
            contributorRegistration.close()
        }
        assertTrue(contributorActive.message.orEmpty().contains("route states remain active"))
        assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(contributor)
        }
        val registrationActive = assertThrows(IllegalStateException::class.java) {
            registration.close()
        }
        assertTrue(registrationActive.message.orEmpty().contains(block.typeId))
        val active = assertThrows(IllegalStateException::class.java) {
            service.unregisterOwner(providerOwner)
        }
        assertTrue(active.message.orEmpty().contains("types=${block.typeId}"))
        assertEquals(1, service.liveClaims(worldId).size)

        providerEnabled = false
        service.forcedDisableOwner(providerOwner)
        assertEquals(1, service.liveClaims(worldId).size)
        assertEquals(1, stalls.size)
        assertTrue(stalls.single().contains("block-provider"))
        assertTrue(stalls.single().contains(block.typeId))
    }

    @Test
    fun `world route admission is attached once consumes the published observation and closes with world`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val topology = buildTestTopology(7L, emptyList(), emptyMap())
        val world = TestWorld(topology)
        val observation = RailwayObservation.fromCore(
            worldId.toString(),
            RailwayWorldFrameSnapshot.create(1L, topology.revision, emptyList())
        )
        service.registerWorld(worldId, { topology.revision }) { false }
        service.attachRouteAdmission(worldId, world, { observation }) { reason, failure ->
            throw IllegalStateException("unexpected stall: $reason", failure)
        }

        service.consumeObservation(worldId, observation)
        assertThrows(IllegalStateException::class.java) {
            service.attachRouteAdmission(worldId, world, { observation }) { _, _ -> }
        }

        service.removeWorld(worldId)
        assertThrows(IllegalStateException::class.java) {
            service.consumeObservation(worldId, observation)
        }
    }

    @Test
    fun `route contributions require resource owner authorization beyond lease existence`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val allowed = plugin("allowed-signal")
        val denied = plugin("other-signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        val preparation = ResourcePreparationDescriptor("example.signal:prepare", emptyMap())
        registerWorld(service, worldId)
        service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) =
                candidateWorld == worldId && resourceId == "home"

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                assertTrue(BukkitThreadGuardBridge.isMainThread())
                assertEquals(worldId, candidateWorld)
                assertEquals("home", resourceId)
                return when {
                    requester !== allowed -> RouteAdmissionFailureCode.INVALID_CONTRIBUTION
                    descriptor != null && descriptor.kindId != preparation.kindId ->
                        RouteAdmissionFailureCode.INVALID_PREPARATION
                    else -> null
                }
            }
        })

        assertEquals(null, service.validateRouteContribution(allowed, worldId, contribution))
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteContribution(allowed, worldId, contribution.copy(preparation = preparation))?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(denied, worldId, contribution)?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(
                allowed, worldId,
                ContributedRouteResource(resource.copy(resourceId = "missing"), RouteBoundary(10.0))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            service.validateRouteContribution(
                allowed, worldId,
                contribution.copy(preparation = ResourcePreparationDescriptor("example.signal:wrong", emptyMap()))
            )?.code
        )
        assertEquals(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            service.validateRouteContribution(allowed, UUID.randomUUID(), contribution)?.code
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
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val registration = service.registerResourceProvider(owner, resource.typeId, rejectingRouteProvider { _, _ -> true })

        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        registration.close()
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        assertTrue(service.liveClaims(worldId).isEmpty())
    }

    @Test
    fun `provider failure or removal during validation cannot authorize an contribution`() {
        val service = DefaultInfrastructureLeaseService()
        val worldId = UUID.randomUUID()
        val owner = plugin("resource-owner")
        val requester = plugin("signal")
        val resource = StableInfrastructureResource("example.signal:block", "home")
        val contribution = ContributedRouteResource(resource, RouteBoundary(10.0))
        registerWorld(service, worldId)
        val failing = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? = error("provider failed")
        })
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
        failing.close()

        lateinit var registration: InfrastructureResourceProviderRegistration
        registration = service.registerResourceProvider(owner, resource.typeId, object : InfrastructureResourceProvider {
            override fun exists(candidateWorld: UUID, resourceId: String) = true

            override fun validateRouteContribution(
                requester: Plugin,
                candidateWorld: UUID,
                resourceId: String,
                descriptor: ResourcePreparationDescriptor?
            ): RouteAdmissionFailureCode? {
                registration.close()
                return null
            }
        })
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            service.validateRouteContribution(requester, worldId, contribution)?.code)
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

        override fun validateRouteContribution(
            requester: Plugin,
            worldId: UUID,
            resourceId: String,
            descriptor: ResourcePreparationDescriptor?
        ) = RouteAdmissionFailureCode.INVALID_CONTRIBUTION
    }

    private fun lease(ownerId: String, typeId: String, resourceId: String) = InfrastructureLeaseRequest(
        7L,
        InfrastructureOwner(ownerId, 1L),
        footprint(typeId, resourceId)
    )

    private fun footprint(typeId: String, resourceId: String) =
        InfrastructureFootprint(listOf(StableInfrastructureResource(typeId, resourceId)))

    private fun routeObservation(
        worldId: UUID,
        topologyRevision: Long,
        epoch: Long,
        railId: UUID,
        tail: Double,
        head: Double
    ): RailwayObservation {
        val train = TrainFrameSnapshot.create(
            trainId = "train",
            trainName = "train",
            revision = 1L,
            physicsEpoch = epoch,
            speedMetersPerSecond = 0.0,
            derailed = false,
            reversed = false,
            cars = emptyList(),
            head = RailPositionSnapshot(railId, ViewDirection.AB, head),
            tail = RailPositionSnapshot(railId, ViewDirection.AB, tail),
            sweptSpans = listOf(SweptRailSpanSnapshot(railId, tail, head))
        )
        return RailwayObservation.fromCore(
            worldId.toString(),
            RailwayWorldFrameSnapshot.create(epoch, topologyRevision, listOf(train))
        )
    }

    private fun plugin(name: String, enabled: () -> Boolean = { true }): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName", "toString" -> name
                "isEnabled" -> enabled()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    @OptIn(ExtensionData::class)
    private class TestWorld(override val topologyState: TopologyState) : World {
        override val tickManager: TickManager get() = error("tick manager is not used by this test")
        override val allTrains: Collection<Train> = emptyList()

        override fun getTrain(name: String): Train? = null
        override fun removeTrain(train: Train) = Unit
        override fun getRail(id: UUID): Rail? = null
        override fun removeRail(rail: Rail) = Unit
        override fun <T : Any> getExtensionDataOrNull(plugin: CorePlugin): T? = null
        override fun <T : Any> requireExtensionData(plugin: CorePlugin): T = error("no extensions")
        override fun <T : Any> registerExtensionData(plugin: CorePlugin, data: T) = Unit
        override fun forEachExtensionData(action: (Any) -> Unit) = Unit
        override fun <T> forEachExtensionData(action: (Any) -> T): List<T> = emptyList()
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
