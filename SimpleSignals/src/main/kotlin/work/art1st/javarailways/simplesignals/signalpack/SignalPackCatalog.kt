package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.bukkit.api.model.asset.CompiledModelAsset
import work.art1st.javarailways.simplesignals.presentation.LoadedPackageModel
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
    /** Shared domain-only input: no materialized Bukkit models enter a simulation capture. */
    val rules = SignalRuleCatalog(logics, signals.mapValues { (_, signal) ->
        SignalRuleType(signal.signalType, signal.logic, java.util.Set.copyOf(signal.model.materialized.variants().keys))
    })
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
) {
    /** Presentation uses this exact captured asset rather than another loader or registry. */
    internal val presentation = LoadedPackageModel(modelId, materialized)
}

data class SignalPackLogic(
    val logicId: String,
    val metadataFile: Path,
    val sourceFile: Path,
    /** Captured while the catalog candidate is prepared; runtime evaluation never rereads [sourceFile]. */
    val source: String,
    val scanDistanceMetres: Double? = null,
    val referenceDecelerationMetresPerSecondSquared: Double? = null
) {
    init {
        require((scanDistanceMetres == null) != (referenceDecelerationMetresPerSecondSquared == null)) {
            "logic requires exactly one scan distance or reference deceleration"
        }
        require(listOfNotNull(scanDistanceMetres, referenceDecelerationMetresPerSecondSquared)
            .all { it.isFinite() && it > 0.0 }) { "logic scan distance and reference deceleration must be finite and positive" }
    }
}

data class SignalPackSignal(
    /** Reusable catalog identity referenced by placed devices' `signalType`. */
    val signalType: String,
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

/** One projection of the loaded catalog, consumed by both LIVE and SIM rule execution. */
class SignalRuleCatalog internal constructor(logics: Map<String, SignalPackLogic>, signals: Map<String, SignalRuleType>) {
    val logics: Map<String, SignalPackLogic> = immutableMap(logics)
    val signals: Map<String, SignalRuleType> = immutableMap(signals)
}

class SignalRuleType internal constructor(val signalType: String, val logic: SignalPackLogic, variants: Set<String>) {
    val logicId: String get() = logic.logicId
    val variants: Set<String> = java.util.Set.copyOf(variants)
}
