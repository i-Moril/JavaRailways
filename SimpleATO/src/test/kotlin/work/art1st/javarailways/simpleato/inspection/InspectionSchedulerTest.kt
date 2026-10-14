package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.UUID

class InspectionSchedulerTest {
    @Test
    fun `an append split on one continuous rail remains one declaration occurrence`() {
        val rail = UUID(0L, 10L)
        val split = RouteSnapshot("test:split", 1L, 7L, listOf(
            RouteSegmentSnapshot(rail, ViewDirection.AB, 10.0, 100.0),
            RouteSegmentSnapshot(rail, ViewDirection.AB, 100.0, 140.0),
            RouteSegmentSnapshot(rail, ViewDirection.AB, 140.0, 200.0)))
        val declaration = declaration("test:joint", rail, ViewDirection.AB, 130.0, 0.0)
        val binding = InspectionDeclarationBinding("test:source", "train", FrozenRouteBinding.from(split),
            1L, 0L, listOf(declaration))
        val compiled = CompiledInspectionBinding.compile(binding, split)
        assertEquals(listOf(declaration), compiled.declarationsBetween(130.0, 130.0))
    }

    private val firstRail = UUID.nameUUIDFromBytes("inspection-first".toByteArray())
    private val secondRail = UUID.nameUUIDFromBytes("inspection-second".toByteArray())
    private val route = RouteSnapshot(
        routeId = "test:route/inspection",
        revision = 3L,
        topologyRevision = 7L,
        segments = listOf(
            segment(firstRail, ViewDirection.AB),
            segment(secondRail, ViewDirection.BA),
            segment(firstRail, ViewDirection.AB)
        )
    )

    @Test
    fun `binding copies declarations and validates stable identities`() {
        val declarations = mutableListOf(declaration("test:first", firstRail, ViewDirection.AB, 50.0, 30.0))
        val binding = binding(declarations)
        declarations.clear()

        assertEquals(listOf("test:first"), binding.declarations.map { it.declarationId })
        assertNotSame(declarations, binding.declarations)
        assertEquals(2, binding(listOf(
                declaration("test:duplicate", firstRail, ViewDirection.AB, 50.0, 10.0),
                declaration("test:duplicate", secondRail, ViewDirection.BA, 150.0, 10.0)
            )).declarations.size)
        assertThrows(IllegalArgumentException::class.java) {
            binding(listOf(
                declaration("test:duplicate", firstRail, ViewDirection.AB, 50.0, 10.0),
                declaration("test:duplicate", firstRail, ViewDirection.AB, 50.0, 20.0)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(listOf(
                declaration("test:zero", firstRail, ViewDirection.AB, 0.0, 10.0),
                declaration("test:zero", firstRail, ViewDirection.AB, -0.0, 10.0)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            declaration("not-namespaced", firstRail, ViewDirection.AB, 50.0, 10.0)
        }
    }

    @Test
    fun `stable identity invalidation wakes all active visits and passing nearer visit changes active set`() {
        val scheduler = scheduler(
            declaration("test:signal", firstRail, ViewDirection.AB, 50.0, 0.0),
            declaration("test:signal", firstRail, ViewDirection.AB, 250.0, 0.0)
        )
        assertEquals(listOf(50.0, 250.0), requireNotNull(scheduler.updatesFor(0.0, 300.0))
            .map { it.triggerRouteDistanceMeters })
        assertNull(scheduler.updatesFor(10.0, 300.0))
        scheduler.markPresentDirty(listOf("test:signal"))
        assertEquals(listOf(50.0, 250.0), requireNotNull(scheduler.updatesFor(10.0, 300.0))
            .map { it.triggerRouteDistanceMeters })
        assertEquals(listOf(250.0), requireNotNull(scheduler.updatesFor(60.0, 300.0))
            .map { it.triggerRouteDistanceMeters })
        assertNull(scheduler.updatesFor(70.0, 300.0))
    }

    @Test
    fun `replacement by later visit updates even when stable active identity stays the same`() {
        val scheduler = scheduler(
            declaration("test:signal", firstRail, ViewDirection.AB, 50.0, 0.0),
            declaration("test:signal", firstRail, ViewDirection.AB, 250.0, 0.0)
        )
        assertEquals(50.0, requireNotNull(scheduler.updatesFor(0.0, 60.0)).single().triggerRouteDistanceMeters)
        assertEquals(250.0, requireNotNull(scheduler.updatesFor(200.0, 60.0)).single().triggerRouteDistanceMeters)
        assertNull(scheduler.updatesFor(210.0, 60.0))
    }

    @Test
    fun `compiler validates exact route and occurrence while caching maximum lead time`() {
        val compiled = compile(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 30.0),
            declaration("test:repeated", firstRail, ViewDirection.AB, 250.0, 100.0)
        )

        assertEquals(100.0, compiled.maximumDeclaredInspectionLeadTimeSeconds, 0.0)
        assertEquals(
            listOf("test:first", "test:repeated"),
            compiled.declarationsBetween(0.0, 300.0).map { it.declarationId }
        )
        assertThrows(IllegalArgumentException::class.java) {
            CompiledInspectionBinding.compile(
                binding(listOf(declaration("test:first", firstRail, ViewDirection.AB, 50.0, 30.0))),
                RouteSnapshot(
                    route.routeId,
                    route.revision + 1,
                    route.topologyRevision,
                    route.segments
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(declaration("test:wrong-rail", secondRail, ViewDirection.BA, 50.0, 30.0))
        }
    }

    @Test
    fun `scheduler distinguishes a repeated rail by absolute route position`() {
        val scheduler = scheduler(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 200.0),
            declaration("test:repeated", firstRail, ViewDirection.AB, 250.0, 100.0)
        )

        val updates = scheduler.updatesFor(160.0, 40.0)

        assertEquals(listOf("test:repeated"), requireNotNull(updates).map { it.declarationId })
    }

    @Test
    fun `dynamic braking lookahead can activate beyond the plugin lead time`() {
        val scheduler = scheduler(
            declaration("test:dynamic", secondRail, ViewDirection.BA, 150.0, 10.0)
        )

        val updates = scheduler.updatesFor(100.0, 60.0)

        assertEquals(listOf("test:dynamic"), requireNotNull(updates).map { it.declarationId })
    }

    @Test
    fun `lead time converts current motion into a train specific activation distance`() {
        assertEquals(8.0, reachableDistanceMeters(0.0, 20.0, 1.0, 4.0), 1.0e-12)
        assertEquals(48.0, reachableDistanceMeters(10.0, 20.0, 1.0, 4.0), 1.0e-12)
        assertEquals(250.0, reachableDistanceMeters(0.0, 10.0, 1.0, 30.0), 1.0e-12)
    }

    @Test
    fun `stopped train activates before acceleration can reach the trigger`() {
        val scheduler = scheduler(
            declaration("test:starting", secondRail, ViewDirection.BA, 150.0, 4.0)
        )

        assertEquals(
            listOf("test:starting"),
            requireNotNull(scheduler.updatesFor(142.0, 0.0, 0.0, 20.0, 1.0))
                .map { it.declarationId }
        )
    }

    @Test
    fun `unchanged active declaration stays idle until explicitly dirtied`() {
        val scheduler = scheduler(
            declaration("test:idle", secondRail, ViewDirection.BA, 150.0, 60.0)
        )
        assertEquals(1, requireNotNull(scheduler.updatesFor(100.0, 0.0)).size)
        assertNull(scheduler.updatesFor(110.0, 0.0))
        scheduler.markPresentDirty(listOf("test:idle"))
        assertEquals(
            listOf("test:idle"),
            requireNotNull(scheduler.updatesFor(120.0, 0.0)).map { it.declarationId }
        )
        assertNull(scheduler.updatesFor(125.0, 0.0))
    }

    @Test
    fun `dirty declaration outside the range waits until activation`() {
        val scheduler = scheduler(
            declaration("test:later", secondRail, ViewDirection.BA, 150.0, 20.0)
        )
        scheduler.markPresentDirty(listOf("test:later"))

        assertNull(scheduler.updatesFor(100.0, 0.0))
        assertEquals(
            listOf("test:later"),
            requireNotNull(scheduler.updatesFor(135.0, 0.0)).map { it.declarationId }
        )
    }

    @Test
    fun `leaving and reentering the range activates again`() {
        val scheduler = scheduler(
            declaration("test:reenter", firstRail, ViewDirection.AB, 50.0, 20.0)
        )
        assertEquals(1, requireNotNull(scheduler.updatesFor(35.0, 0.0)).size)
        assertEquals(0, requireNotNull(scheduler.updatesFor(0.0, 0.0)).size)
        assertEquals(1, requireNotNull(scheduler.updatesFor(35.0, 0.0)).size)
    }

    @Test
    fun `binding replacement resets activation while unrelated source invalidations are ignored`() {
        val scheduler = scheduler(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 20.0)
        )
        assertEquals(1, requireNotNull(scheduler.updatesFor(35.0, 0.0)).size)
        scheduler.markPresentDirty(listOf("test:missing"))
        assertNull(scheduler.updatesFor(35.0, 0.0))

        scheduler.replaceBinding(
            compile(declaration("test:replacement", firstRail, ViewDirection.AB, 50.0, 20.0))
        )

        assertEquals(
            listOf("test:replacement"),
            requireNotNull(scheduler.updatesFor(35.0, 0.0)).map { it.declarationId }
        )
    }

    @Test
    fun `scheduler uses a binary trigger window and each declaration threshold`() {
        val scheduler = scheduler(
            declaration("test:short", secondRail, ViewDirection.BA, 120.0, 10.0),
            declaration("test:long", secondRail, ViewDirection.BA, 180.0, 100.0),
            declaration("test:outside", firstRail, ViewDirection.AB, 250.0, 20.0)
        )

        assertEquals(
            listOf("test:long"),
            requireNotNull(scheduler.updatesFor(100.0, 0.0)).map { it.declarationId }
        )
    }

    private fun scheduler(vararg declarations: InspectionDeclaration) =
        InspectionScheduler(compile(*declarations))

    private fun compile(vararg declarations: InspectionDeclaration) =
        CompiledInspectionBinding.compile(binding(declarations.asList()), route)

    private fun binding(declarations: Collection<InspectionDeclaration>) = InspectionDeclarationBinding(
        sourceId = "test:source",
        trainId = "train",
        route = FrozenRouteBinding.from(route),
        definitionRevision = 11L,
        trainProfileRevision = 13L,
        declarations = declarations
    )

    private fun declaration(
        id: String,
        railId: UUID,
        facing: ViewDirection,
        triggerDistance: Double,
        inspectionLeadTime: Double
    ) = InspectionDeclaration(id, railId, facing, triggerDistance, inspectionLeadTime)

    private fun InspectionScheduler.updatesFor(
        currentHeadRouteDistanceMeters: Double,
        dynamicLookaheadMeters: Double
    ) = updatesFor(
        currentHeadRouteDistanceMeters,
        dynamicLookaheadMeters,
        currentSpeedMetersPerSecond = 1.0,
        maximumSpeedMetersPerSecond = 1.0,
        accelerationMetersPerSecondSquared = 1.0
    )

    private fun segment(railId: UUID, facing: ViewDirection) =
        RouteSegmentSnapshot(railId, facing, 0.0, 100.0)
}
