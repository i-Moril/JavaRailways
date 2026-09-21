package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import java.util.UUID

class LiveClaimTailReleaseTest {
    private val railId = UUID.randomUUID()
    private val platformOwner = Any()
    private val claimOwner = InfrastructureOwner("test:route", 4)

    @Test
    fun `valid progress below the head is retained monotonically`() {
        val first = interval(0.0, 40.0)
        val second = interval(20.0, 60.0)
        val fixture = fixture(listOf(entry(first, 20.0), entry(second, 30.0)))

        val result = assertInstanceOf(
            LiveClaimTailReleaseResult.Retained::class.java,
            fixture.release.advance(19.0)
        )

        assertEquals(19.0, result.maximumAcceptedTailRouteProgressMeters)
        assertEquals(2, result.remainingResourceCount)
        assertEquals(19.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(first, second), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(first, second), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `eligible contiguous prefix shrinks before advancing local state`() {
        val first = interval(0.0, 40.0)
        val overlapping = interval(20.0, 60.0)
        val last = interval(70.0, 80.0)
        val fixture = fixture(
            listOf(entry(first, 20.0), entry(overlapping, 30.0), entry(last, 40.0))
        )

        val result = assertInstanceOf(
            LiveClaimTailReleaseResult.Shrunk::class.java,
            fixture.release.advance(30.0)
        )

        assertEquals(listOf(first, overlapping), result.releasedResources)
        assertEquals(1, result.remainingResourceCount)
        assertEquals(30.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(last), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(last), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `overlapping exact identities remain independent when only the head clears`() {
        val first = interval(0.0, 40.0)
        val overlapping = interval(20.0, 60.0)
        val fixture = fixture(listOf(entry(first, 20.0), entry(overlapping, 30.0)))

        fixture.release.advance(20.0)

        assertEquals(listOf(overlapping), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(overlapping), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `all resources release once and completed calls are idempotent`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(listOf(entry(resource, 10.0)))

        val completed = assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(10.0)
        )
        val repeated = assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(Double.NaN)
        )

        assertEquals(listOf(resource), completed.releasedResources)
        assertEquals(false, completed.alreadyCompleted)
        assertEquals(emptyList<InfrastructureResource>(), repeated.releasedResources)
        assertTrue(repeated.alreadyCompleted)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertTrue(fixture.release.remainingEntries.isEmpty())
        assertEquals(10.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
    }

    @Test
    fun `decreasing progress latches fail closed without changing queue or claim`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(listOf(entry(resource, 10.0)))
        fixture.release.advance(5.0)

        val failed = assertInstanceOf(
            LiveClaimTailReleaseResult.FailedClosed::class.java,
            fixture.release.advance(4.0)
        )
        val repeated = fixture.release.advance(10.0)

        assertEquals(TailReleaseFailureKind.DECREASING_PROGRESS, failed.kind)
        assertEquals(failed, repeated)
        assertEquals(5.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(resource), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(resource), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `invalid initial progress latches fail closed without accepting progress`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(listOf(entry(resource, 10.0)))

        val failed = assertInstanceOf(
            LiveClaimTailReleaseResult.FailedClosed::class.java,
            fixture.release.advance(Double.POSITIVE_INFINITY)
        )

        assertEquals(TailReleaseFailureKind.INVALID_PROGRESS, failed.kind)
        assertEquals(failed, fixture.release.advance(10.0))
        assertNull(fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(resource), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(resource), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `unexpected registry result latches fail closed and preserves local queue`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(listOf(entry(resource, 10.0)), releasePlatformOwner = Any())

        val failed = assertInstanceOf(
            LiveClaimTailReleaseResult.FailedClosed::class.java,
            fixture.release.advance(10.0)
        )

        assertEquals(TailReleaseFailureKind.CLAIM_SHRINK_FAILED, failed.kind)
        assertEquals(InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, failed.mutationStatus)
        assertEquals(false, failed.claimMayHaveChanged)
        assertEquals(failed, fixture.release.advance(10.0))
        assertNull(fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(resource), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(resource), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `unexpected successful shrink status still latches fail closed`() {
        val queued = interval(0.0, 10.0)
        val untracked = interval(20.0, 30.0)
        val fixture = fixture(
            entries = listOf(entry(queued, 10.0)),
            heldResources = listOf(queued, untracked)
        )

        val failed = assertInstanceOf(
            LiveClaimTailReleaseResult.FailedClosed::class.java,
            fixture.release.advance(10.0)
        )

        assertEquals(TailReleaseFailureKind.CLAIM_SHRINK_FAILED, failed.kind)
        assertEquals(InfrastructureMutationStatus.REPLACED, failed.mutationStatus)
        assertTrue(failed.claimMayHaveChanged)
        assertNull(fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(queued), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(untracked), fixture.singleClaim().footprint.resources)
        assertEquals(failed, fixture.release.advance(10.0))
    }

    @Test
    fun `logical owner mismatch latches fail closed without changing claim`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(
            entries = listOf(entry(resource, 10.0)),
            releaseClaimOwner = InfrastructureOwner(claimOwner.ownerId, claimOwner.revision + 1)
        )

        val failed = assertInstanceOf(
            LiveClaimTailReleaseResult.FailedClosed::class.java,
            fixture.release.advance(10.0)
        )

        assertEquals(TailReleaseFailureKind.CLAIM_SHRINK_FAILED, failed.kind)
        assertEquals(InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, failed.mutationStatus)
        assertEquals(false, failed.claimMayHaveChanged)
        assertNull(fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(resource), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(resource), fixture.singleClaim().footprint.resources)
        assertEquals(failed, fixture.release.advance(10.0))
    }

    @Test
    fun `remaining queue views are immutable`() {
        val resource = interval(0.0, 10.0)
        val fixture = fixture(listOf(entry(resource, 10.0)))

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (fixture.release.remainingEntries as MutableList<ResolvedTailReleaseEntry>).clear()
        }
    }

    private fun fixture(
        entries: List<ResolvedTailReleaseEntry>,
        releasePlatformOwner: Any = platformOwner,
        releaseClaimOwner: InfrastructureOwner = claimOwner,
        heldResources: List<InfrastructureResource> = entries.map(ResolvedTailReleaseEntry::resource)
    ): Fixture {
        val registry = InfrastructureLeaseRegistry(
            currentTopologyRevision = { 8L },
            resourceExists = { true }
        )
        val footprint = InfrastructureFootprint(heldResources)
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                platformOwner,
                InfrastructureClaimRequest(8L, claimOwner, footprint)
            ).status
        )
        return Fixture(
            registry,
            LiveClaimTailRelease(
                registry,
                releasePlatformOwner,
                releaseClaimOwner,
                CompiledTailReleaseQueue(entries)
            )
        )
    }

    private fun interval(start: Double, end: Double) = RailIntervalResource(railId, start, end)

    private fun entry(resource: InfrastructureResource, tailClearRouteMeters: Double) =
        ResolvedTailReleaseEntry(resource, tailClearRouteMeters)

    private data class Fixture(
        val registry: InfrastructureLeaseRegistry,
        val release: LiveClaimTailRelease
    ) {
        fun singleClaim(): InfrastructureClaimSnapshot = registry.claimSnapshots().single()
    }
}
