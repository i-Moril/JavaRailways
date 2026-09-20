package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ExitClearanceEnvelope
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogStatus
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

class ProtectedRouteRequestValidatorTest {
    private val revision = 7L

    @Test
    fun `forward throat route validates to clipped rail intervals and one stable turnout resource`() {
        val fixture = throatFixture()
        val route = route(
            fixture,
            segment(fixture.trunk, ViewDirection.AB),
            segment(fixture.straight, ViewDirection.AB),
            segment(fixture.straightClearance, ViewDirection.AB)
        )
        val request = request(
            route,
            window(
                entry = boundary("test:boundary/entry", fixture.trunk, ViewDirection.AB, 5.0, 5.0),
                exit = boundary("test:boundary/straight", fixture.straight, ViewDirection.AB, 5.0, 15.0),
                clear = boundary(
                    "test:boundary/clear",
                    fixture.straightClearance,
                    ViewDirection.AB,
                    5.0,
                    25.0
                )
            )
        )

        val validated = assertInstanceOf(
            ProtectedRouteValidation.Validated::class.java,
            fixture.validator.validate(request)
        )

        assertEquals(
            setOf(
                RailIntervalResource(fixture.trunk, 5.0, 10.0),
                RailIntervalResource(fixture.straight, 0.0, 10.0),
                RailIntervalResource(fixture.straightClearance, 0.0, 5.0)
            ),
            validated.resources.resources.filterIsInstance<RailIntervalResource>().toSet()
        )
        val turnout = validated.resources.resources.filterIsInstance<StableInfrastructureResource>().single()
        assertEquals(TurnoutResourceIdentity.TYPE_ID, turnout.typeId)
    }

    @Test
    fun `reverse throat route preserves canonical AB intervals and turnout resource identity`() {
        val fixture = throatFixture()
        val forward = fixture.validator.validate(
            request(
                route(
                    fixture,
                    segment(fixture.trunk, ViewDirection.AB),
                    segment(fixture.branch, ViewDirection.AB),
                    segment(fixture.branchClearance, ViewDirection.AB)
                ),
                window(
                    boundary("test:boundary/entry", fixture.trunk, ViewDirection.AB, 5.0, 5.0),
                    boundary("test:boundary/branch", fixture.branch, ViewDirection.AB, 5.0, 15.0),
                    boundary("test:boundary/clear", fixture.branchClearance, ViewDirection.AB, 5.0, 25.0)
                )
            )
        ) as ProtectedRouteValidation.Validated
        val reverse = assertInstanceOf(
            ProtectedRouteValidation.Validated::class.java,
            fixture.validator.validate(
                request(
                    route(
                        fixture,
                        segment(fixture.branch, ViewDirection.BA),
                        segment(fixture.trunk, ViewDirection.BA),
                        segment(fixture.west, ViewDirection.BA)
                    ),
                    window(
                        boundary("test:boundary/branch", fixture.branch, ViewDirection.BA, 5.0, 5.0),
                        boundary("test:boundary/entry", fixture.trunk, ViewDirection.BA, 5.0, 15.0),
                        boundary("test:boundary/clear", fixture.west, ViewDirection.BA, 5.0, 25.0)
                    )
                )
            )
        )

        assertEquals(
            setOf(
                RailIntervalResource(fixture.branch, 0.0, 5.0),
                RailIntervalResource(fixture.trunk, 0.0, 10.0),
                RailIntervalResource(fixture.west, 5.0, 10.0)
            ),
            reverse.resources.resources.filterIsInstance<RailIntervalResource>().toSet()
        )
        assertEquals(
            forward.resources.resources.filterIsInstance<StableInfrastructureResource>().single(),
            reverse.resources.resources.filterIsInstance<StableInfrastructureResource>().single()
        )
    }

    @Test
    fun `turnout before the authored entry is rejected as unprotected`() {
        val fixture = throatFixture()
        val route = route(
            fixture,
            segment(fixture.trunk, ViewDirection.AB),
            segment(fixture.straight, ViewDirection.AB),
            segment(fixture.straightClearance, ViewDirection.AB)
        )
        val downstreamWindow = window(
            entry = boundary("test:boundary/downstream-entry", fixture.straight, ViewDirection.AB, 2.0, 12.0),
            exit = boundary("test:boundary/downstream-exit", fixture.straight, ViewDirection.AB, 8.0, 18.0),
            clear = boundary(
                "test:boundary/clear",
                fixture.straightClearance,
                ViewDirection.AB,
                5.0,
                25.0
            ),
            region = ProtectionRegionRef("test:region/downstream", 1L)
        )
        val validator = validator(
            fixture.topology,
            ProtectionRegionDefinition(
                ProtectionRegionRef("test:region/downstream", 1L),
                revision,
                listOf(
                    authored("test:boundary/downstream-entry", fixture.straight, ViewDirection.AB, 2.0),
                    authored("test:boundary/downstream-exit", fixture.straight, ViewDirection.BA, 8.0)
                )
            )
        )

        val rejected = assertInstanceOf(
            ProtectedRouteValidation.Rejected::class.java,
            validator.validate(request(route, downstreamWindow))
        )

        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, rejected.status)
    }

    @Test
    fun `turnout in exit clearance is rejected as unprotected`() {
        val fixture = throatFixture()
        val route = route(
            fixture,
            segment(fixture.trunk, ViewDirection.AB),
            segment(fixture.straight, ViewDirection.AB)
        )
        val upstreamRegion = ProtectionRegionRef("test:region/upstream", 1L)
        val validator = validator(
            fixture.topology,
            ProtectionRegionDefinition(
                upstreamRegion,
                revision,
                listOf(
                    authored("test:boundary/upstream-entry", fixture.trunk, ViewDirection.AB, 2.0),
                    authored("test:boundary/upstream-exit", fixture.trunk, ViewDirection.BA, 8.0)
                )
            )
        )
        val upstreamWindow = window(
            entry = boundary("test:boundary/upstream-entry", fixture.trunk, ViewDirection.AB, 2.0, 2.0),
            exit = boundary("test:boundary/upstream-exit", fixture.trunk, ViewDirection.AB, 8.0, 8.0),
            clear = boundary("test:boundary/clear", fixture.straight, ViewDirection.AB, 5.0, 15.0),
            region = upstreamRegion
        )

        val rejected = assertInstanceOf(
            ProtectedRouteValidation.Rejected::class.java,
            validator.validate(request(route, upstreamWindow))
        )

        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, rejected.status)
    }

    @Test
    fun `route without a protection window is allowed only when it has no turnout`() {
        val straight = UUID(0L, 20L)
        val topology = lineTopologyState(revision, straight)
        val validator = ProtectedRouteRequestValidator(ProtectionRegionCatalog { topology })
        val plain = validator.validate(
            request(RouteSnapshot("test:route/plain", 1L, revision, listOf(segment(straight, ViewDirection.AB))), null)
        )
        val fixture = throatFixture()
        val controlled = fixture.validator.validate(
            request(
                route(
                    fixture,
                    segment(fixture.trunk, ViewDirection.AB),
                    segment(fixture.straight, ViewDirection.AB)
                ),
                null
            )
        )

        assertEquals(ProtectedRouteValidation.NotRequired, plain)
        val rejected = assertInstanceOf(ProtectedRouteValidation.Rejected::class.java, controlled)
        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, rejected.status)
    }

    private fun throatFixture(): ThroatFixture {
        val west = UUID(0L, 1L)
        val trunk = UUID(0L, 2L)
        val straight = UUID(0L, 3L)
        val branch = UUID(0L, 4L)
        val straightClearance = UUID(0L, 5L)
        val branchClearance = UUID(0L, 6L)
        val topology = topologyState(
            revision,
            listOf(west, trunk, straight, branch, straightClearance, branchClearance),
            listOf(
                endpoint(west, RailEndpoint.B) to endpoint(trunk, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A),
                endpoint(straight, RailEndpoint.B) to endpoint(straightClearance, RailEndpoint.A),
                endpoint(branch, RailEndpoint.B) to endpoint(branchClearance, RailEndpoint.A)
            )
        )
        val validator = validator(
            topology,
            ProtectionRegionDefinition(
                ProtectionRegionRef("test:region/main", 1L),
                revision,
                listOf(
                    authored("test:boundary/entry", trunk, ViewDirection.AB, 5.0),
                    authored("test:boundary/straight", straight, ViewDirection.BA, 5.0),
                    authored("test:boundary/branch", branch, ViewDirection.BA, 5.0)
                )
            )
        )
        return ThroatFixture(
            west,
            trunk,
            straight,
            branch,
            straightClearance,
            branchClearance,
            topology,
            validator
        )
    }

    private fun validator(
        topology: TopologyState,
        vararg definitions: ProtectionRegionDefinition
    ): ProtectedRouteRequestValidator {
        val catalog = ProtectionRegionCatalog { topology }
        val provider = object : ProtectionRegionProvider {
            override val providerId: String = "test:provider/main"

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> =
                definitions.asList()
        }
        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, catalog.register(provider).status)
        return ProtectedRouteRequestValidator(catalog)
    }

    private fun request(route: RouteSnapshot, window: ProtectedRouteWindow?) = ProtectedRouteRequest(
        "test:request/main",
        InfrastructureOwner("test:route/main", 1L),
        "train",
        route,
        window
    )

    private fun route(fixture: ThroatFixture, vararg segments: RouteSegmentSnapshot) =
        RouteSnapshot("test:route/main", 1L, fixture.topology.revision, segments.asList())

    private fun segment(railId: UUID, facing: ViewDirection) =
        RouteSegmentSnapshot(railId, facing, 0.0, 10.0)

    private fun authored(
        id: String,
        railId: UUID,
        insideFacing: ViewDirection,
        canonicalMeters: Double
    ) = ProtectionBoundaryDefinition(
        id,
        RailPositionSnapshot(railId, insideFacing, canonicalMeters)
    )

    private fun window(
        entry: DirectedRouteBoundary,
        exit: DirectedRouteBoundary,
        clear: DirectedRouteBoundary,
        region: ProtectionRegionRef = ProtectionRegionRef("test:region/main", 1L)
    ) = ProtectedRouteWindow(region, entry, exit, ExitClearanceEnvelope(clear))

    private fun boundary(
        id: String,
        railId: UUID,
        facing: ViewDirection,
        canonicalMeters: Double,
        routeMeters: Double
    ) = DirectedRouteBoundary(
        id,
        RailPositionSnapshot(railId, facing, canonicalMeters),
        routeMeters
    )

    private data class ThroatFixture(
        val west: UUID,
        val trunk: UUID,
        val straight: UUID,
        val branch: UUID,
        val straightClearance: UUID,
        val branchClearance: UUID,
        val topology: TopologyState,
        val validator: ProtectedRouteRequestValidator
    )
}
