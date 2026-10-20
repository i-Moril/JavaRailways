package work.art1st.javarailways.bukkit.api.infrastructure;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencySnapshot;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureClaimSnapshot;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult;
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest;
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot;
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService;
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState;
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * World-local coordination for non-exclusive edit dependencies, exclusive live claims, and edits.
 *
 * <p>Every mutation is atomic and validates current referenced infrastructure. Edit commits retain
 * local dependency checks and apply deltas to the current topology to prevent lost changes. Dependencies may overlap and never reserve a
 * runtime resource. Live claims are mutually exclusive. An edit permit conflicts with both and remains
 * held until closed. Mutation methods may be called from plugin lifecycle or simulation code and
 * are serialized with JavaRailways topology changes. The protected infrastructure mutation itself
 * must still execute on its platform-required owner while the returned edit permit remains open.</p>
 *
 * <p>A dependency owner declares three independent categories: persistent rail IDs,
 * stable topology-node keys, and runtime resource IDs. These references only prevent conflicting
 * infrastructure edits; trains do not inspect them and runtime claims do not conflict with them.</p>
 */
public interface InfrastructureResourceService {
    /**
     * Returns the exact immutable topology root currently published by one railway world.
     *
     * <p>The returned value is safe to retain and read without access to a mutable Core world. A
     * later structural edit may publish a different root. Transaction listeners invalidate only
     * derived indexes that depend on its affected rails; unaffected indexes remain usable. World
     * unload invalidates only future lookups; it does not mutate a root already returned.</p>
     *
     * @throws IllegalStateException if this service is closed or the requested railway world is
     *                               not currently registered
     */
    TopologyState currentTopology(UUID worldId);

    /**
     * Observes successful structural transactions before the new root becomes visible. The callback
     * may only invalidate indexed dependencies and hand off immutable state; graph compilation and
     * I/O belong on a worker. Exceptions fault the edited world. Closing the registration, unloading
     * this exact world runtime, or disabling its owner removes the listener; reloading the UUID
     * never revives it. Register before capturing the root used to compile dependent state.
     */
    AutoCloseable observeTopologyChanges(Plugin pluginOwner, UUID worldId, RailTopologyChangeListener listener);

    /**
     * Captures one immutable Basic operating-direction subset for the requested rails in one store read.
     * The requested rails must retain their physical identities and connections in this world;
     * missing rails or an unavailable world fail explicitly. A null facing in the returned map means both directions are allowed.
     * This read does not reserve resources and may be made from a worker or signal callback.
     */
    work.art1st.javarailways.basic.api.route.RailOperatingDirections operatingDirections(
        UUID worldId, TopologyState topology, Collection<UUID> railIds
    );

    /**
     * Captures detached static effective speed limits in metres per second for exactly these rails.
     * Each value is the minimum of Basic's CURVE and SUBGRADE limits; a present null value means
     * neither finite limit exists. Missing rails fail rather than disappearing from the map.
     * The returned map is immutable and remains unchanged by subsequent metadata edits.
     * The requested rails must retain their identities and connections before and after the read;
     * unrelated topology changes do not invalidate the request.
     * Intended for low-frequency preparation, not a live-train or signal-output lookup.
     *
     * @throws IllegalStateException if closed, unloaded, stale, or the speed provider is unavailable
     * @throws IllegalArgumentException if any requested rail is absent from the topology
     */
    java.util.Map<UUID, Double> railSpeedLimits(UUID worldId, TopologyState topology, Collection<UUID> railIds);

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
     * Selects one legal turnout continuation after checking live route claims and physical occupancy.
     *
     * <p>The request uses stable rail-endpoint identities in one topology revision. The call is
     * nonblocking and queues the complete operation between physical frames. The future completes
     * after claim checks, physical occupancy checks, selection and coherent world publication.
     * A successful operation mutates the live turnout selection, but never creates a route grant,
     * movement authority, persisted plan, or long-lived resource claim. The returned result is a
     * detached value; a retired plugin/world binding returns {@code WORLD_UNAVAILABLE}. Coordinator
     * shutdown or failed physics completes the future exceptionally. Do not wait for the future on
     * the simulation owner that drains the queue; completion does not confer Bukkit entity ownership.</p>
     */
    CompletableFuture<ManualTurnoutSelectionResult> selectTurnout(
        Plugin pluginOwner,
        UUID worldId,
        ManualTurnoutSelectionRequest request
    );

    /**
     * Registers the authoritative resolver for one namespaced stable resource type.
     * Only one enabled plugin may provide a type at a time.
     *
     * <p>{@code preparationHandlers} belongs to the same registration; kind IDs must be unique and
     * namespaced. Pass an empty collection when this resource type has no delayed preparation.</p>
     *
     * <p>Closing the returned registration is guarded by the same active-reference check as
     * {@link #unregisterOwner(Plugin)} and may be retried after routes release and dependencies
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
     * Atomically installs or replaces one owner's complete non-exclusive dependency set.
     * A replacement must keep the owner ID and advance its owner revision. Dependencies only block
     * intersecting edits; they never conflict with route claims.
     */
    InfrastructureDependencyMutationResult setInfrastructureDependencies(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureDependencyRequest request
    );

    /** Removes the exact dependency owner revision without changing any live route claim. */
    InfrastructureDependencyMutationResult removeInfrastructureDependencies(
        Plugin pluginOwner,
        UUID worldId,
        InfrastructureOwner owner
    );

    /**
     * Checks one exact rail/node/resource impact and, on success, returns a permit that must remain
     * open through the complete topology and persistence transaction. Run synchronous structural
     * mutations inside {@link InfrastructureEditPermit#execute(java.util.function.Supplier)}.
     */
    InfrastructureEditPermitResult acquireEditPermit(
        Plugin pluginOwner,
        UUID worldId,
        TopologyEditRequest request
    );

    /** Returns detached diagnostics for current non-exclusive dependency declarations. */
    List<InfrastructureDependencySnapshot> infrastructureDependencies(UUID worldId);

    /** Read-only diagnostics for claims created by the protected route-resource service. */
    List<InfrastructureClaimSnapshot> liveClaims(UUID worldId);

    /**
     * Returns immutable value snapshots of routes currently preparing or holding a committed grant.
     *
     * <p>The host reads the world-local admission owner inside the serialized railway simulation
     * context. Callers should capture this list once per signal update, rather than once per signal
     * device. Results are ordered by logical infrastructure owner and are safe to retain after the
     * call; they contain no live registry, train, world, handle, or grant object. Every entry
     * exposes the boundary addressed by that request. A preparing entry has no committed authority;
     * a granted entry exposes its committed route boundary. Rejected and terminal attempts are
     * absent.</p>
     *
     * <p>World unload and host shutdown invalidate future reads but do not mutate values already
     * returned. These process-local observations have no persistence or restart meaning.</p>
     *
     * @throws IllegalStateException if this service is closed or route admission is not attached
     *                               for the requested world
     */
    List<ActiveRouteAdmissionSnapshot> activeRouteAdmissions(UUID worldId);

    /**
     * Prepares this plugin instance for a planned disable by removing its infrastructure
     * registrations, edit permits, empty route-admission state, and installed signal-source sets.
     * Every infrastructure dependency must be explicitly removed first.
     *
     * <p>The operation refuses to unregister while any route request is pending, its host-owned
     * claim still contains resources, an infrastructure dependency remains installed, or a stable resource type
     * registered by this plugin is still used by another requester's route, or one of its signal
     * sources still contributes a non-empty retained train restriction. Unrelated trains and
     * installed sources whose retained streams are all empty do not block removal. All checks finish
     * before either infrastructure registrations or signal callbacks are removed. The plugin must
     * remain enabled and continue its normal lifecycle until a later call succeeds. This method does
     * not disable the Bukkit plugin and is not called as a substitute for handling the
     * non-cancellable disable event.</p>
     *
     * @throws IllegalStateException if the service is closed or this plugin still has active route
     *                               resources or non-empty signal restrictions in any registered
     *                               railway world
     */
    void unregisterOwner(Plugin pluginOwner);
}
