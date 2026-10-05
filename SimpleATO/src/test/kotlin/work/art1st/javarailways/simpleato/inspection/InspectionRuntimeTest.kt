package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.UUID
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class InspectionRuntimeTest {
    @Test
    fun `transaction invalidates only routes using changed rails and preserves frozen binding identity`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = runtime(publications)
        bind(runtime, binding()) { input ->
            listOf(snapshot(input.observationStamp, revision = publications.size.toLong() + 1L))
        }
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        val binding = runtime.bindingFor("train")
        val seven = buildTestTopology(7L, emptyList(), emptyMap())
        val eight = buildTestTopology(8L, emptyList(), emptyMap())
        runtime.invalidateTopology(RailTopologyChange(seven, eight, setOf(UUID.randomUUID())))
        runtime.markDirty("test:source", listOf("test:signal"))
        val nextStamp = stamp.copy(physicsEpoch = 12L, topologyRevision = 8L)
        runtime.update(nextStamp, routeBinding, 60.0, 0.0, perception)
        assertEquals(2, publications.size)
        assertEquals(routeBinding, publications.last().route)
        assertEquals(nextStamp, publications.last().observationStamp)
        org.junit.jupiter.api.Assertions.assertSame(binding, runtime.bindingFor("train"))
        runtime.invalidateTopology(RailTopologyChange(eight,
            buildTestTopology(9L, emptyList(), emptyMap()), setOf(railId)))
        assertThrows(IllegalStateException::class.java) {
            runtime.update(nextStamp.copy(physicsEpoch = 13L, topologyRevision = 9L), routeBinding, 65.0, 0.0, perception)
        }
        assertEquals(2, publications.size)
        runtime.removeTrain("train")
        runtime.invalidateTopology(RailTopologyChange(buildTestTopology(9L, emptyList(), emptyMap()),
            buildTestTopology(10L, emptyList(), emptyMap()), setOf(railId)))
        assertEquals(false, runtime.hasBindings("train"))
    }

    @Test
    fun `inspection rejects a newer topology without the transaction handoff`() {
        val runtime = runtime()
        bind(runtime, binding()) { input -> listOf(snapshot(input.observationStamp, revision = 1L)) }
        assertThrows(IllegalStateException::class.java) {
            runtime.update(stamp.copy(topologyRevision = 8L), routeBinding, 50.0, 0.0, perception)
        }
    }

    @Test
    fun `cached ranges baseline without entries survive passage and respond to stable range invalidation`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = runtime(publications)
        val range = work.art1st.javarailways.simpleato.api.inspection.InspectionSpeedRange("test:block",
            work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction("test:block/visit-0", 0.0, 5.0, 80.0))
        val repeatedRange = range.copy(restriction = range.restriction.copy(
            restrictionId = "test:block/visit-1", routeDistanceMeters = 90.0, endRouteDistanceMeters = 100.0))
        val ranges = listOf(range, repeatedRange)
        val binding = InspectionDeclarationBinding("test:source", "train", routeBinding, 1L, 0L,
            emptyList(), ranges)
        var revision = 0L
        bind(runtime, binding) { input ->
            assertTrue(input.activeDeclarations.isEmpty())
            assertEquals(ranges, input.speedRanges)
            listOf(TrainRestrictionSnapshot("test:source", revision++, "train", input.observationStamp,
                input.route, input.speedRanges.map { it.restriction }))
        }
        runtime.update(stamp, routeBinding, 30.0, 10.0, perception)
        runtime.update(stamp, routeBinding, 40.0, 10.0, perception)
        assertEquals(1, publications.size)
        runtime.markDirty("test:source", listOf("test:unrelated"))
        runtime.markDirty("test:other", listOf("test:block"))
        runtime.update(stamp, routeBinding, 50.0, 10.0, perception)
        assertEquals(1, publications.size)
        runtime.markDirty("test:source", listOf("test:block"))
        runtime.update(stamp, routeBinding, 60.0, 10.0, perception)
        assertEquals(2, publications.size)
        runtime.removeTrain("train")
        runtime.update(stamp, routeBinding, 70.0, 10.0, perception)
        assertEquals(2, publications.size)
        assertThrows(IllegalArgumentException::class.java) { InspectionDeclarationBinding("test:source", "train", routeBinding, 1L, 0L,
            emptyList(), listOf(range.copy(restriction = range.restriction.copy(endRouteDistanceMeters = routeBinding.totalLengthMeters + 1)))) }
        assertThrows(IllegalArgumentException::class.java) { InspectionDeclarationBinding("test:source", "train", routeBinding, 1L, 0L,
            emptyList(), listOf(range, range)) }
    }

    @Test
    fun `selected route dependencies use current sources without train bindings or publications`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = runtime(publications)
        val token = StableInfrastructureResource("test:block", "selected")
        val returned = arrayListOf(token, token)
        val calls = arrayListOf<String>()
        fun dependencySource(id: String) = InspectionSource(id,
            { error("static routes must not bind a train") },
            { error("static routes must not inspect live state") },
            { selected -> calls += "$id/${selected.routeId}"; returned })
        runtime.replaceSources(listOf(dependencySource("test:z"), dependencySource("test:a")))
        val selected = listOf(route, RouteSnapshot("test:another", route.revision, route.topologyRevision, route.segments))

        val resources = runtime.collectRouteInfrastructureDependencies(selected)

        assertEquals(setOf(token), resources)
        assertEquals(listOf("test:a/${route.routeId}", "test:a/test:another",
            "test:z/${route.routeId}", "test:z/test:another"), calls)
        returned.clear()
        assertEquals(setOf(token), resources)
        assertTrue(publications.isEmpty())
        assertEquals(false, runtime.hasBindings("train"))
        assertThrows(UnsupportedOperationException::class.java) { (resources as MutableSet).clear() }
        runtime.removeSource("test:a")
        runtime.removeSource("test:z")
        assertTrue(runtime.collectRouteInfrastructureDependencies(selected).isEmpty())
    }

    @Test
    fun `selected route dependency failure includes source and route without changing bindings`() {
        val runtime = runtime()
        runtime.replaceSources(listOf(InspectionSource("test:broken", { binding() }, { emptyList() },
            { error("stale definitions") })))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runtime.collectRouteInfrastructureDependencies(listOf(route))
        }
        assertTrue(failure.message!!.contains("test:broken"))
        assertTrue(failure.message!!.contains(route.routeId))
        assertTrue(failure.message!!.contains("stale definitions"))
        assertEquals(false, runtime.hasBindings("train"))
    }

    private val trainInspections = mutableMapOf<InspectionRuntime, InspectionRuntime.TrainInspection>()
    private val railId = UUID.nameUUIDFromBytes("inspection-runtime".toByteArray())
    private val route = RouteSnapshot(
        "test:route/inspection-runtime",
        1L,
        7L,
        listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 200.0))
    )
    private val routeBinding = FrozenRouteBinding.from(route)
    private val stamp = RailwayStateStamp("world", 11L, route.topologyRevision)
    private val perception = PerceptionSnapshot(
        4L,
        TrainSnapshot(
            "train",
            4L,
            10.0,
            false,
            1,
            RailPositionSnapshot(railId, ViewDirection.AB, 50.0),
            RailPositionSnapshot(railId, ViewDirection.AB, 40.0)
        ),
        route,
        emptyList()
    )

    @Test
    fun `active set changes and source invalidation dispatch complete snapshots without unchanged callbacks`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val inputs = arrayListOf<List<String>>()
        val runtime = runtime(publications = publications)
        bind(runtime, binding()) { input ->
            inputs += input.activeDeclarations.map(InspectionDeclaration::declarationId)
            listOf(snapshot(input.observationStamp, revision = inputs.size.toLong()))
        }

        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        runtime.update(stamp, routeBinding, 55.0, 0.0, perception)
        runtime.markDirty("test:source", listOf("test:signal", "test:not-on-this-route"))
        runtime.update(stamp, routeBinding, 60.0, 0.0, perception)
        runtime.update(stamp, routeBinding, 101.0, 0.0, perception)

        assertEquals(
            listOf(listOf("test:signal"), listOf("test:signal"), emptyList()),
            inputs
        )
        assertEquals(listOf(1L, 2L, 3L), publications.map(TrainRestrictionSnapshot::revision))
        assertEquals(80.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    @Test
    fun `invalid provider binding leaves no partial session`() {
        val runtime = runtime()
        val wrongRoute = RouteSnapshot(
            route.routeId,
            route.revision + 1L,
            route.topologyRevision,
            route.segments
        )
        runtime.replaceSources(listOf(source(binding())))

        assertThrows(IllegalArgumentException::class.java) {
            runtime.synchronizeRoute(stamp, "train", wrongRoute)
        }

        assertEquals(false, runtime.hasBindings("train"))
        assertEquals(0.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    @Test
    fun `callback failure marks the source unavailable and preserves dirty work`() {
        val unavailable = arrayListOf<String>()
        var attempts = 0
        val runtime = runtime(unavailable = unavailable)
        bind(runtime, binding()) { input ->
            attempts++
            if (attempts == 1) error("plugin failed")
            listOf(snapshot(input.observationStamp, attempts.toLong()))
        }

        assertThrows(IllegalStateException::class.java) {
            runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        }
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(2, attempts)
        assertEquals(listOf("test:source/train: plugin failed"), unavailable)
    }

    @Test
    fun `publisher rejection preserves the update for a later dispatch`() {
        val unavailable = arrayListOf<String>()
        val publishedRevisions = arrayListOf<Long>()
        var publishAttempts = 0
        var callbackRevision = 0L
        val runtime = InspectionRuntime(
            publisher = { snapshot ->
                publishAttempts++
                if (publishAttempts == 1) {
                    TrainRestrictionPublicationResult(
                        TrainRestrictionPublicationStatus.SOURCE_UNAVAILABLE,
                        null
                    )
                } else {
                    publishedRevisions += snapshot.revision
                    TrainRestrictionPublicationResult(
                        TrainRestrictionPublicationStatus.ACCEPTED,
                        snapshot.revision
                    )
                }
            },
            sourceUnavailable = { sourceId, trainId, failure ->
                unavailable += "$sourceId/$trainId: ${failure.message}"
            }
        )
        bind(runtime, binding()) { input ->
            callbackRevision++
            listOf(snapshot(input.observationStamp, callbackRevision))
        }

        assertThrows(IllegalStateException::class.java) {
            runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        }
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(listOf(2L), publishedRevisions)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `all callback results validate before the first publication`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val unavailable = arrayListOf<String>()
        val runtime = runtime(publications, unavailable)
        runtime.replaceSources(listOf(
            source(binding("test:first", "test:first-signal")) { input ->
                listOf(snapshot(input.observationStamp, 1L, sourceId = "test:first"))
            },
            source(binding("test:second", "test:second-signal")) { input ->
                listOf(snapshot(input.observationStamp, 1L, sourceId = "test:wrong"))
            }
        ))
        runtime.synchronizeRoute(stamp, "train", route)

        assertThrows(IllegalArgumentException::class.java) {
            runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        }

        assertEquals(emptyList<TrainRestrictionSnapshot>(), publications)
        assertEquals(1, unavailable.size)
        assertTrue(unavailable.single().startsWith("test:second/train:"))
    }

    @Test
    fun `stale route fails closed before invoking the callback`() {
        val unavailable = arrayListOf<String>()
        var callbacks = 0
        val runtime = runtime(unavailable = unavailable)
        bind(runtime, binding()) {
            callbacks++
            emptyList()
        }
        val changedRoute = FrozenRouteBinding(
            routeBinding.routeId,
            routeBinding.routeRevision + 1L,
            routeBinding.topologyRevision,
            routeBinding.totalLengthMeters
        )

        assertThrows(IllegalStateException::class.java) {
            runtime.update(stamp, changedRoute, 50.0, 0.0, perception)
        }

        assertEquals(0, callbacks)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `train and source removal discard scheduler state`() {
        var callbacks = 0
        val runtime = runtime()
        bind(runtime, binding()) {
            callbacks++
            emptyList()
        }
        runtime.removeSource("test:source")
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        runtime.removeTrain("train")
        bind(runtime, binding()) {
            callbacks++
            emptyList()
        }
        runtime.removeTrain("train")
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(0, callbacks)
    }

    @Test
    fun `source provider receives the exact host route and publishes its maximum`() {
        var receivedRoute: RouteSnapshot? = null
        val runtime = runtime()
        runtime.replaceSources(listOf(
            InspectionSource(
                "test:source",
                { input ->
                    receivedRoute = input.route
                    binding()
                },
                { emptyList() }
            )
        ))

        val maximum = runtime.synchronizeRoute(stamp, "train", route)

        assertEquals(route, receivedRoute)
        assertEquals(80.0, maximum, 0.0)
        assertEquals(80.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    @Test
    fun `empty binding publishes one route baseline then remains idle`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        var callbacks = 0
        val runtime = runtime(publications = publications)
        val emptyBinding = InspectionDeclarationBinding(
            "test:source",
            "train",
            routeBinding,
            1L,
            1L,
            emptyList()
        )
        bind(runtime, emptyBinding) { input ->
            callbacks++
            assertEquals(emptyList<InspectionDeclaration>(), input.activeDeclarations)
            listOf(snapshot(input.observationStamp, callbacks.toLong()))
        }

        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(1, callbacks)
        assertEquals(listOf(1L), publications.map(TrainRestrictionSnapshot::revision))
    }

    @Test
    fun `invalid source union leaves the previous complete set active`() {
        val runtime = runtime()
        val valid = InspectionSource("test:source", { binding() }, { emptyList() })
        runtime.replaceSources(listOf(valid))

        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceSources(listOf(valid, valid))
        }
        runtime.synchronizeRoute(stamp, "train", route)

        assertEquals(80.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    @Test
    fun `empty source replacement keeps an active route without a compiled floor`() {
        val runtime = runtime()
        runtime.synchronizeRoute(stamp, "train", route)

        runtime.replaceSources(emptyList())

        assertEquals(
            55.0,
            runtime.adjustRequestedAuthorityBoundary(
                "train",
                routeBinding,
                RouteBoundary(55.0)
            ).distanceFromRouteStartMeters,
            0.0
        )
    }

    @Test
    fun `new source binding on an active route fails closed before changing the live set`() {
        val unavailable = arrayListOf<String>()
        val runtime = runtime(unavailable = unavailable)
        runtime.synchronizeRoute(stamp, "train", route)
        val source = InspectionSource("test:source", { binding() }, { emptyList() })

        assertThrows(IllegalStateException::class.java) {
            runtime.replaceSources(listOf(source))
        }

        assertEquals(0.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(1, unavailable.size)
        assertTrue(unavailable.single().contains("before another physics epoch"))
    }

    @Test
    fun `source removal on an active route fails closed without dropping the binding`() {
        val unavailable = arrayListOf<String>()
        val runtime = runtime(unavailable = unavailable)
        runtime.replaceSources(listOf(
            InspectionSource("test:source", { binding() }, { emptyList() })
        ))
        runtime.synchronizeRoute(stamp, "train", route)

        assertThrows(IllegalStateException::class.java) {
            runtime.replaceSources(emptyList())
        }

        assertEquals(80.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `production train retention removes ended route sessions and cached maximum`() {
        val runtime = runtime()
        runtime.replaceSources(listOf(
            InspectionSource("test:source", { binding() }, { emptyList() })
        ))
        runtime.synchronizeRoute(stamp, "train", route)

        runtime.retainTrains(emptySet())

        assertEquals(0.0, runtime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
        assertEquals(false, runtime.hasBindings("train"))
    }

    @Test
    fun `two physics workers project concurrently and consume dirty sets independently`() {
        val entered = CountDownLatch(2)
        val revisions = AtomicLong()
        val calls = ConcurrentLinkedQueue<String>()
        val runtime = InspectionRuntime(
            publisher = { value ->
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
            },
            sourceUnavailable = { _, _, _ -> }
        )
        runtime.replaceSources(listOf(InspectionSource("test:source", { input ->
            InspectionDeclarationBinding("test:source", input.trainId, routeBinding, 1L, 1L,
                binding().declarations)
        }, { input ->
            entered.countDown()
            check(entered.await(5L, TimeUnit.SECONDS)) { "inspection callbacks were serialized across trains" }
            val trainId = input.perception.train.trainId
            calls += trainId
            listOf(TrainRestrictionSnapshot("test:source", revisions.incrementAndGet(), trainId,
                input.observationStamp, input.route, emptyList()))
        })))
        val workers = listOf("train", "second").associateWith { trainId ->
            runtime.synchronizeRoute(stamp, trainId, route)
            runtime.createTrainInspection(trainId)
        }
        fun update(trainId: String) = workers.getValue(trainId).update(
            stamp, routeBinding, 50.0, 0.0, 20.0, 1.0,
            PerceptionSnapshot(perception.epoch, perception.train.copy(trainId = trainId), route, emptyList())
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = workers.keys.map { trainId -> executor.submit { update(trainId) } }
            futures.forEach { it.get(10L, TimeUnit.SECONDS) }
            runtime.markDirty("test:source", listOf("test:signal"))
            update("second")
            update("second")
            update("train")
            assertEquals(2, calls.count { it == "second" })
            assertEquals(2, calls.count { it == "train" })

            runtime.removeTrain("train")
            update("train")
            runtime.markDirty("test:source", listOf("test:signal"))
            update("second")
            assertEquals(2, calls.count { it == "train" })
            assertEquals(3, calls.count { it == "second" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `captured route survives binding publication then replacement reversal and reset establish baselines`() {
        val calls = arrayListOf<FrozenRouteBinding>()
        val runtime = runtime()
        runtime.replaceSources(listOf(InspectionSource("test:source", { input ->
            InspectionDeclarationBinding("test:source", input.trainId, FrozenRouteBinding.from(input.route),
                1L, 1L, listOf(InspectionDeclaration("test:signal", railId,
                    input.route.segments.single().facing, 100.0, 80.0)))
        }, { input ->
            calls += input.route
            listOf(TrainRestrictionSnapshot("test:source", calls.size.toLong(), "train",
                input.observationStamp, input.route, emptyList()))
        })))
        runtime.synchronizeRoute(stamp, "train", route)
        val worker = runtime.createTrainInspection("train")
        val captured = runtime.bindingFor("train")
        worker.update(stamp, routeBinding, 50.0, 0.0, 20.0, 1.0, perception, captured)
        val reversed = RouteSnapshot(route.routeId, 2L, route.topologyRevision,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.BA, 0.0, 200.0)))
        runtime.synchronizeRoute(stamp, "train", reversed)
        // A worker already in the old control calculation can finish before the route mapping swap.
        worker.update(stamp, routeBinding, 50.0, 0.0, 20.0, 1.0, perception, captured)
        assertEquals(listOf(routeBinding), calls)
        val reverseBinding = FrozenRouteBinding.from(reversed)
        val reversedPerception = PerceptionSnapshot(perception.epoch, perception.train, reversed, emptyList())
        worker.update(stamp, reverseBinding, 50.0, 0.0, 20.0, 1.0, reversedPerception)
        assertEquals(listOf(routeBinding, reverseBinding), calls)

        runtime.removeTrain("train")
        worker.update(stamp, reverseBinding, 50.0, 0.0, 20.0, 1.0, reversedPerception)
        runtime.synchronizeRoute(stamp, "train", reversed)
        worker.update(stamp, reverseBinding, 50.0, 0.0, 20.0, 1.0, reversedPerception)
        assertEquals(listOf(routeBinding, reverseBinding, reverseBinding), calls)
        runtime.clear()
        worker.update(stamp, reverseBinding, 50.0, 0.0, 20.0, 1.0, reversedPerception)
        assertEquals(3, calls.size)
    }

    private fun runtime(
        publications: MutableList<TrainRestrictionSnapshot> = arrayListOf(),
        unavailable: MutableList<String> = arrayListOf()
    ) = InspectionRuntime(
        publisher = { snapshot ->
            publications += snapshot
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        },
        sourceUnavailable = { sourceId, trainId, failure ->
            unavailable += "$sourceId/$trainId: ${failure.message}"
        }
    )

    private fun bind(
        runtime: InspectionRuntime,
        binding: InspectionDeclarationBinding,
        handler: work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler
    ) {
        runtime.replaceSources(listOf(source(binding, handler)))
        runtime.synchronizeRoute(stamp, binding.trainId, route)
    }

    private fun InspectionRuntime.update(
        observationStamp: RailwayStateStamp,
        currentRoute: FrozenRouteBinding,
        routeProgressMeters: Double,
        dynamicLookaheadMeters: Double,
        perception: PerceptionSnapshot
    ) = trainInspections.getOrPut(this) { createTrainInspection("train") }.update(
        observationStamp,
        currentRoute,
        routeProgressMeters,
        dynamicLookaheadMeters,
        maximumSpeedMetersPerSecond = 20.0,
        accelerationMetersPerSecondSquared = 1.0,
        perception
    )

    private fun source(
        binding: InspectionDeclarationBinding,
        handler: work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler =
            work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler { emptyList() }
    ) = InspectionSource(binding.sourceId, { binding }, handler)

    private fun binding(
        sourceId: String = "test:source",
        declarationId: String = "test:signal"
    ) = InspectionDeclarationBinding(
        sourceId,
        "train",
        routeBinding,
        1L,
        1L,
        listOf(InspectionDeclaration(
            declarationId,
            railId,
            ViewDirection.AB,
            100.0,
            80.0
        ))
    )

    private fun snapshot(
        observationStamp: RailwayStateStamp,
        revision: Long,
        sourceId: String = "test:source"
    ) = TrainRestrictionSnapshot(
        sourceId,
        revision,
        "train",
        observationStamp,
        routeBinding,
        listOf(CurrentSpeedRestriction("$sourceId/limit", 8.0))
    )
}
