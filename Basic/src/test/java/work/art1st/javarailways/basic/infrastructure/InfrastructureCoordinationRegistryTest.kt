package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencies
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.UUID

class InfrastructureCoordinationRegistryTest {
    private val railId = UUID.randomUUID()
    private var topologyRevision = 4L
    private val validRails = mutableSetOf(railId)
    private val nodeKey = StableTopologyNodeKey(listOf(RailEndpointRef(railId, RailEndpoint.B)))
    private val validNodes = mutableSetOf(nodeKey)
    private val runtimeResource = StableInfrastructureResource("test:signal", "home")
    private val registry = InfrastructureCoordinationRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = {
            it == runtimeResource || it is RailIntervalResource && it.railId in validRails
        },
        railExists = validRails::contains,
        topologyNodeExists = validNodes::contains
    )

    @Test
    fun `applied plan dependencies overlap without claiming runtime resources`() {
        val firstPlatform = Any()
        val secondPlatform = Any()
        val first = owner("test:first", 1)
        val second = owner("test:second", 2)

        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            registry.setAppliedPlanDependencies(firstPlatform, dependencies(first)).status
        )
        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            registry.setAppliedPlanDependencies(secondPlatform, dependencies(second)).status
        )
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(0.0, 20.0))
            ).status
        )

        val replacement = owner("test:first", 2)
        assertEquals(
            AppliedPlanDependencyMutationStatus.REPLACED,
            registry.setAppliedPlanDependencies(firstPlatform, dependencies(replacement)).status
        )
        assertEquals(setOf(replacement, second), registry.appliedPlanDependencySnapshots().map { it.owner }.toSet())
    }

    @Test
    fun `edit diagnostics preserve independent rail node and resource intersections`() {
        registry.setAppliedPlanDependencies(Any(), dependencies(owner("test:plan", 1)))
        registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(0.0, 10.0))
        )
        val denied = registry.acquireEditPermit(
            Any(),
            TopologyEditRequest(4L, owner("test:editor", 1), editImpact())
        ).decision

        assertEquals(
            TopologyEditStatus.CONFLICT,
            denied.status
        )
        assertEquals(
            setOf(TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY, TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM),
            denied.blockers.map { it.kind }.toSet()
        )
        assertEquals(
            setOf(railId),
            denied.blockers.single { it.kind == TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY }.overlap.railIds
        )
        assertEquals(
            setOf(nodeKey),
            denied.blockers.single { it.kind == TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY }
                .overlap.topologyNodeKeys
        )
        assertEquals(
            setOf(runtimeResource),
            denied.blockers.single { it.kind == TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY }.overlap.resourceIds
        )
    }

    @Test
    fun `held edit permit blocks new dependencies and live claims by category`() {
        val editorPlatform = Any()
        val permit = registry.acquireEditPermit(
            editorPlatform,
            TopologyEditRequest(4L, owner("test:editor", 1), editImpact())
        )
        assertTrue(permit.decision.successful)

        assertEquals(
            AppliedPlanDependencyMutationStatus.EDIT_IN_PROGRESS,
            registry.setAppliedPlanDependencies(Any(), dependencies(owner("test:plan", 1))).status
        )
        val blockedClaim = registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(0.0, 10.0))
        )
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, blockedClaim.status)
        assertEquals(InfrastructureBlockerKind.EDIT_PERMIT, blockedClaim.blockers.single().kind)
        assertTrue(registry.releaseEditPermit(editorPlatform, permit.id))
        assertFalse(registry.releaseEditPermit(editorPlatform, permit.id))
    }

    @Test
    fun `dependencies validate the three categories only when applied`() {
        assertEquals(
            AppliedPlanDependencyMutationStatus.STALE_TOPOLOGY,
            registry.setAppliedPlanDependencies(
                Any(),
                dependencies(owner("test:stale", 1), topologyRevision = 3L)
            ).status
        )
        validNodes.clear()
        assertEquals(
            AppliedPlanDependencyMutationStatus.INVALID_REFERENCE,
            registry.setAppliedPlanDependencies(Any(), dependencies(owner("test:missing", 1))).status
        )
        assertTrue(registry.appliedPlanDependencySnapshots().isEmpty())
    }

    @Test
    fun `topology change during dependency validation rejects before commit`() {
        var revision = 4L
        val changingRegistry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { revision },
            resourceExists = { true },
            railExists = {
                revision = 5L
                true
            },
            topologyNodeExists = { true }
        )

        val result = changingRegistry.setAppliedPlanDependencies(
            Any(),
            dependencies(owner("test:plan", 1))
        )

        assertEquals(AppliedPlanDependencyMutationStatus.STALE_TOPOLOGY, result.status)
        assertTrue(changingRegistry.appliedPlanDependencySnapshots().isEmpty())
    }

    @Test
    fun `owner cleanup keeps applied dependencies until explicit unapply`() {
        val platform = Any()
        registry.setAppliedPlanDependencies(platform, dependencies(owner("test:plan", 1)))
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(20.0, 30.0))
        )
        registry.acquireEditPermit(
            platform,
            TopologyEditRequest(4L, owner("test:edit", 1), editImpact())
        )

        registry.releasePlatformOwner(platform)
        assertEquals(1, registry.appliedPlanDependencySnapshots().size)
        assertTrue(registry.claimSnapshots().isEmpty())
        assertEquals(
            TopologyEditStatus.CONFLICT,
            registry.acquireEditPermit(
                Any(), TopologyEditRequest(4L, owner("test:next", 1), editImpact())
            ).decision.status
        )
        assertEquals(
            AppliedPlanDependencyMutationStatus.REMOVED,
            registry.removeAppliedPlanDependencies(platform, owner("test:plan", 1)).status
        )

        registry.close()
        assertEquals(
            AppliedPlanDependencyMutationStatus.SERVICE_CLOSED,
            registry.setAppliedPlanDependencies(Any(), dependencies(owner("test:late", 1))).status
        )
    }

    @Test
    fun `applied dependencies survive unrelated topology revisions`() {
        val platform = Any()
        val plan = owner("test:plan", 1)
        assertEquals(
            AppliedPlanDependencyMutationStatus.APPLIED,
            registry.setAppliedPlanDependencies(platform, dependencies(plan)).status
        )

        topologyRevision = 5L

        assertEquals(listOf(plan), registry.appliedPlanDependencySnapshots().map { it.owner })
        val edit = registry.acquireEditPermit(
            Any(),
            TopologyEditRequest(5L, owner("test:editor", 1), editImpact())
        ).decision
        assertEquals(TopologyEditStatus.CONFLICT, edit.status)
        assertEquals(
            TopologyEditBlockerKind.APPLIED_PLAN_DEPENDENCY,
            edit.blockers.first().kind
        )
    }

    @Test
    fun `pending admission survives platform cleanup and completed token cannot remove its claim`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val mutation = registry.beginAcquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, footprint(0.0, 10.0))
        )

        assertEquals(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
            registry.releaseClaim(platform, routeOwner).status
        )
        registry.releasePlatformOwner(platform)
        assertEquals(listOf(routeOwner), registry.claimSnapshots().map { it.owner })
        assertTrue(registry.completeClaimMutation(mutation))
        assertFalse(registry.completeClaimMutation(mutation))
        assertEquals(listOf(routeOwner), registry.claimSnapshots().map { it.owner })
    }

    @Test
    fun `registry close invalidates an unfinished admission token`() {
        val mutation = registry.beginAcquireClaim(
            Any(),
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(0.0, 10.0))
        )

        registry.close()

        assertFalse(registry.completeClaimMutation(mutation))
        assertEquals(InfrastructureMutationStatus.NOT_FOUND, registry.rollbackClaimMutation(mutation).status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `claim shrink removes an exact overlapping interval and preserves original order`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val first = interval(0.0, 40.0)
        val overlapping = interval(20.0, 60.0)
        val last = interval(70.0, 80.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(
                4L,
                routeOwner,
                InfrastructureFootprint(listOf(first, overlapping, last))
            )
        )

        val result = registry.shrinkClaim(platform, routeOwner, listOf(overlapping))

        assertEquals(InfrastructureMutationStatus.REPLACED, result.status)
        val snapshot = registry.claimSnapshots().single()
        assertEquals(routeOwner, snapshot.owner)
        assertEquals(listOf(first, last), snapshot.footprint.resources)
        assertEquals(
            InfrastructureMutationStatus.RESOURCE_CONFLICT,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(
                    4L,
                    owner("test:overlapping-route", 1),
                    InfrastructureFootprint(listOf(interval(30.0, 35.0)))
                )
            ).status
        )
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(
                    4L,
                    owner("test:released-overlap", 1),
                    InfrastructureFootprint(listOf(interval(50.0, 55.0)))
                )
            ).status
        )
    }

    @Test
    fun `claim snapshots stay detached across shrink append and rollback`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        val delta = interval(40.0, 50.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(first, second)))
        )
        val before = registry.claimSnapshots().single()

        assertEquals(InfrastructureMutationStatus.REPLACED, registry.shrinkClaim(platform, routeOwner, listOf(first)).status)
        val afterShrink = registry.claimSnapshots().single()
        val append = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(delta))
        assertEquals(InfrastructureMutationStatus.REPLACED, append.result.status)
        val afterAppend = registry.claimSnapshots().single()
        assertEquals(InfrastructureMutationStatus.REPLACED, registry.rollbackClaimAppend(append).status)

        assertEquals(listOf(first, second), before.footprint.resources)
        assertEquals(listOf(second), afterShrink.footprint.resources)
        assertEquals(listOf(second, delta), afterAppend.footprint.resources)
        assertEquals(listOf(second), registry.claimSnapshots().single().footprint.resources)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (afterAppend.footprint.resources as MutableList).clear()
        }
    }

    @Test
    fun `many ordered resources release one by one without losing owner or order`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val resources = (0 until 128).map { index ->
            interval(index * 2.0, index * 2.0 + 1.0)
        }
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                platform,
                InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(resources))
            ).status
        )

        resources.forEachIndexed { index, resource ->
            val expected = if (index == resources.lastIndex) InfrastructureMutationStatus.RELEASED
            else InfrastructureMutationStatus.REPLACED
            assertEquals(expected, registry.shrinkClaim(platform, routeOwner, listOf(resource)).status)
        }

        assertTrue(registry.claimSnapshots().isEmpty())
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                Any(),
                InfrastructureClaimRequest(4L, owner("test:reuse", 1), InfrastructureFootprint(resources))
            ).status
        )
        assertEquals(resources, registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `unchanged claim requires the same resource order after release`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        val last = interval(40.0, 50.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(first, second, last)))
        )
        assertEquals(InfrastructureMutationStatus.REPLACED, registry.shrinkClaim(platform, routeOwner, listOf(first)).status)

        assertEquals(
            InfrastructureMutationStatus.UNCHANGED,
            registry.acquireClaim(
                platform,
                InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(second, last)))
            ).status
        )
        assertEquals(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
            registry.acquireClaim(
                platform,
                InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(last, second)))
            ).status
        )
        assertEquals(listOf(second, last), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink releases the claim when every exact resource is removed`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val resources = listOf(interval(0.0, 10.0), interval(20.0, 30.0))
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(resources))
        )

        assertEquals(
            InfrastructureMutationStatus.RELEASED,
            registry.shrinkClaim(platform, routeOwner, resources.reversed()).status
        )
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `claim shrink rejects missing exact resource without mutation`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val held = interval(0.0, 40.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(held)))
        )

        val result = registry.shrinkClaim(platform, routeOwner, listOf(interval(10.0, 20.0)))

        assertEquals(InfrastructureMutationStatus.NOT_FOUND, result.status)
        assertEquals(listOf(held), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink validates every requested identity before removing any`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(first, second)))
        )

        val result = registry.shrinkClaim(
            platform,
            routeOwner,
            listOf(first, interval(40.0, 50.0))
        )

        assertEquals(InfrastructureMutationStatus.NOT_FOUND, result.status)
        assertEquals(listOf(first, second), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink reports a missing claim without mutation`() {
        val result = registry.shrinkClaim(
            Any(),
            owner("test:missing-route", 1),
            listOf(interval(0.0, 10.0))
        )

        assertEquals(InfrastructureMutationStatus.NOT_FOUND, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `claim shrink rejects wrong owner without mutation`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val held = interval(0.0, 10.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(held)))
        )

        assertEquals(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
            registry.shrinkClaim(Any(), routeOwner, listOf(held)).status
        )
        assertEquals(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
            registry.shrinkClaim(platform, owner("test:route", 2), listOf(held)).status
        )
        assertEquals(routeOwner, registry.claimSnapshots().single().owner)
        assertEquals(listOf(held), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink rejects pending admission without mutation`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val held = interval(0.0, 10.0)
        val mutation = registry.beginAcquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(held)))
        )

        val result = registry.shrinkClaim(platform, routeOwner, listOf(held))

        assertEquals(InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, result.status)
        assertEquals(listOf(held), registry.claimSnapshots().single().footprint.resources)
        assertTrue(registry.completeClaimMutation(mutation))
    }

    @Test
    fun `claim shrink does not revalidate topology or resource providers`() {
        var revision = 4L
        var revisionReads = 0
        var providerCalls = 0
        val independentRegistry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = {
                revisionReads += 1
                revision
            },
            resourceExists = {
                providerCalls += 1
                true
            }
        )
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        independentRegistry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(first, second)))
        )
        revision = 5L
        revisionReads = 0
        providerCalls = 0

        val result = independentRegistry.shrinkClaim(platform, routeOwner, listOf(first))

        assertEquals(InfrastructureMutationStatus.REPLACED, result.status)
        assertEquals(0, revisionReads)
        assertEquals(0, providerCalls)
        assertEquals(listOf(second), independentRegistry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink requires a nonempty unique release collection`() {
        val platform = Any()
        val routeOwner = owner("test:route", 1)
        val held = interval(0.0, 10.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(held)))
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.shrinkClaim(platform, routeOwner, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.shrinkClaim(platform, routeOwner, listOf(held, held))
        }
        assertEquals(listOf(held), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `claim shrink reports closed registry without consulting resource providers`() {
        var providerCalls = 0
        val closedRegistry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { 4L },
            resourceExists = {
                providerCalls += 1
                true
            }
        )
        closedRegistry.close()

        val result = closedRegistry.shrinkClaim(
            Any(),
            owner("test:route", 1),
            listOf(interval(0.0, 10.0))
        )

        assertEquals(InfrastructureMutationStatus.SERVICE_CLOSED, result.status)
        assertEquals(0, providerCalls)
        assertTrue(closedRegistry.claimSnapshots().isEmpty())
    }

    @Test
    fun `claim append conflict leaves the established claim unchanged`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )
        registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(
                4L,
                owner("test:blocker", 1),
                InfrastructureFootprint(listOf(interval(30.0, 40.0)))
            )
        )

        val append = registry.beginAppendClaim(
            platform,
            routeOwner,
            4L,
            listOf(interval(35.0, 45.0))
        )

        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, append.result.status)
        val routeSnapshot = registry.claimSnapshots().single { it.owner == routeOwner }
        assertEquals(listOf(established), routeSnapshot.footprint.resources)
        assertFalse(registry.completeClaimAppend(append))
    }

    @Test
    fun `pending claim append allows established prefix shrink`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        val delta = interval(40.0, 50.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(first, second)))
        )

        val append = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(delta))
        val shrink = registry.shrinkClaim(platform, routeOwner, listOf(first))

        assertEquals(InfrastructureMutationStatus.REPLACED, append.result.status)
        assertEquals(InfrastructureMutationStatus.REPLACED, shrink.status)
        val snapshot = registry.claimSnapshots().single()
        assertEquals(routeOwner, snapshot.owner)
        assertEquals(listOf(second, delta), snapshot.footprint.resources)
    }

    @Test
    fun `finalizing claim append keeps the same owner revision and appended order`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        val firstDelta = interval(20.0, 30.0)
        val secondDelta = interval(40.0, 50.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )

        val append = registry.beginAppendClaim(
            platform,
            routeOwner,
            4L,
            listOf(firstDelta, secondDelta)
        )

        assertTrue(registry.completeClaimAppend(append))
        assertFalse(registry.completeClaimAppend(append))
        val snapshot = registry.claimSnapshots().single()
        assertEquals(routeOwner, snapshot.owner)
        assertEquals(listOf(established, firstDelta, secondDelta), snapshot.footprint.resources)
    }

    @Test
    fun `claim append rollback removes only delta after established prefix shrink`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val releasedPrefix = interval(0.0, 10.0)
        val remainingPrefix = interval(20.0, 30.0)
        val delta = interval(40.0, 50.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(
                4L,
                routeOwner,
                InfrastructureFootprint(listOf(releasedPrefix, remainingPrefix))
            )
        )
        val append = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(delta))
        registry.shrinkClaim(platform, routeOwner, listOf(releasedPrefix))

        val rollback = registry.rollbackClaimAppend(append)

        assertEquals(InfrastructureMutationStatus.REPLACED, rollback.status)
        val snapshot = registry.claimSnapshots().single()
        assertEquals(routeOwner, snapshot.owner)
        assertEquals(listOf(remainingPrefix), snapshot.footprint.resources)
    }

    @Test
    fun `claim append rollback releases claim after every established resource shrinks`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )
        val append = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(delta))

        assertEquals(
            InfrastructureMutationStatus.REPLACED,
            registry.shrinkClaim(platform, routeOwner, listOf(established)).status
        )
        assertEquals(listOf(delta), registry.claimSnapshots().single().footprint.resources)

        assertEquals(InfrastructureMutationStatus.RELEASED, registry.rollbackClaimAppend(append).status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `pending append reports delta only after every established resource is released`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = (0 until 64).map { index -> interval(index * 2.0, index * 2.0 + 1.0) }
        val delta = listOf(interval(200.0, 201.0), interval(202.0, 203.0))
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(established))
        )
        val append = registry.beginAppendClaim(platform, routeOwner, 4L, delta)
        assertEquals(InfrastructureMutationStatus.REPLACED, append.result.status)

        established.forEachIndexed { index, resource ->
            val shrink = registry.shrinkClaimWithEvidence(platform, routeOwner, listOf(resource))
            assertEquals(InfrastructureMutationStatus.REPLACED, shrink.mutation.status)
            assertEquals(index == established.lastIndex, shrink.pendingAppendOnly)
        }
        assertEquals(delta, registry.claimSnapshots().single().footprint.resources)
        assertEquals(InfrastructureMutationStatus.RELEASED, registry.rollbackClaimAppend(append).status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `second claim append is busy while the first delta is pending`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        val firstDelta = interval(20.0, 30.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )
        val firstAppend = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(firstDelta))

        val secondAppend = registry.beginAppendClaim(
            platform,
            routeOwner,
            4L,
            listOf(interval(40.0, 50.0))
        )

        assertEquals(InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, secondAppend.result.status)
        assertEquals(
            listOf(established, firstDelta),
            registry.claimSnapshots().single().footprint.resources
        )
        assertTrue(registry.completeClaimAppend(firstAppend))
    }

    @Test
    fun `claim shrink cannot remove a pending appended resource`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )
        val append = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(delta))

        val shrink = registry.shrinkClaim(platform, routeOwner, listOf(established, delta))

        assertEquals(InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, shrink.status)
        assertEquals(
            listOf(established, delta),
            registry.claimSnapshots().single().footprint.resources
        )
        assertEquals(InfrastructureMutationStatus.REPLACED, registry.rollbackClaimAppend(append).status)
    }

    @Test
    fun `claim append rejects invalid delta identity without mutation`() {
        val platform = Any()
        val routeOwner = owner("test:route", 7)
        val established = interval(0.0, 10.0)
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, routeOwner, InfrastructureFootprint(listOf(established)))
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.beginAppendClaim(platform, routeOwner, 4L, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.beginAppendClaim(platform, routeOwner, 4L, listOf(established, established))
        }
        val alreadyHeld = registry.beginAppendClaim(platform, routeOwner, 4L, listOf(established))

        assertEquals(InfrastructureMutationStatus.INVALID_RESOURCE, alreadyHeld.result.status)
        assertEquals(listOf(established), registry.claimSnapshots().single().footprint.resources)
    }

    @Test
    fun `public footprint cannot be mutated through Java collection view`() {
        val footprint = footprint(0.0, 10.0)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (footprint.resources as MutableList).add(interval(20.0, 30.0))
        }
    }

    private fun owner(id: String, revision: Long) = InfrastructureOwner(id, revision)
    private fun interval(start: Double, end: Double) = RailIntervalResource(railId, start, end)
    private fun footprint(start: Double, end: Double) = InfrastructureFootprint(listOf(interval(start, end)))
    private fun dependencies(owner: InfrastructureOwner, topologyRevision: Long = 4L) =
        AppliedPlanDependencyRequest(
            topologyRevision,
            owner,
            AppliedPlanDependencies(setOf(railId), setOf(nodeKey), setOf(runtimeResource))
        )

    private fun editImpact() = TopologyEditImpact(setOf(railId), setOf(nodeKey), setOf(runtimeResource))
}
