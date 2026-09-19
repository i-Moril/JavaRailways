package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.infrastructure.InfrastructureLeaseRegistry
import work.art1st.javarailways.basic.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermit
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermitResult
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureLeaseService
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultInfrastructureLeaseService : InfrastructureLeaseService, AutoCloseable {
    private val lock = Any()
    private val registries = linkedMapOf<UUID, InfrastructureLeaseRegistry>()
    private data class ProviderRegistration(val pluginOwner: Plugin, val provider: InfrastructureResourceProvider)
    private val resourceProviders = linkedMapOf<String, ProviderRegistration>()
    private var closed = false

    fun registerWorld(
        worldId: UUID,
        currentTopologyRevision: () -> Long,
        resourceExists: (InfrastructureResource) -> Boolean
    ) = synchronized(lock) {
        check(!closed) { "InfrastructureLeaseService is closed" }
        check(worldId !in registries) { "Infrastructure lease registry already exists for world $worldId" }
        registries[worldId] = InfrastructureLeaseRegistry(currentTopologyRevision, resourceExists)
    }

    override fun registerResourceProvider(
        pluginOwner: Plugin,
        typeId: String,
        provider: InfrastructureResourceProvider
    ): InfrastructureResourceProviderRegistration {
        val owner = requireEnabled(pluginOwner)
        // Reuse the public value object's validation instead of maintaining a second typeId grammar.
        StableInfrastructureResource(typeId, "validation")
        synchronized(lock) {
            check(!closed) { "InfrastructureLeaseService is closed" }
            check(typeId !in resourceProviders) { "Infrastructure resource provider already exists for $typeId" }
            resourceProviders[typeId] = ProviderRegistration(owner, provider)
        }
        val registrationClosed = AtomicBoolean()
        return InfrastructureResourceProviderRegistration {
            if (registrationClosed.compareAndSet(false, true)) {
                synchronized(lock) {
                    resourceProviders[typeId]?.takeIf { it.pluginOwner === owner && it.provider === provider }
                        ?.let { resourceProviders.remove(typeId) }
                }
            }
        }
    }

    fun stableResourceExists(worldId: UUID, resource: StableInfrastructureResource): Boolean {
        val provider = synchronized(lock) {
            if (closed) null else resourceProviders[resource.typeId]?.provider
        } ?: return false
        return provider.exists(worldId, resource.resourceId)
    }

    fun removeWorld(worldId: UUID) {
        BukkitThreadGuardBridge.runInSimulationContext {
            synchronized(lock) { registries.remove(worldId) }?.close()
        }
    }

    override fun acquireLease(pluginOwner: Plugin, worldId: UUID, request: InfrastructureLeaseRequest) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.acquireLease(requireEnabled(pluginOwner), request) ?: unavailable(worldId)
        }

    override fun replaceLease(
        pluginOwner: Plugin,
        worldId: UUID,
        expectedOwner: InfrastructureOwner,
        replacement: InfrastructureLeaseRequest
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        registry(worldId)?.replaceLease(requireEnabled(pluginOwner), expectedOwner, replacement) ?: unavailable(worldId)
    }

    override fun releaseLease(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.releaseLease(pluginOwner, owner) ?: unavailable(worldId)
        }

    /** Internal seam for the future ProtectedRouteWindow service; deliberately absent from public API. */
    fun acquireLiveClaim(pluginOwner: Plugin, worldId: UUID, request: InfrastructureClaimRequest) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.acquireClaim(requireEnabled(pluginOwner), request) ?: unavailable(worldId)
        }

    /** Internal seam for atomic route-window revision replacement. */
    fun replaceLiveClaim(
        pluginOwner: Plugin,
        worldId: UUID,
        expectedOwner: InfrastructureOwner,
        replacement: InfrastructureClaimRequest
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        registry(worldId)?.replaceClaim(requireEnabled(pluginOwner), expectedOwner, replacement) ?: unavailable(worldId)
    }

    fun releaseLiveClaim(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.releaseClaim(pluginOwner, owner) ?: unavailable(worldId)
        }

    override fun acquireEditPermit(
        pluginOwner: Plugin,
        worldId: UUID,
        request: InfrastructureEditRequest
    ): InfrastructureEditPermitResult = BukkitThreadGuardBridge.runInSimulationContext {
        val registry = registry(worldId)
            ?: return@runInSimulationContext InfrastructureEditPermitResult.denied(unavailable(worldId))
        val owner = requireEnabled(pluginOwner)
        val acquired = registry.acquireEditPermit(owner, request)
        if (!acquired.result.successful) {
            return@runInSimulationContext InfrastructureEditPermitResult.denied(acquired.result)
        }
        val permit = object : InfrastructureEditPermit {
            private val permitClosed = AtomicBoolean()
            override fun owner(): InfrastructureOwner = request.owner
            override fun close() {
                if (permitClosed.compareAndSet(false, true)) {
                    registry.releaseEditPermit(owner, acquired.id)
                }
            }
        }
        InfrastructureEditPermitResult.granted(acquired.result, permit)
    }

    override fun leases(worldId: UUID): List<InfrastructureLeaseSnapshot> =
        registry(worldId)?.leaseSnapshots().orEmpty()

    override fun liveClaims(worldId: UUID): List<InfrastructureClaimSnapshot> =
        registry(worldId)?.claimSnapshots().orEmpty()

    override fun unregisterOwner(pluginOwner: Plugin) {
        BukkitThreadGuardBridge.runInSimulationContext {
            val snapshot = synchronized(lock) {
                resourceProviders.values.removeIf { it.pluginOwner === pluginOwner }
                registries.values.toList()
            }
            snapshot.forEach { it.releasePlatformOwner(pluginOwner) }
        }
    }

    override fun close() {
        BukkitThreadGuardBridge.runInSimulationContext {
            val snapshot = synchronized(lock) {
                if (closed) return@runInSimulationContext
                closed = true
                resourceProviders.clear()
                registries.values.toList().also { registries.clear() }
            }
            snapshot.forEach(InfrastructureLeaseRegistry::close)
        }
    }

    private fun registry(worldId: UUID): InfrastructureLeaseRegistry? = synchronized(lock) {
        if (closed) null else registries[worldId]
    }

    private fun requireEnabled(pluginOwner: Plugin): Plugin {
        check(pluginOwner.isEnabled) { "Cannot acquire infrastructure ownership for disabled plugin ${pluginOwner.name}" }
        return pluginOwner
    }

    private fun unavailable(worldId: UUID) = InfrastructureMutationResult(
        InfrastructureMutationStatus.WORLD_UNAVAILABLE,
        detail = "railway world is not registered: $worldId"
    )
}
