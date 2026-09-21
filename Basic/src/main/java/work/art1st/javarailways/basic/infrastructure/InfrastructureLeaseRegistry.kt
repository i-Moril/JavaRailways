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

    private data class Held(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val footprint: InfrastructureFootprint
    )

    private data class PendingClaimMutation(
        val before: Held?,
        val after: Held
    )

    private val lock = Any()
    private val leases = linkedMapOf<String, Held>()
    private val claims = linkedMapOf<String, Held>()
    private val pendingClaimMutations = linkedMapOf<UUID, PendingClaimMutation>()
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

    fun replaceClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: InfrastructureClaimRequest
    ): InfrastructureMutationResult = replaceClaimMutation(
        platformOwner,
        expectedOwner,
        request,
        rollbackTracked = false
    ).result

    internal fun beginReplaceClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: InfrastructureClaimRequest
    ): ClaimMutation = replaceClaimMutation(platformOwner, expectedOwner, request, rollbackTracked = true)

    private fun replaceClaimMutation(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: InfrastructureClaimRequest,
        rollbackTracked: Boolean
    ): ClaimMutation = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let {
            return@synchronized ClaimMutation(it, null)
        }
        pendingForOwner(expectedOwner.ownerId)?.let {
            return@synchronized ClaimMutation(
                result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${expectedOwner.ownerId} has an admission transaction in progress"
                ),
                null
            )
        }
        if (expectedOwner.ownerId != request.owner.ownerId || request.owner.revision <= expectedOwner.revision) {
            return@synchronized ClaimMutation(
                result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "replacement must keep ownerId and advance its revision"
                ),
                null
            )
        }
        val existing = claims[expectedOwner.ownerId]
            ?: return@synchronized ClaimMutation(
                result(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist"),
                null
            )
        if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
            return@synchronized ClaimMutation(
                result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner or expected revision does not match"
                ),
                null
            )
        }
        val blockers = blockers(
            request.footprint,
            claims.values.filterNot { it === existing } + rollbackReservations(),
            InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
        ) + blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
        if (blockers.isNotEmpty()) return@synchronized ClaimMutation(conflict(blockers), null)
        val replacement = Held(platformOwner, request.owner, request.footprint)
        claims[request.owner.ownerId] = replacement
        trackedMutation(
            result(InfrastructureMutationStatus.REPLACED),
            before = existing,
            after = replacement,
            rollbackTracked = rollbackTracked
        )
    }

    fun releaseClaim(platformOwner: Any, owner: InfrastructureOwner): InfrastructureMutationResult = synchronized(lock) {
        if (pendingForOwner(owner.ownerId) != null) {
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
    ): InfrastructureMutationResult {
        val requested = resourcesToRelease.toList()
        require(requested.isNotEmpty()) { "resourcesToRelease must not be empty" }
        require(requested.distinct().size == requested.size) {
            "resourcesToRelease contains duplicate resources"
        }

        return synchronized(lock) {
            if (closed) {
                return@synchronized result(InfrastructureMutationStatus.SERVICE_CLOSED, "registry is closed")
            }
            val existing = claims[expectedOwner.ownerId]
                ?: return@synchronized result(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist")
            if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
                return@synchronized result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner or expected revision does not match"
                )
            }
            if (pendingForOwner(expectedOwner.ownerId) != null) {
                return@synchronized result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${expectedOwner.ownerId} has an admission transaction in progress"
                )
            }

            val releaseSet = requested.toHashSet()
            val heldResources = existing.footprint.resources
            val missing = requested.firstOrNull { it !in heldResources }
            if (missing != null) {
                return@synchronized result(
                    InfrastructureMutationStatus.NOT_FOUND,
                    "claim does not hold exact resource: $missing"
                )
            }

            val remaining = heldResources.filterNot(releaseSet::contains)
            if (remaining.isEmpty()) {
                claims.remove(expectedOwner.ownerId)
                return@synchronized result(InfrastructureMutationStatus.RELEASED)
            }
            claims[expectedOwner.ownerId] = existing.copy(footprint = InfrastructureFootprint(remaining))
            result(InfrastructureMutationStatus.REPLACED)
        }
    }

    /** Finalizes a successful admission so its exact-before rollback state can be discarded. */
    internal fun completeClaimMutation(mutation: ClaimMutation): Boolean = synchronized(lock) {
        val token = mutation.rollbackToken ?: return@synchronized true
        val pending = pendingClaimMutations[token] ?: return@synchronized false
        if (!sameHeld(claims[pending.after.owner.ownerId], pending.after)) return@synchronized false
        pendingClaimMutations.remove(token)
        true
    }

    /** Restores the exact pre-mutation claim without revalidating its older owner revision. */
    internal fun rollbackClaimMutation(mutation: ClaimMutation): InfrastructureMutationResult = synchronized(lock) {
        val token = mutation.rollbackToken
            ?: return@synchronized result(InfrastructureMutationStatus.UNCHANGED)
        val pending = pendingClaimMutations[token]
            ?: return@synchronized result(
                InfrastructureMutationStatus.NOT_FOUND,
                "claim admission rollback token does not exist"
            )
        val current = claims[pending.after.owner.ownerId]
        if (!sameHeld(current, pending.after)) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim changed while its admission transaction was in progress"
            )
        }
        if (pending.before == null) {
            claims.remove(pending.after.owner.ownerId)
        } else {
            claims[pending.before.owner.ownerId] = pending.before
        }
        pendingClaimMutations.remove(token)
        result(
            if (pending.before == null) InfrastructureMutationStatus.RELEASED
            else InfrastructureMutationStatus.REPLACED
        )
    }

    fun acquireEditPermit(platformOwner: Any, request: InfrastructureEditRequest): EditPermit = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let { return@synchronized EditPermit(UUID(0L, 0L), it) }
        val blockers = blockers(request.footprint, leases.values, InfrastructureBlockerKind.APPLIED_PLAN_LEASE) +
            blockers(
                request.footprint,
                claims.values + rollbackReservations(),
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
        Collections.unmodifiableList(claims.values.map { InfrastructureClaimSnapshot(it.owner, it.footprint) })
    }

    fun releasePlatformOwner(platformOwner: Any) = synchronized(lock) {
        val pendingOwnerIds = pendingClaimMutations.values.mapTo(hashSetOf()) { it.after.owner.ownerId }
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
        pendingForOwner(request.owner.ownerId)?.let {
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
                existing.footprint == request.footprint
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
            claims.values + rollbackReservations(),
            InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
        ) + blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
        if (blockers.isNotEmpty()) return@synchronized ClaimMutation(conflict(blockers), null)
        val acquired = Held(platformOwner, request.owner, request.footprint)
        claims[request.owner.ownerId] = acquired
        trackedMutation(
            result(InfrastructureMutationStatus.ACQUIRED),
            before = null,
            after = acquired,
            rollbackTracked = rollbackTracked
        )
    }

    private fun trackedMutation(
        result: InfrastructureMutationResult,
        before: Held?,
        after: Held,
        rollbackTracked: Boolean
    ): ClaimMutation {
        if (!rollbackTracked) return ClaimMutation(result, null)
        val token = UUID.randomUUID()
        pendingClaimMutations[token] = PendingClaimMutation(before, after)
        return ClaimMutation(result, token)
    }

    private fun pendingForOwner(ownerId: String): PendingClaimMutation? =
        pendingClaimMutations.values.firstOrNull { it.after.owner.ownerId == ownerId }

    private fun rollbackReservations(): List<Held> =
        pendingClaimMutations.values.mapNotNull(PendingClaimMutation::before)

    private fun sameHeld(first: Held?, second: Held): Boolean =
        first != null && first.platformOwner === second.platformOwner &&
            first.owner == second.owner && first.footprint == second.footprint

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
        held: Collection<Held>,
        kind: InfrastructureBlockerKind
    ): List<InfrastructureBlocker> = held.mapNotNull { holder ->
        val overlapping = requested.resources.filter { wanted ->
            holder.footprint.resources.any(wanted::overlaps)
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
