package example.signaladdon;

import org.bukkit.Server;
import work.art1st.javarailways.bukkit.api.simulation.SimulationFeatureService;
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature;
import work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget;
import org.bukkit.Material;
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
import work.art1st.javarailways.bukkit.api.model.ItemAppearance;
import work.art1st.javarailways.bukkit.api.model.ModelAnimation;
import work.art1st.javarailways.bukkit.api.model.ModelDefinition;
import work.art1st.javarailways.bukkit.api.model.ModelPart;
import work.art1st.javarailways.bukkit.api.model.ModelPose;
import work.art1st.javarailways.bukkit.api.model.ModelScale;
import work.art1st.javarailways.bukkit.api.model.ModelPresentation;
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService;
import work.art1st.javarailways.bukkit.api.model.ModelHandle;
import work.art1st.javarailways.bukkit.api.model.ModelRay;
import work.art1st.javarailways.bukkit.api.model.ModelPick;
import work.art1st.javarailways.bukkit.api.model.ModelSelectionBox;
import work.art1st.javarailways.bukkit.api.model.asset.CompiledModelAsset;
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetImportMode;
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompilation;
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompiler;
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlSource;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest;
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest;
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult;
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource;
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditBlockerKind;
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact;
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest;
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus;
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile;
import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile;
import work.art1st.javarailways.basic.api.ato.VehicleDeviceEquipment;
import work.art1st.javarailways.basic.api.ato.VehicleDeviceState;
import work.art1st.javarailways.basic.api.ato.DynamicsProfileSource;
import work.art1st.javarailways.basic.api.ato.ConsistDynamicsActuator;
import work.art1st.javarailways.basic.api.ato.PreparedDynamicsChange;
import work.art1st.javarailways.basic.api.ato.ConfigurationDynamicsActuator;
import work.art1st.javarailways.basic.api.ato.PreparedConfigurationDynamicsChange;
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
import work.art1st.javarailways.basic.api.route.ExpandedRoute;
import work.art1st.javarailways.basic.api.route.PlannedRoute;
import work.art1st.javarailways.basic.api.route.RailViewRef;
import work.art1st.javarailways.basic.api.route.RouteSegment;
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions;
import work.art1st.javarailways.basic.api.route.planning.OperationalRouteBlocker;
import work.art1st.javarailways.basic.api.route.planning.OperationalRouteLocation;
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanner;
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningInput;
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningRejection;
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningResult;
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState;
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
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearance;
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearanceState;
import work.art1st.javarailways.simpleato.api.inspection.InspectionRestrictionIdentity;
import work.art1st.javarailways.simpleato.api.inspection.InspectionClearanceSnapshot;
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource;
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher;
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot;
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding;
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding;
import work.art1st.javarailways.simpleato.api.line.CompiledLineLeg;
import work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg;
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint;
import work.art1st.javarailways.simpleato.api.mission.WayPointRef;
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult;
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Compile-only fixture for an independently built JavaRailways add-on. The adjacent README explains
 * the lifecycle and threading contract exercised by this source.
 *
 * <p>This source set may import Bukkit and documented JavaRailways API packages, but must not import
 * BukkitPlugin implementation packages.</p>
 */
public final class SignalApiConsumerPlugin extends JavaPlugin {
    /** Invoke on the viewer owner; tool release closes the returned handle. */
    public work.art1st.javarailways.bukkit.api.editor.EditorTextHandle showEditorText(org.bukkit.entity.Player viewer) {
        var service = Objects.requireNonNull(getServer().getServicesManager().load(
            work.art1st.javarailways.bukkit.api.editor.EditorTextService.class));
        var handle = service.create(this, viewer, "boundary-editor");
        handle.submit(0, List.of(new work.art1st.javarailways.bukkit.api.editor.EditorTextLabel(
            "entry", 10, 65, 20, net.kyori.adventure.text.Component.text("Entry")),
            work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels.at("arrow",
                work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph.DIRECTION,
                new org.joml.Vector3d(10, 64, 20), new org.joml.Vector3d(0, 0, 1),
                new org.joml.Vector3d(0, 1, 0), 1, 2, net.kyori.adventure.text.format.NamedTextColor.AQUA)));
        return handle;
    }

    /** Observe completion while the JavaRailways host is still enabled. */
    public CompletionStage<Void> closeEditorText() {
        return Objects.requireNonNull(getServer().getServicesManager().load(
            work.art1st.javarailways.bukkit.api.editor.EditorTextService.class)).closeOwner(this);
    }

    /** An add-on may still construct the immutable ordinary model API directly. */
    public ModelDefinition ordinaryModelDefinition() {
        var lamp = new ItemAppearance(Material.PAPER, "example.signaladdon:signals/lamp", 42,
            new ItemAppearance.Brightness(15, 15));
        var animation = new ModelAnimation("Lamp", List.of(
            new ModelAnimation.Frame(0.0, "Base", ModelPose.IDENTITY, ModelScale.UNIT, null),
            new ModelAnimation.Frame(1.0, "Shown", new ModelPose(0, 1, 0, 0, 0, 0, 1),
                ModelScale.UNIT, lamp)
        ));
        var item = new ModelPart.Item(ModelPose.IDENTITY, ModelScale.UNIT,
            new ItemAppearance(Material.PAPER, null, null, null), List.of(animation));
        return new ModelDefinition(List.of(new ModelPart.Group(ModelPose.IDENTITY, List.of(item), List.of())),
            List.of(new ModelSelectionBox(-0.5, 0, -0.5, 0.5, 2, 0.5)));
    }

    /** The caller captures the ray on the player owner thread and applies block occlusion there. */
    public java.util.Optional<ModelPick> pickOrdinaryModel(UUID worldId, ModelRay capturedRay) {
        return Objects.requireNonNull(getServer().getServicesManager().load(ModelPresentationService.class))
            .pick(this, worldId, capturedRay);
    }

    /** Captured package text can be compiled on an add-on worker without Bukkit world access. */
    public Map<String, CompiledModelAsset> compileModelAssets(Map<String, String> capturedModels) {
        var sources = new LinkedHashMap<String, ModelAssetYamlSource>();
        capturedModels.forEach((id, yaml) ->
            sources.put(id, new ModelAssetYamlSource("model/" + id + ".yml", yaml))
        );
        return ModelAssetYamlCompiler.compile(new ModelAssetYamlCompilation(
            "example.signaladdon",
            sources,
            Map.of(),
            ModelAssetImportMode.MODELS
        ));
    }

    private Services services;
    private ModelHandle ordinaryModel;
    private CompletionStage<Void> modelCleanup;
    private RailwayObservationSubscription observationSubscription;
    private AutoCloseable topologySubscription;
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

    /** Value-only submission; completion of entity updates is deliberately asynchronous. */
    public void presentOrdinaryModel(long revision, ModelPose pose) {
        if (ordinaryModel == null) {
            ordinaryModel = services.models().create(this, worldId, "home-display", ordinaryModelDefinition());
        }
        ordinaryModel.submit(new ModelPresentation(revision, pose, Map.of("Lamp", "Shown")));
    }

    /** Stop this add-on's producers first, then observe this stage before disabling JavaRailways. */
    public CompletionStage<Void> closeModelsBeforeHostDisable() {
        if (ordinaryModel != null) {
            ordinaryModel.close();
            ordinaryModel = null;
        }
        modelCleanup = services.models().closeOwner(this);
        return modelCleanup;
    }

    /** Compile a real external Java registration without linking host implementation classes. */
    public AutoCloseable registerStationResolver(
        work.art1st.javarailways.bukkit.api.station.StationCallResolverService service,
        UUID worldId
    ) {
        return service.register(this, worldId, new StationResolver());
    }

    private static final class StationResolver implements
        work.art1st.javarailways.simpleato.api.station.StationCallResolutionProvider,
        work.art1st.javarailways.simpleato.api.station.StationCallResolutionSimulationFactory {
        @Override
        public work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult resolve(
            work.art1st.javarailways.simpleato.api.station.StationCallResolutionContext context
        ) {
            return new work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult.Success(
                context.getDefaultResolution());
        }
        @Override
        public work.art1st.javarailways.simpleato.api.station.StationCallResolutionSimulationPreparation prepareSimulation() {
            return targetWorld -> new StationResolver();
        }
    }

    /** Compiles against the public preparation boundary, without linking host implementation types. */
    public AutoCloseable registerSimulationPreparation(SimulationFeatureService service) {
        return service.register(this, sourceWorldId -> new PreparedSimulationFeature() {
            @Override public String getId() { return getName().toLowerCase(java.util.Locale.ROOT); }
            @Override public AutoCloseable install(SimulationDomainTarget target) {
                var rails = target.getTopology().getRailProjection().keySet();
                target.operatingDirections(rails);
                target.railSpeedLimits(rails);
                var signals = target.installSignalSources(List.of());
                var inspections = target.installInspectionSources(List.of());
                var planning = target.installRoutePlanningSources(List.of());
                var contributor = target.registerRouteContributor("example.signaladdon:routes", intent -> List.of());
                return () -> {
                    contributor.close();
                    planning.close();
                    inspections.close();
                    signals.close();
                };
            }
        });
    }

    @Override
    public void onEnable() {
        services = loadServices(getServer());
        worldId = getServer().getWorlds().getFirst().getUID();
        topologySubscription = services.infrastructure().observeTopologyChanges(this, worldId, change -> {
            Objects.requireNonNull(change.getPrevious());
            Objects.requireNonNull(change.getCurrent());
            Objects.requireNonNull(change.getAffectedRailIds());
            // Invalidate only indexed dependencies here; dispatch compilation to the add-on worker.
        });
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
            work.art1st.javarailways.basic.api.route.RailOperatingDirections directionSubset =
                services.infrastructure().operatingDirections(worldId, topology, List.of());
            directionSubset.getDirections(); // null facing means both; no host implementation imports
            java.util.Map<UUID, Double> staticSpeedSubset =
                services.infrastructure().railSpeedLimits(worldId, topology, List.of());
            staticSpeedSubset.size(); // present null means no finite CURVE/SUBGRADE limit
            if (topology.getRevision() != observation.getStamp().getTopologyRevision()) {
                throw new IllegalStateException("Observation and topology root are not coherent");
            }
            var epochSources = List.of(compileReactiveSignalApi(observation));
            var inspectionSources = List.of(new InspectionSource(
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
                    route -> List.of(new StableInfrastructureResource("example.signaladdon:signal", "home")),
                    (route, proposedMovingRoute) -> null
                ));
            var planningSources = List.of(new RoutePlanningRestrictionBinding(
                    "example.signaladdon:route-table",
                    sourceTopology -> RoutePlanningRestrictions.Companion.unrestricted(sourceTopology)
                ));
            try {
                services.signalSources().replaceSources(this, worldId, epochSources);
                services.inspectionSources().replaceInspectionSources(this, worldId, inspectionSources);
                services.routePlanningSources().replaceRoutePlanningSources(this, worldId, planningSources);
            } catch (RuntimeException installFailure) {
                try {
                    services.signalSources().retireSources(
                        this, worldId, epochSources, inspectionSources, planningSources
                    );
                } catch (RuntimeException retirementFailure) {
                    installFailure.addSuppressed(retirementFailure);
                }
                throw installFailure;
            }
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
            Function<ManualTurnoutSelectionRequest, java.util.concurrent.CompletableFuture<ManualTurnoutSelectionResult>> selectTurnout =
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
        if (topologySubscription != null) {
            try {
                topologySubscription.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot close topology subscription", failure);
            }
            topologySubscription = null;
        }
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
            closeModelsBeforeHostDisable();
            services.infrastructure().unregisterOwner(this);
            routeContributorRegistration = null;
            resourceProviderRegistration = null;
        }
    }

    Services services() {
        return Objects.requireNonNull(services, "plugin is not enabled");
    }

    /** Compile-only coverage for the public route types shared by line and mission APIs. */
    private static AtoMissionLeg compileOperationalRouteApi(UUID railId) {
        var traversal = new ExpandedRoute(
            "origin", "destination",
            List.of(new RouteSegment(new RailViewRef(railId, ViewDirection.BA), 5.0, 125.0))
        );
        var lineLeg = new CompiledLineLeg(
            "origin-station", "destination-station", "origin", "destination", traversal
        );
        var plannedRoute = new PlannedRoute("example.signaladdon:route/main", 1L, lineLeg.getRoute());
        return new AtoMissionLeg(0, new AtoMissionPoint.Via(new WayPointRef("destination")), plannedRoute);
    }

    /** Compile-only visibility coverage for the first-party planning contract, not an add-on compatibility promise. */
    private static TopologyRoutePlanningResult compileTopologyPlanningApi(
        TopologyState topology, RoutePlanningRestrictions restrictions, UUID startRailId, UUID endRailId
    ) {
        var start = new OperationalRouteLocation(startRailId, 0.0);
        var end = new OperationalRouteLocation(endRailId, 5.0);
        var input = new TopologyRoutePlanningInput(
            topology, restrictions, start, end, "example.signaladdon:planning/main", 1L,
            ViewDirection.AB, null, false, List.of(new OperationalRouteBlocker(start, null)), 100_000
        );
        TopologyRoutePlanner.isValidLocation(topology, start);
        TopologyRoutePlanner.invalidRestrictionsReason(topology, restrictions);
        var result = TopologyRoutePlanner.plan(input);
        if (result instanceof TopologyRoutePlanningResult.Planned planned) {
            RouteSnapshot route = planned.getRoute();
            TopologyRoutePlanner.invalidRouteReason(topology, route);
        } else if (result instanceof TopologyRoutePlanningResult.Rejected rejected) {
            TopologyRoutePlanningRejection reason = rejected.getReason();
            String detail = rejected.getDetail();
        }
        return result;
    }

    /** Compile-only first-party dynamics contracts; this does not install the returned updates. */
    private static List<work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate> compileDynamicsPreparationApi(
        TrainDynamicsProfile trainProfile, List<CarDynamicsProfile> carProfiles,
        List<work.art1st.javarailways.core.api.physics.train.Car> cars, DynamicsProfileSource source,
        ConsistDynamicsActuator consist, ConfigurationDynamicsActuator configuration
    ) {
        CarDynamicsProfile carProfile = source.dynamicsProfileFor(cars.getFirst());
        VehicleDeviceState devices = source.dynamicsDeviceStateFor(cars.getFirst());
        VehicleDeviceEquipment equipment = carProfiles.getFirst().getEquipment();
        PreparedDynamicsChange structural = consist.prepareDynamicsChange(cars, source);
        double structuralDeceleration = structural.getServiceDecelerationMetersPerSecondSquared();
        double structuralReaction = structural.getReactionTimeSeconds();
        List<work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate> structuralUpdates = structural.getUpdates();
        PreparedConfigurationDynamicsChange replacement = configuration.prepareConfigurationChange(trainProfile, carProfiles);
        double configurationDeceleration = replacement.getServiceDecelerationMetersPerSecondSquared();
        double configurationReaction = replacement.getReactionTimeSeconds();
        return replacement.getUpdates();
    }

    /** Compile-only first-party track queries, with timing/currentness owned by the caller. */
    private static Map<UUID, Double> compilePhysicalTrackQueries(
        work.art1st.javarailways.core.api.physics.train.Train train,
        work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot frame,
        work.art1st.javarailways.core.api.physics.curve.PrimeCurve orientedCurve
    ) {
        work.art1st.javarailways.basic.api.ato.RailGradeBounds.minimumGrade(orientedCurve);
        work.art1st.javarailways.basic.api.ato.RailGradeBounds.mostAdverseOccupiedGrade(train);
        return work.art1st.javarailways.basic.api.ato.NativeBodySpeedLimitsKt.nativeBodySpeedLimits(train, frame);
    }

    /** Compile-only two-phase probing; route/perception policy remains with the caller. */
    private static void compileSelectedTrackAheadApi(
        work.art1st.javarailways.core.api.physics.train.Train train,
        RailPositionSnapshot head,
        work.art1st.javarailways.basic.api.rail.RailObjectStore objects
    ) {
        var path = work.art1st.javarailways.basic.api.ato.SelectedTrackAheadKt
            .traceSelectedTrackAhead(train.getWorld(), head, 100.0);
        long topologyRevision = path.getTopologyRevision();
        double coveredLength = path.getCoveredLengthMeters();
        var segment = path.getSegments().getFirst();
        work.art1st.javarailways.core.api.physics.rail.RailView liveView = segment.getRailView();
        double intervalLength = segment.getEndArcLength() - segment.getStartArcLength();
        var bounded = work.art1st.javarailways.basic.api.ato.SelectedTrackAheadKt
            .traceBoundedSelectedTrackAhead(train.getWorld(), head, 100.0, 256);
        List<work.art1st.javarailways.core.api.physics.rail.RailObject> staticObjects =
            work.art1st.javarailways.basic.api.ato.SelectedTrackAheadKt.staticTrackObjects(liveView);
        java.util.Collection<work.art1st.javarailways.core.api.physics.train.TrainEndRailObject> liveEnds =
            work.art1st.javarailways.basic.api.ato.SelectedTrackAheadKt.trackTrainEnds(liveView);
        var observations = work.art1st.javarailways.basic.api.ato.SelectedTrackAheadKt
            .observeTrackObjects(train, path, objects, 0.1, 3.0);
        for (var observation : observations.values()) {
            var snapshot = observation.getSnapshot();
            double distance = observation.getDistanceMeters();
            Double relativeSpeed = observation.getRelativeSpeedMetersPerSecond();
            if (observation.getRailObject() instanceof
                work.art1st.javarailways.core.api.physics.train.TrainEndRailObject end) {
                work.art1st.javarailways.core.api.physics.train.Train owner = end.getTrainOrNull();
                boolean headEnd = end.isHead();
            }
        }
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
            new RouteBoundary(100.0),
            null
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
            List.of(declaration),
            List.of(new work.art1st.javarailways.simpleato.api.inspection.InspectionSpeedRange(
                "example.signaladdon:slow-section",
                new work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction(
                    "example.signaladdon:slow-section/visit-0", 10.0, 20.0 / 3.6, route.getTotalLengthMeters())
            )),
            List.of(new InspectionEntryClearance(declaration.getDeclarationId(), 80.0, 90.0,
                List.of(new InspectionRestrictionIdentity("example.signaladdon:main", "example.signaladdon:home-red")))),
            List.of()
        );
    }

    private static InspectionClearanceSnapshot compileClearanceValues(
        work.art1st.javarailways.core.api.physics.train.Train train, RailwayStateStamp stamp, RouteSnapshot route
    ) {
        var frame = train.getPublishedFrame(); // Null explicitly means no physical clearance proof.
        if (frame != null) Objects.requireNonNull(frame.getBodyTraversal());
        return new InspectionClearanceSnapshot("example.signaladdon:main", train.getId(), stamp,
            FrozenRouteBinding.from(route), List.of(new InspectionEntryClearanceState(
                "example.signaladdon:home-entry", 80.0, 90.0, 100.0, 102.0, false)));
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
            List.of(),
            List.of("example.signaladdon:main"),
            input -> {
                Objects.requireNonNull(input.getObservation());
                Objects.requireNonNull(input.getAreaUpdates());
                Objects.requireNonNull(input.getAdmissionUpdates());
                input.getEntryClearanceUpdates().forEach(update -> {
                    Objects.requireNonNull(update.getRoute());
                    update.getClearances().forEach(clearance -> Objects.requireNonNull(clearance.getDeclarationId()));
                });
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
            ),
            Objects.requireNonNull(
                serviceManager.load(ModelPresentationService.class),
                "JavaRailways did not register ModelPresentationService"
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
        RoutePlanningRestrictionSourceService routePlanningSources,
        ModelPresentationService models
    ) {
        Services {
            Objects.requireNonNull(railTargets, "railTargets");
            Objects.requireNonNull(playerOperations, "playerOperations");
            Objects.requireNonNull(observations, "observations");
            Objects.requireNonNull(infrastructure, "infrastructure");
            Objects.requireNonNull(signalSources, "signalSources");
            Objects.requireNonNull(inspectionSources, "inspectionSources");
            Objects.requireNonNull(routePlanningSources, "routePlanningSources");
            Objects.requireNonNull(models, "models");
        }
    }
}
