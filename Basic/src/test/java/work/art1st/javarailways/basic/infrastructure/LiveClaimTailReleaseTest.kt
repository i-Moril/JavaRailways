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
    fun `append order is rejected before registry mutation`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 20.0)))

        val result = assertInstanceOf(
            LiveClaimAppendResult.Rejected::class.java,
            fixture.release.beginAppend(8L, listOf(entry(delta, 10.0)))
        )

        assertTrue(result.detail.contains("precedes"))
        assertEquals(listOf(established), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `only one claim append may be pending`() {
        val established = interval(0.0, 10.0)
        val firstDelta = interval(20.0, 30.0)
        val secondDelta = interval(40.0, 50.0)
        val fixture = fixture(listOf(entry(established, 10.0)))

        assertInstanceOf(
            LiveClaimAppendResult.Reserved::class.java,
            fixture.release.beginAppend(8L, listOf(entry(firstDelta, 20.0)))
        )
        val second = assertInstanceOf(
            LiveClaimAppendResult.Rejected::class.java,
            fixture.release.beginAppend(8L, listOf(entry(secondDelta, 30.0)))
        )

        assertTrue(second.detail.contains("already pending"))
        assertEquals(listOf(established, firstDelta), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `established prefix continues releasing while append is pending`() {
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        val delta = interval(40.0, 50.0)
        val fixture = fixture(listOf(entry(first, 10.0), entry(second, 20.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 30.0)))

        val shrunk = assertInstanceOf(
            LiveClaimTailReleaseResult.Shrunk::class.java,
            fixture.release.advance(10.0)
        )

        assertEquals(listOf(first), shrunk.releasedResources)
        assertEquals(listOf(second), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(second, delta), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `empty established queue remains live while append is pending`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))

        val cleared = assertInstanceOf(
            LiveClaimTailReleaseResult.AwaitingAppend::class.java,
            fixture.release.advance(10.0)
        )
        val waiting = assertInstanceOf(
            LiveClaimTailReleaseResult.AwaitingAppend::class.java,
            fixture.release.advance(15.0)
        )

        assertEquals(listOf(established), cleared.releasedResources)
        assertEquals(1, cleared.pendingResourceCount)
        assertEquals(emptyList<InfrastructureResource>(), waiting.releasedResources)
        assertEquals(1, waiting.pendingResourceCount)
        assertTrue(fixture.release.remainingEntries.isEmpty())
        assertEquals(listOf(delta), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `finalized append revives an emptied queue without changing owner`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))
        fixture.release.advance(10.0)

        val finalized = assertInstanceOf(
            LiveClaimAppendResult.Finalized::class.java,
            fixture.release.finalizeAppend()
        )

        assertEquals(1, finalized.remainingResourceCount)
        assertEquals(listOf(delta), fixture.release.remainingEntries.map { it.resource })
        assertEquals(claimOwner, fixture.singleClaim().owner)
        assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(20.0)
        )
    }

    @Test
    fun `finalized append follows the still-held established suffix`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))

        val finalized = assertInstanceOf(
            LiveClaimAppendResult.Finalized::class.java,
            fixture.release.finalizeAppend()
        )

        assertEquals(2, finalized.remainingResourceCount)
        assertEquals(
            listOf(established, delta),
            fixture.release.remainingEntries.map { it.resource }
        )
        assertEquals(listOf(established, delta), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `append rollback preserves only the established suffix after partial release`() {
        val first = interval(0.0, 10.0)
        val second = interval(20.0, 30.0)
        val delta = interval(40.0, 50.0)
        val fixture = fixture(listOf(entry(first, 10.0), entry(second, 20.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 30.0)))
        fixture.release.advance(10.0)

        val rolledBack = assertInstanceOf(
            LiveClaimAppendResult.RolledBack::class.java,
            fixture.release.rollbackAppend()
        )

        assertEquals(false, rolledBack.completed)
        assertEquals(listOf(second), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(second), fixture.singleClaim().footprint.resources)
    }

    @Test
    fun `append rollback completes lifecycle after all established resources release`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))
        fixture.release.advance(10.0)

        val rolledBack = assertInstanceOf(
            LiveClaimAppendResult.RolledBack::class.java,
            fixture.release.rollbackAppend()
        )

        assertTrue(rolledBack.completed)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(10.0)
        )
    }

    @Test
    fun `append finalize registry mismatch latches lifecycle fail closed`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))
        fixture.registry.close()

        val failed = assertInstanceOf(
            LiveClaimAppendResult.FailedClosed::class.java,
            fixture.release.finalizeAppend()
        ).failure

        assertEquals(TailReleaseFailureKind.CLAIM_APPEND_FAILED, failed.kind)
        assertEquals(failed, fixture.release.advance(10.0))
        assertEquals(listOf(established), fixture.release.remainingEntries.map { it.resource })
    }

    @Test
    fun `append rollback registry mismatch latches lifecycle fail closed`() {
        val established = interval(0.0, 10.0)
        val delta = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(established, 10.0)))
        fixture.release.beginAppend(8L, listOf(entry(delta, 20.0)))
        fixture.registry.close()

        val failed = assertInstanceOf(
            LiveClaimAppendResult.FailedClosed::class.java,
            fixture.release.rollbackAppend()
        ).failure

        assertEquals(TailReleaseFailureKind.CLAIM_APPEND_FAILED, failed.kind)
        assertEquals(InfrastructureMutationStatus.NOT_FOUND, failed.mutationStatus)
        assertEquals(failed, fixture.release.advance(10.0))
        assertEquals(listOf(established), fixture.release.remainingEntries.map { it.resource })
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
