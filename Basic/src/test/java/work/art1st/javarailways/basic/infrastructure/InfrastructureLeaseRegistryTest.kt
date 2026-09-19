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
