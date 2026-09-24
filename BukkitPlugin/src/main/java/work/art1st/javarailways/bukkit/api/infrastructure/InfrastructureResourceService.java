package work.art1st.javarailways.bukkit.api.infrastructure;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult;
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest;
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencySnapshot;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest;
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * World-local coordination for applied-plan dependencies, exclusive live claims, and edits.
 *
 * <p>Every mutation is atomic and checks the request's topology revision against the authoritative
 * current world revision. Applied-plan dependencies may overlap each other and never reserve a
 * runtime resource. Live claims are mutually exclusive. An edit permit conflicts with both and remains
 * held until closed. Mutation methods may be called from plugin lifecycle or simulation code and
 * are serialized with JavaRailways topology changes. The protected infrastructure mutation itself
 * must still execute on its platform-required owner while the returned edit permit remains open.</p>
 *
 * <p>An applied plan declares three independent dependency categories: persistent rail IDs,
 * stable topology-node keys, and runtime resource IDs. These references only prevent conflicting
 * infrastructure edits; trains do not inspect them and runtime claims do not conflict with them.</p>
 */
public interface InfrastructureResourceService {
    /**
     * Returns the route-resource admission entry point for one plugin and railway world.
     *
     * <p>The returned service authenticates every request, handle inspection, and grant end as
     * {@code pluginOwner}. It becomes unavailable when that plugin is disabled, this host service
     * closes, or this exact world runtime unloads. A later world runtime with the same UUID does not
     * revive an old binding. Existing host-owned claims are not released merely because the caller
     * becomes unavailable. A caller must successfully {@link #unregisterOwner(Plugin)} before a
     * planned disable; Bukkit's already-fired disable event cannot be cancelled and therefore
     * stalls each affected railway world when route resources are still active.</p>
     *
     * @throws IllegalStateException if the plugin is disabled or route admission is not attached
     *                               for the requested world
     */
    RouteResourceAdmissionService routeAdmission(Plugin pluginOwner, UUID worldId);

    /**
     * Registers the authoritative resolver for one namespaced stable resource type.
     * Only one enabled plugin may provide a type at a time.
     *
     * <p>{@code preparationHandlers} belongs to the same registration; kind IDs must be unique and
     * namespaced. Pass an empty collection when this resource type has no delayed preparation.</p>
     *
     * <p>Closing the returned registration is guarded by the same active-reference check as
     * {@link #unregisterOwner(Plugin)} and may be retried after routes release and applied plans
     * remove every reference to the type.</p>
     */
    InfrastructureResourceProviderRegistration registerResourceProvider(
        Plugin pluginOwner,
        String typeId,
        InfrastructureResourceProvider provider,
        Collection<InfrastructureResourcePreparationHandler> preparationHandlers
    );

    /**
     * Registers one signalling-policy contributor whose results are collected inside route
     * admission. The caller of {@link #routeAdmission(Plugin, UUID)} never sees those resources.
     * Contributor membership cannot change while any route state remains active.
     */
    RouteResourceContributorRegistration registerRouteResourceContributor(
        Plugin pluginOwner,
        RouteResourceContributor contributor
    );

    /**
     * Atomically installs or replaces one applied plan's complete non-exclusive dependency set.
     * A replacement must keep the owner ID and advance its owner revision. Dependencies only block
     * intersecting edits; they never conflict with route claims.
     */
    AppliedPlanDependencyMutationResult setAppliedPlanDependencies(
        Plugin pluginOwner,
        UUID worldId,
        AppliedPlanDependencyRequest request
    );

    /** Removes the exact applied-plan owner revision without changing any live route claim. */
    AppliedPlanDependencyMutationResult removeAppliedPlanDependencies(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureOwner owner
    );

    /**
     * Checks one exact rail/node/resource impact and, on success, returns a permit that must remain
     * open through the complete topology and persistence transaction.
     */
    InfrastructureEditPermitResult acquireEditPermit(
        Plugin pluginOwner,
        UUID worldId,
        TopologyEditRequest request
    );

    /** Returns detached diagnostics for the currently applied plan dependency declarations. */
    List<AppliedPlanDependencySnapshot> appliedPlanDependencies(UUID worldId);

    /** Read-only diagnostics for claims created by the protected route-resource service. */
    List<InfrastructureClaimSnapshot> liveClaims(UUID worldId);

    /**
     * Prepares this plugin instance for a planned disable by removing providers, edit permits, and
     * empty route-admission state. Every applied plan dependency must be explicitly removed first.
     *
     * <p>The operation refuses to unregister while any route request is pending, its host-owned
     * claim still contains resources, an applied plan remains installed, or a stable resource type
     * registered by this plugin is still used by another requester's route. The plugin must remain
     * enabled and continue its normal lifecycle until a later call succeeds. This method does not
     * disable the Bukkit plugin and is not called as a substitute for handling the non-cancellable
     * disable event.</p>
     *
     * @throws IllegalStateException if the service is closed or this plugin still has active route
     *                               resources in any registered railway world
     */
    void unregisterOwner(Plugin pluginOwner);
}
