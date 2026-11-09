package work.art1st.javarailways.bukkit.config.model

import org.bukkit.Material
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
        if (!partsDir.exists() || !partsDir.isDirectory) return

        val errors = mutableListOf<String>()
        scanDirectory(partsDir, "", errors)
        if (errors.isNotEmpty()) {
            throw IllegalStateException(buildString {
                append("Failed to load part definitions under ${partsDir.path}:")
                errors.forEach { append("\n- ").append(it) }
            })
        }
    }

    private fun scanDirectory(dir: File, namespace: String, errors: MutableList<String>) {
        for (file in dir.listFiles() ?: emptyArray()) {
            when {
                file.isDirectory -> {
                    val childNs = if (namespace.isEmpty()) file.name else "$namespace/${file.name}"
                    scanDirectory(file, childNs, errors)
                }
                file.isFile && (file.extension == "yml" || file.extension == "yaml") -> {
                    try {
                        val part = loadPartFile(file, namespace)
                        parts[part.name] = part
                    } catch (e: Exception) {
                        errors += "${file.path}: ${e.message ?: e::class.simpleName.orEmpty()}"
                    }
                }
            }
        }
    }

    private fun loadPartFile(file: File, namespace: String): PartDefinition {
        val map = loadYamlMap(file, "part definition ${file.path}")
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

        private val PART_KEYS = setOf(
            "config-version", "name", "type", "item", "interaction", "platform",
            "offset", "rotation", "parts", "animations", "screen"
        )
        private val TRANSFORM_KEYS = setOf("x", "y", "z")
        private val SCALE_KEYS = setOf("x", "y", "z")

        fun parsePartDefinition(map: Map<String, Any>, namespace: String): PartDefinition {
            map.requireOnlyKeys(PART_KEYS, "part definition")
            val version = map.requireInt("config-version", "part definition")
            require(version == CONFIG_VERSION) { "Unsupported config-version: $version" }

            val simpleName = map.requireString("name", "part definition")
            require(simpleName.isNotBlank()) { "part definition.name must not be blank" }
            val qualifiedName = if (namespace.isEmpty()) simpleName else "$namespace/$simpleName"

            val typeStr = map["type"] as? String
                ?: throw IllegalArgumentException("Missing 'type' in part '$qualifiedName'")
            require(typeStr == typeStr.lowercase()) { "Part '$qualifiedName'.type must be lowercase" }
            val type = PartType.fromString(typeStr)

            val screenMap = map.optionalMap("screen", "part '$qualifiedName'")
            require((type == PartType.SCREEN) == (screenMap != null)) {
                "Part '$qualifiedName' must declare screen exactly when type is screen"
            }
            val screen = screenMap?.let { parseScreenConfig(it, "part '$qualifiedName'.screen") }
            val item = parseItemConfig(map.optionalMap("item", "part '$qualifiedName'"))
            val interaction = parseInteractionConfig(map.optionalMap("interaction", "part '$qualifiedName'"))
            val platform = parsePlatformConfig(map.optionalMap("platform", "part '$qualifiedName'"))
            val offset = parseVec3(map.optionalMap("offset", "part '$qualifiedName'"))
            val rotation = parseVec3(map.optionalMap("rotation", "part '$qualifiedName'"))
            val parts = parsePartList(map.optionalList("parts", "part '$qualifiedName'"), namespace)
            val animations = parseAnimations(map.optionalList("animations", "part '$qualifiedName'"))
            require(type != PartType.ITEM_DISPLAY || item != null) {
                "Part '$qualifiedName' of type item_display must declare item"
            }

            return PartDefinition(
                name = qualifiedName,
                type = type,
                item = item,
                interaction = interaction,
                platform = platform,
                offset = offset,
                rotation = rotation,
                parts = parts,
                animations = animations,
                screen = screen
            )
        }

        private fun parseScreenConfig(map: Map<String, Any>, subject: String): PartScreenConfig {
            map.requireOnlyKeys(setOf("category", "interior", "width", "height"), subject)
            return PartScreenConfig(
                category = map.requireString("category", subject),
                interior = map.optionalBooleanValue("interior", subject)
                    ?: throw IllegalArgumentException("$subject.interior is required"),
                width = map.optionalDoubleValue("width", subject)
                    ?: throw IllegalArgumentException("$subject.width is required"),
                height = map.optionalDoubleValue("height", subject)
                    ?: throw IllegalArgumentException("$subject.height is required")
            )
        }

        fun parseItemConfig(map: Map<String, Any>?): PartItemConfig? {
            if (map == null) return null
            map.requireOnlyKeys(
                setOf("type", "item-model", "custom-model-data", "scale"),
                "item"
            )
            val itemModel = map.optionalStringValue("item-model", "item")
            val customModelData = map.optionalIntValue("custom-model-data", "item")
            require(itemModel != null || customModelData != null) {
                "item must declare item-model or custom-model-data"
            }
            require(itemModel == null || itemModel.isNotBlank()) { "item.item-model must not be blank" }
            val itemType = map.requireString("type", "item")
            require(itemType.isNotBlank()) { "item.type must not be blank" }
            require(Material.getMaterial(itemType.uppercase()) != null) { "Unknown item.type '$itemType'" }
            return PartItemConfig(
                type = itemType,
                itemModel = itemModel,
                customModelData = customModelData,
                scale = parseScale(map.optionalMap("scale", "item"))
            )
        }

        fun parseInteractionConfig(map: Map<String, Any>?): PartInteractionConfig {
            if (map == null) return PartInteractionConfig()
            map.requireOnlyKeys(setOf("width", "height", "driver", "control-end"), "interaction")
            val controlEndText = map["control-end"]?.let {
                it as? String ?: throw IllegalArgumentException("interaction.control-end must be a string")
            }
            require(controlEndText == null || controlEndText == controlEndText.trim().lowercase()) {
                "interaction.control-end must be a lowercase value without surrounding whitespace"
            }
            val controlEnd = DriverControlEnd.fromConfig(controlEndText)
            require(controlEndText == null || controlEnd != null) {
                "interaction.control-end has unsupported value: $controlEndText"
            }
            return PartInteractionConfig(
                width = map.optionalDoubleValue("width", "interaction") ?: 1.0,
                height = map.optionalDoubleValue("height", "interaction") ?: 1.0,
                driver = map.optionalBooleanValue("driver", "interaction") ?: false,
                controlEnd = controlEnd
            )
        }

        fun parsePlatformConfig(map: Map<String, Any>?): PartPlatformConfig {
            if (map == null) return PartPlatformConfig()
            map.requireOnlyKeys(
                setOf("rows", "columns", "spacing-x", "spacing-z", "spacing"),
                "platform"
            )
            val spacing = parseVec3(map.optionalMap("spacing", "platform"))
            return PartPlatformConfig(
                rows = map.optionalIntValue("rows", "platform") ?: 1,
                columns = map.optionalIntValue("columns", "platform") ?: 1,
                spacingX = map.optionalDoubleValue("spacing-x", "platform")
                    ?: spacing.x.takeIf { it != 0.0 }
                    ?: 1.0,
                spacingZ = map.optionalDoubleValue("spacing-z", "platform")
                    ?: spacing.z.takeIf { it != 0.0 }
                    ?: 1.0
            )
        }

        fun parseScale(map: Map<String, Any>?): PartScaleConfig? {
            if (map == null) return null
            map.requireOnlyKeys(SCALE_KEYS, "scale")
            return PartScaleConfig(
                x = map.optionalDoubleValue("x", "scale") ?: 1.0,
                y = map.optionalDoubleValue("y", "scale") ?: 1.0,
                z = map.optionalDoubleValue("z", "scale") ?: 1.0
            )
        }

        fun parseVec3(map: Map<String, Any>?): Vec3Config {
            if (map == null) return Vec3Config()
            map.requireOnlyKeys(TRANSFORM_KEYS, "vector")
            return Vec3Config(
                x = map.optionalDoubleValue("x", "vector") ?: 0.0,
                y = map.optionalDoubleValue("y", "vector") ?: 0.0,
                z = map.optionalDoubleValue("z", "vector") ?: 0.0
            )
        }

        fun parsePartList(list: List<*>?, namespace: String): List<PartRefEntry> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val entryMap = (entry as? Map<*, *>)?.toStringKeyMap("parts[$index]")
                    ?: throw IllegalArgumentException("parts[$index] must be a map")
                val offset = parseVec3(entryMap.optionalMap("offset", "parts[$index]"))
                val rotation = parseVec3(entryMap.optionalMap("rotation", "parts[$index]"))

                when {
                    entryMap.containsKey("import") -> {
                        entryMap.requireOnlyKeys(setOf("import", "offset", "rotation"), "parts[$index]")
                        val importName = entryMap.requireString("import", "parts[$index]")
                        require(importName.isNotBlank()) { "parts[$index].import must not be blank" }
                        PartRefEntry(name = importName, offset = offset, rotation = rotation)
                    }
                    entryMap.containsKey("type") -> {
                        entryMap.requireOnlyKeys(PART_KEYS, "parts[$index]")
                        val def = parsePartDefinition(entryMap, namespace)
                        // Inline transforms already belong to the definition. Only imports
                        // carry an additional reference-local transform.
                        PartRefEntry(name = def.name, inline = def)
                    }
                    else -> throw IllegalArgumentException(
                        "parts[$index] must declare either 'import' or an inline 'type'"
                    )
                }
            }
        }

        fun parseAnimations(list: List<*>?): List<PartAnimationConfig> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val entryMap = (entry as? Map<*, *>)?.toStringKeyMap("animations[$index]")
                    ?: throw IllegalArgumentException("animations[$index] must be a map")
                entryMap.requireOnlyKeys(
                    setOf("name", "keep-final-state", "commands", "keyframes"),
                    "animations[$index]"
                )
                val name = entryMap.requireString("name", "animations[$index]")
                require(name.isNotBlank()) { "animations[$index].name must not be blank" }
                val commands = parseAnimationCommands(entryMap.optionalList("commands", "animations[$index]"))
                val keyframes = parseAnimationKeyframes(entryMap.optionalList("keyframes", "animations[$index]"))
                validateUniqueScenes(name, commands.mapNotNull { it.scene } + keyframes.mapNotNull { it.scene })

                PartAnimationConfig(
                    name = name,
                    keepFinalState = entryMap.optionalBooleanValue("keep-final-state", "animations[$index]") ?: true,
                    commands = commands,
                    keyframes = keyframes.sortedBy { it.at }
                )
            }
        }

        private fun parseAnimationCommands(list: List<*>?): List<PartAnimationCommandConfig> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val subject = "animation.commands[$index]"
                val entryMap = (entry as? Map<*, *>)?.toStringKeyMap(subject)
                    ?: throw IllegalArgumentException("$subject must be a map")
                entryMap.requireOnlyKeys(
                    setOf(
                        "duration", "offset", "rotation", "scale", "scene", "item-type",
                        "item-model", "custom-model-data", "brightness"
                    ),
                    subject
                )
                PartAnimationCommandConfig(
                    duration = entryMap.optionalDoubleValue("duration", subject) ?: 0.0,
                    offset = parseVec3(entryMap.optionalMap("offset", subject)),
                    rotation = parseVec3(entryMap.optionalMap("rotation", subject)),
                    scale = parseScale(entryMap.optionalMap("scale", subject)),
                    scene = normalizeSceneName(entryMap.optionalStringValue("scene", subject)),
                    itemType = entryMap.optionalStringValue("item-type", subject)?.also {
                        require(Material.getMaterial(it.uppercase()) != null) { "Unknown $subject.item-type '$it'" }
                    },
                    itemModel = entryMap.optionalStringValue("item-model", subject),
                    customModelData = entryMap.optionalIntValue("custom-model-data", subject),
                    brightness = parseBrightness(entryMap.optionalMap("brightness", subject))
                )
            }
        }

        private fun parseAnimationKeyframes(list: List<*>?): List<PartAnimationKeyframeConfig> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val subject = "animation.keyframes[$index]"
                val entryMap = (entry as? Map<*, *>)?.toStringKeyMap(subject)
                    ?: throw IllegalArgumentException("$subject must be a map")
                entryMap.requireOnlyKeys(
                    setOf(
                        "at", "scene", "offset", "rotation", "scale", "item-type",
                        "item-model", "custom-model-data", "brightness"
                    ),
                    subject
                )
                val at = entryMap.optionalDoubleValue("at", subject)
                    ?: throw IllegalArgumentException("$subject.at is required")
                PartAnimationKeyframeConfig(
                    at = at,
                    scene = normalizeSceneName(entryMap.optionalStringValue("scene", subject)),
                    offset = parseVec3(entryMap.optionalMap("offset", subject)),
                    rotation = parseVec3(entryMap.optionalMap("rotation", subject)),
                    scale = parseScale(entryMap.optionalMap("scale", subject)),
                    itemType = entryMap.optionalStringValue("item-type", subject)?.also {
                        require(Material.getMaterial(it.uppercase()) != null) { "Unknown $subject.item-type '$it'" }
                    },
                    itemModel = entryMap.optionalStringValue("item-model", subject),
                    customModelData = entryMap.optionalIntValue("custom-model-data", subject),
                    brightness = parseBrightness(entryMap.optionalMap("brightness", subject))
                )
            }
        }

        fun parseBrightness(map: Map<String, Any>?): PartBrightnessConfig? {
            if (map == null) return null
            map.requireOnlyKeys(setOf("block", "sky"), "brightness")
            return PartBrightnessConfig(
                block = map.optionalIntValue("block", "brightness") ?: 0,
                sky = map.optionalIntValue("sky", "brightness") ?: 0
            )
        }

        private fun normalizeSceneName(scene: String?): String? =
            scene?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

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
