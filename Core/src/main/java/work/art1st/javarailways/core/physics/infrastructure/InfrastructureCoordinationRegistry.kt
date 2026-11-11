package work.art1st.javarailways.core.physics.infrastructure

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencySnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlocker
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlockerKind
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditDecision
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditOverlap
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
import work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt
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
    class ClaimMutation internal constructor(
        val result: InfrastructureMutationResult,
        internal val rollbackToken: UUID?
    )

    /** Opaque handle for finalizing or undoing one append-only delta reservation. */
    class ClaimAppendReservation internal constructor(
        val result: InfrastructureMutationResult,
        internal val token: UUID?
    )

    /** Result of shrinking committed resources while an append reservation may remain. */
    data class ClaimShrinkResult(
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
        val impact: TopologyEditImpact,
        var activeScopes: Int = 0
    )

    /** Only live claims mutate; insertion order is the public footprint order. Access is under [lock]. */
    private class ClaimHeld(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        footprint: InfrastructureFootprint
    ) {
        val resources = LinkedHashSet(footprint.resources)
        var activeScopes = 0

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

    fun beginAcquireClaim(
        platformOwner: Any,
        request: InfrastructureClaimRequest
    ): ClaimMutation = acquireClaimMutation(platformOwner, request, rollbackTracked = true)

    /**
     * Atomically reserves a validated resource delta on an established claim.
     *
     * The reservation is immediately exclusive but remains distinguishable from the established
     * prefix until [completeClaimAppend] or [rollbackClaimAppend] consumes its opaque handle.
     */
    fun beginAppendClaim(
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
        if (existing.activeScopes > 0) return@synchronized result(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, "claim is in use by device actuation")
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
    fun shrinkClaim(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        resourcesToRelease: Collection<InfrastructureResource>
    ): InfrastructureMutationResult = shrinkClaimWithEvidence(
        platformOwner,
        expectedOwner,
        resourcesToRelease
    ).mutation

    fun shrinkClaimWithEvidence(
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
            if (existing.activeScopes > 0) return@synchronized shrinkResult(
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, "claim is in use by device actuation")
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
    fun completeClaimMutation(mutation: ClaimMutation): Boolean = synchronized(lock) {
        val token = mutation.rollbackToken ?: return@synchronized true
        val pending = pendingClaimMutations[token] ?: return@synchronized false
        if (!sameHeld(claims[pending.after.owner.ownerId], pending)) return@synchronized false
        pendingClaimMutations.remove(token)
        true
    }

    /** Removes exactly the newly acquired claim after its preparation was safely aborted. */
    fun rollbackClaimMutation(mutation: ClaimMutation): InfrastructureMutationResult = synchronized(lock) {
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
        if (checkNotNull(current).activeScopes > 0) return@synchronized result(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, "claim is in use by device actuation")
        claims.remove(pending.after.owner.ownerId)
        pendingClaimMutations.remove(token)
        result(InfrastructureMutationStatus.RELEASED)
    }

    /** Commits a pending delta without changing the claim's logical owner revision. */
    fun completeClaimAppend(reservation: ClaimAppendReservation): Boolean = synchronized(lock) {
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
    fun rollbackClaimAppend(
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

        if (current.activeScopes > 0) return@synchronized result(
            InfrastructureMutationStatus.OWNER_REVISION_CONFLICT, "claim is in use by device actuation")
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

    private val resourceValidators = linkedMapOf<Any, (InfrastructureResource) -> Boolean>()
    private val activeEditPermits = ThreadLocal.withInitial { ArrayList<UUID>() }
    private val pendingOwnerReleases = Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private val internalEditOwner = Any()
    private var editSequence = 0L

    /** Adds host-owned resource kinds to the world's single arbiter. */
    fun registerResourceValidator(validator: (InfrastructureResource) -> Boolean): AutoCloseable = synchronized(lock) {
        RailwaySimulationHalt.ensureRunning()
        check(!closed) { "registry is closed" }
        val token = Any()
        resourceValidators[token] = validator
        AutoCloseable { synchronized(lock) { resourceValidators.remove(token) } }
    }

    private fun isResourcePresent(resource: InfrastructureResource): Boolean =
        resourceExists(resource) || resourceValidators.values.any { it(resource) }

    /** Enters a lexical scope using an existing, authenticated permit; does not release its ownership. */
    fun enterEditPermit(platformOwner: Any, permitId: UUID): AutoCloseable = synchronized(lock) {
        RailwaySimulationHalt.ensureRunning()
        val held = checkNotNull(editPermits[permitId]) { "edit permit is no longer held" }
        check(held.platformOwner === platformOwner) { "edit permit belongs to another owner" }
        val thread = Thread.currentThread()
        val active = activeEditPermits.get()
        active.add(permitId)
        held.activeScopes++
        AutoCloseable {
            check(Thread.currentThread() === thread) { "edit permit scope must close on its opening thread" }
            check(active.lastOrNull() == permitId) { "edit permit scopes must close in reverse order" }
            active.removeAt(active.lastIndex)
            if (active.isEmpty()) activeEditPermits.remove()
            synchronized(lock) {
                held.activeScopes--
                finishOwnerRelease(held.platformOwner)
            }
        }
    }

    fun <T> withEditPermit(platformOwner: Any, permitId: UUID, action: () -> T): T =
        enterEditPermit(platformOwner, permitId).use { action() }

    private val activeClaims = ThreadLocal.withInitial { ArrayList<ClaimHeld>() }

    /** Authorizes device actuation using a currently held claim, never structural destruction. */
    fun <T> withClaim(platformOwner: Any, owner: InfrastructureOwner, action: () -> T): T {
        val held = synchronized(lock) {
            RailwaySimulationHalt.ensureRunning()
            check(!closed) { "registry is closed" }
            checkNotNull(claims[owner.ownerId]).also {
                check(it.platformOwner === platformOwner && it.owner == owner) { "claim belongs to another owner" }
                it.activeScopes++
            }
        }
        val active = activeClaims.get()
        active.add(held)
        try {
            return action()
        } finally {
            check(active.removeAt(active.lastIndex) === held)
            if (active.isEmpty()) activeClaims.remove()
            synchronized(lock) {
                held.activeScopes--
                finishOwnerRelease(held.platformOwner)
            }
        }
    }

    /** Selection changes may be made by the resource's live claimant; structure edits may not. */
    fun ensureSelectionProtected(resource: StableInfrastructureResource): AutoCloseable = synchronized(lock) {
        RailwaySimulationHalt.ensureRunning()
        check(!closed) { "registry is closed" }
        if (activeClaims.get().any { held ->
                claims[held.owner.ownerId] === held && resource in held.resources
            }) return@synchronized AutoCloseable { }
        ensureEditProtected(TopologyEditImpact(emptySet(), emptySet(), setOf(resource)))
    }

    /** Core mutation gate: reuse a covering scope, otherwise acquire protection from this same registry. */
    fun ensureEditProtected(impact: TopologyEditImpact): AutoCloseable = synchronized(lock) {
        RailwaySimulationHalt.ensureRunning()
        check(!closed) { "registry is closed" }
        val active = activeEditPermits.get()
        val scopes = active.map { checkNotNull(editPermits[it]) { "active edit permit was released" } }
        if (impact.railIds.all { id -> scopes.any { id in it.impact.railIds } } &&
            impact.topologyNodeKeys.all { key -> scopes.any { key in it.impact.topologyNodeKeys } } &&
            impact.resourceIds.all { id -> scopes.any { id in it.impact.resourceIds } }) {
            return@synchronized AutoCloseable { }
        }
        val request = TopologyEditRequest(currentTopologyRevision(),
            InfrastructureOwner("javarailways:core-edit/${editSequence++}", 0L), impact)
        val acquired = acquireEditPermit(internalEditOwner, request, active.toSet())
        check(acquired.decision.successful) { "rail edit denied: ${acquired.decision.detail}" }
        val scope = enterEditPermit(internalEditOwner, acquired.id)
        AutoCloseable {
            scope.close()
            check(releaseEditPermit(internalEditOwner, acquired.id)) { "core edit permit was released prematurely" }
        }
    }

    fun acquireEditPermit(platformOwner: Any, request: TopologyEditRequest): EditPermit =
        acquireEditPermit(platformOwner, request, emptySet())

    private fun acquireEditPermit(platformOwner: Any, request: TopologyEditRequest,
        enclosingPermits: Set<UUID>): EditPermit = synchronized(lock) {
        validateEdit(request)?.let { return@synchronized EditPermit(UUID(0L, 0L), it) }
        val blockers = dependencyDeclarations.values.mapNotNull { held ->
            overlaps(held.dependencies, request.impact)?.let { overlap ->
                TopologyEditBlocker(TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY, held.owner, overlap)
            }
        } + claims.values.mapNotNull { held ->
            claimOverlap(held.resources, request.impact)?.let { overlap ->
                TopologyEditBlocker(TopologyEditBlockerKind.LIVE_RUNTIME_CLAIM, held.owner, overlap)
            }
        } + editPermits.filterKeys { it !in enclosingPermits }.values.mapNotNull { held ->
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
        if (permit.platformOwner !== platformOwner || permit.activeScopes > 0) return@synchronized false
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

    /** Keeps in-flight operations protected, then completes owner cleanup when their scopes finish. */
    fun releasePlatformOwner(platformOwner: Any) = synchronized(lock) {
        pendingOwnerReleases.add(platformOwner)
        finishOwnerRelease(platformOwner)
    }

    private fun finishOwnerRelease(platformOwner: Any) {
        if (platformOwner !in pendingOwnerReleases) return
        if (claims.values.any { it.platformOwner === platformOwner && it.activeScopes > 0 } ||
            editPermits.values.any { it.platformOwner === platformOwner && it.activeScopes > 0 }) return
        val pendingOwnerIds = pendingClaimMutations.values.mapTo(hashSetOf()) { it.after.owner.ownerId }
        pendingClaimAppends.values.mapTo(pendingOwnerIds) { it.owner.ownerId }
        claims.values.removeIf { held ->
            held.platformOwner === platformOwner && held.owner.ownerId !in pendingOwnerIds
        }
        editPermits.values.removeIf { it.platformOwner === platformOwner }
        // Pending admission reservations retain their existing fail-closed recovery semantics.
        // Owner teardown must not turn their eventual commit into permission to release a train's resources.
        pendingOwnerReleases.remove(platformOwner)
    }

    fun close() = synchronized(lock) {
        check(claims.values.none { it.activeScopes > 0 } && editPermits.values.none { it.activeScopes > 0 }) {
            "cannot close infrastructure coordination during an active resource operation"
        }
        closed = true
        dependencyDeclarations.clear()
        claims.clear()
        pendingClaimMutations.clear()
        pendingClaimAppends.clear()
        editPermits.clear()
        pendingOwnerReleases.clear()
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
        RailwaySimulationHalt.ensureRunning()
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
        RailwaySimulationHalt.ensureRunning()
        if (closed) return TopologyEditDecision(TopologyEditStatus.SERVICE_CLOSED, detail = "registry is closed")
        val invalid = firstInvalidReference(
            request.impact.railIds,
            request.impact.topologyNodeKeys,
            request.impact.resourceIds
        )
        if (invalid != null) {
            return TopologyEditDecision(TopologyEditStatus.INVALID_REFERENCE, detail = invalid)
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
            ?: resourceIds.firstOrNull { !isResourcePresent(it) }
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
        RailwaySimulationHalt.ensureRunning()
        if (closed) return result(InfrastructureMutationStatus.SERVICE_CLOSED, "registry is closed")
        for (resource in footprint.resources) {
            val exists = try {
                isResourcePresent(resource)
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
