package work.art1st.javarailways.bukkit.api.model.extension

import org.bukkit.plugin.Plugin
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.TreeMap

/** Decorative configuration validation only. Implementations must not create runtime visuals here. */
interface PartExtensionProvider {
    val typeId: String
    /** Validate all occurrences and the exact owning package's captured resource directory. */
    fun validate(context: PartExtensionPackage)
}

interface PartExtensionService {
    /** Duplicate type IDs are rejected. Already published packages are validated without discarding their data. */
    fun register(owner: Plugin, provider: PartExtensionProvider): PartExtensionRegistration
}

interface PartExtensionRegistration : AutoCloseable {
    val existingPackageFailures: Map<String, String>
    override fun close()
}

class PartExtensionPackage(
    val packageName: String,
    parts: List<PartExtensionPart>,
    val resources: PartExtensionResources?
) {
    val parts: List<PartExtensionPart> = Collections.unmodifiableList(ArrayList(parts))
}

class PartExtensionPart(
    val name: String,
    val schemaVersion: Int,
    config: JsonObject
) {
    val config: JsonObject = immutablePartExtensionJson(config, 0) as JsonObject
    init { require(schemaVersion > 0) { "Part extension schemaVersion must be positive" } }
}

internal fun immutablePartExtensionJson(value: JsonElement, depth: Int): JsonElement {
    require(depth <= 64) { "Extension configuration exceeds maximum nesting depth" }
    return when (value) {
        is JsonObject -> JsonObject(Collections.unmodifiableMap(TreeMap(value.mapValues { immutablePartExtensionJson(it.value, depth + 1) })))
        is JsonArray -> JsonArray(Collections.unmodifiableList(value.map { immutablePartExtensionJson(it, depth + 1) }))
        is JsonPrimitive -> value.also {
            require(value.isString || value === JsonNull || value.booleanOrNull != null ||
                value.longOrNull != null || value.doubleOrNull?.isFinite() == true) { "Invalid JSON primitive in extension configuration" }
        }
    }
}

