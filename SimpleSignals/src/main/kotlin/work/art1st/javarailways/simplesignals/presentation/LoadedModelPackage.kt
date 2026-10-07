package work.art1st.javarailways.simplesignals.presentation

import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.asset.CompiledModelAsset
import java.util.Collections

/** Fully prepared package; lookup never retains a file, parser or mutable authoring definition. */
internal class LoadedModelPackage(
    val name: String,
    val displayName: String?,
    models: Map<String, LoadedPackageModel>
) {
    /** Keys are complete package:model IDs. Host model values can be reused by every device. */
    val models: Map<String, LoadedPackageModel> = immutableModelMap(models)
}

internal class LoadedPackageModel(
    val id: String,
    val definition: ModelDefinition,
    defaultScenes: Map<String, String>,
    variants: Map<String, Map<String, String>>,
    val followRailTilt: Boolean,
    railPlacementPresets: Map<String, RailPlacementPreset>
) {
    constructor(id: String, compiled: CompiledModelAsset) : this(
        id,
        compiled.definition(),
        compiled.defaultScenes(),
        compiled.variants(),
        compiled.placement().followReferenceTilt(),
        compiled.placement().presets().mapValues { (_, preset) ->
            RailPlacementPreset(preset.localPose(), preset.followReferenceTilt())
        }
    )

    val defaultScenes: Map<String, String> = immutableModelMap(defaultScenes)
    val variants: Map<String, Map<String, String>> = immutableModelMap(
        variants.mapValues { (_, selection) -> immutableModelMap(selection) }
    )
    val railPlacementPresets: Map<String, RailPlacementPreset> = immutableModelMap(railPlacementPresets)

    /** An explicit unknown output is an error, even when a default exists. */
    fun variant(name: String): Map<String, String> = variants[name]
        ?: throw IllegalArgumentException("$id: unknown model variant '$name'")
}

private fun <K, V> immutableModelMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
