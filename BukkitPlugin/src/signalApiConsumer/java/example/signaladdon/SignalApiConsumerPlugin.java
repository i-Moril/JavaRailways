package example.signaladdon;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationSubscription;
import work.art1st.javarailways.bukkit.api.rail.RailTargetService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureLeaseService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourcePreparationHandler;
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributorRegistration;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot;
import work.art1st.javarailways.basic.api.route.ContributedRouteResource;
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor;
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot;
import work.art1st.javarailways.basic.api.route.ResourcePreparationState;
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket;
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult;
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode;
import work.art1st.javarailways.basic.api.route.RouteBoundary;
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService;
import work.art1st.javarailways.basic.api.route.RouteResourceIntent;
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp;
import work.art1st.javarailways.core.api.physics.rail.ViewDirection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Compile-only fixture for an independently built JavaRailways add-on.
 *
 * <p>This source set may import Bukkit and documented JavaRailways API packages, but must not import
 * BukkitPlugin implementation packages.</p>
 */
public final class SignalApiConsumerPlugin extends JavaPlugin {
    private Services services;
    private RailwayObservationSubscription observationSubscription;
    private InfrastructureResourceProviderRegistration resourceProviderRegistration;
    private RouteResourceContributorRegistration routeContributorRegistration;

    @Override
    public void onEnable() {
        services = loadServices(getServer());
        var worldId = getServer().getWorlds().getFirst().getUID();
        services.observations().latest(worldId).ifPresent(observation ->
            getLogger().fine("Latest railway epoch: " + observation.getStamp().getPhysicsEpoch())
        );
        observationSubscription = services.observations().subscribe(this, worldId, (publishedWorldId, observation) ->
            getLogger().fine("Observed railway epoch: " + observation.getStamp().getPhysicsEpoch())
        );
        resourceProviderRegistration = services.infrastructure().registerResourceProvider(
            this,
            "example.signaladdon:signal",
            new InfrastructureResourceProvider() {
                @Override
                public boolean exists(UUID candidateWorld, String resourceId) {
                    return candidateWorld.equals(worldId) && resourceId.equals("home");
                }

                @Override
                public RouteAdmissionFailureCode validateRouteContribution(
                    Plugin contributor, UUID candidateWorld, String resourceId, ResourcePreparationDescriptor preparation
                ) {
                    if (contributor != SignalApiConsumerPlugin.this || !exists(candidateWorld, resourceId)) {
                        return RouteAdmissionFailureCode.INVALID_CONTRIBUTION;
                    }
                    return preparation != null &&
                        preparation.getKindId().equals("example.signaladdon:display")
                        ? null
                        : RouteAdmissionFailureCode.INVALID_PREPARATION;
                }
            },
            List.of(new InfrastructureResourcePreparationHandler() {
                @Override
                public String kindId() {
                    return "example.signaladdon:display";
                }

                @Override
                public ResourcePreparationTicket start(
                    UUID attemptId,
                    UUID candidateWorld,
                    StableInfrastructureResource resource,
                    ResourcePreparationDescriptor descriptor
                ) {
                    return new ResourcePreparationTicket() {
                        @Override
                        public ResourcePreparationSnapshot snapshot() {
                            return new ResourcePreparationSnapshot(ResourcePreparationState.READY, "");
                        }

                        @Override
                        public void abort() {
                        }
                    };
                }
            })
        );
        routeContributorRegistration = services.infrastructure().registerRouteResourceContributor(
            this,
            (candidateWorld, intent) -> candidateWorld.equals(worldId)
                ? List.of(new ContributedRouteResource(
                    new StableInfrastructureResource("example.signaladdon:signal", "home"),
                    intent.getDesiredAuthority(),
                    new ResourcePreparationDescriptor("example.signaladdon:display", java.util.Map.of())
                ))
                : List.of()
        );
        services.observations().latest(worldId).ifPresent(observation -> {
            var request = new InfrastructureLeaseRequest(
                observation.getStamp().getTopologyRevision(),
                new InfrastructureOwner("example.signaladdon:plan/main", 1L),
                new InfrastructureFootprint(List.of(
                    new StableInfrastructureResource("example.signaladdon:signal", "home")
                ))
            );
            var result = services.infrastructure().acquireLease(this, worldId, request);
            getLogger().fine("Infrastructure lease result: " + result.getStatus());

            // Independent add-ons receive an authenticated, world-bound admission service. The
            // fixture builds a request but does not submit its synthetic random route at runtime.
            var routeAdmission = services.infrastructure().routeAdmission(this, worldId);
            var routeIntent = routeIntentExample(observation.getStamp());
            Function<RouteResourceIntent, RouteAdmissionResult> requestRoute = routeAdmission::request;
            getLogger().fine("Route-resource request: " + routeIntent.getRequestId());
            getLogger().finest("Route-resource submitter: " + requestRoute);
        });
    }

    @Override
    public void onDisable() {
        if (observationSubscription != null) {
            observationSubscription.close();
            observationSubscription = null;
        }
        if (routeContributorRegistration != null) {
            routeContributorRegistration.close();
            routeContributorRegistration = null;
        }
        if (resourceProviderRegistration != null) {
            resourceProviderRegistration.close();
            resourceProviderRegistration = null;
        }
    }

    Services services() {
        return Objects.requireNonNull(services, "plugin is not enabled");
    }

    private static RouteResourceIntent routeIntentExample(RailwayStateStamp observationStamp) {
        var railId = UUID.randomUUID();
        var route = new RouteSnapshot(
            "example.signaladdon:route/main",
            1L,
            observationStamp.getTopologyRevision(),
            List.of(new RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 120.0))
        );
        return new RouteResourceIntent(
            "example.signaladdon:request/main",
            new InfrastructureOwner("example.signaladdon:route/main", 1L),
            "example-train",
            observationStamp,
            route,
            new RouteBoundary(100.0)
        );
    }

    /** Compile-only proof of pending inspection and explicit safe grant end. */
    private static RouteAdmissionResult compileAdmissionLifecycle(
        RouteResourceAdmissionService admission,
        RouteAdmissionResult result
    ) {
        if (result instanceof RouteAdmissionResult.Pending pending) {
            return admission.progress(pending.getHandle());
        }
        if (result instanceof RouteAdmissionResult.Granted granted) {
            admission.endGrant(granted.getGrant().getGrantId());
        }
        return result;
    }

    private static Services loadServices(Server server) {
        Objects.requireNonNull(server, "server");
        var serviceManager = server.getServicesManager();
        return new Services(
            Objects.requireNonNull(
                serviceManager.load(RailTargetService.class),
                "JavaRailways did not register RailTargetService"
            ),
            Objects.requireNonNull(
                serviceManager.load(PlayerOperationService.class),
                "JavaRailways did not register PlayerOperationService"
            ),
            Objects.requireNonNull(
                serviceManager.load(RailwayObservationService.class),
                "JavaRailways did not register RailwayObservationService"
            ),
            Objects.requireNonNull(
                serviceManager.load(InfrastructureLeaseService.class),
                "JavaRailways did not register InfrastructureLeaseService"
            )
        );
    }

    record Services(
        RailTargetService railTargets,
        PlayerOperationService playerOperations,
        RailwayObservationService observations,
        InfrastructureLeaseService infrastructure
    ) {
        Services {
            Objects.requireNonNull(railTargets, "railTargets");
            Objects.requireNonNull(playerOperations, "playerOperations");
            Objects.requireNonNull(observations, "observations");
            Objects.requireNonNull(infrastructure, "infrastructure");
        }
    }
}
