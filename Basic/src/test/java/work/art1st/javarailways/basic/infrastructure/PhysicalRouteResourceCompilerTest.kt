package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import java.util.UUID

class PhysicalRouteResourceCompilerTest {
    @Test
    fun `physical floor compiles rails turnouts and diamond without a protection region`() {
        val revision = 7L
        val firstTrunk = UUID(0L, 41L)
        val middle = UUID(0L, 42L)
        val firstBranch = UUID(0L, 43L)
        val secondTrunk = UUID(0L, 44L)
        val east = UUID(0L, 45L)
        val secondBranch = UUID(0L, 46L)
        val clearance = UUID(0L, 47L)
        val crossingRail = UUID(0L, 48L)
        val topology = topologyState(
            revision,
            listOf(
                firstTrunk,
                middle,
                firstBranch,
                secondTrunk,
                east,
                secondBranch,
                clearance,
                crossingRail
            ),
            listOf(
                endpoint(firstTrunk, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
                endpoint(firstTrunk, RailEndpoint.B) to endpoint(firstBranch, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(secondTrunk, RailEndpoint.A),
                endpoint(secondTrunk, RailEndpoint.B) to endpoint(east, RailEndpoint.A),
                endpoint(secondTrunk, RailEndpoint.B) to endpoint(secondBranch, RailEndpoint.A),
                endpoint(east, RailEndpoint.B) to endpoint(clearance, RailEndpoint.A)
            )
        )
        val route = RouteSnapshot(
            "test:route/physical-floor",
            1L,
            revision,
            listOf(firstTrunk, middle, secondTrunk, east, clearance).map { railId ->
                RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 10.0)
            }
        )
        val projection = assertInstanceOf(
            TopologyRouteProjectionResult.Projected::class.java,
            TopologyRouteProjector.project(route, topology)
        ).route
        val diamond = StableInfrastructureResource("javarailways:diamond", "test:diamond/main")
        val routeBoundResources = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            routeBoundResources.publish(
                topology,
                listOf(
                    RouteBoundResourceBinding(
                        diamond,
                        listOf(
                            RailSliceRef(middle, 2.0, 3.0),
                            RailSliceRef(crossingRail, 4.0, 5.0)
                        )
                    )
                )
            )
        )

        val compiled = assertInstanceOf(
            PhysicalRouteCompilation.Compiled::class.java,
            PhysicalRouteResourceCompiler(routeBoundResources).compile(
                projection,
                PhysicalRouteRange(5.0, 45.0)
            )
        )

        val firstTurnout = TurnoutResourceIdentity.fromPorts(
            listOf(
                endpoint(firstTrunk, RailEndpoint.B),
                endpoint(middle, RailEndpoint.A),
                endpoint(firstBranch, RailEndpoint.A)
            )
        )
        val secondTurnout = TurnoutResourceIdentity.fromPorts(
            listOf(
                endpoint(secondTrunk, RailEndpoint.B),
                endpoint(east, RailEndpoint.A),
                endpoint(secondBranch, RailEndpoint.A)
            )
        )
        assertEquals(
            setOf(
                RailIntervalResource(firstTrunk, 5.0, 10.0),
                RailIntervalResource(middle, 0.0, 10.0),
                RailIntervalResource(secondTrunk, 0.0, 10.0),
                RailIntervalResource(east, 0.0, 10.0),
                RailIntervalResource(clearance, 0.0, 5.0),
                firstTurnout,
                secondTurnout,
                diamond
            ),
            compiled.admission.resources.resources.toSet()
        )
        assertEquals(
            listOf(firstTurnout, secondTurnout),
            compiled.admission.preparations.map(InfrastructurePreparationRequirement::resource)
        )
        assertEquals(setOf(firstTurnout, secondTurnout, diamond), compiled.controlledResources)
        assertEquals(
            RailInterval(crossingRail, 4.0, 5.0),
            compiled.admission.occupancyGeometry.intervals.single { it.railId == crossingRail }
        )
        assertEquals(
            compiled.admission.resources.resources.toSet(),
            compiled.admission.tailReleaseEntries.map(ResolvedTailReleaseEntry::resource).toSet()
        )
        assertEquals(
            13.0,
            compiled.admission.tailReleaseEntries.single { it.resource == diamond }.tailClearRouteMeters
        )

        val block = StableInfrastructureResource("test:block", "east")
        val descriptor = ResourcePreparationDescriptor("test:prepare", emptyMap())
        val contributions = listOf(
            ContributedRouteResource(diamond, RouteBoundary(20.0)),
            ContributedRouteResource(block, RouteBoundary(44.0), descriptor)
        )
        val validated = ArrayList<StableInfrastructureResource>()
        val expanded = assertInstanceOf(
            RouteContributionsCompilation.Compiled::class.java,
            compiled.admission.withContributions(contributions) { contribution ->
                validated += contribution.resource
                null
            }
        ).admission
        assertEquals(listOf(diamond, block), validated)
        assertEquals(compiled.admission.resources.resources.toSet() + block, expanded.resources.resources.toSet())
        assertEquals(
            compiled.admission.preparations + ResolvedContributedResourcePreparation(block, descriptor),
            expanded.preparations
        )
        assertEquals(compiled.admission.occupancyGeometry, expanded.occupancyGeometry)
        assertEquals(20.0, expanded.tailReleaseEntries.single { it.resource == diamond }.tailClearRouteMeters)
        assertEquals(44.0, expanded.tailReleaseEntries.single { it.resource == block }.tailClearRouteMeters)
        assertEquals(13.0, compiled.admission.tailReleaseEntries.single { it.resource == diamond }.tailClearRouteMeters)

        assertSame(
            compiled.admission,
            assertInstanceOf(
                RouteContributionsCompilation.Compiled::class.java,
                compiled.admission.withContributions(emptyList()) { error("no contribution to validate") }
            ).admission
        )
        assertEquals(
            13.0,
            assertInstanceOf(
                RouteContributionsCompilation.Compiled::class.java,
                compiled.admission.withContributions(listOf(
                    ContributedRouteResource(diamond, RouteBoundary(10.0))
                )) { null }
            ).admission.tailReleaseEntries.single { it.resource == diamond }.tailClearRouteMeters
        )

        val denied = RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "unregistered block")
        assertEquals(
            RouteContributionsCompilation.Rejected(denied),
            compiled.admission.withContributions(contributions) { contribution ->
                if (contribution.resource == block) denied else null
            }
        )
        assertEquals(compiled.admission.resources.resources.toSet(), compiled.admission.tailReleaseEntries
            .map(ResolvedTailReleaseEntry::resource).toSet())
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            assertInstanceOf(
                RouteContributionsCompilation.Rejected::class.java,
                compiled.admission.withContributions(listOf(contributions[0], contributions[0])) { null }
            ).failure.code
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            assertInstanceOf(
                RouteContributionsCompilation.Rejected::class.java,
                compiled.admission.withContributions(listOf(
                    ContributedRouteResource(diamond, RouteBoundary(20.0), descriptor)
                )) { null }
            ).failure.code
        )
    }
}
