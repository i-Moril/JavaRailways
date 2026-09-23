package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import java.util.UUID
import java.util.Collections

/** Internal route-resource footprint request; public hosts do not expose raw claim mutation. */
data class InfrastructureClaimRequest(
    val topologyRevision: Long,
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
) {
    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}

/**
 * World-local atomic registry. Platform ownership tokens are deliberately opaque and never enter
 * persisted owner identities or public diagnostics.
 */
class InfrastructureLeaseRegistry(
    private val currentTopologyRevision: () -> Long,
    private val resourceExists: (InfrastructureResource) -> Boolean
) {
    class EditPermit internal constructor(val id: UUID, val result: InfrastructureMutationResult)

    /** Opaque handle for undoing only the exact claim mutation that created it. */
    internal class ClaimMutation internal constructor(
        val result: InfrastructureMutationResult,
        internal val rollbackToken: UUID?
    )

    /** Opaque handle for finalizing or undoing one append-only delta reservation. */
    internal class ClaimAppendReservation internal constructor(
        val result: InfrastructureMutationResult,
        internal val token: UUID?
    )

    /** Result of shrinking committed resources while an append reservation may remain. */
    internal data class ClaimShrinkResult(
        val mutation: InfrastructureMutationResult,
        /** True only when every remaining resource is the caller's still-pending append delta. */
        val pendingAppendOnly: Boolean
    )

    private interface Stored {
        val platformOwner: Any
        val owner: InfrastructureOwner
        val resources: Collection<InfrastructureResource>
    }

    private data class Held(
        override val platformOwner: Any,
        override val owner: InfrastructureOwner,
        val footprint: InfrastructureFootprint
    ) : Stored {
        override val resources: Collection<InfrastructureResource> get() = footprint.resources
    }

    /** Only live claims mutate; insertion order is the public footprint order. Access is under [lock]. */
    private class ClaimHeld(
        override val platformOwner: Any,
        override val owner: InfrastructureOwner,
        footprint: InfrastructureFootprint
    ) : Stored {
        override val resources = LinkedHashSet(footprint.resources)

        fun hasFootprint(footprint: InfrastructureFootprint): Boolean {
            if (resources.size != footprint.resources.size) return false
            val held = resources.iterator()
            return footprint.resources.all { held.next() == it }
        }

        fun snapshotFootprint() = InfrastructureFootprint(resources)
    }

    private data class PendingClaimMutation(
        val after: ClaimHeld,
        /** Independent immutable admission state; [after] owns mutable live resources. */
        val expectedFootprint: InfrastructureFootprint
    )

    private data class PendingClaimAppend(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val delta: List<InfrastructureResource>,
        val deltaSet: Set<InfrastructureResource>
    )

    private val lock = Any()
    private val leases = linkedMapOf<String, Held>()
    private val claims = linkedMapOf<String, ClaimHeld>()
    private val pendingClaimMutations = linkedMapOf<UUID, PendingClaimMutation>()
    private val pendingClaimAppends = linkedMapOf<UUID, PendingClaimAppend>()
    private val editPermits = linkedMapOf<UUID, Held>()
    private var closed = false

    fun acquireLease(platformOwner: Any, request: InfrastructureLeaseRequest): InfrastructureMutationResult =
        synchronized(lock) {
            validate(request.topologyRevision, request.footprint)?.let { return@synchronized it }
            leases[request.owner.ownerId]?.let { existing ->
                if (existing.platformOwner === platformOwner && existing.owner == request.owner &&
                    existing.footprint == request.footprint
                ) {
                    return@synchronized result(InfrastructureMutationStatus.UNCHANGED)
                }
                return@synchronized result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "lease owner ${request.owner.ownerId} already has revision ${existing.owner.revision}"
                )
            }
            blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
                .takeIf { it.isNotEmpty() }
                ?.let { return@synchronized conflict(it) }
            leases[request.owner.ownerId] = Held(platformOwner, request.owner, request.footprint)
            result(InfrastructureMutationStatus.ACQUIRED)
        }

    fun replaceLease(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: InfrastructureLeaseRequest
    ): InfrastructureMutationResult = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let { return@synchronized it }
        if (expectedOwner.ownerId != request.owner.ownerId || request.owner.revision <= expectedOwner.revision) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "replacement must keep ownerId and advance its revision"
            )
        }
        val existing = leases[expectedOwner.ownerId]
            ?: return@synchronized result(InfrastructureMutationStatus.NOT_FOUND, "lease does not exist")
        if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "lease owner or expected revision does not match"
            )
        }
        blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { return@synchronized conflict(it) }
        leases[request.owner.ownerId] = Held(platformOwner, request.owner, request.footprint)
        result(InfrastructureMutationStatus.REPLACED)
    }

    fun releaseLease(platformOwner: Any, owner: InfrastructureOwner): InfrastructureMutationResult = synchronized(lock) {
        val existing = leases[owner.ownerId]
            ?: return@synchronized result(InfrastructureMutationStatus.NOT_FOUND, "lease does not exist")
        if (existing.platformOwner !== platformOwner || existing.owner != owner) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "lease owner or expected revision does not match"
            )
        }
        leases.remove(owner.ownerId)
        result(InfrastructureMutationStatus.RELEASED)
    }

    fun acquireClaim(platformOwner: Any, request: InfrastructureClaimRequest): InfrastructureMutationResult =
        acquireClaimMutation(platformOwner, request, rollbackTracked = false).result

    internal fun beginAcquireClaim(
        platformOwner: Any,
        request: InfrastructureClaimRequest
    ): ClaimMutation = acquireClaimMutation(platformOwner, request, rollbackTracked = true)

    /**
     * Atomically reserves a validated resource delta on an established claim.
     *
     * The reservation is immediately exclusive but remains distinguishable from the established
     * prefix until [completeClaimAppend] or [rollbackClaimAppend] consumes its opaque handle.
     */
    internal fun beginAppendClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        topologyRevision: Long,
        deltaResources: Collection<InfrastructureResource>
    ): ClaimAppendReservation {
        val delta = deltaResources.toList()
        require(delta.isNotEmpty()) { "deltaResources must not be empty" }
        require(delta.distinct().size == delta.size) { "deltaResources contains duplicate resources" }

        return synchronized(lock) {
            val footprint = InfrastructureFootprint(delta)
            validate(topologyRevision, footprint)?.let {
                return@synchronized ClaimAppendReservation(it, null)
            }
            if (hasPendingMutation(expectedOwner.ownerId)) {
                return@synchronized ClaimAppendReservation(
                    result(
                        InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                        "claim owner ${expectedOwner.ownerId} already has a mutation in progress"
                    ),
                    null
                )
            }
            val existing = claims[expectedOwner.ownerId]
                ?: return@synchronized ClaimAppendReservation(
                    result(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist"),
                    null
                )
            if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
                return@synchronized ClaimAppendReservation(
                    result(
                        InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                        "claim owner or expected revision does not match"
                    ),
                    null
                )
            }
            val alreadyHeld = delta.firstOrNull { it in existing.resources }
            if (alreadyHeld != null) {
                return@synchronized ClaimAppendReservation(
                    result(
                        InfrastructureMutationStatus.INVALID_RESOURCE,
                        "claim already holds exact resource: $alreadyHeld"
                    ),
                    null
                )
            }
            val blockers = blockers(
                footprint,
                claims.values.filterNot { it === existing },
                InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
            ) + blockers(footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
            if (blockers.isNotEmpty()) {
                return@synchronized ClaimAppendReservation(conflict(blockers), null)
            }

            existing.resources.addAll(delta)
            val token = UUID.randomUUID()
            pendingClaimAppends[token] = PendingClaimAppend(platformOwner, expectedOwner, delta, delta.toHashSet())
            ClaimAppendReservation(result(InfrastructureMutationStatus.REPLACED), token)
        }
    }

    fun releaseClaim(platformOwner: Any, owner: InfrastructureOwner): InfrastructureMutationResult = synchronized(lock) {
        if (hasPendingMutation(owner.ownerId)) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim owner ${owner.ownerId} has an admission transaction in progress"
            )
        }
        val existing = claims[owner.ownerId]
            ?: return@synchronized result(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist")
        if (existing.platformOwner !== platformOwner || existing.owner != owner) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim owner or expected revision does not match"
            )
        }
        claims.remove(owner.ownerId)
        result(InfrastructureMutationStatus.RELEASED)
    }

    /**
     * Atomically removes exact resource identities from an established live claim.
     *
     * This is deliberately removal-only: it does not revalidate topology, query providers, look for
     * blockers, or change infrastructure preparation. Those checks cannot become newly necessary
     * when an existing claim only loses resources.
     */
    internal fun shrinkClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        resourcesToRelease: Collection<InfrastructureResource>
    ): InfrastructureMutationResult = shrinkClaimWithEvidence(
        platformOwner,
        expectedOwner,
        resourcesToRelease
    ).mutation

    internal fun shrinkClaimWithEvidence(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        resourcesToRelease: Collection<InfrastructureResource>
    ): ClaimShrinkResult {
        val requested = resourcesToRelease.toList()
        require(requested.isNotEmpty()) { "resourcesToRelease must not be empty" }
        val releaseSet = requested.toHashSet()
        require(releaseSet.size == requested.size) {
            "resourcesToRelease contains duplicate resources"
        }

        return synchronized(lock) {
            if (closed) {
                return@synchronized shrinkResult(InfrastructureMutationStatus.SERVICE_CLOSED, "registry is closed")
            }
            val existing = claims[expectedOwner.ownerId]
                ?: return@synchronized shrinkResult(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist")
            if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
                return@synchronized shrinkResult(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner or expected revision does not match"
                )
            }
            if (pendingInitialMutationForOwner(expectedOwner.ownerId) != null) {
                return@synchronized shrinkResult(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${expectedOwner.ownerId} has an admission transaction in progress"
                )
            }

            val pendingAppend = pendingAppendForOwner(expectedOwner.ownerId)
            val pendingRelease = requested.firstOrNull { it in pendingAppend?.deltaSet.orEmpty() }
            if (pendingRelease != null) {
                return@synchronized shrinkResult(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "pending appended resource cannot be released: $pendingRelease"
                )
            }

            val heldResources = existing.resources
            val missing = requested.firstOrNull { it !in heldResources }
            if (missing != null) {
                return@synchronized shrinkResult(
                    InfrastructureMutationStatus.NOT_FOUND,
                    "claim does not hold exact resource: $missing"
                )
            }

            releaseSet.forEach(heldResources::remove)
            if (heldResources.isEmpty()) {
                claims.remove(expectedOwner.ownerId)
                return@synchronized ClaimShrinkResult(
                    result(InfrastructureMutationStatus.RELEASED),
                    pendingAppendOnly = false
                )
            }
            ClaimShrinkResult(
                result(InfrastructureMutationStatus.REPLACED),
                pendingAppendOnly = pendingAppend != null && heldResources.size == pendingAppend.delta.size
            )
        }
    }

    private fun shrinkResult(status: InfrastructureMutationStatus, detail: String) =
        ClaimShrinkResult(result(status, detail), pendingAppendOnly = false)

    /** Finalizes a successful admission so its exact-before rollback state can be discarded. */
    internal fun completeClaimMutation(mutation: ClaimMutation): Boolean = synchronized(lock) {
        val token = mutation.rollbackToken ?: return@synchronized true
        val pending = pendingClaimMutations[token] ?: return@synchronized false
        if (!sameHeld(claims[pending.after.owner.ownerId], pending)) return@synchronized false
        pendingClaimMutations.remove(token)
        true
    }

    /** Removes exactly the newly acquired claim after its preparation was safely aborted. */
    internal fun rollbackClaimMutation(mutation: ClaimMutation): InfrastructureMutationResult = synchronized(lock) {
        val token = mutation.rollbackToken
            ?: return@synchronized result(InfrastructureMutationStatus.UNCHANGED)
        val pending = pendingClaimMutations[token]
            ?: return@synchronized result(
                InfrastructureMutationStatus.NOT_FOUND,
                "claim admission rollback token does not exist"
            )
        val current = claims[pending.after.owner.ownerId]
        if (!sameHeld(current, pending)) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim changed while its admission transaction was in progress"
            )
        }
        claims.remove(pending.after.owner.ownerId)
        pendingClaimMutations.remove(token)
        result(InfrastructureMutationStatus.RELEASED)
    }

    /** Commits a pending delta without changing the claim's logical owner revision. */
    internal fun completeClaimAppend(reservation: ClaimAppendReservation): Boolean = synchronized(lock) {
        val token = reservation.token ?: return@synchronized false
        val pending = pendingClaimAppends[token] ?: return@synchronized false
        val current = claims[pending.owner.ownerId] ?: return@synchronized false
        if (current.platformOwner !== pending.platformOwner || current.owner != pending.owner) {
            return@synchronized false
        }
        if (!current.resources.containsAll(pending.delta)) return@synchronized false
        pendingClaimAppends.remove(token)
        true
    }

    /**
     * Removes only this reservation's delta from the claim's then-current footprint.
     * Established prefix resources may already have been removed by tail release.
     */
    internal fun rollbackClaimAppend(
        reservation: ClaimAppendReservation
    ): InfrastructureMutationResult = synchronized(lock) {
        val token = reservation.token
            ?: return@synchronized result(InfrastructureMutationStatus.UNCHANGED)
        val pending = pendingClaimAppends[token]
            ?: return@synchronized result(
                InfrastructureMutationStatus.NOT_FOUND,
                "claim append reservation does not exist"
            )
        val current = claims[pending.owner.ownerId]
            ?: return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim disappeared while its append reservation was pending"
            )
        if (current.platformOwner !== pending.platformOwner || current.owner != pending.owner ||
            !current.resources.containsAll(pending.delta)
        ) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim changed inconsistently while its append reservation was pending"
            )
        }

        pending.deltaSet.forEach(current.resources::remove)
        if (current.resources.isEmpty()) {
            claims.remove(pending.owner.ownerId)
        }
        pendingClaimAppends.remove(token)
        result(
            if (current.resources.isEmpty()) InfrastructureMutationStatus.RELEASED
            else InfrastructureMutationStatus.REPLACED
        )
    }

    fun acquireEditPermit(platformOwner: Any, request: InfrastructureEditRequest): EditPermit = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let { return@synchronized EditPermit(UUID(0L, 0L), it) }
        val blockers = blockers(request.footprint, leases.values, InfrastructureBlockerKind.APPLIED_PLAN_LEASE) +
            blockers(
                request.footprint,
                claims.values,
                InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
            ) +
            blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
        if (blockers.isNotEmpty()) return@synchronized EditPermit(UUID(0L, 0L), conflict(blockers))
        val id = UUID.randomUUID()
        editPermits[id] = Held(platformOwner, request.owner, request.footprint)
        EditPermit(id, result(InfrastructureMutationStatus.ACQUIRED))
    }

    fun releaseEditPermit(platformOwner: Any, permitId: UUID): Boolean = synchronized(lock) {
        val permit = editPermits[permitId] ?: return@synchronized false
        if (permit.platformOwner !== platformOwner) return@synchronized false
        editPermits.remove(permitId)
        true
    }

    fun leaseSnapshots(): List<InfrastructureLeaseSnapshot> = synchronized(lock) {
        Collections.unmodifiableList(leases.values.map { InfrastructureLeaseSnapshot(it.owner, it.footprint) })
    }

    fun claimSnapshots(): List<InfrastructureClaimSnapshot> = synchronized(lock) {
        Collections.unmodifiableList(claims.values.map { InfrastructureClaimSnapshot(it.owner, it.snapshotFootprint()) })
    }

    fun releasePlatformOwner(platformOwner: Any) = synchronized(lock) {
        val pendingOwnerIds = pendingClaimMutations.values.mapTo(hashSetOf()) { it.after.owner.ownerId }
        pendingClaimAppends.values.mapTo(pendingOwnerIds) { it.owner.ownerId }
        leases.values.removeIf { it.platformOwner === platformOwner }
        claims.values.removeIf { held ->
            held.platformOwner === platformOwner && held.owner.ownerId !in pendingOwnerIds
        }
        editPermits.values.removeIf { it.platformOwner === platformOwner }
    }

    fun close() = synchronized(lock) {
        closed = true
        leases.clear()
        claims.clear()
        pendingClaimMutations.clear()
        pendingClaimAppends.clear()
        editPermits.clear()
    }

    private fun acquireClaimMutation(
        platformOwner: Any,
        request: InfrastructureClaimRequest,
        rollbackTracked: Boolean
    ): ClaimMutation = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let {
            return@synchronized ClaimMutation(it, null)
        }
        if (hasPendingMutation(request.owner.ownerId)) {
            return@synchronized ClaimMutation(
                result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${request.owner.ownerId} has an admission transaction in progress"
                ),
                null
            )
        }
        claims[request.owner.ownerId]?.let { existing ->
            if (existing.platformOwner === platformOwner && existing.owner == request.owner &&
                existing.hasFootprint(request.footprint)
            ) {
                return@synchronized ClaimMutation(result(InfrastructureMutationStatus.UNCHANGED), null)
            }
            return@synchronized ClaimMutation(
                result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${request.owner.ownerId} already has revision ${existing.owner.revision}"
                ),
                null
            )
        }
        val blockers = blockers(
            request.footprint,
            claims.values,
            InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
        ) + blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
        if (blockers.isNotEmpty()) return@synchronized ClaimMutation(conflict(blockers), null)
        val acquired = ClaimHeld(platformOwner, request.owner, request.footprint)
        claims[request.owner.ownerId] = acquired
        trackedMutation(
            result(InfrastructureMutationStatus.ACQUIRED),
            after = acquired,
            expectedFootprint = request.footprint,
            rollbackTracked = rollbackTracked
        )
    }

    private fun trackedMutation(
        result: InfrastructureMutationResult,
        after: ClaimHeld,
        expectedFootprint: InfrastructureFootprint,
        rollbackTracked: Boolean
    ): ClaimMutation {
        if (!rollbackTracked) return ClaimMutation(result, null)
        val token = UUID.randomUUID()
        pendingClaimMutations[token] = PendingClaimMutation(after, expectedFootprint)
        return ClaimMutation(result, token)
    }

    private fun hasPendingMutation(ownerId: String): Boolean =
        pendingInitialMutationForOwner(ownerId) != null || pendingAppendForOwner(ownerId) != null

    private fun pendingInitialMutationForOwner(ownerId: String): PendingClaimMutation? =
        pendingClaimMutations.values.firstOrNull { it.after.owner.ownerId == ownerId }

    private fun pendingAppendForOwner(ownerId: String): PendingClaimAppend? =
        pendingClaimAppends.values.firstOrNull { it.owner.ownerId == ownerId }

    private fun sameHeld(first: ClaimHeld?, pending: PendingClaimMutation): Boolean =
        first === pending.after && first.platformOwner === pending.after.platformOwner &&
            first.owner == pending.after.owner && first.hasFootprint(pending.expectedFootprint)

    private fun validate(
        topologyRevision: Long,
        footprint: InfrastructureFootprint
    ): InfrastructureMutationResult? {
        if (closed) return result(InfrastructureMutationStatus.SERVICE_CLOSED, "registry is closed")
        val currentRevision = currentTopologyRevision()
        if (topologyRevision != currentRevision) {
            return result(
                InfrastructureMutationStatus.STALE_TOPOLOGY,
                "requested topology revision $topologyRevision does not match current $currentRevision"
            )
        }
        for (resource in footprint.resources) {
            val exists = try {
                resourceExists(resource)
            } catch (failure: Exception) {
                return result(
                    InfrastructureMutationStatus.RESOURCE_PROVIDER_FAILED,
                    "resource provider failed for $resource: ${failure::class.simpleName}: ${failure.message.orEmpty()}"
                )
            }
            if (!exists) {
                return result(InfrastructureMutationStatus.INVALID_RESOURCE, "resource does not exist: $resource")
            }
        }
        val confirmedRevision = currentTopologyRevision()
        if (topologyRevision != confirmedRevision) {
            return result(
                InfrastructureMutationStatus.STALE_TOPOLOGY,
                "topology changed during validation from requested $topologyRevision to $confirmedRevision"
            )
        }
        return null
    }

    private fun blockers(
        requested: InfrastructureFootprint,
        held: Collection<Stored>,
        kind: InfrastructureBlockerKind
    ): List<InfrastructureBlocker> = held.mapNotNull { holder ->
        val overlapping = requested.resources.filter { wanted ->
            holder.resources.any(wanted::overlaps)
        }
        overlapping.takeIf { it.isNotEmpty() }?.let {
            InfrastructureBlocker(kind, holder.owner, it.toList())
        }
    }

    private fun conflict(blockers: List<InfrastructureBlocker>) = InfrastructureMutationResult(
        InfrastructureMutationStatus.RESOURCE_CONFLICT,
        blockers.toList(),
        "${blockers.size} infrastructure owner(s) block the request"
    )

    private fun result(status: InfrastructureMutationStatus, detail: String = "") =
        InfrastructureMutationResult(status, detail = detail)
}
