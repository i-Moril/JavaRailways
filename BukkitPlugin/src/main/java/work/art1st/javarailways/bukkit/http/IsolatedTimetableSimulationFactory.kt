package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
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
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.basic.route.TurnoutSafetyDefaults
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationWorld
import work.art1st.javarailways.simpleato.timetable.simulation.SimulationTrainOperationDurationResolver
import work.art1st.javarailways.simpleato.timetable.simulation.SimulationTrainOperationGateway
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreBogie
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain

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
    val turnoutSafetyDefaults: TurnoutSafetyDefaults,
    val timetableExtensions: TimetableExtensionRegistry
)

/** Captures static configuration on the live thread, then creates a runtime with no live trains. */
internal fun interface IsolatedSimulationProfileResolver {
    fun resolve(world: CoreWorld, type: SimulationProfileType, id: String): SimulationControlProfile
}

internal class IsolatedTimetableSimulationFactory(
    private val profileResolver: IsolatedSimulationProfileResolver,
    private val gravityMetersPerSecondSquared: Double
) {
    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
    }

    constructor(
        profileCatalog: SimulationControlProfileCatalog,
        gravityMetersPerSecondSquared: Double
    ) : this(
        IsolatedSimulationProfileResolver { world, type, id -> profileCatalog.resolve(world, type, id) },
        gravityMetersPerSecondSquared
    )
    fun capture(
        liveWorld: CoreWorld,
        timetable: Timetable,
        selections: List<TimetableProfileSelection>
    ): IsolatedTimetableSimulationSnapshot {
        val selectionsByService = selections.associateBy(TimetableProfileSelection::serviceId)
        require(selectionsByService.size == selections.size) { "duplicate simulation profile assignment" }
        val unknown = selectionsByService.keys - timetable.services.mapTo(HashSet()) { it.id }
        require(unknown.isEmpty()) { "profile assignments contain unknown services: ${unknown.sorted().joinToString()}" }
        val missing = timetable.services.map(TimetableService::id).filterNot(selectionsByService::containsKey)
        require(missing.isEmpty()) { "missing simulation profiles for services: ${missing.joinToString()}" }
        timetable.services.groupBy(::selectorIdentity).values.forEach { equivalentServices ->
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
        val profiles = timetable.services.associate { service ->
            val selection = selectionsByService.getValue(service.id)
            service.id to profileResolver.resolve(liveWorld, selection.profileType, selection.profileId)
        }
        val revision = 0L
        val coreInfrastructure = liveWorld.saveInfrastructure(revision).let { infrastructure ->
            infrastructure.copy(rails = infrastructure.rails.map { rail -> rail.copy(
                activeTrackAtA = rail.nextRailAtA.sorted().firstOrNull(),
                activeTrackAtB = rail.nextRailAtB.sorted().firstOrNull()
            ) })
        }
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
            liveWorld.basicWorldData.turnoutSafetyDefaults,
            liveWorld.simpleAtoWorldData.timetableExtensions.createSimulationRegistry()
        )
    }

    fun create(
        snapshot: IsolatedTimetableSimulationSnapshot,
        workerThreads: Int
    ): IsolatedTimetableSimulationWorld {
        val profilesByTrainId = LinkedHashMap<String, SimulationControlProfile>()
        val operationGateway = SimulationTrainOperationGateway(
            SimulationTrainOperationDurationResolver { request -> operationDuration(request, profilesByTrainId) }
        )
        return IsolatedTimetableSimulationWorld.create(
            snapshot.coreInfrastructure,
            snapshot.basicInfrastructure,
            snapshot.simpleAtoInfrastructure,
            workerThreads,
            basicDataFactory = { world, directionChanges ->
                world.tickManager.register(operationGateway)
                BasicWorldData(
                    turnoutSafetyDefaults = snapshot.turnoutSafetyDefaults,
                    operatingDirections = work.art1st.javarailways.basic.rail.OperatingDirectionStore(directionChanges)
                )
            },
            timetableExtensions = snapshot.timetableExtensions,
            dispatcherFactory = {
                    _, routeAdmissionContextProvider, signalRestrictions, inspectionRuntime, infrastructureDependencyService ->
                TimetableDispatcher(
                    routeAdmissionContextProvider = routeAdmissionContextProvider,
                    signalRestrictionSnapshots = signalRestrictions,
                    inspectionRuntime = inspectionRuntime,
                    infrastructureDependencyService = infrastructureDependencyService,
                    operationGateway = operationGateway,
                    atoFactory = TimetableAtoFactory { train, owningWorld ->
                        val profile = requireNotNull(profilesByTrainId[train.id]) {
                            "simulation profile missing for train ${train.id}"
                        }
                        val gravity = snapshot.gravityMetersPerSecondSquared
                        val safety = AtoSafetyProfile(
                            serviceDecelerationMetersPerSecondSquared =
                                TrainDynamics.flatServiceDeceleration(
                                    profile.trainControl,
                                    profile.carControls,
                                    gravity
                                ),
                            reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                                profile.trainControl,
                                profile.carControls,
                                gravity
                            )
                        )
                        SimpleATO(
                            train,
                            owningWorld,
                            HandleTrainActuator(
                                train,
                                profile.trainControl,
                                profile.carControls,
                                gravity
                            ),
                            safety,
                            inspectionRuntime::maximumDeclaredLeadTimeSeconds
                        )
                    }
                )
            },
            seed = { world -> seedTimetableTrains(world, snapshot, profilesByTrainId) }
        )
    }

    private fun seedTimetableTrains(
        world: CoreWorld,
        snapshot: IsolatedTimetableSimulationSnapshot,
        profilesByTrainId: MutableMap<String, SimulationControlProfile>
    ) {
        val occupiedStablingPoints = HashSet<String>()
        val occupiedDynamicWaypoints = HashSet<String>()
        val namedTrains = HashMap<String, CoreTrain>()
        snapshot.timetable.services.forEach { service ->
            val profile = snapshot.profilesByService.getValue(service.id)
            require(profile.cars.isNotEmpty()) { "simulation profile ${profile.id} has no physical car blueprint" }
            val namedTrainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId
            namedTrainId?.let(namedTrains::get)?.let { existingTrain ->
                profilesByTrainId[existingTrain.id] = profile
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
                    occupiedStablingPoints
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
            profilesByTrainId[train.id] = profile
            world.basicWorldData.trainSpeedProfiles.put(
                train.id,
                TrainSpeedProfile(profile.trainControl.curveSpeedLimitOffset)
            )
            placement.depotSelector?.let { selector ->
                val point = requireNotNull(world.simpleAtoWorldData.depots.stablingPoints(selector.depotId)
                    .firstOrNull { it.point.destinationId == placement.wayPointId })
                world.simpleAtoWorldData.depots.registerRollingStock(
                    RollingStockRecord(
                        train.id,
                        selector.depotId,
                        profile.id,
                        placement.rollingStockTags,
                        profile.cars.size,
                        profile.cars.sumOf(SimulationCarBlueprint::length),
                        profile.trainControl.maxSpeed,
                        selector.requiredCapabilities
                    ),
                    point.point.ref
                )
            }
        }
    }

    private fun depotPlacement(
        world: CoreWorld,
        selector: DepotTrainSelector,
        profile: SimulationControlProfile,
        occupied: MutableSet<String>
    ): Placement {
        val trainLength = profile.cars.sumOf(SimulationCarBlueprint::length)
        val point = world.simpleAtoWorldData.depots.stablingPoints(selector.depotId)
            .sortedBy { it.point.ref.pointId }
            .filter { it.point.usableLengthMeters == null || trainLength <= it.point.usableLengthMeters!! }
            .filter {
                it.point.allowedTags.isEmpty() || selector.requiredTags.isEmpty() ||
                    selector.requiredTags.any(it.point.allowedTags::contains)
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
        val direction = world.simpleAtoWorldData.destinations[wayPointId]?.approachDirection ?: ViewDirection.AB
        val view = rail.getView(direction)
        val arc = if (direction == ViewDirection.AB) point.arcLength else view.curve.totalArcLength - point.arcLength
        val head = RailLocationWithFacing(view, arc)
        val cars = profile.cars.mapIndexed { index, blueprint ->
            val bogieA = CoreBogie.create(head)
            val bogieB = CoreBogie.create(head)
            CoreCar.create(
                "$trainId/car-$index",
                bogieA,
                bogieB,
                blueprint.length,
                blueprint.floorHeight,
                blueprint.connectorLengthAtA,
                blueprint.connectorLengthAtB,
                blueprint.bogieAOffset,
                blueprint.bogieBOffset,
                world
            )
        }
        return world.physicsCoordinator.processBetweenFrames {
            CoreTrain.create(trainId, cars, world, trainName).also { train ->
                train.setLocation(head)
                world.publishOwnerChanges(listOf(train))
            }
        }
    }

    private fun operationDuration(
        request: TrainOperationRequest,
        profilesByTrainId: Map<String, SimulationControlProfile>
    ): Long? {
        val profile = profilesByTrainId[request.trainId] ?: return null
        fun actions(vararg names: String): Long? {
            val durations = names.map { profile.modelActionDurationTicks[it] ?: return null }
            return durations.maxOrNull() ?: 0L
        }
        return when (val operation = request.operation) {
            is TrainOperation.OpenDoors -> when (operation.side) {
                DoorSide.LEFT -> actions("door-left-open")
                DoorSide.RIGHT -> actions("door-right-open")
                DoorSide.BOTH -> actions("door-left-open", "door-right-open")
                DoorSide.NONE -> null
            }
            is TrainOperation.CloseDoors -> when (operation.side) {
                DoorSide.LEFT -> actions("door-left-close")
                DoorSide.RIGHT -> actions("door-right-close")
                DoorSide.BOTH -> actions("door-left-close", "door-right-close")
                DoorSide.NONE -> null
            }
            is TrainOperation.ExecuteModelAction -> actions(operation.actionName)
            is TrainOperation.SetCabLights,
            is TrainOperation.Announce -> 0L
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
