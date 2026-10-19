package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencySnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlocker
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditOverlap
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import java.util.UUID
import java.util.Collections

/** Internal route-resource footprint request; public hosts do not expose raw claim mutation. */
data class InfrastructureClaimRequest(
    val owner: InfrastructureOwner,
    val footprint: InfrastructureFootprint
)

/**
 * World-local atomic registry. Platform ownership tokens are deliberately opaque and never enter
 * persisted owner identities or public diagnostics.
 * Reference validators must be side-effect-free. Validation and installation use the same lock
 * as edit-permit acquisition, so supported edits cannot pass between those two operations.
 */
class InfrastructureCoordinationRegistry(
    private val currentTopologyRevision: () -> Long,
    private val resourceExists: (InfrastructureResource) -> Boolean,
    private val railExists: (UUID) -> Boolean = { false },
    private val topologyNodeExists: (StableTopologyNodeKey) -> Boolean = { false }
) {
    class EditPermit internal constructor(val id: UUID, val decision: TopologyEditDecision)

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

    private data class DependencyHeld(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val dependencies: InfrastructureDependencies
    )

    private data class EditPermitHeld(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val impact: TopologyEditImpact
    )

    /** Only live claims mutate; insertion order is the public footprint order. Access is under [lock]. */
    private class ClaimHeld(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        footprint: InfrastructureFootprint
    ) {
        val resources = LinkedHashSet(footprint.resources)

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
    private val dependencyDeclarations = linkedMapOf<String, DependencyHeld>()
    private val claims = linkedMapOf<String, ClaimHeld>()
    private val pendingClaimMutations = linkedMapOf<UUID, PendingClaimMutation>()
    private val pendingClaimAppends = linkedMapOf<UUID, PendingClaimAppend>()
    private val editPermits = linkedMapOf<UUID, EditPermitHeld>()
    private var closed = false

    fun setInfrastructureDependencies(
        platformOwner: Any,
        request: InfrastructureDependencyRequest
    ): InfrastructureDependencyMutationResult = synchronized(lock) {
        validateDependencies(request)?.let { return@synchronized it }
        dependencyDeclarations[request.owner.ownerId]?.let { existing ->
            if (existing.platformOwner !== platformOwner || request.owner.revision < existing.owner.revision) {
                return@synchronized dependencyResult(
                    InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT,
                    "infrastructure dependency owner or revision does not match"
                )
            }
            if (request.owner == existing.owner && request.dependencies == existing.dependencies) {
                return@synchronized dependencyResult(InfrastructureDependencyMutationStatus.UNCHANGED)
            }
            if (request.owner.revision == existing.owner.revision) {
                return@synchronized dependencyResult(
                    InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT,
                    "changing infrastructure dependencies requires a newer owner revision"
                )
            }
        }
        if (editPermits.values.any { overlaps(request.dependencies, it.impact) != null }) {
            return@synchronized dependencyResult(
                InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS,
                "an infrastructure edit already covers this dependency"
            )
        }
        val replaced = dependencyDeclarations.put(
            request.owner.ownerId,
            DependencyHeld(platformOwner, request.owner, request.dependencies)
        ) != null
        dependencyResult(
            if (replaced) InfrastructureDependencyMutationStatus.REPLACED
            else InfrastructureDependencyMutationStatus.APPLIED
        )
    }

    fun removeInfrastructureDependencies(
        platformOwner: Any,
        owner: InfrastructureOwner
    ): InfrastructureDependencyMutationResult = synchronized(lock) {
        if (closed) return@synchronized dependencyResult(
            InfrastructureDependencyMutationStatus.SERVICE_CLOSED,
            "registry is closed"
        )
        val existing = dependencyDeclarations[owner.ownerId]
            ?: return@synchronized dependencyResult(
                InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT,
                "infrastructure dependencies do not exist"
            )
        if (existing.platformOwner !== platformOwner || existing.owner != owner) {
            return@synchronized dependencyResult(
                InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT,
                "infrastructure dependency owner or revision does not match"
            )
        }
        dependencyDeclarations.remove(owner.ownerId)
        dependencyResult(InfrastructureDependencyMutationStatus.REMOVED)
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
        deltaResources: Collection<InfrastructureResource>
    ): ClaimAppendReservation {
        val delta = deltaResources.toList()
        require(delta.isNotEmpty()) { "deltaResources must not be empty" }
        require(delta.distinct().size == delta.size) { "deltaResources contains duplicate resources" }

        return synchronized(lock) {
            val footprint = InfrastructureFootprint(delta)
            validate(footprint)?.let {
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
                claims.values.filterNot { it === existing }
            ) + editPermitClaimBlockers(footprint)
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

    fun acquireEditPermit(platformOwner: Any, request: TopologyEditRequest): EditPermit = synchronized(lock) {
        validateEdit(request)?.let { return@synchronized EditPermit(UUID(0L, 0L), it) }
        val blockers = dependencyDeclarations.values.mapNotNull { held ->
            overlaps(held.dependencies, request.impact)?.let { overlap ->
                TopologyEditBlocker(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, held.owner, overlap)
            }
        } + claims.values.mapNotNull { held ->
            claimOverlap(held.resources, request.impact)?.let { overlap ->
                TopologyEditBlocker(TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM, held.owner, overlap)
            }
        } + editPermits.values.mapNotNull { held ->
            overlaps(held.impact, request.impact)?.let { overlap ->
                TopologyEditBlocker(TopologyEditBlockerKind.EDIT_PERMIT, held.owner, overlap)
            }
        }
        if (blockers.isNotEmpty()) {
            return@synchronized EditPermit(
                UUID(0L, 0L),
                TopologyEditDecision(
                    TopologyEditStatus.CONFLICT,
                    blockers,
                    "${blockers.size} infrastructure owner(s) block the edit"
                )
            )
        }
        val id = UUID.randomUUID()
        editPermits[id] = EditPermitHeld(platformOwner, request.owner, request.impact)
        EditPermit(id, TopologyEditDecision(TopologyEditStatus.GRANTED))
    }

    fun releaseEditPermit(platformOwner: Any, permitId: UUID): Boolean = synchronized(lock) {
        val permit = editPermits[permitId] ?: return@synchronized false
        if (permit.platformOwner !== platformOwner) return@synchronized false
        editPermits.remove(permitId)
        true
    }

    fun dependencySnapshots(): List<InfrastructureDependencySnapshot> = synchronized(lock) {
        Collections.unmodifiableList(dependencyDeclarations.values.map {
            InfrastructureDependencySnapshot(it.owner, it.dependencies)
        })
    }

    fun dependenciesOwnedBy(platformOwner: Any): List<InfrastructureDependencySnapshot> =
        synchronized(lock) {
            Collections.unmodifiableList(dependencyDeclarations.values
                .filter { it.platformOwner === platformOwner }
                .map { InfrastructureDependencySnapshot(it.owner, it.dependencies) })
        }

    fun dependenciesUsingResourceTypes(
        typeIds: Set<String>
    ): List<InfrastructureDependencySnapshot> = synchronized(lock) {
        if (typeIds.isEmpty()) return@synchronized emptyList()
        Collections.unmodifiableList(dependencyDeclarations.values
            .filter { held -> held.dependencies.resourceIds.any { it.typeId in typeIds } }
            .map { InfrastructureDependencySnapshot(it.owner, it.dependencies) })
    }

    fun claimSnapshots(): List<InfrastructureClaimSnapshot> = synchronized(lock) {
        Collections.unmodifiableList(claims.values.map { InfrastructureClaimSnapshot(it.owner, it.snapshotFootprint()) })
    }

    fun releasePlatformOwner(platformOwner: Any) = synchronized(lock) {
        val pendingOwnerIds = pendingClaimMutations.values.mapTo(hashSetOf()) { it.after.owner.ownerId }
        pendingClaimAppends.values.mapTo(pendingOwnerIds) { it.owner.ownerId }
        claims.values.removeIf { held ->
            held.platformOwner === platformOwner && held.owner.ownerId !in pendingOwnerIds
        }
        editPermits.values.removeIf { it.platformOwner === platformOwner }
    }

    fun close() = synchronized(lock) {
        closed = true
        dependencyDeclarations.clear()
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
        validate(request.footprint)?.let {
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
            claims.values
        ) + editPermitClaimBlockers(request.footprint)
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
        first === pending.after && first.hasFootprint(pending.expectedFootprint)

    private fun validateDependencies(
        request: InfrastructureDependencyRequest
    ): InfrastructureDependencyMutationResult? {
        if (closed) return dependencyResult(InfrastructureDependencyMutationStatus.SERVICE_CLOSED, "registry is closed")
        val invalid = firstInvalidReference(
            request.dependencies.railIds,
            request.dependencies.topologyNodeKeys,
            request.dependencies.resourceIds
        )
        if (invalid != null) {
            return dependencyResult(InfrastructureDependencyMutationStatus.INVALID_REFERENCE, invalid)
        }
        return null
    }

    private fun validateEdit(request: TopologyEditRequest): TopologyEditDecision? {
        if (closed) return TopologyEditDecision(TopologyEditStatus.SERVICE_CLOSED, detail = "registry is closed")
        val currentRevision = currentTopologyRevision()
        if (request.topologyRevision != currentRevision) {
            return TopologyEditDecision(
                TopologyEditStatus.STALE_TOPOLOGY,
                detail = "requested topology revision ${request.topologyRevision} does not match current $currentRevision"
            )
        }
        val invalid = firstInvalidReference(
            request.impact.railIds,
            request.impact.topologyNodeKeys,
            request.impact.resourceIds
        )
        if (invalid != null) {
            return TopologyEditDecision(TopologyEditStatus.INVALID_REFERENCE, detail = invalid)
        }
        val confirmedRevision = currentTopologyRevision()
        if (request.topologyRevision != confirmedRevision) {
            return TopologyEditDecision(
                TopologyEditStatus.STALE_TOPOLOGY,
                detail = "topology changed during validation from requested ${request.topologyRevision} to $confirmedRevision"
            )
        }
        return null
    }

    private fun firstInvalidReference(
        railIds: Set<UUID>,
        topologyNodeKeys: Set<StableTopologyNodeKey>,
        resourceIds: Set<StableInfrastructureResource>
    ): String? = try {
        railIds.firstOrNull { !railExists(it) }?.let { "rail does not exist: $it" }
            ?: topologyNodeKeys.firstOrNull { !topologyNodeExists(it) }
                ?.let { "topology node does not exist: $it" }
            ?: resourceIds.firstOrNull { !resourceExists(it) }
                ?.let { "runtime resource does not exist: $it" }
    } catch (failure: Exception) {
        "reference provider failed: ${failure::class.simpleName}: ${failure.message.orEmpty()}"
    }

    private fun overlaps(
        dependencies: InfrastructureDependencies,
        impact: TopologyEditImpact
    ): TopologyEditOverlap? = overlap(
        dependencies.railIds,
        dependencies.topologyNodeKeys,
        dependencies.resourceIds,
        impact
    )

    private fun overlaps(first: TopologyEditImpact, second: TopologyEditImpact): TopologyEditOverlap? = overlap(
        first.railIds,
        first.topologyNodeKeys,
        first.resourceIds,
        second
    )

    private fun overlap(
        railIds: Set<UUID>,
        topologyNodeKeys: Set<StableTopologyNodeKey>,
        resourceIds: Set<StableInfrastructureResource>,
        impact: TopologyEditImpact
    ): TopologyEditOverlap? {
        val overlappingRails = railIds.intersect(impact.railIds)
        val overlappingNodes = topologyNodeKeys.intersect(impact.topologyNodeKeys)
        val overlappingResources = resourceIds.intersect(impact.resourceIds)
        if (overlappingRails.isEmpty() && overlappingNodes.isEmpty() && overlappingResources.isEmpty()) return null
        return TopologyEditOverlap(overlappingRails, overlappingNodes, overlappingResources)
    }

    private fun claimOverlap(
        resources: Collection<InfrastructureResource>,
        impact: TopologyEditImpact
    ): TopologyEditOverlap? {
        val rails = resources.asSequence()
            .filterIsInstance<RailIntervalResource>()
            .map(RailIntervalResource::railId)
            .filter(impact.railIds::contains)
            .toSet()
        val stableResources = resources.asSequence()
            .filterIsInstance<StableInfrastructureResource>()
            .filter(impact.resourceIds::contains)
            .toSet()
        if (rails.isEmpty() && stableResources.isEmpty()) return null
        return TopologyEditOverlap(rails, emptySet(), stableResources)
    }

    private fun editPermitClaimBlockers(footprint: InfrastructureFootprint): List<InfrastructureBlocker> =
        editPermits.values.mapNotNull { permit ->
            val overlapping = footprint.resources.filter { resource ->
                when (resource) {
                    is RailIntervalResource -> resource.railId in permit.impact.railIds
                    is StableInfrastructureResource -> resource in permit.impact.resourceIds
                }
            }
            overlapping.takeIf(List<InfrastructureResource>::isNotEmpty)?.let {
                InfrastructureBlocker(InfrastructureBlockerKind.EDIT_PERMIT, permit.owner, it)
            }
        }

    private fun validate(footprint: InfrastructureFootprint): InfrastructureMutationResult? {
        if (closed) return result(InfrastructureMutationStatus.SERVICE_CLOSED, "registry is closed")
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
        return null
    }

    private fun blockers(
        requested: InfrastructureFootprint,
        held: Collection<ClaimHeld>
    ): List<InfrastructureBlocker> = held.mapNotNull { holder ->
        val overlapping = requested.resources.filter { wanted ->
            holder.resources.any(wanted::overlaps)
        }
        overlapping.takeIf { it.isNotEmpty() }?.let {
            InfrastructureBlocker(InfrastructureBlockerKind.LIVE_RUNTIME_CLAIM, holder.owner, it.toList())
        }
    }

    private fun conflict(blockers: List<InfrastructureBlocker>) = InfrastructureMutationResult(
        InfrastructureMutationStatus.RESOURCE_CONFLICT,
        blockers.toList(),
        "${blockers.size} infrastructure owner(s) block the request"
    )

    private fun result(status: InfrastructureMutationStatus, detail: String = "") =
        InfrastructureMutationResult(status, detail = detail)

    private fun dependencyResult(
        status: InfrastructureDependencyMutationStatus,
        detail: String = ""
    ) = InfrastructureDependencyMutationResult(status, detail)
}
