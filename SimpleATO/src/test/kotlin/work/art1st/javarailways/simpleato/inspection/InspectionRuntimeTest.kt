package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
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

class InspectionRuntimeTest {
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
        assertEquals(80.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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
        assertEquals(0.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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
        assertEquals(80.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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

        assertEquals(80.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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

        assertEquals(0.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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

        assertEquals(80.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
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

        assertEquals(0.0, runtime.maximumDeclaredDistanceMeters("train"), 0.0)
        assertEquals(false, runtime.hasBindings("train"))
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
