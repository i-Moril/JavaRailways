package example.signaladdon;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationSubscription;
import work.art1st.javarailways.bukkit.api.rail.RailTargetService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourcePreparationHandler;
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributorRegistration;
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService;
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService;
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencies;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest;
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult;
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource;
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditBlockerKind;
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact;
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest;
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot;
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot;
import work.art1st.javarailways.basic.api.route.ContributedRouteResource;
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor;
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot;
import work.art1st.javarailways.basic.api.route.ResourcePreparationState;
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket;
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState;
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult;
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode;
import work.art1st.javarailways.basic.api.route.RouteBoundary;
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService;
import work.art1st.javarailways.basic.api.route.RouteResourceIntent;
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions;
import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyEngine;
import work.art1st.javarailways.basic.api.observation.RailwayObservation;
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition;
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary;
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp;
import work.art1st.javarailways.core.api.physics.rail.RailInterval;
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef;
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot;
import work.art1st.javarailways.core.api.physics.rail.ViewDirection;
import work.art1st.javarailways.core.api.physics.curve.Curve;
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration;
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding;
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource;
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot;
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding;
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding;
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult;
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Compile-only fixture for an independently built JavaRailways add-on. The adjacent README explains
 * the lifecycle and threading contract exercised by this source.
 *
 * <p>This source set may import Bukkit and documented JavaRailways API packages, but must not import
 * BukkitPlugin implementation packages.</p>
 */
public final class SignalApiConsumerPlugin extends JavaPlugin {
    private Services services;
    private RailwayObservationSubscription observationSubscription;
    private InfrastructureResourceProviderRegistration resourceProviderRegistration;
    private RouteResourceContributorRegistration routeContributorRegistration;
    private UUID worldId;
    private InfrastructureOwner infrastructureDependencyOwner;
    private boolean infrastructureDependenciesInstalled;
    private final AtomicLong inspectionRevision = new AtomicLong();

    /** Call during placement preparation, including on a worker; s is canonical AB metres. */
    public Curve.Point sampleRail(UUID railId, double arcLengthInAbMeters) {
        var curve = services.railTargets().findCurve(worldId, railId)
            .orElseThrow(() -> new IllegalArgumentException("Missing rail " + railId));
        if (!Double.isFinite(arcLengthInAbMeters) || arcLengthInAbMeters < 0.0 ||
            arcLengthInAbMeters > curve.getTotalArcLength()) {
            throw new IllegalArgumentException("Rail position is outside the curve");
        }
        return curve.getPointAt(arcLengthInAbMeters);
    }

    @Override
    public void onEnable() {
        services = loadServices(getServer());
        worldId = getServer().getWorlds().getFirst().getUID();
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
            var topology = services.infrastructure().currentTopology(worldId);
            if (topology.getRevision() != observation.getStamp().getTopologyRevision()) {
                throw new IllegalStateException("Observation and topology root are not coherent");
            }
            services.signalSources().replaceSources(
                this,
                worldId,
                List.of(compileReactiveSignalApi(observation))
            );
            services.inspectionSources().replaceInspectionSources(
                this,
                worldId,
                List.of(new InspectionSource(
                    "example.signaladdon:main",
                    input -> inspectionDeclarationsExample(input.getTrainId(), input.getRoute()),
                    input -> {
                        Objects.requireNonNull(input.getObservationStamp());
                        Objects.requireNonNull(input.getRoute());
                        Objects.requireNonNull(input.getPerception());
                        Objects.requireNonNull(input.getActiveDeclarations());
                        return List.of(new TrainRestrictionSnapshot(
                            "example.signaladdon:main",
                            inspectionRevision.incrementAndGet(),
                            input.getPerception().getTrain().getTrainId(),
                            input.getObservationStamp(),
                            input.getRoute(),
                            List.of()
                        ));
                    },
                    route -> List.of(new StableInfrastructureResource("example.signaladdon:signal", "home"))
                ))
            );
            services.routePlanningSources().replaceRoutePlanningSources(
                this,
                worldId,
                List.of(new RoutePlanningRestrictionBinding(
                    "example.signaladdon:route-table",
                    sourceTopology -> RoutePlanningRestrictions.Companion.unrestricted(sourceTopology)
                ))
            );
            var signalResource = new StableInfrastructureResource("example.signaladdon:signal", "home");
            var editImpact = new TopologyEditImpact(List.of(), List.of(), List.of(signalResource));
            var editRequest = new TopologyEditRequest(
                observation.getStamp().getTopologyRevision(),
                new InfrastructureOwner("example.signaladdon:editor/main", 1L),
                editImpact
            );
            var editable = services.infrastructure().acquireEditPermit(this, worldId, editRequest);
            if (editable.decision().getStatus() != TopologyEditStatus.GRANTED) {
                throw new IllegalStateException("Expected an edit permit for an unprotected resource");
            }
            try (var permit = editable.permit().orElseThrow()) {
                Objects.requireNonNull(permit.owner());
                // Apply and persist the exact resource edit while this permit remains open.
            }

            infrastructureDependencyOwner = new InfrastructureOwner("example.signaladdon:dependency/main", 1L);
            var request = new InfrastructureDependencyRequest(
                observation.getStamp().getTopologyRevision(),
                infrastructureDependencyOwner,
                new InfrastructureDependencies(List.of(), List.of(), List.of(
                    signalResource
                ))
            );
            var result = services.infrastructure().setInfrastructureDependencies(this, worldId, request);
            infrastructureDependenciesInstalled = result.getSuccessful();
            getLogger().fine("Infrastructure dependency result: " + result.getStatus());
            if (infrastructureDependenciesInstalled) {
                var denied = services.infrastructure().acquireEditPermit(this, worldId, editRequest);
                var reportsDependency = denied.decision().getBlockers().stream().anyMatch(blocker ->
                    blocker.getKind() == TopologyEditBlockerKind.INFRASTRUCTURE_DEPENDENCY &&
                        blocker.getOverlap().getResourceIds().contains(signalResource)
                );
                if (denied.decision().getStatus() != TopologyEditStatus.CONFLICT ||
                    denied.permit().isPresent() || !reportsDependency) {
                    throw new IllegalStateException("Infrastructure edit conflict did not return exact diagnostics");
                }
            }

            // Independent add-ons receive an authenticated, world-bound admission service. The
            // fixture builds a request but does not submit its synthetic random route at runtime.
            var routeAdmission = services.infrastructure().routeAdmission(this, worldId);
            services.infrastructure().activeRouteAdmissions(worldId).forEach(active -> {
                Objects.requireNonNull(active.getRoute());
                Objects.requireNonNull(active.getRequestedAuthority());
                if (active.getState() == ActiveRouteAdmissionState.GRANTED) {
                    Objects.requireNonNull(active.getCommittedAuthority());
                }
            });
            var routeIntent = routeIntentExample(observation.getStamp());
            var inspectionDeclarations = inspectionDeclarationsExample("example-train", routeIntent.getRoute());
            var exampleInspectionRevision = new AtomicLong();
            InspectionUpdateHandler inspectionHandler = input -> {
                Objects.requireNonNull(input.getObservationStamp());
                Objects.requireNonNull(input.getRoute());
                Objects.requireNonNull(input.getPerception());
                Objects.requireNonNull(input.getActiveDeclarations());
                return List.of(new TrainRestrictionSnapshot(
                    "example.signaladdon:main",
                    exampleInspectionRevision.incrementAndGet(),
                    input.getPerception().getTrain().getTrainId(),
                    input.getObservationStamp(),
                    input.getRoute(),
                    List.of()
                ));
            };
            Function<RouteResourceIntent, RouteAdmissionResult> requestRoute = routeAdmission::request;
            Function<ManualTurnoutSelectionRequest, ManualTurnoutSelectionResult> selectTurnout =
                selection -> services.infrastructure().selectTurnout(this, worldId, selection);
            getLogger().fine("Route-resource request: " + routeIntent.getRequestId());
            getLogger().fine("Inspection declarations: " + inspectionDeclarations.getDeclarations().size());
            getLogger().finest("Inspection update handler: " + inspectionHandler);
            getLogger().finest("Route-resource submitter: " + requestRoute);
            getLogger().finest("Manual turnout selector: " + selectTurnout);
        });
    }

    @Override
    public void onDisable() {
        if (infrastructureDependenciesInstalled && services != null && worldId != null &&
            infrastructureDependencyOwner != null) {
            services.infrastructure().removeInfrastructureDependencies(
                this,
                worldId,
                infrastructureDependencyOwner
            );
            infrastructureDependenciesInstalled = false;
        }
        if (observationSubscription != null) {
            observationSubscription.close();
            observationSubscription = null;
        }
        if (services != null) {
            services.infrastructure().unregisterOwner(this);
            routeContributorRegistration = null;
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

    /** Compile-only proof that a Java signal add-on can precompile immutable inspection values. */
    private static InspectionDeclarationBinding inspectionDeclarationsExample(String trainId, RouteSnapshot route) {
        var segment = route.getSegments().getFirst();
        var declaration = new InspectionDeclaration(
            "example.signaladdon:home-entry",
            segment.getRailId(),
            segment.getFacing(),
            80.0,
            12.0,
            null
        );
        return new InspectionDeclarationBinding(
            "example.signaladdon:main",
            trainId,
            FrozenRouteBinding.from(route),
            1L,
            1L,
            List.of(declaration)
        );
    }

    /** Compile-only proof that the reactive signal slice is consumable from ordinary Java. */
    private static SignalEpochSource compileReactiveSignalApi(RailwayObservation observation) {
        var railId = UUID.randomUUID();
        var area = RailwayOccupancyAreaDefinition.create(
            new RailObjectRef("example.signaladdon", "home-block"),
            1L,
            observation.getStamp().getTopologyRevision(),
            List.of(new RailInterval(railId, 0.0, 100.0)),
            List.of(new RailwayOccupancyBoundary(
                new RailObjectRef("example.signaladdon", "home-entry"),
                new RailPositionSnapshot(railId, ViewDirection.AB, 0.0)
            ))
        );
        var occupancy = RailwayAreaOccupancyEngine.create(List.of(area));
        var baseline = occupancy.consume(observation);
        var source = new SignalEpochSource(
            "example.signaladdon:main",
            List.of(area),
            List.of(),
            List.of(railId),
            input -> {
                Objects.requireNonNull(input.getObservation());
                Objects.requireNonNull(input.getAreaUpdates());
                Objects.requireNonNull(input.getAdmissionUpdates());
                input.getAdmissionUpdates().forEach(update -> {
                    Objects.requireNonNull(update.getOwner());
                    if (update.getCurrent() != null) {
                        var admission = update.getCurrent();
                        Objects.requireNonNull(admission.getRequestedAuthority());
                        if (admission.getReleasedThroughRouteMeters() < 0.0) {
                            throw new IllegalStateException("invalid released tail lower bound");
                        }
                    }
                });
                return SignalEpochResult.empty();
            }
        );
        TrainRestrictionPublisher publisher = snapshot -> new TrainRestrictionPublicationResult(
            TrainRestrictionPublicationStatus.ACCEPTED,
            snapshot.getRevision()
        );
        Objects.requireNonNull(publisher);
        Objects.requireNonNull(baseline.getUpdates());
        return source;
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
                serviceManager.load(InfrastructureResourceService.class),
                "JavaRailways did not register InfrastructureResourceService"
            ),
            Objects.requireNonNull(
                serviceManager.load(SignalEpochSourceService.class),
                "JavaRailways did not register SignalEpochSourceService"
            ),
            Objects.requireNonNull(
                serviceManager.load(InspectionSourceService.class),
                "JavaRailways did not register InspectionSourceService"
            ),
            Objects.requireNonNull(
                serviceManager.load(RoutePlanningRestrictionSourceService.class),
                "JavaRailways did not register RoutePlanningRestrictionSourceService"
            )
        );
    }

    record Services(
        RailTargetService railTargets,
        PlayerOperationService playerOperations,
        RailwayObservationService observations,
        InfrastructureResourceService infrastructure,
        SignalEpochSourceService signalSources,
        InspectionSourceService inspectionSources,
        RoutePlanningRestrictionSourceService routePlanningSources
    ) {
        Services {
            Objects.requireNonNull(railTargets, "railTargets");
            Objects.requireNonNull(playerOperations, "playerOperations");
            Objects.requireNonNull(observations, "observations");
            Objects.requireNonNull(infrastructure, "infrastructure");
            Objects.requireNonNull(signalSources, "signalSources");
            Objects.requireNonNull(inspectionSources, "inspectionSources");
            Objects.requireNonNull(routePlanningSources, "routePlanningSources");
        }
    }
}
