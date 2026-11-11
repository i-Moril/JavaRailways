package work.art1st.javarailways.bukkit.config.model

import work.art1st.javarailways.bukkit.config.RailModelConfig

/**
 * A fully loaded train package: manifest + local parts + cars + train definition.
 */
class LoadedTrainPackage(
    val manifest: PackageManifest,
    /** Local part registry for this package (parts/ subdirectory). */
    val partRegistry: PartRegistry,
    /** Car definitions keyed by car name (e.g. "head", "passenger"). */
    val cars: Map<String, CarDefinition>,
    /** The resolved train definition. */
    val train: TrainDefinition,
    /** Declarative train actions keyed by action name. */
    val actions: Map<String, TrainActionConfig> = emptyMap(),
    /** Optional JS behavior script for this train package. */
    val behavior: TrainBehaviorScript? = null,
    /** Manual-controller performance and physics configuration. */
    val control: TrainControlConfig,
    /** Generic captured addon resources, independent of installed providers. */
    extensions: Map<String, work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources> = emptyMap()
) {
    val extensions = work.art1st.javarailways.bukkit.api.model.extension.immutableExtensionResources(extensions)
}

/**
 * A fully loaded rail package: manifest + rail model config.
 */
data class LoadedRailPackage(
    val manifest: PackageManifest,
    /** The rail visual configuration. */
    val railConfig: RailModelConfig
)
