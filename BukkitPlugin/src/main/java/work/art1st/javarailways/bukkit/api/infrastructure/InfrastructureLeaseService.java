package work.art1st.javarailways.bukkit.api.infrastructure;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseSnapshot;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService;

import java.util.List;
import java.util.UUID;

/**
 * World-local arbitration for long-lived shared plan leases, exclusive live claims, and edits.
 *
 * <p>Every mutation is atomic and checks the request's topology revision against the authoritative
 * current world revision. Applied-plan leases may overlap each other and do not reserve a turnout
 * selection. Live claims are mutually exclusive. An edit permit conflicts with both and remains
 * held until closed. Mutation methods may be called from plugin lifecycle or simulation code and
 * are serialized with JavaRailways topology changes. The protected infrastructure mutation itself
 * must still execute on its platform-required owner while the returned edit permit remains open.</p>
 *
 * <p>A route-spanning footprint contains one canonical-AB rail interval for each traversed rail.
 * Turnouts, diamonds, and other shared conflict nodes must also appear as stable resources in that
 * same footprint; adjacent rail intervals do not implicitly claim the node between them.</p>
 */
public interface InfrastructureLeaseService {
    /**
     * Returns the route-resource admission entry point for one plugin and railway world.
     *
     * <p>The returned service authenticates every request, handle inspection, and grant end as
     * {@code pluginOwner}. It becomes unavailable when that plugin is disabled, this host service
     * closes, or this exact world runtime unloads. A later world runtime with the same UUID does not
     * revive an old binding. Existing host-owned claims are not released merely because the caller
     * becomes unavailable.</p>
     *
     * @throws IllegalStateException if the plugin is disabled or route admission is not attached
     *                               for the requested world
     */
    RouteResourceAdmissionService routeAdmission(Plugin pluginOwner, UUID worldId);

    /**
     * Registers the authoritative resolver for one namespaced stable resource type.
     * Only one enabled plugin may provide a type at a time.
     */
    InfrastructureResourceProviderRegistration registerResourceProvider(
        Plugin pluginOwner,
        String typeId,
        InfrastructureResourceProvider provider
    );

    InfrastructureMutationResult acquireLease(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureLeaseRequest request
    );

    InfrastructureMutationResult replaceLease(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureOwner expectedOwner,
        InfrastructureLeaseRequest replacement
    );

    InfrastructureMutationResult releaseLease(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureOwner owner
    );

    InfrastructureEditPermitResult acquireEditPermit(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureEditRequest request
    );

    List<InfrastructureLeaseSnapshot> leases(UUID worldId);

    /** Read-only diagnostics for claims created by the protected route-resource service. */
    List<InfrastructureClaimSnapshot> liveClaims(UUID worldId);

    /**
     * Removes providers, leases, and edit permits directly owned by this plugin instance.
     * Host-owned route grants remain protected until their normal physical release and explicit
     * grant end.
     */
    void unregisterOwner(Plugin pluginOwner);
}
