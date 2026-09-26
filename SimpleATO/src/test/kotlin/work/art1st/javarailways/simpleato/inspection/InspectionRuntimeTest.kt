package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
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
    fun `newly active and dirty declarations dispatch without unchanged callbacks`() {
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val inputs = arrayListOf<List<String>>()
        val runtime = runtime(publications = publications)
        runtime.replaceBinding(binding(), route) { input ->
            inputs += input.declarations.map(InspectionDeclaration::declarationId)
            listOf(snapshot(input.observationStamp, revision = inputs.size.toLong()))
        }

        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        runtime.update(stamp, routeBinding, 55.0, 0.0, perception)
        runtime.markDirty("test:source", "train", listOf("test:signal"))
        runtime.update(stamp, routeBinding, 60.0, 0.0, perception)

        assertEquals(listOf(listOf("test:signal"), listOf("test:signal")), inputs)
        assertEquals(listOf(1L, 2L), publications.map(TrainRestrictionSnapshot::revision))
        assertEquals(80.0, runtime.maximumDeclaredDistanceMeters("train", routeBinding), 0.0)
    }

    @Test
    fun `invalid replacement leaves the previous binding active`() {
        var callbacks = 0
        val runtime = runtime()
        runtime.replaceBinding(binding(), route) {
            callbacks++
            emptyList()
        }
        val wrongRoute = RouteSnapshot(
            route.routeId,
            route.revision + 1L,
            route.topologyRevision,
            route.segments
        )

        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceBinding(binding(), wrongRoute) { emptyList() }
        }
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(1, callbacks)
    }

    @Test
    fun `callback failure marks the source unavailable and preserves dirty work`() {
        val unavailable = arrayListOf<String>()
        var attempts = 0
        val runtime = runtime(unavailable = unavailable)
        runtime.replaceBinding(binding(), route) {
            attempts++
            if (attempts == 1) error("plugin failed")
            emptyList()
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
        runtime.replaceBinding(binding(), route) { input ->
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
        runtime.replaceBinding(binding("test:first", "test:first-signal"), route) { input ->
            listOf(snapshot(input.observationStamp, 1L, sourceId = "test:first"))
        }
        runtime.replaceBinding(binding("test:second", "test:second-signal"), route) { input ->
            listOf(snapshot(input.observationStamp, 1L, sourceId = "test:wrong"))
        }

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
        runtime.replaceBinding(binding(), route) {
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
        runtime.replaceBinding(binding(), route) {
            callbacks++
            emptyList()
        }
        runtime.removeSource("test:source")
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)
        runtime.replaceBinding(binding(), route) {
            callbacks++
            emptyList()
        }
        runtime.removeTrain("train")
        runtime.update(stamp, routeBinding, 50.0, 0.0, perception)

        assertEquals(0, callbacks)
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
