package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributor
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.routing.RoutePlanningRestrictionRuntime
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultSignalEpochSourceServiceTest {
    private val railId = UUID.nameUUIDFromBytes("signal-service-rail".toByteArray())

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    @work.art1st.javarailways.core.testing.IsolateGlobalHalt
    fun `halt rejects definition commit callbacks while world cleanup remains available`(haltDuringPreparation: Boolean) {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        service.registerWorld(worldId, runtime(), inspectionRuntime(), { emptyList() }) { _, _ -> }
        service.replaceSources(owner, worldId, listOf(source("test:retained", 0.0) {}))
        var committed = false
        val replacement = source("test:replacement", 0.0) {}
        val candidates = if (haltDuringPreparation) object : AbstractCollection<SignalEpochSource>() {
            override val size = 1
            override fun iterator(): Iterator<SignalEpochSource> {
                work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt("test signal preparation halt")
                return listOf(replacement).iterator()
            }
        } else listOf(replacement).also {
            work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt("test signal commit halt")
        }
        try {
            assertThrows(work.art1st.javarailways.core.physics.tick.PhysicsStalledException::class.java) {
                service.replaceSourcesAtomically(owner, worldId, candidates) {
                    committed = true
                }
            }
            assertFalse(committed)
            assertThrows(work.art1st.javarailways.core.physics.tick.PhysicsStalledException::class.java) {
                service.replaceInspectionSources(owner, worldId, listOf(inspectionSource()))
            }
            assertThrows(work.art1st.javarailways.core.physics.tick.PhysicsStalledException::class.java) {
                service.replaceRoutePlanningSources(owner, worldId, listOf(routePlanningSource("test:planning")))
            }
            assertThrows(work.art1st.javarailways.core.physics.tick.PhysicsStalledException::class.java) {
                service.registerWorld(UUID.randomUUID(), runtime(), inspectionRuntime(), { emptyList() }) { _, _ -> }
            }
            service.removeWorld(worldId)
        } finally {
            service.close()
        }
    }

    @Test
    fun `atomic callback rejects another world reentry before waiting for its busy owner`() {
        val service = DefaultSignalEpochSourceService()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val owner = plugin("signal")
        val world = work.art1st.javarailways.core.physics.train.createTestWorld(
            work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false))
        val executor = Executors.newSingleThreadExecutor()
        try {
            service.registerWorld(firstId, runtime(), inspectionRuntime(), { emptyList() }) { _, _ -> }
            service.registerWorld(secondId, runtime(), inspectionRuntime(), { emptyList() }, physicalWorld = world) { _, _ -> }
            world.executionOwner.run {
                val rejected = executor.submit<Boolean> {
                    try {
                        service.replaceSourcesAtomically(owner, firstId, listOf(source("test:first", 0.0) {})) {
                            service.replaceSources(owner, secondId, listOf(source("test:second", 20.0) {}))
                        }
                        false
                    } catch (failure: IllegalStateException) {
                        failure.message.orEmpty().contains("cannot be re-entered")
                    }
                }
                assertTrue(rejected.get(5, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `owner replacement preserves other owners and invalid union changes nothing`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val calls = mutableListOf<String>()
        val runtime = runtime()
        service.registerWorld(worldId, runtime, inspectionRuntime(), { emptyList() }) { reason, failure ->
            throw IllegalStateException(reason, failure)
        }
        service.replaceSources(firstOwner, worldId, listOf(source("test:first", 0.0) {
            calls += "first-old"
        }))
        service.replaceSources(secondOwner, worldId, listOf(source("test:second", 20.0) {
            calls += "second"
        }))
        runtime.process(frame(1L))
        assertEquals(listOf("first-old", "second"), calls)
        calls.clear()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSources(secondOwner, worldId, listOf(source("test:first", 40.0) {
                calls += "invalid"
            }))
        }
        service.replaceSources(firstOwner, worldId, listOf(source("test:first", 60.0) {
            calls += "first-new"
        }))
        runtime.process(frame(2L))

        assertEquals(listOf("first-new", "second"), calls)
    }

    @Test
    fun `atomic replacement validates before callback and callback failure preserves owner and runtime state`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val calls = mutableListOf<String>()
        val runtime = runtime()
        val old = source("test:old", 0.0) { calls += "old" }
        val replacement = source("test:new", 20.0) { calls += "replacement" }
        service.registerWorld(worldId, runtime, inspectionRuntime(), { emptyList() }) { _, _ -> }
        service.replaceSources(firstOwner, worldId, listOf(old))
        var commitCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSourcesAtomically(firstOwner, worldId, listOf(replacement, replacement)) {
                commitCalls++
            }
        }
        assertEquals(0, commitCalls)
        assertThrows(IllegalStateException::class.java) {
            service.replaceSourcesAtomically(firstOwner, worldId, listOf(replacement)) {
                commitCalls++
                throw IllegalStateException("definition commit failed")
            }
        }
        assertEquals(1, commitCalls)

        // The failed candidate did not reserve test:new in the service owner records.
        service.replaceSources(secondOwner, worldId, listOf(replacement))
        runtime.process(frame(1L))
        assertEquals(listOf("replacement", "old"), calls)
    }

    @Test
    fun `atomic replacement rejects service reentry and restores the guard after failure`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        val otherOwner = plugin("other")
        val calls = mutableListOf<String>()
        val runtime = runtime()
        val old = source("test:old", 0.0) { calls += "old" }
        val replacement = source("test:new", 20.0) { calls += "new" }
        service.registerWorld(worldId, runtime, inspectionRuntime(), { emptyList() }) { _, _ -> }
        service.replaceSources(owner, worldId, listOf(old))

        val failure = assertThrows(IllegalStateException::class.java) {
            service.replaceSourcesAtomically(owner, worldId, listOf(replacement)) {
                service.replaceRoutePlanningSources(
                    owner,
                    worldId,
                    listOf(routePlanningSource("test:planning"))
                )
            }
        }

        assertTrue(failure.message.orEmpty().contains("cannot be re-entered"))
        runtime.process(frame(1L))
        assertEquals(listOf("old"), calls)
        service.replaceRoutePlanningSources(
            otherOwner,
            worldId,
            listOf(routePlanningSource("test:planning"))
        )
        service.replaceSources(owner, worldId, listOf(replacement))
        runtime.process(frame(2L))
        assertEquals(listOf("old", "new"), calls)
    }

    @Test
    fun `atomic replacement callback does not block an epoch and publishes for the next epoch`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        val runtime = runtime()
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val calls = mutableListOf<String>()
        val otherWorldId = UUID.randomUUID()
        val otherRuntime = runtime()
        service.registerWorld(otherWorldId, otherRuntime, inspectionRuntime(), { emptyList() }) { _, _ -> }
        service.registerWorld(worldId, runtime, inspectionRuntime(), { emptyList() }) { _, _ -> }
        service.replaceSources(owner, worldId, listOf(source("test:old", 0.0) { calls += "old" }))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val replacement = executor.submit {
                service.replaceSourcesAtomically(
                    owner,
                    worldId,
                    listOf(source("test:new", 20.0) { calls += "new" })
                ) {
                    commitEntered.countDown()
                    check(releaseCommit.await(5, TimeUnit.SECONDS)) {
                        "test did not release the atomic commit callback"
                    }
                }
            }
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
            val physics = executor.submit {
                BukkitThreadGuardBridge.runInSimulationContext {
                    runtime.process(frame(1L))
                }
            }
            physics.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("old"), calls)
            // A's slow compile/commit must not retain the service directory monitor for B.
            executor.submit {
                service.replaceSources(owner, otherWorldId,
                    listOf(source("test:other", 40.0) { calls += "other" }))
                otherRuntime.process(frame(1L))
            }.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("old", "other"), calls)

            releaseCommit.countDown()
            replacement.get(5, TimeUnit.SECONDS)
            runtime.process(frame(2L))
        } finally {
            releaseCommit.countDown()
            executor.shutdownNow()
        }

        assertEquals(listOf("old", "other", "new"), calls)
    }

    @Test
    fun `empty epoch replacement still requires withdrawn restrictions and preserves infrastructure ownership`() {
        val signalService = DefaultSignalEpochSourceService()
        val infrastructureService = DefaultInfrastructureResourceService(signalService)
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        var active = listOf("train/$SIMPLE_SIGNALS_SIGNAL_SOURCE_ID@4")
        var calls = 0
        val contributor = RouteResourceContributor { _, _ -> emptyList() }
        val runtime = runtime()
        signalService.registerWorld(worldId, runtime, inspectionRuntime(), { sourceIds ->
            if (SIMPLE_SIGNALS_SIGNAL_SOURCE_ID in sourceIds) active else emptyList()
        }) { _, _ -> }
        signalService.replaceSources(
            owner,
            worldId,
            listOf(source(SIMPLE_SIGNALS_SIGNAL_SOURCE_ID, 0.0) { calls++ })
        )
        infrastructureService.registerRouteResourceContributor(owner, contributor)
        var removalCommitCalled = false

        val removalFailure = assertThrows(IllegalStateException::class.java) {
            signalService.replaceSourcesAtomically(owner, worldId, emptyList()) {
                removalCommitCalled = true
            }
        }
        assertEquals(true, removalFailure.message.orEmpty().contains(SIMPLE_SIGNALS_SIGNAL_SOURCE_ID))
        assertFalse(removalCommitCalled)
        val failure = assertThrows(IllegalStateException::class.java) {
            infrastructureService.unregisterOwner(owner)
        }
        assertEquals(true, failure.message.orEmpty().contains(SIMPLE_SIGNALS_SIGNAL_SOURCE_ID))
        assertThrows(IllegalStateException::class.java) {
            infrastructureService.registerRouteResourceContributor(owner, contributor)
        }
        runtime.process(frame(1L))
        assertEquals(1, calls)

        active = emptyList()
        signalService.replaceSources(owner, worldId, emptyList())
        runtime.process(frame(2L))
        assertEquals(1, calls)

        // Clearing epoch callbacks is not complete owner removal; the contributor remains owned.
        assertThrows(IllegalStateException::class.java) {
            infrastructureService.registerRouteResourceContributor(owner, contributor)
        }

        infrastructureService.unregisterOwner(owner)
        infrastructureService.unregisterOwner(owner)
        infrastructureService.registerRouteResourceContributor(owner, contributor).close()
        runtime.process(frame(3L))
        assertEquals(1, calls)
    }

    @Test
    fun `empty epoch replacement preserves inspection and route planning registrations`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("combined")
        val otherOwner = plugin("other")
        val epochRuntime = runtime()
        val inspectionRuntime = inspectionRuntime()
        val planningRuntime = RoutePlanningRestrictionRuntime()
        var epochCalls = 0
        service.registerWorld(
            worldId,
            epochRuntime,
            inspectionRuntime,
            { emptyList() },
            planningRuntime
        ) { reason, failure -> throw IllegalStateException(reason, failure) }
        service.replaceSources(owner, worldId, listOf(source("test:epoch", 0.0) { epochCalls++ }))
        service.replaceInspectionSources(owner, worldId, listOf(inspectionSource()))
        service.replaceRoutePlanningSources(owner, worldId, listOf(routePlanningSource("test:planning")))

        service.replaceSources(owner, worldId, emptyList())
        epochRuntime.process(frame(1L))
        inspectionRuntime.synchronizeRoute(
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp("world", 1L, 7L),
            "train",
            inspectionRoute()
        )

        assertEquals(0, epochCalls)
        assertEquals(80.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceRoutePlanningSources(
                otherOwner,
                worldId,
                listOf(routePlanningSource("test:planning"))
            )
        }
    }

    @Test
    fun `forced disable removes callbacks and stalls only worlds with active owner restrictions`() {
        val signalService = DefaultSignalEpochSourceService()
        val infrastructureService = DefaultInfrastructureResourceService(signalService)
        val idleWorldId = UUID.randomUUID()
        val activeWorldId = UUID.randomUUID()
        var enabled = true
        val disabledOwner = plugin("disabled") { enabled }
        val survivingOwner = plugin("surviving")
        val calls = mutableListOf<String>()
        val stalls = mutableListOf<String>()
        val idleRuntime = runtime()
        val activeRuntime = runtime()
        signalService.registerWorld(
            idleWorldId,
            idleRuntime,
            inspectionRuntime(),
            { emptyList() }
        ) { reason, _ -> stalls += reason }
        signalService.registerWorld(activeWorldId, activeRuntime, inspectionRuntime(), { sourceIds ->
            if ("test:active" in sourceIds) listOf("train/test:active@7") else emptyList()
        }) { reason, _ -> stalls += reason }
        signalService.replaceSources(disabledOwner, idleWorldId, listOf(source("test:idle", 0.0) {
            calls += "disabled-idle"
        }))
        signalService.replaceSources(disabledOwner, activeWorldId, listOf(source("test:active", 0.0) {
            calls += "disabled-active"
        }))
        signalService.replaceSources(survivingOwner, activeWorldId, listOf(source("test:surviving", 20.0) {
            calls += "surviving"
        }))

        enabled = false
        infrastructureService.forcedDisableOwner(disabledOwner)
        idleRuntime.process(frame(1L))
        activeRuntime.process(frame(1L))

        assertEquals(1, stalls.size)
        assertEquals(true, stalls.single().contains("disabled"))
        assertEquals(true, stalls.single().contains(activeWorldId.toString()))
        assertEquals(true, stalls.single().contains("train/test:active@7"))
        assertEquals(listOf("surviving"), calls)
    }

    @Test
    fun `world unload invalidates its binding without stalling`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("signal")
        val runtime = runtime()
        val inspectionRuntime = inspectionRuntime()
        service.registerWorld(worldId, runtime, inspectionRuntime, { emptyList() }) { reason, failure ->
            throw IllegalStateException(reason, failure)
        }
        service.replaceSources(owner, worldId, listOf(source("test:main", 0.0) {}))
        service.replaceInspectionSources(owner, worldId, listOf(inspectionSource()))
        inspectionRuntime.synchronizeRoute(
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp("world", 1L, 7L),
            "train",
            inspectionRoute()
        )

        service.removeWorld(worldId)
        service.removeWorld(worldId)

        assertEquals(0.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertThrows(IllegalStateException::class.java) {
            service.replaceSources(owner, worldId, listOf(source("test:main", 0.0) {}))
        }
        var commitCalled = false
        assertThrows(IllegalStateException::class.java) {
            service.replaceSourcesAtomically(
                owner,
                worldId,
                listOf(source("test:main", 0.0) {})
            ) {
                commitCalled = true
            }
        }
        assertFalse(commitCalled)
        service.close()
        service.close()
    }

    @Test
    fun `inspection service binds the host route and rejects unsafe live replacement`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("inspection")
        val unavailable = mutableListOf<String>()
        val stalls = mutableListOf<String>()
        val inspectionRuntime = inspectionRuntime { sourceId, trainId, failure ->
            unavailable += "$sourceId/$trainId: ${failure.message}"
        }
        service.registerWorld(worldId, runtime(), inspectionRuntime, { emptyList() }) { reason, _ ->
            stalls += reason
        }
        val route = work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot(
            "test:route",
            1L,
            7L,
            listOf(
                work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(
                    railId,
                    ViewDirection.AB,
                    0.0,
                    200.0
                )
            )
        )
        var boundRoute: FrozenRouteBinding? = null
        service.replaceInspectionSources(owner, worldId, listOf(
            InspectionSource(
                "test:inspection",
                { input ->
                    boundRoute = FrozenRouteBinding.from(input.route)
                    InspectionDeclarationBinding(
                        "test:inspection",
                        input.trainId,
                        FrozenRouteBinding.from(input.route),
                        1L,
                        1L,
                        listOf(
                            InspectionDeclaration(
                                "test:signal",
                                railId,
                                ViewDirection.AB,
                                100.0,
                                80.0
                            )
                        )
                    )
                },
                { emptyList() }
            )
        ))

        inspectionRuntime.synchronizeRoute(
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp("world", 1L, 7L),
            "train",
            route
        )
        assertEquals(FrozenRouteBinding.from(route), boundRoute)
        assertEquals(80.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(emptyList<String>(), unavailable)

        assertThrows(IllegalStateException::class.java) {
            service.replaceInspectionSources(owner, worldId, listOf(inspectionSource()))
        }
        assertEquals(80.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(1, unavailable.size)
        assertEquals(1, stalls.size)
    }

    @Test
    fun `invalid idle inspection replacement is rejected without stalling the world`() {
        val service = DefaultSignalEpochSourceService()
        val worldId = UUID.randomUUID()
        val owner = plugin("inspection")
        val stalls = mutableListOf<String>()
        service.registerWorld(worldId, runtime(), inspectionRuntime(), { emptyList() }) { reason, _ ->
            stalls += reason
        }
        val duplicate = inspectionSource()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceInspectionSources(owner, worldId, listOf(duplicate, duplicate))
        }

        assertEquals(emptyList<String>(), stalls)
    }

    @Test
    fun `forced disable removes active inspection callbacks and stalls the world`() {
        val service = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(service)
        val worldId = UUID.randomUUID()
        var enabled = true
        val owner = plugin("inspection") { enabled }
        val stalls = mutableListOf<String>()
        val inspectionRuntime = inspectionRuntime()
        service.registerWorld(worldId, runtime(), inspectionRuntime, { emptyList() }) { reason, _ ->
            stalls += reason
        }
        val route = inspectionRoute()
        service.replaceInspectionSources(owner, worldId, listOf(inspectionSource()))
        inspectionRuntime.synchronizeRoute(
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp("world", 1L, 7L),
            "train",
            route
        )

        enabled = false
        infrastructure.forcedDisableOwner(owner)

        assertEquals(1, stalls.size)
        assertEquals(true, stalls.single().contains("active inspection binding"))
        assertEquals(0.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    @Test
    fun `planned owner removal preflights active inspection bindings before any source mutation`() {
        val service = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(service)
        val worldId = UUID.randomUUID()
        val owner = plugin("inspection")
        val stalls = mutableListOf<String>()
        var epochCallbacks = 0
        val epochRuntime = runtime()
        val inspectionRuntime = inspectionRuntime()
        val contributor = RouteResourceContributor { _, _ -> emptyList() }
        service.registerWorld(worldId, epochRuntime, inspectionRuntime, { emptyList() }) { reason, _ ->
            stalls += reason
        }
        infrastructure.registerResourceProvider(
            owner,
            SIMPLE_SIGNALS_FIXED_BLOCK_TYPE,
            resourceProvider(),
            emptyList()
        )
        infrastructure.registerRouteResourceContributor(owner, contributor)
        service.replaceSources(
            owner,
            worldId,
            listOf(source(SIMPLE_SIGNALS_SIGNAL_SOURCE_ID, 0.0) { epochCallbacks++ })
        )
        service.replaceInspectionSources(owner, worldId, listOf(
            inspectionSource(SIMPLE_SIGNALS_INSPECTION_SOURCE_ID)
        ))
        service.replaceRoutePlanningSources(
            owner,
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
        inspectionRuntime.synchronizeRoute(
            work.art1st.javarailways.basic.api.observation.RailwayStateStamp("world", 1L, 7L),
            "train",
            inspectionRoute()
        )

        assertThrows(IllegalStateException::class.java) {
            infrastructure.unregisterOwner(owner)
        }
        epochRuntime.process(frame(2L))

        assertEquals(1, epochCallbacks)
        assertEquals(80.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(emptyList<String>(), stalls)
        assertThrows(IllegalStateException::class.java) {
            infrastructure.registerResourceProvider(
                plugin("other-provider"),
                SIMPLE_SIGNALS_FIXED_BLOCK_TYPE,
                resourceProvider(),
                emptyList()
            )
        }
        assertThrows(IllegalStateException::class.java) {
            infrastructure.registerRouteResourceContributor(owner, contributor)
        }

        inspectionRuntime.removeTrain("train")
        infrastructure.unregisterOwner(owner)
        epochRuntime.process(frame(3L))

        assertEquals(1, epochCallbacks)
        assertEquals(0.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        infrastructure.registerResourceProvider(
            plugin("replacement-provider"),
            SIMPLE_SIGNALS_FIXED_BLOCK_TYPE,
            resourceProvider(),
            emptyList()
        ).close()
        infrastructure.registerRouteResourceContributor(owner, contributor).close()
        service.replaceRoutePlanningSources(
            plugin("replacement-planner"),
            worldId,
            listOf(routePlanningSource(SIMPLE_SIGNALS_ROUTE_SOURCE_ID))
        )
    }

    @Test
    fun `route planning sources share owner replacement and planned unregister lifecycle`() {
        val service = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(service)
        val worldId = UUID.randomUUID()
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val planningRuntime = RoutePlanningRestrictionRuntime()
        service.registerWorld(
            worldId,
            runtime(),
            inspectionRuntime(),
            { emptyList() },
            planningRuntime
        ) { reason, failure -> throw IllegalStateException(reason, failure) }
        val source = routePlanningSource("test:route-table")

        service.replaceRoutePlanningSources(firstOwner, worldId, listOf(source))
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceRoutePlanningSources(secondOwner, worldId, listOf(source))
        }

        infrastructure.unregisterOwner(firstOwner)
        service.replaceRoutePlanningSources(secondOwner, worldId, listOf(source))
    }

    @Test
    fun `forced disable removes idle route planning callbacks without stalling`() {
        val service = DefaultSignalEpochSourceService()
        val infrastructure = DefaultInfrastructureResourceService(service)
        val worldId = UUID.randomUUID()
        var enabled = true
        val disabledOwner = plugin("disabled") { enabled }
        val survivingOwner = plugin("surviving")
        val stalls = mutableListOf<String>()
        service.registerWorld(
            worldId,
            runtime(),
            inspectionRuntime(),
            { emptyList() },
            RoutePlanningRestrictionRuntime()
        ) { reason, _ -> stalls += reason }
        val source = routePlanningSource("test:route-table")
        service.replaceRoutePlanningSources(disabledOwner, worldId, listOf(source))

        enabled = false
        infrastructure.forcedDisableOwner(disabledOwner)
        service.replaceRoutePlanningSources(survivingOwner, worldId, listOf(source))

        assertEquals(emptyList<String>(), stalls)
    }

    private fun runtime() = SignalEpochRuntime(
        "world",
        TrainRestrictionPublisher { snapshot ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        }
    )

    private fun inspectionRuntime(
        unavailable: (String, String, Exception) -> Unit = { _, _, _ -> }
    ) = InspectionRuntime(
        TrainRestrictionPublisher { snapshot ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        },
        unavailable
    )

    private fun inspectionRoute() = work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot(
        "test:route",
        1L,
        7L,
        listOf(
            work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(
                railId,
                ViewDirection.AB,
                0.0,
                200.0
            )
        )
    )

    private fun inspectionSource(sourceId: String = "test:inspection") = InspectionSource(
        sourceId,
        { input ->
            InspectionDeclarationBinding(
                sourceId,
                input.trainId,
                FrozenRouteBinding.from(input.route),
                1L,
                1L,
                listOf(
                    InspectionDeclaration(
                        "test:signal",
                        railId,
                        ViewDirection.AB,
                        100.0,
                        80.0
                    )
                )
            )
        },
        { emptyList() }
    )

    private fun routePlanningSource(sourceId: String) = RoutePlanningRestrictionBinding(sourceId) {
        RoutePlanningRestrictions.unrestricted(it)
    }

    private fun resourceProvider() = object : InfrastructureResourceProvider {
        override fun exists(worldId: UUID, resourceId: String) = true

        override fun validateRouteContribution(
            contributor: Plugin,
            worldId: UUID,
            resourceId: String,
            preparation: ResourcePreparationDescriptor?
        ): RouteAdmissionFailureCode? = null
    }

    private fun source(sourceId: String, start: Double, callback: () -> Unit): SignalEpochSource {
        val areaId = RailObjectRef("test", sourceId.substringAfter(':'))
        val area = RailwayOccupancyAreaDefinition.create(
            areaId,
            1L,
            7L,
            listOf(RailInterval(railId, start, start + 10.0)),
            listOf(
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${areaId.id}-entry"),
                    RailPositionSnapshot(railId, ViewDirection.AB, start)
                ),
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${areaId.id}-exit"),
                    RailPositionSnapshot(railId, ViewDirection.BA, start + 10.0)
                )
            )
        )
        return SignalEpochSource(sourceId, listOf(area)) {
            callback()
            SignalEpochResult.empty()
        }
    }

    private fun frame(epoch: Long) = RailwayWorldFrameSnapshot.create(
        epoch,
        7L,
        emptyList(),
        ActiveRailContinuationsSnapshot.empty(7L)
    )

    private fun plugin(name: String, enabled: () -> Boolean = { true }): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName" -> name
                "isEnabled" -> enabled()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Plugin($name)"
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

    private companion object {
        private const val SIMPLE_SIGNALS_FIXED_BLOCK_TYPE = "simplesignals:fixed-block"
        private const val SIMPLE_SIGNALS_SIGNAL_SOURCE_ID = "simplesignals:fixed-block-signals"
        private const val SIMPLE_SIGNALS_INSPECTION_SOURCE_ID = "simplesignals:protection-regions"
        private const val SIMPLE_SIGNALS_ROUTE_SOURCE_ID = "simplesignals:fixed-protection-routes"
    }
}
