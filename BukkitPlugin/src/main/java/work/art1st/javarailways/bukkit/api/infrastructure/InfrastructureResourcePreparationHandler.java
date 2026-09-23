package work.art1st.javarailways.bukkit.api.infrastructure;

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource;
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor;
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket;

import java.util.UUID;

/**
 * Starts one provider-owned preparation after the resource has been atomically claimed.
 *
 * <p>The handler runs on the world simulation owner and must return quickly. Bukkit entity,
 * location, or region work must be dispatched to its platform owner while the returned ticket
 * reports {@code PREPARING}. The handler cannot change the claim or movement authority.</p>
 */
public interface InfrastructureResourcePreparationHandler {
    /** Namespaced preparation kind accepted by this handler. */
    String kindId();

    /**
     * Starts one process-local attempt. The immutable descriptor has already been approved by the
     * owning {@link InfrastructureResourceProvider}; the returned ticket is not persisted.
     */
    ResourcePreparationTicket start(
        UUID attemptId,
        UUID worldId,
        StableInfrastructureResource resource,
        ResourcePreparationDescriptor descriptor
    );
}
