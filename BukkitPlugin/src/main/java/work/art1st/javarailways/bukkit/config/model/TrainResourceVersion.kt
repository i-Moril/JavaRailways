package work.art1st.javarailways.bukkit.config.model

/** A fully prepared model and operational configuration version for one train type. */
data class TrainResourceVersion(
    val packageSnapshot: SavedTrainPackageSnapshot,
    val models: Map<String, SavedCarModelTree>,
    val carPackages: Map<String, SavedTrainPackageSnapshot>,
    val composition: List<String>
)

internal data class PreparedModelReload(
    val baseGeneration: Long,
    val trainPackages: Map<String, LoadedTrainPackage>,
    val railPackages: Map<String, LoadedRailPackage>,
    val versions: Map<String, TrainResourceVersion>,
    val failures: Map<String, Throwable>,
    val trainSources: Map<String, String>,
    val extensionGeneration: Long = 0
)
