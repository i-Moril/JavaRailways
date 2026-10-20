package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.CompiledTailReleaseQueue
import work.art1st.javarailways.basic.infrastructure.admission.LiveClaimTailRelease
import work.art1st.javarailways.basic.infrastructure.admission.LiveClaimTailReleaseResult
import work.art1st.javarailways.basic.infrastructure.admission.ResolvedTailReleaseEntry
import work.art1st.javarailways.basic.infrastructure.admission.TailReleaseFailureKind

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
    fun `released tail lower bound does not free an overlapping longer held resource`() {
        val short = interval(0.0, 20.0)
        val long = interval(0.0, 60.0)
        val fixture = fixture(listOf(entry(short, 20.0), entry(long, 60.0)))
        fixture.release.advance(25.0)
        assertEquals(20.0, fixture.release.releasedThroughRouteMeters)
        assertEquals(listOf(long), fixture.singleClaim().footprint.resources)
        val competing = fixture.registry.acquireClaim(Any(), InfrastructureClaimRequest(InfrastructureOwner("test:competing", 1L), InfrastructureFootprint(listOf(interval(5.0, 15.0)))))
        assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, competing.status)
        assertEquals(listOf(long), fixture.singleClaim().footprint.resources)
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
            fixture.release.advance(15.0)
        )

        assertEquals(listOf(resource), completed.releasedResources)
        assertEquals(false, completed.alreadyCompleted)
        assertEquals(emptyList<InfrastructureResource>(), repeated.releasedResources)
        assertTrue(repeated.alreadyCompleted)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertTrue(fixture.release.remainingEntries.isEmpty())
        assertEquals(15.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
    }

    @Test
    fun `many sequential releases advance only the queue head and retain ordered suffix`() {
        val resources = (1..64).map { StableInfrastructureResource("test:release", "segment/$it") }
        val fixture = fixture(resources.mapIndexed { index, resource -> entry(resource, (index + 1).toDouble()) })

        resources.forEachIndexed { index, resource ->
            val update = fixture.release.advance((index + 1).toDouble())
            if (index == resources.lastIndex) {
                assertEquals(listOf(resource),
                    assertInstanceOf(LiveClaimTailReleaseResult.Completed::class.java, update).releasedResources)
            } else {
                val shrunk = assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java, update)
                assertEquals(listOf(resource), shrunk.releasedResources)
                assertEquals(resources.size - index - 1, shrunk.remainingResourceCount)
                assertEquals(resources.drop(index + 1), fixture.release.remainingEntries.map { it.resource })
                assertEquals(resources.drop(index + 1), fixture.singleClaim().footprint.resources)
            }
        }
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `extended suffix appends to the active queue and releases in the same order`() {
        val first = interval(0.0, 10.0)
        val suffixResource = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(first, 10.0)))
        val append = fixture.registry.beginAppendClaim(
            platformOwner,
            claimOwner,
            deltaResources = listOf(suffixResource)
        )
        fixture.release.stageExtendedHolds(listOf(entry(first, 10.0)))
        assertTrue(fixture.registry.completeClaimAppend(append))

        val suffix = listOf(entry(suffixResource, 20.0))
        fixture.release.validateExtendedSuffix(suffix)
        fixture.release.installExtendedSuffix(suffix)

        assertEquals(
            listOf(first, suffixResource),
            fixture.release.remainingEntries.map(ResolvedTailReleaseEntry::resource)
        )
        assertInstanceOf(
            LiveClaimTailReleaseResult.Shrunk::class.java,
            fixture.release.advance(10.0)
        )
        assertEquals(listOf(suffixResource), fixture.singleClaim().footprint.resources)
        assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(20.0)
        )
    }

    @Test
    fun `extended suffix keeps only the unpassed queue tail after partial release`() {
        val first = interval(0.0, 10.0)
        val second = interval(10.0, 20.0)
        val suffix = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(first, 10.0), entry(second, 20.0)))
        assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java, fixture.release.advance(10.0))
        val append = fixture.registry.beginAppendClaim(
            platformOwner, claimOwner, deltaResources = listOf(suffix)
        )
        fixture.release.stageExtendedHolds(listOf(entry(second, 20.0)))
        assertTrue(fixture.registry.completeClaimAppend(append))

        val extended = listOf(entry(suffix, 30.0))
        fixture.release.validateExtendedSuffix(extended)
        fixture.release.installExtendedSuffix(extended)

        assertEquals(listOf(second, suffix), fixture.release.remainingEntries.map { it.resource })
        assertEquals(listOf(second, suffix), fixture.singleClaim().footprint.resources)
        assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java, fixture.release.advance(20.0))
        assertInstanceOf(LiveClaimTailReleaseResult.Completed::class.java, fixture.release.advance(30.0))
    }

    @Test
    fun `extended suffix revives an empty queue without resetting accepted progress`() {
        val first = interval(0.0, 10.0)
        val suffixResource = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(first, 10.0)))
        fixture.release.advance(10.0)
        fixture.release.advance(15.0)
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            fixture.registry.acquireClaim(
                platformOwner,
                InfrastructureClaimRequest(
                    claimOwner,
                    InfrastructureFootprint(listOf(suffixResource))
                )
            ).status
        )

        fixture.release.stageExtendedHolds(emptyList())
        val suffix = listOf(entry(suffixResource, 20.0))
        fixture.release.validateExtendedSuffix(suffix)
        fixture.release.installExtendedSuffix(suffix)

        assertEquals(15.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
        assertEquals(listOf(suffixResource), fixture.release.remainingEntries.map { it.resource })
        assertInstanceOf(
            LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.advance(20.0)
        )
    }

    @Test
    fun `extended hold advances its release boundary when the suffix is installed`() {
        val held = interval(0.0, 10.0)
        val suffixResource = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(held, 10.0)))
        val append = fixture.registry.beginAppendClaim(
            platformOwner, claimOwner, listOf(suffixResource)
        )
        fixture.release.stageExtendedHolds(listOf(entry(held, 20.0)))
        assertTrue(fixture.registry.completeClaimAppend(append))

        val suffix = listOf(entry(suffixResource, 30.0))
        fixture.release.validateExtendedSuffix(suffix)
        fixture.release.installExtendedSuffix(suffix)

        assertEquals(listOf(held, suffixResource), fixture.release.remainingEntries.map { it.resource })
        assertInstanceOf(LiveClaimTailReleaseResult.Retained::class.java, fixture.release.advance(15.0))
        assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java, fixture.release.advance(20.0))
        assertEquals(listOf(suffixResource), fixture.singleClaim().footprint.resources)
        assertInstanceOf(LiveClaimTailReleaseResult.Completed::class.java, fixture.release.advance(30.0))
    }

    @Test
    fun `staged later hold stays claimed while other old resources release and safe abort reconciles it`() {
        val ordinary = interval(0.0, 20.0)
        val held = StableInfrastructureResource("test:block", "held")
        val delta = interval(20.0, 40.0)
        val fixture = fixture(listOf(entry(ordinary, 20.0), entry(held, 20.0)))
        fixture.release.validateExtendedHolds(listOf(entry(held, 40.0)))
        val append = fixture.registry.beginAppendClaim(
            platformOwner, claimOwner, listOf(delta)
        )
        fixture.release.stageExtendedHolds(listOf(entry(held, 40.0)))

        assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java,
            fixture.release.advance(21.0))
        assertEquals(listOf(held), fixture.release.remainingEntries.map { it.resource })
        assertEquals(setOf(held, delta), fixture.singleClaim().footprint.resources.toSet())

        assertEquals(InfrastructureMutationStatus.REPLACED, fixture.registry.rollbackClaimAppend(append).status)
        assertInstanceOf(LiveClaimTailReleaseResult.Completed::class.java,
            fixture.release.rollbackExtendedHolds())
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertTrue(fixture.release.resourceQueueEmpty)
    }

    @Test
    fun `invalid extended suffix duplicate or cleared boundary is rejected without local mutation`() {
        val first = interval(0.0, 10.0)
        val outOfOrder = interval(20.0, 30.0)
        val fixture = fixture(listOf(entry(first, 20.0)))
        fixture.release.stageExtendedHolds(listOf(entry(first, 20.0)))

        assertThrows(IllegalArgumentException::class.java) {
            fixture.release.validateExtendedSuffix(listOf(entry(first, 30.0)))
        }
        assertEquals(listOf(first), fixture.release.remainingEntries.map { it.resource })
        assertThrows(IllegalArgumentException::class.java) {
            fixture.release.validateExtendedSuffix(
                listOf(entry(outOfOrder, 10.0), entry(outOfOrder, 15.0))
            )
        }
        assertEquals(listOf(first), fixture.release.remainingEntries.map { it.resource })

        fixture.release.advance(20.0)
        fixture.release.advance(25.0)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.release.validateExtendedSuffix(listOf(entry(outOfOrder, 25.0)))
        }
        assertTrue(fixture.release.resourceQueueEmpty)
        assertEquals(25.0, fixture.release.maximumAcceptedTailRouteProgressMeters)
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
        val registry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { 8L },
            resourceExists = { true }
        )
        val footprint = InfrastructureFootprint(heldResources)
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                platformOwner,
                InfrastructureClaimRequest(claimOwner, footprint)
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
        val registry: InfrastructureCoordinationRegistry,
        val release: LiveClaimTailRelease
    ) {
        fun singleClaim(): InfrastructureClaimSnapshot = registry.claimSnapshots().single()
    }
}
