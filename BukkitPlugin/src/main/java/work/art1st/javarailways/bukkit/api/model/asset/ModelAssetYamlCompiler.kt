package work.art1st.javarailways.bukkit.api.model.asset

import org.bukkit.Material
import org.joml.Quaterniond
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.api.model.ModelAnimation
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import java.io.StringReader
import java.util.Collections

/** Pure worker-safe compilation of captured ordinary-model YAML. Performs no file or world access. */
object ModelAssetYamlCompiler {
    @JvmStatic
    fun compile(compilation: ModelAssetYamlCompilation): Map<String, CompiledModelAsset> =
        Compile(compilation).compile()

    private class Compile(private val input: ModelAssetYamlCompilation) {
        private val definitions = linkedMapOf<String, PartDefinition>()
        private val expanded = hashMapOf<String, ModelPart>()
        private val active = linkedSetOf<String>()

        fun compile(): Map<String, CompiledModelAsset> {
            val models = input.models().toSortedMap().mapValues { (_, source) -> read(source) }
            when (input.importMode()) {
                ModelAssetImportMode.SHARED_PARTS -> input.sharedParts().toSortedMap().forEach { (name, source) ->
                    reference(name, Source(input.diagnosticNamespace(), source.sourceName()), "$")
                    definitions[name] = concrete(read(source))
                }
                ModelAssetImportMode.MODELS -> models.forEach { (name, value) ->
                    reference(name, value.source, "$")
                    definitions[name] = modelImportDefinition(value)
                }
            }
            validateDefinitions()

            val results = linkedMapOf<String, CompiledModelAsset>()
            models.forEach { (name, value) ->
                reference(name, value.source, "$")
                val parts = when (input.importMode()) {
                    ModelAssetImportMode.SHARED_PARTS -> null
                    ModelAssetImportMode.MODELS -> {
                        val imported = shared(name, value) as? ModelPart.Group
                            ?: error("model import root must be a group")
                        imported.children()
                    }
                }
                results[name] = model(value, parts)
            }
            return Collections.unmodifiableMap(LinkedHashMap(results))
        }

        private fun read(source: ModelAssetYamlSource): YamlValue =
            StrictYaml.read(source.yaml(), Source(input.diagnosticNamespace(), source.sourceName()))

        private fun validateDefinitions() {
            definitions.forEach { (name, definition) -> shared(name, definition.location) }
            val referenced = hashSetOf<String>()
            fun collect(definition: PartDefinition) {
                definition.children.forEach { entry ->
                    entry.import?.let(referenced::add)
                    entry.inline?.let(::collect)
                }
            }
            definitions.values.forEach(::collect)
            definitions.forEach { (name, definition) ->
                if (name !in referenced) {
                    definition.location.publicValue { ModelDefinition(listOf(expanded.getValue(name))) }
                }
            }
        }

        private fun modelImportDefinition(value: YamlValue): PartDefinition {
            val fields = modelFields(value)
            val partsValue = value.required(fields, "parts")
            val entries = partsValue.list()
            if (entries.isEmpty()) partsValue.fail("model parts must not be empty")
            return PartDefinition(
                value,
                true,
                Vector.ZERO,
                Vector.ZERO,
                entries.map(::entry),
                ModelScale.UNIT,
                null,
                emptyList()
            )
        }

        private fun model(value: YamlValue, materializedParts: List<ModelPart>?): CompiledModelAsset {
            val fields = modelFields(value)
            val partsValue = value.required(fields, "parts")
            val entries = partsValue.list()
            if (entries.isEmpty()) partsValue.fail("model parts must not be empty")
            val parts = materializedParts ?: entries.map { expand(entry(it)) }
            val definition = partsValue.publicValue { ModelDefinition(parts) }
            val defaults = value.required(fields, "default")
            val defaultScenes = selection(defaults)
            val variantFields = fields["variants"]?.mapping().orEmpty()
            val variants = variantFields.mapValues { (name, selected) ->
                if (name.isBlank()) selected.fail("variant name must not be blank")
                selection(selected)
            }
            val index = sceneIndex(parts)
            validateSelection(defaults, defaultScenes, index)
            variantFields.forEach { (name, selected) ->
                validateSelection(selected, variants.getValue(name), index)
            }

            val placement = fields["rail-placement"]
            val placementFields = placement?.mapping(setOf("follow-rail-tilt", "presets")).orEmpty()
            val modelTilt = placementFields["follow-rail-tilt"]?.boolean() ?: false
            val presets = placementFields["presets"]?.mapping().orEmpty().mapValues { (name, preset) ->
                if (name.isBlank()) preset.fail("preset name must not be blank")
                val presetFields = preset.mapping(setOf("offset", "rotation", "follow-rail-tilt"))
                ModelAssetPlacementPreset(
                    pose(vector(presetFields["offset"]), vector(presetFields["rotation"]), preset),
                    presetFields["follow-rail-tilt"]?.boolean() ?: modelTilt
                )
            }
            return CompiledModelAsset(
                definition,
                defaultScenes,
                variants,
                ModelAssetPlacement(modelTilt, presets)
            )
        }

        private fun modelFields(value: YamlValue): Map<String, YamlValue> =
            value.mapping(setOf("parts", "default", "variants", "rail-placement"))

        private fun selection(value: YamlValue): Map<String, String> = value.mapping().mapValues { (name, scene) ->
            if (name.isBlank()) scene.fail("animation name must not be blank")
            scene.string()
        }

        private data class SceneHit(val occurrence: Int, val scenes: Set<String>)

        private fun sceneIndex(parts: List<ModelPart>): Map<String, List<SceneHit>> {
            val index = hashMapOf<String, MutableList<SceneHit>>()
            var occurrence = 0
            fun visit(part: ModelPart) {
                val current = occurrence++
                part.animations().forEach { animation ->
                    val scenes = animation.frames().mapNotNull { it.scene() }.toSet()
                    index.getOrPut(animation.name()) { arrayListOf() }.add(SceneHit(current, scenes))
                }
                if (part is ModelPart.Group) part.children().forEach(::visit)
            }
            parts.forEach(::visit)
            return index
        }

        private fun validateSelection(
            value: YamlValue,
            selection: Map<String, String>,
            index: Map<String, List<SceneHit>>
        ) {
            val selected = hashMapOf<Int, String>()
            val fields = value.mapping()
            selection.forEach { (animation, scene) ->
                val field = fields.getValue(animation)
                val hits = index[animation] ?: field.fail("unknown animation '$animation'")
                hits.forEach { hit ->
                    if (scene !in hit.scenes) {
                        field.fail("unknown scene '$scene'; available scenes: ${hit.scenes.joinToString()}")
                    }
                    val previous = selected.putIfAbsent(hit.occurrence, animation)
                    if (previous != null) {
                        field.fail("part occurrence ${hit.occurrence} selects both '$previous' and '$animation'")
                    }
                }
            }
        }

        private data class Vector(val x: Double, val y: Double, val z: Double) {
            operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)

            companion object { val ZERO = Vector(0.0, 0.0, 0.0) }
        }

        private data class PartDefinition(
            val location: YamlValue,
            val group: Boolean,
            val offset: Vector,
            val rotation: Vector,
            val children: List<PartEntry>,
            val scale: ModelScale,
            val appearance: ItemAppearance?,
            val animations: List<ModelAnimation>
        )

        private data class PartEntry(
            val location: YamlValue,
            val import: String? = null,
            val offset: Vector = Vector.ZERO,
            val rotation: Vector = Vector.ZERO,
            val inline: PartDefinition? = null
        )

        private fun entry(value: YamlValue): PartEntry {
            val fields = value.mapping()
            if ("import" !in fields) return PartEntry(value, inline = concrete(value))
            value.mapping(setOf("import", "offset", "rotation"))
            val location = fields.getValue("import")
            val name = location.string()
            reference(name, location.source, location.path)
            return PartEntry(location, name, vector(fields["offset"]), vector(fields["rotation"]))
        }

        private fun concrete(value: YamlValue): PartDefinition {
            val fields = value.mapping(setOf("type", "offset", "rotation", "parts", "item", "animations"))
            val type = value.required(fields, "type")
            val group = when (type.string()) {
                "dummy" -> true
                "item_display" -> false
                else -> type.fail("only dummy and item_display parts are supported")
            }
            if (group && "item" in fields) fields.getValue("item").fail("dummy cannot have an item")
            if (!group && "parts" in fields) fields.getValue("parts").fail("item_display must be a leaf")
            val item = if (group) null else value.required(fields, "item")
            val appearance = item?.let { appearance(it, true) }
            val scale = item?.mapping()?.get("scale")?.let(::scale) ?: ModelScale.UNIT
            val animations = fields["animations"]?.list().orEmpty().map { animation(it, group) }
            val names = hashSetOf<String>()
            animations.forEachIndexed { index, animation ->
                if (!names.add(animation.name())) {
                    fields.getValue("animations").list()[index].fail("part repeats animation '${animation.name()}'")
                }
            }
            return PartDefinition(
                value,
                group,
                vector(fields["offset"]),
                vector(fields["rotation"]),
                fields["parts"]?.list().orEmpty().map(::entry),
                scale,
                appearance,
                animations
            )
        }

        private fun animation(value: YamlValue, group: Boolean): ModelAnimation {
            val fields = value.mapping(setOf("name", "scenes"))
            val name = value.required(fields, "name").string()
            val scenesValue = value.required(fields, "scenes")
            val scenes = scenesValue.mapping()
            if (scenes.isEmpty()) scenesValue.fail("scenes must not be empty")
            val frames = scenes.entries.mapIndexed { index, (scene, frame) ->
                if (scene.isBlank()) frame.fail("scene name must not be blank")
                val allowed = if (group) setOf("offset", "rotation")
                    else setOf("offset", "rotation", "scale", "item")
                val frameFields = frame.mapping(allowed)
                frame.publicValue {
                    ModelAnimation.Frame(
                        index.toDouble(),
                        scene,
                        pose(vector(frameFields["offset"]), vector(frameFields["rotation"]), frame),
                        frameFields["scale"]?.let(::scale) ?: ModelScale.UNIT,
                        frameFields["item"]?.let { appearance(it, false) }
                    )
                }
            }
            return value.publicValue { ModelAnimation(name, frames) }
        }

        private fun appearance(value: YamlValue, base: Boolean): ItemAppearance {
            val allowed = setOf("type", "item-model", "custom-model-data", "brightness") +
                if (base) setOf("scale") else emptySet()
            val fields = value.mapping(allowed)
            val type = value.required(fields, "type")
            val material = type.publicValue { Material.valueOf(type.string()) }
            val brightness = fields["brightness"]?.let { light ->
                val levels = light.mapping(setOf("block", "sky"))
                light.publicValue {
                    ItemAppearance.Brightness(
                        light.required(levels, "block").integer(),
                        light.required(levels, "sky").integer()
                    )
                }
            }
            return value.publicValue {
                ItemAppearance(
                    material,
                    fields["item-model"]?.string(),
                    fields["custom-model-data"]?.integer(),
                    brightness
                )
            }
        }

        private fun vector(value: YamlValue?, default: Double = 0.0): Vector {
            val fields = value?.mapping(setOf("x", "y", "z")).orEmpty()
            return Vector(
                fields["x"]?.number() ?: default,
                fields["y"]?.number() ?: default,
                fields["z"]?.number() ?: default
            )
        }

        private fun scale(value: YamlValue): ModelScale {
            val vector = vector(value, 1.0)
            return value.publicValue { ModelScale(vector.x, vector.y, vector.z) }
        }

        private fun pose(offset: Vector, rotation: Vector, location: YamlValue): ModelPose = location.publicValue {
            val quaternion = Quaterniond().rotateXYZ(
                Math.toRadians(rotation.x),
                Math.toRadians(rotation.y),
                Math.toRadians(rotation.z)
            )
            ModelPose(offset.x, offset.y, offset.z, quaternion.x, quaternion.y, quaternion.z, quaternion.w)
        }

        private fun shared(name: String, location: YamlValue): ModelPart {
            expanded[name]?.let { return it }
            val definition = definitions[name] ?: location.fail(
                when (input.importMode()) {
                    ModelAssetImportMode.SHARED_PARTS -> "missing shared part '$name'"
                    ModelAssetImportMode.MODELS -> "missing model '$name'"
                }
            )
            if (!active.add(name)) {
                location.fail("cyclic part reference: ${(active.toList() + name).joinToString(" -> ")}")
            }
            try {
                return build(definition).also { expanded[name] = it }
            } finally {
                active.remove(name)
            }
        }

        private fun expand(entry: PartEntry): ModelPart {
            entry.inline?.let { return build(it) }
            val name = requireNotNull(entry.import)
            val part = shared(name, entry.location)
            val definition = definitions.getValue(name)
            if (entry.offset == Vector.ZERO && entry.rotation == Vector.ZERO) return part
            val transformed = pose(definition.offset + entry.offset, definition.rotation + entry.rotation, entry.location)
            return entry.location.publicValue {
                when (part) {
                    is ModelPart.Group -> ModelPart.Group(transformed, part.children(), part.animations())
                    is ModelPart.Item -> ModelPart.Item(transformed, part.scale(), part.appearance(), part.animations())
                }
            }
        }

        private fun build(definition: PartDefinition): ModelPart = definition.location.publicValue {
            val transform = pose(definition.offset, definition.rotation, definition.location)
            if (definition.group) {
                ModelPart.Group(transform, definition.children.map(::expand), definition.animations)
            } else {
                ModelPart.Item(transform, definition.scale, requireNotNull(definition.appearance), definition.animations)
            }
        }

        private fun reference(name: String, source: Source, path: String) {
            if (!localPath(name)) source.fail(path, "invalid local model/part path '$name'")
        }

        private fun localPath(name: String): Boolean =
            name.split('/').all { LOCAL_NAME.matches(it) && it != "." && it != ".." }
    }

    private data class Source(val namespace: String, val file: String) {
        fun fail(path: String, reason: String, cause: Throwable? = null): Nothing =
            throw IllegalArgumentException("$namespace/$file: $path — $reason", cause)
    }

    private object StrictYaml {
        fun read(text: String, source: Source): YamlValue {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                tagInspector = org.yaml.snakeyaml.inspector.TagInspector { true }
            }
            val yaml = Yaml(SafeConstructor(options))
            try {
                yaml.parse(StringReader(text)).forEach { event ->
                    if (event is AliasEvent) {
                        source.fail("$", "YAML aliases are not allowed (line ${event.startMark.line + 1})")
                    }
                }
                val root = yaml.compose(StringReader(text)) ?: source.fail("$", "empty YAML document")
                validate(root, source, "$")
                return YamlValue(yaml.load<Any>(text), source, "$")
            } catch (error: IllegalArgumentException) {
                if (error.message?.startsWith("${source.namespace}/${source.file}:") == true) throw error
                source.fail("$", "invalid YAML: ${error.message}", error)
            } catch (error: RuntimeException) {
                source.fail("$", "invalid YAML: ${error.message}", error)
            }
        }

        private fun validate(node: Node, source: Source, path: String) {
            when (node) {
                is MappingNode -> {
                    if (node.tag != Tag.MAP) source.fail(path, "YAML object tags are not allowed: ${node.tag}")
                    val keys = hashSetOf<String>()
                    node.value.forEach { entry ->
                        val key = entry.keyNode as? ScalarNode
                            ?: source.fail(path, "mapping keys must be strings")
                        if (key.tag != Tag.STR || key.value == "<<") {
                            source.fail(path, "mapping keys must be strings; merge keys are not allowed")
                        }
                        val childPath = if (path == "$") key.value else "$path.${key.value}"
                        if (!keys.add(key.value)) source.fail(childPath, "duplicate YAML key '${key.value}'")
                        validate(entry.valueNode, source, childPath)
                    }
                }
                is SequenceNode -> {
                    if (node.tag != Tag.SEQ) source.fail(path, "YAML object tags are not allowed: ${node.tag}")
                    node.value.forEachIndexed { index, child -> validate(child, source, "$path[$index]") }
                }
                is ScalarNode -> {
                    if (node.tag == Tag.NULL) source.fail(path, "explicit null is not allowed; omit optional fields")
                    if (node.tag !in setOf(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL)) {
                        source.fail(path, "YAML object/value tag is not allowed: ${node.tag}")
                    }
                }
                else -> source.fail(path, "unsupported YAML value")
            }
        }
    }

    private class YamlValue(private val value: Any, val source: Source, val path: String) {
        fun fail(reason: String): Nothing = source.fail(path, reason)

        fun mapping(allowed: Set<String>? = null): Map<String, YamlValue> {
            val map = value as? Map<*, *> ?: fail("expected a mapping")
            return map.entries.associate { (key, child) ->
                val name = key as? String ?: fail("mapping keys must be strings")
                val childPath = if (path == "$") name else "$path.$name"
                if (allowed != null && name !in allowed) source.fail(childPath, "unknown field '$name'")
                name to YamlValue(child ?: source.fail(childPath, "explicit null is not allowed"), source, childPath)
            }
        }

        fun list(): List<YamlValue> {
            val list = value as? List<*> ?: fail("expected a list")
            return list.mapIndexed { index, child ->
                YamlValue(child ?: source.fail("$path[$index]", "explicit null is not allowed"), source, "$path[$index]")
            }
        }

        fun string(): String = (value as? String ?: fail("expected a string")).also {
            if (it.isBlank()) fail("name/value must not be blank")
        }

        fun boolean(): Boolean = value as? Boolean ?: fail("expected a boolean")

        fun number(): Double {
            val number = value as? Number ?: fail("expected a number")
            return number.toDouble().also { if (!it.isFinite()) fail("number must be finite") }
        }

        fun integer(): Int {
            if (value !is Byte && value !is Short && value !is Int && value !is Long &&
                value !is java.math.BigInteger
            ) fail("expected an integer")
            return value.toString().toIntOrNull() ?: fail("integer is outside the 32-bit range")
        }

        fun required(map: Map<String, YamlValue>, key: String): YamlValue = map[key]
            ?: source.fail(if (path == "$") key else "$path.$key", "missing required field")

        fun <T> publicValue(create: () -> T): T = try {
            create()
        } catch (error: IllegalArgumentException) {
            source.fail(path, error.message ?: "invalid public model value", error)
        }
    }

    private val LOCAL_NAME = Regex("[a-z0-9._-]+")
}
