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

    private data class Held(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val footprint: InfrastructureFootprint
    )

    private val lock = Any()
    private val leases = linkedMapOf<String, Held>()
    private val claims = linkedMapOf<String, Held>()
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
        synchronized(lock) {
            validate(request.topologyRevision, request.footprint)?.let { return@synchronized it }
            claims[request.owner.ownerId]?.let { existing ->
                if (existing.platformOwner === platformOwner && existing.owner == request.owner &&
                    existing.footprint == request.footprint
                ) return@synchronized result(InfrastructureMutationStatus.UNCHANGED)
                return@synchronized result(
                    InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                    "claim owner ${request.owner.ownerId} already has revision ${existing.owner.revision}"
                )
            }
            val blockers = blockers(
                request.footprint,
                claims.values,
                InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
            ) + blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
            if (blockers.isNotEmpty()) return@synchronized conflict(blockers)
            claims[request.owner.ownerId] = Held(platformOwner, request.owner, request.footprint)
            result(InfrastructureMutationStatus.ACQUIRED)
        }

    fun replaceClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: InfrastructureClaimRequest
    ): InfrastructureMutationResult = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let { return@synchronized it }
        if (expectedOwner.ownerId != request.owner.ownerId || request.owner.revision <= expectedOwner.revision) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "replacement must keep ownerId and advance its revision"
            )
        }
        val existing = claims[expectedOwner.ownerId]
            ?: return@synchronized result(InfrastructureMutationStatus.NOT_FOUND, "claim does not exist")
        if (existing.platformOwner !== platformOwner || existing.owner != expectedOwner) {
            return@synchronized result(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT,
                "claim owner or expected revision does not match"
            )
        }
        val blockers = blockers(
            request.footprint,
            claims.values.filterNot { it === existing },
            InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM
        ) + blockers(request.footprint, editPermits.values, InfrastructureBlockerKind.EDIT_PERMIT)
        if (blockers.isNotEmpty()) return@synchronized conflict(blockers)
        claims[request.owner.ownerId] = Held(platformOwner, request.owner, request.footprint)
        result(InfrastructureMutationStatus.REPLACED)
    }

    fun releaseClaim(platformOwner: Any, owner: InfrastructureOwner): InfrastructureMutationResult = synchronized(lock) {
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

    fun acquireEditPermit(platformOwner: Any, request: InfrastructureEditRequest): EditPermit = synchronized(lock) {
        validate(request.topologyRevision, request.footprint)?.let { return@synchronized EditPermit(UUID(0L, 0L), it) }
        val blockers = blockers(request.footprint, leases.values, InfrastructureBlockerKind.APPLIED_PLAN_LEASE) +
            blockers(request.footprint, claims.values, InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM) +
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
        leases.values.removeIf { it.platformOwner === platformOwner }
        claims.values.removeIf { it.platformOwner === platformOwner }
        editPermits.values.removeIf { it.platformOwner === platformOwner }
    }

    fun close() = synchronized(lock) {
        closed = true
        leases.clear()
        claims.clear()
        editPermits.clear()
    }

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
