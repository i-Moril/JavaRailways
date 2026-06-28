package work.art1st.mineRails.bukkit.config.model

import work.art1st.mineRails.bukkit.config.RailModelConfig

/**
 * A fully loaded train package: manifest + local parts + cars + train definition.
 */
data class LoadedTrainPackage(
    val manifest: PackageManifest,
    /** Local part registry for this package (parts/ subdirectory). */
    val partRegistry: PartRegistry,
    /** Car definitions keyed by car name (e.g. "head", "passenger"). */
    val cars: Map<String, CarDefinition>,
    /** The resolved train definition. */
    val train: TrainDefinition
)

/**
 * A fully loaded rail package: manifest + rail model config.
 */
data class LoadedRailPackage(
    val manifest: PackageManifest,
    /** The rail visual configuration. */
    val railConfig: RailModelConfig
)
