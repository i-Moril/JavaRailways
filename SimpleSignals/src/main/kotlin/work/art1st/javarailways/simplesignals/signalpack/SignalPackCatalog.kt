package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.bukkit.api.model.asset.CompiledModelAsset
import java.nio.file.Path
import java.util.Collections

/** A fully prepared, immutable candidate. Loading this value does not install or publish anything. */
class SignalPackCatalog internal constructor(
    packages: Map<String, SignalPack>,
    models: Map<String, SignalPackModel>,
    logics: Map<String, SignalPackLogic>,
    signals: Map<String, SignalPackSignal>
) {
    val packages: Map<String, SignalPack> = immutableMap(packages)
    val models: Map<String, SignalPackModel> = immutableMap(models)
    val logics: Map<String, SignalPackLogic> = immutableMap(logics)

    /** First-class player-facing signal types. Placed logical-signal IDs are a separate identity. */
    val signals: Map<String, SignalPackSignal> = immutableMap(signals)
}

class SignalPack internal constructor(
    val name: String,
    val displayName: String?,
    val directory: Path,
    models: Map<String, SignalPackModel>,
    logics: Map<String, SignalPackLogic>,
    signals: Map<String, SignalPackSignal>
) {
    val models: Map<String, SignalPackModel> = immutableMap(models)
    val logics: Map<String, SignalPackLogic> = immutableMap(logics)
    val signals: Map<String, SignalPackSignal> = immutableMap(signals)
}

data class SignalPackModel(
    val modelId: String,
    val file: Path,
    val materialized: CompiledModelAsset
)

data class SignalPackLogic(
    val logicId: String,
    val metadataFile: Path,
    val sourceFile: Path,
    /** Captured while the catalog candidate is prepared; runtime evaluation never rereads [sourceFile]. */
    val source: String,
    val scanDistanceMetres: Double
)

data class SignalPackSignal(
    val signalId: String,
    val file: Path,
    val displayName: String,
    val model: SignalPackModel,
    val logic: SignalPackLogic
) {
    val modelId: String get() = model.modelId
    val logicId: String get() = logic.logicId
}

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
