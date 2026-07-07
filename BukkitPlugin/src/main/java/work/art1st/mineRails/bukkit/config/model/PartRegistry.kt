package work.art1st.mineRails.bukkit.config.model

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Registry that loads and resolves all part definitions from the parts/ directory tree.
 *
 * File path convention: parts/<namespace>/.../<part-name>.yml
 * This yields a fully qualified name like "s100/body" or "s100/doors/left".
 *
 * Parts can reference sub-parts by name. Within the same namespace,
 * a bare name like "body" resolves to the same namespace. Cross-namespace
 * references use the full path like "shared/wheel".
 */
class PartRegistry(
    private val partsDir: File
) : PartResolver {
    private val parts: MutableMap<String, PartDefinition> = mutableMapOf()

    /**
     * Other packages' part registries, keyed by package name.
     * Used for cross-package part resolution: `import: "pkg/part"`.
     * Set by PackageLoader/ModelManager after all packages are loaded.
     */
    var fallbackRegistries: Map<String, PartRegistry> = emptyMap()

    /** Load all part files from the directory tree. */
    fun load() {
        parts.clear()
        if (partsDir.exists() && partsDir.isDirectory) {
            scanDirectory(partsDir, "")
        }
    }

    private fun scanDirectory(dir: File, namespace: String) {
        for (file in dir.listFiles() ?: emptyArray()) {
            when {
                file.isDirectory -> {
                    val childNs = if (namespace.isEmpty()) file.name else "$namespace/${file.name}"
                    scanDirectory(file, childNs)
                }
                file.isFile && (file.extension == "yml" || file.extension == "yaml") -> {
                    try {
                        val part = loadPartFile(file, namespace)
                        parts[part.name] = part
                    } catch (e: Exception) {
                        System.err.println("[MineRails] Failed to load part file ${file.path}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadPartFile(file: File, namespace: String): PartDefinition {
        val yaml = Yaml()
        val map = yaml.load<Map<String, Any>>(file.inputStream())
        return parsePartDefinition(map, namespace)
    }

    /**
     * Resolve a part name to its definition.
     *
     * Resolution order:
     * 1. Local registry (using defaultNamespace for bare names)
     * 2. Cross-package: if name contains "/", treat first segment as package name
     *    and resolve the rest in that package's PartRegistry.
     *
     * @param refName the part name, optionally namespace-qualified
     * @param defaultNamespace the namespace to use if refName is unqualified
     */
    override fun resolve(refName: String, defaultNamespace: String): ResolvedPart {
        val qualifiedName = if (refName.contains("/")) {
            refName
        } else {
            "$defaultNamespace/$refName"
        }

        // Try local registry first
        parts[qualifiedName]?.let {
            return ResolvedPart(it.name, it, qualifiedName)
        }

        // Cross-package fallback: "pkg/part" → look up "pkg" in fallbackRegistries
        val slashIdx = refName.indexOf('/')
        if (slashIdx > 0) {
            val pkgName = refName.substring(0, slashIdx)
            val partName = refName.substring(slashIdx + 1)
            fallbackRegistries[pkgName]?.let { otherRegistry ->
                // Resolve in the other package's registry using the part name as-is
                return otherRegistry.resolve(partName, pkgName)
            }
        }

        throw IllegalArgumentException(
            "Part not found: $qualifiedName (ref: $refName, ns: $defaultNamespace)"
        )
    }

    /** Get all loaded part names. */
    fun getAllNames(): Set<String> = parts.keys.toSet()

    /** Get a part directly by its fully qualified name. */
    operator fun get(name: String): PartDefinition? = parts[name]

    companion object {
        const val CONFIG_VERSION = 1

        fun parsePartDefinition(map: Map<String, Any>, namespace: String): PartDefinition {
            val version = (map["config-version"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Missing config-version")
            require(version == CONFIG_VERSION) { "Unsupported config-version: $version" }

            val simpleName = map["name"] as? String
                ?: throw IllegalArgumentException("Missing 'name' in part definition")
            val qualifiedName = if (namespace.isEmpty()) simpleName else "$namespace/$simpleName"

            val typeStr = map["type"] as? String
                ?: throw IllegalArgumentException("Missing 'type' in part '$qualifiedName'")
            val type = PartType.fromString(typeStr)

            val item = parseItemConfig(map["item"] as? Map<String, Any>)
            val interaction = parseInteractionConfig(map["interaction"] as? Map<String, Any>)
            val platform = parsePlatformConfig(map["platform"] as? Map<String, Any>)
            val offset = parseVec3(map["offset"] as? Map<String, Any>)
            val rotation = parseVec3(map["rotation"] as? Map<String, Any>)
            val parts = parsePartList(map["parts"] as? List<*>, namespace)
            val animations = parseAnimations(map["animations"] as? List<*>)

            return PartDefinition(
                name = qualifiedName,
                type = type,
                item = item,
                interaction = interaction,
                platform = platform,
                offset = offset,
                rotation = rotation,
                parts = parts,
                animations = animations
            )
        }

        fun parseItemConfig(map: Map<String, Any>?): PartItemConfig? {
            if (map == null) return null
            return PartItemConfig(
                type = map["type"] as? String ?: error("Missing item.type"),
                itemModel = map["item-model"] as? String,
                customModelData = (map["custom-model-data"] as? Number)?.toInt(),
                scale = parseScale(map["scale"] as? Map<String, Any>),
                animations = parseAnimations(map["animations"] as? List<*>)
            )
        }

        fun parseInteractionConfig(map: Map<String, Any>?): PartInteractionConfig {
            if (map == null) return PartInteractionConfig()
            return PartInteractionConfig(
                width = (map["width"] as? Number)?.toDouble() ?: 1.0,
                height = (map["height"] as? Number)?.toDouble() ?: 1.0,
                driver = (map["driver"] as? Boolean) ?: false,
                controlEnd = DriverControlEnd.fromConfig(map["control-end"] as? String)
            )
        }

        fun parsePlatformConfig(map: Map<String, Any>?): PartPlatformConfig {
            if (map == null) return PartPlatformConfig()
            val spacing = parseVec3(map["spacing"] as? Map<String, Any>)
            return PartPlatformConfig(
                rows = (map["rows"] as? Number)?.toInt() ?: 1,
                columns = (map["columns"] as? Number)?.toInt() ?: 1,
                spacingX = (map["spacing-x"] as? Number)?.toDouble()
                    ?: (map["spacingX"] as? Number)?.toDouble()
                    ?: spacing.x.takeIf { it != 0.0 }
                    ?: 1.0,
                spacingZ = (map["spacing-z"] as? Number)?.toDouble()
                    ?: (map["spacingZ"] as? Number)?.toDouble()
                    ?: spacing.z.takeIf { it != 0.0 }
                    ?: 1.0
            )
        }

        fun parseScale(map: Map<String, Any>?): PartScaleConfig? {
            if (map == null) return null
            return PartScaleConfig(
                x = (map["x"] as? Number)?.toDouble() ?: 1.0,
                y = (map["y"] as? Number)?.toDouble() ?: 1.0,
                z = (map["z"] as? Number)?.toDouble() ?: 1.0
            )
        }

        fun parseVec3(map: Map<String, Any>?): Vec3Config {
            if (map == null) return Vec3Config()
            return Vec3Config(
                x = (map["x"] as? Number)?.toDouble() ?: 0.0,
                y = (map["y"] as? Number)?.toDouble() ?: 0.0,
                z = (map["z"] as? Number)?.toDouble() ?: 0.0
            )
        }

        fun parsePartList(list: List<*>?, namespace: String): List<PartRefEntry> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
                val offset = parseVec3(entryMap["offset"] as? Map<String, Any>)
                val rotation = parseVec3(entryMap["rotation"] as? Map<String, Any>)

                when {
                    // Import format: "import: namespace/part-name"
                    entryMap.containsKey("import") -> {
                        val importName = entryMap["import"] as? String ?: return@mapNotNull null
                        PartRefEntry(name = importName, offset = offset, rotation = rotation, isImport = true)
                    }
                    // Inline format: "name: ..., type: ..."
                    entryMap.containsKey("type") -> {
                        val def = parsePartDefinition(entryMap, namespace)
                        PartRefEntry(name = def.name, offset = offset, rotation = rotation, inline = def)
                    }
                    // Legacy format: "name: part-name"
                    else -> {
                        val refName = entryMap["name"] as? String ?: return@mapNotNull null
                        PartRefEntry(name = refName, offset = offset, rotation = rotation)
                    }
                }
            }
        }

        fun parseAnimations(list: List<*>?): List<PartAnimationConfig> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
                val name = entryMap["name"] as? String ?: return@mapNotNull null
                val commands = parseAnimationCommands(entryMap["commands"] as? List<*>)
                val keyframes = parseAnimationKeyframes(entryMap["keyframes"] as? List<*>)
                validateUniqueScenes(name, commands.mapNotNull { it.scene } + keyframes.mapNotNull { it.scene })

                PartAnimationConfig(
                    name = name,
                    keepFinalState = (entryMap["keep-final-state"] as? Boolean) ?: true,
                    commands = commands,
                    keyframes = keyframes.sortedBy { it.at }
                )
            }
        }

        private fun parseAnimationCommands(list: List<*>?): List<PartAnimationCommandConfig> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
                PartAnimationCommandConfig(
                    duration = (entryMap["duration"] as? Number)?.toDouble() ?: 0.0,
                    offset = parseVec3(entryMap["offset"] as? Map<String, Any>),
                    rotation = parseVec3(entryMap["rotation"] as? Map<String, Any>),
                    scale = parseScale(entryMap["scale"] as? Map<String, Any>),
                    scene = normalizeSceneName(entryMap["scene"] as? String),
                    itemType = entryMap["item-type"] as? String,
                    itemModel = entryMap["item-model"] as? String,
                    customModelData = (entryMap["custom-model-data"] as? Number)?.toInt(),
                    brightness = parseBrightness(entryMap["brightness"] as? Map<String, Any>)
                )
            }
        }

        private fun parseAnimationKeyframes(list: List<*>?): List<PartAnimationKeyframeConfig> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
                val at = (entryMap["at"] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Animation keyframe missing 'at'")
                PartAnimationKeyframeConfig(
                    at = at,
                    scene = normalizeSceneName(entryMap["scene"] as? String),
                    offset = parseVec3(entryMap["offset"] as? Map<String, Any>),
                    rotation = parseVec3(entryMap["rotation"] as? Map<String, Any>),
                    scale = parseScale(entryMap["scale"] as? Map<String, Any>),
                    itemType = entryMap["item-type"] as? String,
                    itemModel = entryMap["item-model"] as? String,
                    customModelData = (entryMap["custom-model-data"] as? Number)?.toInt(),
                    brightness = parseBrightness(entryMap["brightness"] as? Map<String, Any>)
                )
            }
        }

        fun parseBrightness(map: Map<String, Any>?): PartBrightnessConfig? {
            if (map == null) return null
            return PartBrightnessConfig(
                block = (map["block"] as? Number)?.toInt() ?: 0,
                sky = (map["sky"] as? Number)?.toInt() ?: 0
            )
        }

        private fun normalizeSceneName(scene: String?): String? {
            return when (val normalized = scene?.trim()?.lowercase()) {
                null, "" -> null
                "o" -> "open"
                "c" -> "closed"
                else -> normalized
            }
        }

        private fun validateUniqueScenes(animationName: String, scenes: List<String>) {
            val duplicates = scenes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(duplicates.isEmpty()) {
                "Animation '$animationName' has duplicate scene(s): ${duplicates.joinToString()}"
            }
        }
    }
}

/**
 * Result of resolving a part reference.
 */
data class ResolvedPart(
    val qualifiedName: String,
    val resolved: PartDefinition,
    val lookupKey: String
)
