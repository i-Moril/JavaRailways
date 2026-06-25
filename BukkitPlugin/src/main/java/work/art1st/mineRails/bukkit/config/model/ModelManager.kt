package work.art1st.mineRails.bukkit.config.model

import work.art1st.mineRails.core.api.physics.World
import java.io.File

/**
 * Central manager for the model configuration system.
 *
 * Manages loading and access to:
 * - PartRegistry (part definitions from parts/ directory)
 * - CarLoader (car definitions from cars/ directory)
 * - TrainLoader (train definitions from trains/ directory)
 * - ModelTreeBuilder factory (per-world model tree construction)
 */
class ModelManager(
    private val dataFolder: File
) {
    val partRegistry: PartRegistry
    val carLoader: CarLoader
    val trainLoader: TrainLoader

    init {
        val partsDir = File(dataFolder, "parts")
        val carsDir = File(dataFolder, "cars")
        val trainsDir = File(dataFolder, "trains")

        partRegistry = PartRegistry(partsDir)
        carLoader = CarLoader(carsDir, partRegistry)
        trainLoader = TrainLoader(trainsDir, carLoader)
    }

    /**
     * Load/reload all config files from disk.
     */
    fun loadAll() {
        partRegistry.load()
        carLoader.load()
        trainLoader.load()
    }

    /**
     * Get a car definition by fully qualified name (e.g. "s100/passenger").
     */
    fun getCar(name: String): CarDefinition? = carLoader[name]

    /**
     * Get a train definition by name (e.g. "s100_4car").
     */
    fun getTrain(name: String): TrainDefinition? = trainLoader[name]

    /**
     * Create a ModelTreeBuilder for a specific world.
     */
    fun createTreeBuilder(world: World): ModelTreeBuilder {
        return ModelTreeBuilder(world, partRegistry)
    }
}
