package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.train.SimulationTrainExtension
import work.art1st.javarailways.bukkit.extension.train.simulationTrainProfileOrNull
import work.art1st.javarailways.bukkit.operation.simulationBehaviorOrNull
import work.art1st.javarailways.bukkit.operation.SimulationCarBehaviorExtension
import work.art1st.javarailways.bukkit.operation.SimulationBehaviorEffect
import work.art1st.javarailways.bukkit.operation.SimulationTrainOperationGateway
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnitAssignment
import work.art1st.javarailways.simpleato.api.depot.RollingStockSnapshot
import work.art1st.javarailways.simpleato.api.depot.RollingStockState
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.timetable.TimetableSimulationExtensions
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.simpleato.timetable.LineMaximumSpeedResolver
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationWorld
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.TrainPlacementResult

internal data class TimetableProfileSelection(
    val serviceId: String,
    val profileType: SimulationProfileType,
    val profileId: String
)

internal data class IsolatedTimetableSimulationSnapshot(
    val coreInfrastructure: CoreWorld.SavedInfrastructureV2,
    val basicInfrastructure: BasicInfrastructureSnapshotV2,
    val simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV1,
    val timetable: Timetable,
    val profilesByService: Map<String, SimulationControlProfile>,
    val gravityMetersPerSecondSquared: Double,
    val timetableExtensions: TimetableSimulationExtensions,
    val speedSafety: SpeedSafetyMonitor.Config,
    val domainFeatures: List<PreparedSimulationFeature>,
    val atoSafetyDistanceMeters: Double,
    val atoAdmissionRetryTicks: Int,
    val formationsByService: Map<String, SimulationFormationBlueprint> = emptyMap(),
    val standaloneProgramServiceIds: Set<String> = emptySet()
)

/** Captures static configuration on the live thread, then creates a runtime with no live trains. */
internal fun interface IsolatedSimulationProfileResolver {
    fun resolve(world: CoreWorld, type: SimulationProfileType, id: String): SimulationControlProfile
}

internal class IsolatedTimetableSimulationFactory(
    private val profileResolver: IsolatedSimulationProfileResolver,
    private val gravityMetersPerSecondSquared: Double,
    private val atoSafetyDistanceMeters: Double = 5.0,
    private val speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
    private val requiredDomainFeatures: (CoreWorld) -> Set<String> = { emptySet() },
    private val prepareDomainFeatures: (CoreWorld) -> List<PreparedSimulationFeature> = { emptyList() },
    private val extensionPlugin: Plugin = BukkitPlugin.INSTANCE,
    private val effectObserver: ((Car, SimulationBehaviorEffect) -> Unit)? = null
) {
    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(atoSafetyDistanceMeters.isFinite() && atoSafetyDistanceMeters >= 0.0)
    }

    constructor(
        profileCatalog: SimulationControlProfileCatalog,
        gravityMetersPerSecondSquared: Double,
        atoSafetyDistanceMeters: Double = 5.0,
        speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
        requiredDomainFeatures: (CoreWorld) -> Set<String> = { emptySet() },
        prepareDomainFeatures: (CoreWorld) -> List<PreparedSimulationFeature> = { emptyList() }
    ) : this(
        IsolatedSimulationProfileResolver { world, type, id -> profileCatalog.resolve(world, type, id) },
        gravityMetersPerSecondSquared,
        atoSafetyDistanceMeters, speedSafety, requiredDomainFeatures, prepareDomainFeatures
    )
    fun capture(
        liveWorld: CoreWorld,
        timetable: Timetable,
        selections: List<TimetableProfileSelection>,
        standaloneProgramServiceIds: Set<String> = emptySet(),
        requireRegisteredUnitsForServices: Set<String> = emptySet()
    ): IsolatedTimetableSimulationSnapshot {
        val selectionsByService = selections.associateBy(TimetableProfileSelection::serviceId)
        require(selectionsByService.size == selections.size) { "duplicate simulation profile assignment" }
        val unknown = selectionsByService.keys - timetable.services.mapTo(HashSet()) { it.id }
        require(unknown.isEmpty()) { "profile assignments contain unknown services: ${unknown.sorted().joinToString()}" }
        val initialServices = timetable.services.filterNot(::takesFormationOutput)
        val initialServiceIds = initialServices.mapTo(HashSet(), TimetableService::id)
        require((standaloneProgramServiceIds + requireRegisteredUnitsForServices).all { it in initialServiceIds }) {
            "standalone simulation or registered-unit requirements contain unknown initial services"
        }
        standaloneProgramServiceIds.forEach { serviceId ->
            val acquisition = (initialServices.single { it.id == serviceId }.steps.firstOrNull() as? TimetableStep.At)?.action
                as? TimetableAction.TakeFromWaypoint
            val wayPointId = requireNotNull(acquisition?.wayPointIds?.singleOrNull()) {
                "standalone simulation requires one initial acquisition waypoint: $serviceId"
            }
            resolveProgramSimulationWayPoint(liveWorld, wayPointId)
        }
        val unexpected = selectionsByService.keys.intersect(timetable.services.filter(::takesFormationOutput).mapTo(HashSet()) { it.id })
        require(unexpected.isEmpty()) { "formation output duties inherit their physical units and cannot select simulation profiles: ${unexpected.sorted().joinToString()}" }
        val missing = initialServices.map(TimetableService::id).filterNot(selectionsByService::containsKey)
        require(missing.isEmpty()) { "missing simulation profiles for services: ${missing.joinToString()}" }
        initialServices.groupBy(::selectorIdentity).values.forEach { equivalentServices ->
            val selectedProfiles = equivalentServices.map { service ->
                selectionsByService.getValue(service.id).let { it.profileType to it.profileId }
            }.distinct()
            require(selectedProfiles.size == 1) {
                "equivalent selectors must use one simulation profile: " +
                    equivalentServices.joinToString { it.id }
            }
        }
        val lineMaximumSpeedResolver = LineMaximumSpeedResolver(timetable)
        timetable.services.filter { it.lineId != null }.forEach { service ->
            requireNotNull(lineMaximumSpeedResolver.resolve(service)) {
                "simulation service has no configured line maximum speed: ${service.id}"
            }
        }
        val profiles = initialServices.associate { service ->
            val selection = selectionsByService.getValue(service.id)
            service.id to profileResolver.resolve(liveWorld, selection.profileType, selection.profileId)
        }.toMutableMap()
        val structuralSources = timetable.formationEvents.flatMapTo(HashSet()) { it.participantDutyIds }
        val structuralSelectors = initialServices.filter {
            it.id in structuralSources || it.id in requireRegisteredUnitsForServices
        }.mapTo(HashSet(), ::selectorIdentity)
        val formations = initialServices.filter { selectorIdentity(it) in structuralSelectors }.associate { service ->
            val (formation, normalized) = SimulationFormationBlueprint.capture(liveWorld,
                selectionsByService.getValue(service.id), profiles.getValue(service.id))
            profiles[service.id] = normalized
            service.id to formation
        }
        val features = prepareDomainFeatures(liveWorld).toList()
        require(features.map { it.id }.distinct().size == features.size) { "duplicate simulation domain feature" }
        val unavailable = requiredDomainFeatures(liveWorld) - features.map { it.id }.toSet()
        require(unavailable.isEmpty()) {
            "simulation preparation unavailable for domain features: ${unavailable.sorted().joinToString()}"
        }
        val extensions = liveWorld.simpleAtoWorldData.timetableExtensions.prepareSimulation()
        val revision = 0L
        val coreInfrastructure = liveWorld.saveInfrastructure(revision)
        return IsolatedTimetableSimulationSnapshot(
            coreInfrastructure,
            BasicInfrastructureSnapshotV2.capture(
                liveWorld,
                revision,
                liveWorld.simpleAtoWorldData.destinations.all().mapTo(HashSet()) { it.id }
            ),
            SimpleATOInfrastructureSnapshotV1.capture(liveWorld, revision),
            timetable,
            profiles,
            gravityMetersPerSecondSquared,
            extensions,
            speedSafety.copy(),
            features,
            atoSafetyDistanceMeters,
            liveWorld.simpleAtoWorldData.dispatcher.admissionRetryTicks,
            formations,
            java.util.Set.copyOf(standaloneProgramServiceIds)
        )
    }

    fun create(
        snapshot: IsolatedTimetableSimulationSnapshot,
        workerThreads: Int
    ): IsolatedTimetableSimulationWorld {
        lateinit var operationGateway: SimulationTrainOperationGateway
        return IsolatedTimetableSimulationWorld.create(
            snapshot.coreInfrastructure,
            snapshot.basicInfrastructure,
            snapshot.simpleAtoInfrastructure,
            workerThreads,
            basicDataFactory = { _, directionChanges ->
                BasicWorldData(
                    operatingDirections = work.art1st.javarailways.basic.rail.OperatingDirectionStore(directionChanges)
                )
            },
            timetableExtensions = { world -> snapshot.timetableExtensions.create(world) },
            speedSafety = snapshot.speedSafety,
            preparedFeatures = snapshot.domainFeatures,
            operationResourceFactory = { world ->
                SimulationTrainOperationGateway(world).also { operationGateway = it }
            },
            dispatcherFactory = {
                    _, routeAdmissionContextProvider, signalRestrictions, inspectionRuntime, infrastructureDependencyService ->
                TimetableDispatcher(
                    authorityStoppingMarginMeters = snapshot.atoSafetyDistanceMeters,
                    admissionRetryTicks = snapshot.atoAdmissionRetryTicks,
                    routeAdmissionContextProvider = routeAdmissionContextProvider,
                    signalRestrictionSnapshots = signalRestrictions,
                    inspectionRuntime = inspectionRuntime,
                    infrastructureDependencyService = infrastructureDependencyService,
                    standaloneSimulationServices = snapshot.standaloneProgramServiceIds.mapTo(HashSet()) {
                        TimetableServiceRunId(snapshot.timetable.id, it)
                    },
                    operationGateway = operationGateway,
                    atoFactory = TimetableAtoFactory { train, owningWorld ->
                        val profile = requireNotNull(train.simulationTrainProfileOrNull) {
                            "simulation train profile missing for train ${train.id}"
                        }
                        fun carProfile(car: work.art1st.javarailways.core.api.physics.train.Car): TrainDynamics.CarProfile? =
                            car.simulationBehaviorOrNull?.carProfile
                        val carProfiles = train.cars.map { car -> requireNotNull(carProfile(car)) {
                            "simulation car profile missing for car ${car.id}"
                        } }
                        val gravity = snapshot.gravityMetersPerSecondSquared
                        val safety = AtoSafetyProfile(
                            safetyDistanceMeters = snapshot.atoSafetyDistanceMeters,
                            serviceDecelerationMetersPerSecondSquared =
                                TrainDynamics.flatServiceDeceleration(
                                    profile,
                                    carProfiles,
                                    gravity
                                ),
                            reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                                profile, carProfiles, gravity
                            )
                        )
                        SimpleATO(
                            train,
                            owningWorld,
                            HandleTrainActuator(
                                train,
                                profile,
                                carProfiles,
                                gravity,
                                profileResolver = ::carProfile
                            ),
                            safety,
                            inspectionRuntime::maximumDeclaredLeadTimeSeconds
                        )
                    }
                )
            },
            seed = { world -> seedTimetableTrains(world, snapshot) }
        )
    }

    private fun seedTimetableTrains(
        world: CoreWorld,
        snapshot: IsolatedTimetableSimulationSnapshot
    ) {
        val occupiedStablingPoints = HashSet<String>()
        val occupiedDynamicWaypoints = HashSet<String>()
        val namedTrains = HashMap<String, CoreTrain>()
        val stocks = arrayListOf<RollingStockSnapshot>()
        val units = arrayListOf<FixedEmuUnit>()
        val claims = arrayListOf<FixedEmuUnitAssignment>()
        snapshot.timetable.services.filterNot(::takesFormationOutput).forEach { service ->
            val profile = snapshot.profilesByService.getValue(service.id)
            require(profile.cars.isNotEmpty()) { "simulation profile ${profile.id} has no physical car blueprint" }
            val namedTrainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId
            namedTrainId?.let(namedTrains::get)?.let { existingTrain ->
                world.basicWorldData.trainSpeedProfiles.put(
                    existingTrain.id,
                    TrainSpeedProfile(profile.trainControl.curveSpeedLimitOffset)
                )
                return@forEach
            }
            val acquisition = service.steps.asSequence().filterIsInstance<TimetableStep.At>()
                .map(TimetableStep.At::action)
                .firstOrNull { it is TimetableAction.TakeFromDepot || it is TimetableAction.TakeFromWaypoint }
            val placement = when (acquisition) {
                is TimetableAction.TakeFromDepot -> depotPlacement(
                    world,
                    acquisition.selector,
                    profile,
                    occupiedStablingPoints,
                    snapshot.formationsByService[service.id]?.stock
                )
                is TimetableAction.TakeFromWaypoint -> Placement(
                    acquisition.wayPointIds.firstOrNull(occupiedDynamicWaypoints::add)
                        ?: error("waypoint selector has insufficient distinct origins for simulation: ${acquisition.wayPointIds.joinToString()}"),
                    null,
                    emptySet()
                )
                else -> Placement(
                    service.steps.filterIsInstance<TimetableStep.Check>().firstOrNull()?.wayPointId
                        ?: error("service ${service.id} has no deterministic initial waypoint"),
                    null,
                    emptySet()
                )
            }
            val trainName = namedTrainId ?: "simulation-${service.id}"
            val train = createTrain(
                world,
                "simulation/${snapshot.timetable.id}/${service.id}",
                trainName,
                profile,
                placement.wayPointId
            )
            if (namedTrainId != null) namedTrains[namedTrainId] = train
            world.basicWorldData.trainSpeedProfiles.put(
                train.id,
                TrainSpeedProfile(profile.trainControl.curveSpeedLimitOffset)
            )
            val formation = snapshot.formationsByService[service.id]
            val physicalIds = train.cars.map { it.id }
            val localUnits = formation?.unitsFor(train.id, physicalIds).orEmpty()
            units += localUnits
            placement.depotSelector?.let { selector ->
                val point = requireNotNull(world.simpleAtoWorldData.depots.stablingPoints(selector.depotId)
                    .firstOrNull { it.point.destinationId == placement.wayPointId })
                val record = formation?.stock?.copy(trainId = train.id) ?: RollingStockRecord(
                        train.id,
                        selector.depotId,
                        profile.id,
                        placement.rollingStockTags,
                        profile.cars.size,
                        profile.cars.sumOf(SimulationCarBlueprint::length),
                        profile.trainControl.maxSpeed,
                        selector.requiredCapabilities
                    )
                require(selector.matches(record)) { "structural simulation source does not satisfy its depot selector" }
                stocks += RollingStockSnapshot(record, RollingStockState.AVAILABLE, point.point.ref)
            } ?: formation?.let {
                val assignment = "${train.id}/initial-assignment"
                stocks += RollingStockSnapshot(it.stock.copy(trainId = train.id), RollingStockState.IN_SERVICE, null, assignment)
                claims += localUnits.map { unit -> FixedEmuUnitAssignment(unit.id, train.id, assignment) }
            }
        }
        world.simpleAtoWorldData.depots.restoreOperationalLedger(stocks, units, claims,
            world.allTrains.associate { it.id to it.cars.map { car -> car.id } })
    }

    private fun takesFormationOutput(service: TimetableService): Boolean =
        (service.steps.firstOrNull() as? TimetableStep.At)?.action is TimetableAction.TakeFromFormationOutput

    private fun depotPlacement(
        world: CoreWorld,
        selector: DepotTrainSelector,
        profile: SimulationControlProfile,
        occupied: MutableSet<String>,
        registeredStock: RollingStockRecord? = null
    ): Placement {
        val trainLength = profile.cars.sumOf(SimulationCarBlueprint::length)
        val point = world.simpleAtoWorldData.depots.stablingPoints(selector.depotId)
            .sortedBy { it.point.ref.pointId }
            .filter { it.point.usableLengthMeters == null || trainLength <= it.point.usableLengthMeters!! }
            .filter {
                it.point.allowedTags.isEmpty() || if (registeredStock != null) registeredStock.tags.any(it.point.allowedTags::contains)
                else selector.requiredTags.isEmpty() || selector.requiredTags.any(it.point.allowedTags::contains)
            }
            .firstOrNull { occupied.add(it.point.ref.toString()) }
            ?: error("depot ${selector.depotId} has insufficient stabling points for simulation")
        val rollingStockTags = selector.requiredTags.ifEmpty {
            point.point.allowedTags.minOrNull()?.let(::setOf).orEmpty()
        }
        return Placement(point.point.destinationId, selector, rollingStockTags)
    }

    private fun createTrain(
        world: CoreWorld,
        trainId: String,
        trainName: String,
        profile: SimulationControlProfile,
        wayPointId: String
    ): CoreTrain {
        val point = requireNotNull(world.basicWorldData.wayPoints[wayPointId]) {
            "simulation waypoint not found: $wayPointId"
        }
        val rail = requireNotNull(world.getRail(point.railId)) { "simulation rail not found: ${point.railId}" }
        val direction = resolveProgramSimulationWayPoint(world, wayPointId).direction
        val view = rail.getView(direction)
        val arc = if (direction == ViewDirection.AB) point.arcLength else view.curve.totalArcLength - point.arcLength
        val head = RailLocationWithFacing(view, arc)
        return world.physicsCoordinator.processBetweenFrames {
            CoreTrain.createOwned(world) {
                val cars = profile.cars.mapIndexed { index, blueprint ->
                    val bogieA = bogie(head)
                    val bogieB = bogie(head)
                    car(
                        "$trainId/car-$index", bogieA, bogieB, blueprint.length, blueprint.floorHeight,
                        blueprint.connectorLengthAtA, blueprint.connectorLengthAtB,
                        blueprint.bogieAOffset, blueprint.bogieBOffset
                    ).also { car ->
                        if (blueprint.installedReversed) car.reverse()
                        val behavior = SimulationCarBehaviorExtension(blueprint.behavior, blueprint.control, effectObserver)
                        onFailure(behavior::close)
                        car.registerExtensionData(extensionPlugin, behavior)
                        behavior.initialize(car, emptyMap())
                    }
                }
                val placement = when (val prepared = prepareUniquePlacement(cars, head)) {
                    is TrainPlacementResult.Unique -> prepared.placement
                    is TrainPlacementResult.Unplaceable -> error("cannot place simulation train at $wayPointId: ${prepared.reason}")
                    is TrainPlacementResult.Ambiguous -> error("simulation train body has multiple valid placement paths at $wayPointId")
                    is TrainPlacementResult.SearchLimit -> error("simulation train placement could not be proven at $wayPointId: ${prepared.reason}")
                }
                train(trainId, cars, trainName, preparedPlacement = placement).also { created ->
                    val configuration = SimulationTrainExtension(profile.trainControl)
                    created.registerExtensionData(extensionPlugin, configuration)
                    configuration.initialize(created, emptyMap())
                    publish()
                }
            }
        }
    }

    private fun selectorIdentity(service: TimetableService): Any {
        (service.trainSelector as? TrainSelector.TrainId)?.let { return NamedSelector(it.trainId) }
        return when (val acquisition = service.steps.asSequence().filterIsInstance<TimetableStep.At>()
            .map(TimetableStep.At::action).firstOrNull {
                it is TimetableAction.TakeFromDepot || it is TimetableAction.TakeFromWaypoint
            }) {
            is TimetableAction.TakeFromDepot -> acquisition.selector
            is TimetableAction.TakeFromWaypoint -> WaypointSelector(acquisition.wayPointIds)
            else -> AnySelector
        }
    }

    private data class Placement(
        val wayPointId: String,
        val depotSelector: DepotTrainSelector?,
        val rollingStockTags: Set<String>
    )
    private data class NamedSelector(val trainId: String)
    private data class WaypointSelector(val wayPointIds: List<String>)
    private data object AnySelector
}
