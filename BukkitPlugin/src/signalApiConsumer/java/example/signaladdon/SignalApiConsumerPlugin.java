package example.signaladdon;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationSubscription;
import work.art1st.javarailways.bukkit.api.rail.RailTargetService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureLeaseService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureLeaseRequest;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot;
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary;
import work.art1st.javarailways.basic.api.route.ExitClearanceEnvelope;
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult;
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus;
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow;
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest;
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef;
import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition;
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition;
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider;
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp;
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot;
import work.art1st.javarailways.core.api.physics.rail.ViewDirection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
            (candidateWorld, resourceId) -> candidateWorld.equals(worldId) && resourceId.equals("home")
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

            // Compile the protected-route contract without gaining access to raw live claims.
            var protectedRoute = protectedRouteExample(observation.getStamp());
            getLogger().fine("Protected-route request: " + protectedRoute.getRequestId());
            var regionProvider = protectionRegionProviderExample(
                protectedRoute.getRoute().getSegments().get(0).getRailId()
            );
            getLogger().fine("Protection-region provider: " + regionProvider.getProviderId());
        });
    }

    @Override
    public void onDisable() {
        if (observationSubscription != null) {
            observationSubscription.close();
            observationSubscription = null;
        }
        if (resourceProviderRegistration != null) {
            resourceProviderRegistration.close();
            resourceProviderRegistration = null;
        }
    }

    Services services() {
        return Objects.requireNonNull(services, "plugin is not enabled");
    }

    private static ProtectedRouteRequest protectedRouteExample(RailwayStateStamp observationStamp) {
        var railId = UUID.randomUUID();
        var route = new RouteSnapshot(
            "example.signaladdon:route/main",
            1L,
            observationStamp.getTopologyRevision(),
            List.of(new RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 120.0))
        );
        var entry = boundary("example.signaladdon:boundary/entry", railId, 20.0);
        var exit = boundary("example.signaladdon:boundary/exit", railId, 80.0);
        var clearance = boundary("example.signaladdon:boundary/clear", railId, 100.0);
        var window = new ProtectedRouteWindow(
            new ProtectionRegionRef("example.signaladdon:region/throat", 1L),
            entry,
            exit,
            new ExitClearanceEnvelope(clearance)
        );
        return new ProtectedRouteRequest(
            "example.signaladdon:request/main",
            new InfrastructureOwner("example.signaladdon:route/main", 1L),
            "example-train",
            observationStamp,
            route,
            window
        );
    }

    private static DirectedRouteBoundary boundary(String id, UUID railId, double metres) {
        return new DirectedRouteBoundary(
            id,
            new RailPositionSnapshot(railId, ViewDirection.AB, metres),
            metres
        );
    }

    private static ProtectionRegionProvider protectionRegionProviderExample(UUID railId) {
        return new ProtectionRegionProvider() {
            @Override
            public String getProviderId() {
                return "example.signaladdon:regions";
            }

            @Override
            public List<ProtectionRegionDefinition> definitions(long topologyRevision) {
                return List.of(new ProtectionRegionDefinition(
                    new ProtectionRegionRef("example.signaladdon:region/throat", 1L),
                    topologyRevision,
                    List.of(
                        new ProtectionBoundaryDefinition(
                            "example.signaladdon:boundary/entry",
                            new RailPositionSnapshot(railId, ViewDirection.AB, 20.0)
                        ),
                        new ProtectionBoundaryDefinition(
                            "example.signaladdon:boundary/exit",
                            new RailPositionSnapshot(railId, ViewDirection.BA, 80.0)
                        )
                    )
                ));
            }
        };
    }

    private static boolean requiresProtectionDefinition(ProtectedRouteClaimResult result) {
        return result.getStatus() == ProtectedRouteClaimStatus.PROTECTION_REQUIRED;
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
