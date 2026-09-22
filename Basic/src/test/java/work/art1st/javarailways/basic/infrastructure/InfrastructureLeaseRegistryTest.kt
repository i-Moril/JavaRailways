package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import java.util.UUID

class InfrastructureLeaseRegistryTest {
    private val railId = UUID.randomUUID()
    private var topologyRevision = 4L
    private val validRails = mutableSetOf(railId)
    private val registry = InfrastructureLeaseRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = { it is RailIntervalResource && it.railId in validRails }
    )

    @Test
    fun `shared leases overlap and replacement is atomic`() {
        val firstPlatform = Any()
        val secondPlatform = Any()
        val first = owner("test:first", 1)
        val second = owner("test:second", 2)

        assertEquals(InfrastructureMutationStatus.ACQUIRED, registry.acquireLease(firstPlatform, lease(first, 0.0, 20.0)).status)
        assertEquals(InfrastructureMutationStatus.ACQUIRED, registry.acquireLease(secondPlatform, lease(second, 10.0, 30.0)).status)

        val staleReplacement = lease(owner("test:first", 2), 40.0, 50.0, revision = 3L)
        assertEquals(
            InfrastructureMutationStatus.STALE_TOPOLOGY,
            registry.replaceLease(firstPlatform, first, staleReplacement).status
        )
        assertEquals(listOf(first, second), registry.leaseSnapshots().map { it.owner })
        assertEquals(interval(0.0, 20.0), registry.leaseSnapshots().first().footprint.resources.single())

        val replacementOwner = owner("test:first", 2)
        assertEquals(
            InfrastructureMutationStatus.REPLACED,
            registry.replaceLease(firstPlatform, first, lease(replacementOwner, 40.0, 50.0)).status
        )
        assertEquals(interval(40.0, 50.0), registry.leaseSnapshots().first().footprint.resources.single())
    }

    @Test
    fun `held edit permit blocks overlapping lease and claim but preserves disjoint concurrency`() {
        val editorPlatform = Any()
        val permit = registry.acquireEditPermit(
            editorPlatform,
            InfrastructureEditRequest(4L, owner("test:editor", 1), footprint(10.0, 20.0))
        )
        assertTrue(permit.result.successful)

        val blockedLease = registry.acquireLease(Any(), lease(owner("test:plan", 1), 15.0, 25.0))
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, blockedLease.status)
        assertEquals(InfrastructureBlockerKind.EDIT_PERMIT, blockedLease.blockers.single().kind)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (blockedLease.blockers as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (blockedLease.blockers.single().overlappingResources as MutableList).clear()
        }
        assertTrue(registry.leaseSnapshots().isEmpty())

        val blockedClaim = registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(12.0, 13.0))
        )
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, blockedClaim.status)
        assertTrue(registry.claimSnapshots().isEmpty())

        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireLease(Any(), lease(owner("test:disjoint", 1), 30.0, 40.0)).status
        )
        assertTrue(registry.releaseEditPermit(editorPlatform, permit.id))
        assertFalse(registry.releaseEditPermit(editorPlatform, permit.id))
    }

    @Test
    fun `edit diagnostics identify every lease and live claim owner revision`() {
        registry.acquireLease(Any(), lease(owner("test:plan-a", 5), 0.0, 20.0))
        registry.acquireLease(Any(), lease(owner("test:plan-b", 8), 10.0, 30.0))
        registry.acquireClaim(
            Any(),
            InfrastructureClaimRequest(4L, owner("test:route", 3), footprint(40.0, 50.0))
        )

        val denied = registry.acquireEditPermit(
            Any(),
            InfrastructureEditRequest(4L, owner("test:editor", 1), InfrastructureFootprint(listOf(
                interval(15.0, 16.0),
                interval(45.0, 46.0)
            )))
        ).result

        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, denied.status)
        assertEquals(
            setOf("test:plan-a" to 5L, "test:plan-b" to 8L, "test:route" to 3L),
            denied.blockers.map { it.owner.ownerId to it.owner.revision }.toSet()
        )
        assertEquals(
            setOf(InfrastructureBlockerKind.APPLIED_PLAN_LEASE, InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM),
            denied.blockers.map { it.kind }.toSet()
        )
    }

    @Test
    fun `stale topology invalid resources and owner mismatch never partially mutate`() {
        assertEquals(
            InfrastructureMutationStatus.STALE_TOPOLOGY,
            registry.acquireLease(Any(), lease(owner("test:stale", 1), 0.0, 10.0, revision = 3L)).status
        )
        validRails.clear()
        assertEquals(
            InfrastructureMutationStatus.INVALID_RESOURCE,
            registry.acquireLease(Any(), lease(owner("test:missing", 1), 0.0, 10.0)).status
        )
        assertTrue(registry.leaseSnapshots().isEmpty())

        validRails += railId
        val platform = Any()
        val held = owner("test:owned", 1)
        registry.acquireLease(platform, lease(held, 0.0, 10.0))
        assertEquals(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
            registry.releaseLease(Any(), held).status
        )
        assertEquals(listOf(held), registry.leaseSnapshots().map { it.owner })
    }

    @Test
    fun `topology change during resource validation rejects before commit`() {
        var revision = 4L
        val changingRegistry = InfrastructureLeaseRegistry(
            currentTopologyRevision = { revision },
            resourceExists = {
                revision = 5L
                true
            }
        )

        val result = changingRegistry.acquireLease(
            Any(),
            InfrastructureLeaseRequest(4L, owner("test:plan", 1), footprint(0.0, 10.0))
        )

        assertEquals(InfrastructureMutationStatus.STALE_TOPOLOGY, result.status)
        assertTrue(changingRegistry.leaseSnapshots().isEmpty())
    }

    @Test
    fun `owner cleanup and close release every held mode`() {
        val platform = Any()
        registry.acquireLease(platform, lease(owner("test:plan", 1), 0.0, 10.0))
        registry.acquireClaim(
            platform,
            InfrastructureClaimRequest(4L, owner("test:route", 1), footprint(20.0, 30.0))
        )
        registry.acquireEditPermit(
            platform,
            InfrastructureEditRequest(4L, owner("test:edit", 1), footprint(40.0, 50.0))
        )

        registry.releasePlatformOwner(platform)
        assertTrue(registry.leaseSnapshots().isEmpty())
        assertTrue(registry.claimSnapshots().isEmpty())
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireEditPermit(
                Any(), InfrastructureEditRequest(4L, owner("test:next", 1), footprint(40.0, 50.0))
            ).result.status
        )

        registry.close()
        assertEquals(
            InfrastructureMutationStatus.SERVICE_CLOSED,
            registry.acquireLease(Any(), lease(owner("test:late", 1), 60.0, 70.0)).status
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
        val independentRegistry = InfrastructureLeaseRegistry(
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
        val closedRegistry = InfrastructureLeaseRegistry(
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
    private fun lease(owner: InfrastructureOwner, start: Double, end: Double, revision: Long = 4L) =
        InfrastructureLeaseRequest(revision, owner, footprint(start, end))
}
