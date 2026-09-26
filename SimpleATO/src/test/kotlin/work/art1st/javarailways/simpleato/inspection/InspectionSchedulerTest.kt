package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
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
        assertThrows(IllegalArgumentException::class.java) {
            binding(listOf(
                declaration("test:duplicate", firstRail, ViewDirection.AB, 50.0, 10.0),
                declaration("test:duplicate", secondRail, ViewDirection.BA, 150.0, 10.0)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            declaration("not-namespaced", firstRail, ViewDirection.AB, 50.0, 10.0)
        }
    }

    @Test
    fun `compiler validates exact route and occurrence while caching maximum distance`() {
        val compiled = compile(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 30.0),
            declaration("test:repeated", firstRail, ViewDirection.AB, 250.0, 100.0)
        )

        assertEquals(100.0, compiled.maximumDeclaredInspectionDistanceMeters, 0.0)
        assertEquals(
            listOf("test:repeated"),
            compiled.declarationsAt(occurrence(2, firstRail, ViewDirection.AB)).map { it.declarationId }
        )
        assertThrows(IllegalArgumentException::class.java) {
            compiled.declarationsAt(occurrence(2, secondRail, ViewDirection.BA))
        }
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
    fun `scheduler activates only the encountered repeated occurrence`() {
        val scheduler = scheduler(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 200.0),
            declaration("test:repeated", firstRail, ViewDirection.AB, 250.0, 100.0)
        )

        val updates = scheduler.updatesFor(
            listOf(occurrence(2, firstRail, ViewDirection.AB)),
            currentHeadRouteDistanceMeters = 160.0,
            dynamicLookaheadMeters = 40.0
        )

        assertEquals(listOf("test:repeated"), updates.map { it.declarationId })
    }

    @Test
    fun `dynamic braking lookahead can activate beyond the plugin distance`() {
        val scheduler = scheduler(
            declaration("test:dynamic", secondRail, ViewDirection.BA, 150.0, 10.0)
        )

        val updates = scheduler.updatesFor(
            listOf(occurrence(1, secondRail, ViewDirection.BA)),
            currentHeadRouteDistanceMeters = 100.0,
            dynamicLookaheadMeters = 60.0
        )

        assertEquals(listOf("test:dynamic"), updates.map { it.declarationId })
    }

    @Test
    fun `unchanged active declaration stays idle until explicitly dirtied`() {
        val scheduler = scheduler(
            declaration("test:idle", secondRail, ViewDirection.BA, 150.0, 60.0)
        )
        val perception = listOf(occurrence(1, secondRail, ViewDirection.BA))

        assertEquals(1, scheduler.updatesFor(perception, 100.0, 0.0).size)
        assertEquals(emptyList<InspectionDeclaration>(), scheduler.updatesFor(perception, 110.0, 0.0))
        scheduler.markDirty(listOf("test:idle"))
        assertEquals(listOf("test:idle"), scheduler.updatesFor(perception, 120.0, 0.0).map { it.declarationId })
        assertEquals(emptyList<InspectionDeclaration>(), scheduler.updatesFor(perception, 125.0, 0.0))
    }

    @Test
    fun `dirty declaration outside the range waits until activation`() {
        val scheduler = scheduler(
            declaration("test:later", secondRail, ViewDirection.BA, 150.0, 20.0)
        )
        val perception = listOf(occurrence(1, secondRail, ViewDirection.BA))
        scheduler.markDirty(listOf("test:later"))

        assertEquals(emptyList<InspectionDeclaration>(), scheduler.updatesFor(perception, 100.0, 0.0))
        assertEquals(listOf("test:later"), scheduler.updatesFor(perception, 135.0, 0.0).map { it.declarationId })
    }

    @Test
    fun `leaving and reentering the range activates again`() {
        val scheduler = scheduler(
            declaration("test:reenter", firstRail, ViewDirection.AB, 50.0, 20.0)
        )
        val perception = listOf(occurrence(0, firstRail, ViewDirection.AB))

        assertEquals(1, scheduler.updatesFor(perception, 35.0, 0.0).size)
        assertEquals(0, scheduler.updatesFor(perception, 0.0, 0.0).size)
        assertEquals(1, scheduler.updatesFor(perception, 35.0, 0.0).size)
    }

    @Test
    fun `binding replacement resets activation without accepting unknown dirty IDs`() {
        val scheduler = scheduler(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 20.0)
        )
        val perception = listOf(occurrence(0, firstRail, ViewDirection.AB))
        assertEquals(1, scheduler.updatesFor(perception, 35.0, 0.0).size)
        assertThrows(IllegalArgumentException::class.java) {
            scheduler.markDirty(listOf("test:missing"))
        }

        scheduler.replaceBinding(
            compile(declaration("test:replacement", firstRail, ViewDirection.AB, 50.0, 20.0))
        )

        assertEquals(
            listOf("test:replacement"),
            scheduler.updatesFor(perception, 35.0, 0.0).map { it.declarationId }
        )
    }

    @Test
    fun `scheduler rejects perception occurrences outside frozen route order`() {
        val scheduler = scheduler(
            declaration("test:first", firstRail, ViewDirection.AB, 50.0, 300.0),
            declaration("test:second", firstRail, ViewDirection.AB, 250.0, 300.0)
        )

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.updatesFor(
                listOf(
                    occurrence(2, firstRail, ViewDirection.AB),
                    occurrence(0, firstRail, ViewDirection.AB)
                ),
                currentHeadRouteDistanceMeters = 0.0,
                dynamicLookaheadMeters = 0.0
            )
        }
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
        inspectionDistance: Double
    ) = InspectionDeclaration(id, railId, facing, triggerDistance, inspectionDistance)

    private fun occurrence(index: Int, railId: UUID, facing: ViewDirection) =
        InspectionPerceptionOccurrence(index, railId, facing)

    private fun segment(railId: UUID, facing: ViewDirection) =
        RouteSegmentSnapshot(railId, facing, 0.0, 100.0)
}
