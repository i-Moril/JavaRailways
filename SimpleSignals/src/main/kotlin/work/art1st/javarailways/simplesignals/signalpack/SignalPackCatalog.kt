package work.art1st.javarailways.simplesignals.signalpack

import java.nio.file.Path
import java.util.Collections

/** A fully prepared, immutable candidate. Loading this value does not install or publish anything. */
class SignalPackCatalog internal constructor(
    packages: Map<String, SignalPack>,
    models: Map<String, SignalPackModel>,
    logics: Map<String, SignalPackLogic>,
    combinations: Map<String, SignalPackCombination>
) {
    val packages: Map<String, SignalPack> = immutableMap(packages)
    val models: Map<String, SignalPackModel> = immutableMap(models)
    val logics: Map<String, SignalPackLogic> = immutableMap(logics)

    /** The only entries intended for a player-facing picker. */
    val combinations: Map<String, SignalPackCombination> = immutableMap(combinations)
}

class SignalPack internal constructor(
    val name: String,
    val directory: Path,
    models: Map<String, SignalPackModel>,
    logics: Map<String, SignalPackLogic>,
    combinations: Map<String, SignalPackCombination>
) {
    val models: Map<String, SignalPackModel> = immutableMap(models)
    val logics: Map<String, SignalPackLogic> = immutableMap(logics)
    val combinations: Map<String, SignalPackCombination> = immutableMap(combinations)
}

data class SignalPackModel(val id: String, val file: Path)

data class SignalPackLogic(
    val id: String,
    val file: Path,
    /** Captured while the catalog candidate is prepared; runtime evaluation never rereads [file]. */
    val source: String,
    val scanDistanceMetres: Double
)

data class SignalPackCombination(
    val id: String,
    val displayName: String,
    val model: SignalPackModel,
    val logic: SignalPackLogic
)

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
