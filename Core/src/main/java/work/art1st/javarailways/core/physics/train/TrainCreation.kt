package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.train.TrainComposition

import work.art1st.javarailways.core.api.util.DestructionSteps
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData

/** Tracks only resources newly constructed by one creation attempt, never a borrowed consist. */
class TrainCreation internal constructor(private val world: CoreWorld) {
    private val bogies = mutableListOf<CoreBogie>()
    private val cars = mutableListOf<CoreCar>()
    private var train: CoreTrain? = null
    private var published = false
    private var installedPlacement: PreparedTrainPlacement? = null
    private val rollback = mutableListOf<() -> Unit>()
    private val rollbackAfterResources = mutableListOf<() -> Unit>()

    private var closed = false

    private fun ensureBuilding() {
        check(!closed && !published) { "Train creation scope is closed" }
        check(train == null) { "Train has already been constructed" }
    }

    fun bogie(
        location: RailLocationWithFacing,
        extensionData: Map<String, SerializableExtensionData> = emptyMap()
    ): CoreBogie {
        ensureBuilding()
        require(location.railView.rail.world === world) { "Bogie location belongs to another world" }
        return CoreBogie.create(location, extensionData).also(bogies::add)
    }

    fun car(
        id: String,
        bogieA: CoreBogie,
        bogieB: CoreBogie,
        length: Double = 2.0,
        floorHeight: Double = 0.5,
        connectorLengthAtA: Double = 0.3,
        connectorLengthAtB: Double = 0.3,
        bogieAOffset: Double? = null,
        bogieBOffset: Double? = null,
        extensionData: Map<String, SerializableExtensionData> = emptyMap()
    ): CoreCar {
        ensureBuilding()
        require(bogieA !== bogieB && listOf(bogieA, bogieB).all { candidate ->
            bogies.any { it === candidate } && cars.none { car -> car.bogies.any { it === candidate } }
        }) { "Car bogies must be unused resources created by this scope" }
        return CoreCar.create(id, bogieA, bogieB, length, floorHeight,
            connectorLengthAtA, connectorLengthAtB, bogieAOffset, bogieBOffset,
            world, extensionData).also(cars::add)
    }

    private fun validateCars(selected: List<CoreCar>) {
        ensureBuilding()
        require(selected.size == cars.size && selected.toSet().size == selected.size &&
            selected.all { candidate -> cars.any { it === candidate } && candidate.train == null }) {
            "Train must use exactly the new cars created by this scope"
        }
        require(bogies.all { bogie -> selected.any { car -> car.bogies.any { it === bogie } } }) {
            "Train creation has unused bogies"
        }
    }

    /**
     * Prepare all directed rigid body paths for these new cars, without registering a train or
     * changing cars, switches or extensions. Bounded failure never claims a unique placement.
     * Call on the world's between-frame owner and consume a Unique result in this scope's train().
     */
    fun prepareUniquePlacement(
        cars: List<CoreCar>,
        headLocation: RailLocationWithFacing,
        maximumPathExpansions: Int = 4096
    ): TrainPlacementResult {
        validateCars(cars)
        return prepareUniqueTrainPlacement(world, cars, headLocation, maximumPathExpansions)
    }

    fun train(
        id: String,
        cars: List<CoreCar>,
        name: String = id,
        extensionData: Map<String, SerializableExtensionData> = emptyMap(),
        preparedPlacement: PreparedTrainPlacement? = null,
        composition: TrainComposition? = null
    ): CoreTrain {
        validateCars(cars)
        preparedPlacement?.let { prepared ->
            require(prepared.world === world) { "prepared train placement belongs to another world" }
            prepared.install(cars, rollbackAfterResources::add)
            installedPlacement = prepared
        }
        return CoreTrain(id, cars, world, extensionData, name,
            persistedHeadLocation = preparedPlacement?.head,
            persistedTailLocation = preparedPlacement?.tail, initialComposition = composition).also {
                train = it
                preparedPlacement?.validateInstalled(it)
            }
    }

    internal fun loadCar(saved: SavedCarV1): CoreCar {
        ensureBuilding()
        val first = saved.bogieA.load(world).also(bogies::add)
        val second = saved.bogieB.load(world).also(bogies::add)
        return saved.config.buildCar(saved.id, world, first, second).also(cars::add)
    }

    internal fun loadTrain(saved: SavedTrainV1, cars: List<CoreCar>): CoreTrain {
        validateCars(cars)
        return CoreTrain(saved.id, cars, world, saved.extension, saved.name,
            persistedHeadLocation = saved.headLocation.load(world),
            persistedTailLocation = saved.tailLocation.load(world),
            initialReversed = saved.reversed,
            initialComposition = saved.composition.takeIf { it.isNotEmpty() }?.let { units ->
                TrainComposition.create(units.map { it.restore() }, cars.map { it.id })
            }).also { train = it }
    }

    fun onFailure(cleanup: () -> Unit) {
        check(!closed && !published) { "Train creation scope is closed" }
        rollback += cleanup
    }

    /** Transfers the fully initialized train to the world's published lifecycle. */
    fun publish() {
        check(!closed && !published) { "Train creation scope is closed" }
        val created = requireNotNull(train)
        installedPlacement?.validateInstalled(created)
        world.publishOwnerChanges(listOf(created))
        published = true
    }

    internal fun finish() {
        check(published) { "Train creation must publish before returning" }
        closed = true
    }

    /** Standalone saved-car loading transfers only a newly constructed car to its caller. */
    internal fun finishCar(): CoreCar {
        check(!closed && train == null && cars.size == 1)
        closed = true
        return cars.single()
    }

    internal fun discard(failure: Throwable) {
        closed = true
        if (published) return
        // A failed publication may already have committed physical state. Halt preserves it.
        if (train != null && !world.physicsCoordinator.isUsableForOwnerOperations) return
        val cleanup = DestructionSteps()
        rollback.asReversed().forEach { cleanup.action(it) }
        train?.let { created -> cleanup.await { created.destroy() } }
        cars.forEach { car -> cleanup.await { car.destroyOwnedResources() } }
        bogies.forEach { bogie -> cleanup.await { bogie.destroy() } }
        rollbackAfterResources.asReversed().forEach { cleanup.action(it) }
        cleanup.finish().whenComplete { _, cleanupFailure ->
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure)
                world.physicsCoordinator.stall("Failed to clean rejected train creation", cleanupFailure)
            }
        }
    }
}
