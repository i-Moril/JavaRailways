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

import java.util.List;
import java.util.Objects;

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
