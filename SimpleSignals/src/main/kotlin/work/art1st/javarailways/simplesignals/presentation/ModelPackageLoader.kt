package work.art1st.javarailways.simplesignals.presentation

import org.bukkit.Material
import org.joml.Quaterniond
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.api.model.ModelAnimation
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Synchronous preparation only. The caller owns worker scheduling and later binding/installation. */
internal object ModelPackageLoader {
    fun load(directory: Path): LoadedModelPackage = Load(directory).load()

    private class Load(private val directory: Path) {
        private var packageName = directory.fileName?.toString() ?: "model-package"
        private lateinit var root: Path
        private val definitions = LinkedHashMap<String, PartDefinition>()
        private val expanded = HashMap<String, ModelPart>()
        private val active = LinkedHashSet<String>()

        fun load(): LoadedModelPackage {
            root = io(ModelYamlSource(packageName, "manifest.yml"), "$") { directory.toRealPath() }
            if (!Files.isDirectory(root)) ModelYamlSource(packageName, "manifest.yml").fail("$", "package must be a directory")
            val manifest = read("manifest.yml")
            val fields = manifest.mapping(setOf("config-version", "name", "display-name", "models"))
            val version = manifest.required(fields, "config-version")
            if (version.integer() != 1) version.fail("only config-version 1 is supported")
            packageName = manifest.required(fields, "name").string().also {
                if (!PACKAGE_NAME.matches(it)) manifest.required(fields, "name").fail("invalid package name '$it'")
            }
            val displayName = fields["display-name"]?.string()
            val modelsField = manifest.required(fields, "models")
            val names = modelsField.list()
            if (names.isEmpty()) modelsField.fail("models must not be empty")
            val modelNames = HashSet<String>()
            names.forEach { name ->
                val text = name.string()
                reference(text, name)
                if (!modelNames.add(text)) name.fail("duplicate model name '$text'")
            }

            val partsDirectory = root.resolve("parts")
            if (Files.exists(partsDirectory, LinkOption.NOFOLLOW_LINKS)) scanParts(partsDirectory, HashSet())
            validateSharedDefinitions()
            val models = LinkedHashMap<String, LoadedPackageModel>()
            for (nameValue in names) {
                val name = nameValue.string()
                val model = model(name, read("models/$name.yml"))
                models[model.id] = model
            }
            return LoadedModelPackage(packageName, displayName, models)
        }

        private fun validateSharedDefinitions() {
            // Expand every definition to reject missing references/cycles, including unused parts.
            definitions.forEach { (name, definition) -> shared(name, definition.location) }
            val referenced = HashSet<String>()
            fun collectReferences(definition: PartDefinition) {
                for (entry in definition.children) {
                    entry.import?.let(referenced::add)
                    entry.inline?.let(::collectReferences)
                }
            }
            definitions.values.forEach(::collectReferences)
            // An acyclic shared subtree is covered by a root. Avoid traversing every chain suffix,
            // while keeping unrelated roots' animation scene namespaces independent.
            definitions.forEach { (name, definition) ->
                if (name !in referenced) {
                    definition.location.publicValue { ModelDefinition(listOf(expanded.getValue(name))) }
                }
            }
        }

        private fun scanParts(directory: Path, directories: MutableSet<Path>) {
            val relative = relativeFile(directory)
            val source = ModelYamlSource(packageName, relative)
            val real = inside(directory, source, "$")
            if (!Files.isDirectory(real)) source.fail("$", "parts path must be a directory")
            if (!directories.add(real)) source.fail("$", "cyclic directory symlink")
            try {
                io(source, "$") {
                    Files.newDirectoryStream(directory).use { entries ->
                        for (entry in entries) {
                            val entrySource = ModelYamlSource(packageName, relativeFile(entry))
                            val entryReal = inside(entry, entrySource, "$")
                            if (Files.isDirectory(entryReal)) {
                                scanParts(entry, directories)
                            } else if (entry.fileName.toString().endsWith(".yml")) {
                                val name = root.resolve("parts").relativize(entry).joinToString("/").removeSuffix(".yml")
                                if (!localPath(name)) entrySource.fail("$", "invalid local part path '$name'")
                                val value = read("parts/$name.yml")
                                definitions[name] = concrete(value)
                            }
                        }
                    }
                }
            } finally {
                directories.remove(real)
            }
        }

        private fun model(name: String, value: ModelYamlValue): LoadedPackageModel {
            val fields = value.mapping(setOf("parts", "default", "variants", "rail-placement"))
            val partsValue = value.required(fields, "parts")
            val entries = partsValue.list()
            if (entries.isEmpty()) partsValue.fail("model parts must not be empty")
            val parts = entries.map { expand(entry(it)) }
            val definition = partsValue.publicValue { ModelDefinition(parts) }
            val defaults = value.required(fields, "default")
            val defaultScenes = selection(defaults)
            val variantFields = fields["variants"]?.mapping().orEmpty()
            val variants = variantFields.mapValues { (name, selection) ->
                if (name.isBlank()) selection.fail("variant name must not be blank")
                selection(selection)
            }
            val index = sceneIndex(parts)
            validateSelection(defaults, defaultScenes, index)
            variantFields.forEach { (name, selection) -> validateSelection(selection, variants.getValue(name), index) }

            val placement = fields["rail-placement"]
            val placementFields = placement?.mapping(setOf("follow-rail-tilt", "presets")).orEmpty()
            val modelTilt = placementFields["follow-rail-tilt"]?.boolean() ?: false
            val presets = placementFields["presets"]?.mapping().orEmpty().mapValues { (name, preset) ->
                if (name.isBlank()) preset.fail("preset name must not be blank")
                val presetFields = preset.mapping(setOf("offset", "rotation", "follow-rail-tilt"))
                RailPlacementPreset(
                    pose(vector(presetFields["offset"]), vector(presetFields["rotation"]), preset),
                    presetFields["follow-rail-tilt"]?.boolean() ?: modelTilt
                )
            }
            return LoadedPackageModel("$packageName:$name", definition, defaultScenes, variants, modelTilt, presets)
        }

        private fun selection(value: ModelYamlValue): Map<String, String> = value.mapping().mapValues { (name, scene) ->
            if (name.isBlank()) scene.fail("animation name must not be blank")
            scene.string()
        }

        private data class SceneHit(val occurrence: Int, val scenes: Set<String>)

        private fun sceneIndex(parts: List<ModelPart>): Map<String, List<SceneHit>> {
            val index = HashMap<String, MutableList<SceneHit>>()
            var occurrence = 0
            fun visit(part: ModelPart) {
                val current = occurrence++
                for (animation in part.animations()) {
                    val scenes = animation.frames().mapNotNull { it.scene() }.toSet()
                    index.getOrPut(animation.name()) { ArrayList() }.add(SceneHit(current, scenes))
                }
                if (part is ModelPart.Group) part.children().forEach(::visit)
            }
            parts.forEach(::visit)
            return index
        }

        private fun validateSelection(value: ModelYamlValue, selection: Map<String, String>, index: Map<String, List<SceneHit>>) {
            val selected = HashMap<Int, String>()
            val fields = value.mapping()
            selection.forEach { (animation, scene) ->
                val field = fields.getValue(animation)
                val hits = index[animation] ?: field.fail("unknown animation '$animation'")
                for (hit in hits) {
                    if (scene !in hit.scenes) field.fail("unknown scene '$scene'; available scenes: ${hit.scenes.joinToString()}")
                    val previous = selected.putIfAbsent(hit.occurrence, animation)
                    if (previous != null) field.fail("part occurrence ${hit.occurrence} selects both '$previous' and '$animation'")
                }
            }
        }

        private data class Vector(val x: Double, val y: Double, val z: Double) {
            operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)
        }

        private data class PartDefinition(
            val location: ModelYamlValue,
            val group: Boolean,
            val offset: Vector,
            val rotation: Vector,
            val children: List<PartEntry>,
            val scale: ModelScale,
            val appearance: ItemAppearance?,
            val animations: List<ModelAnimation>
        )

        private data class PartEntry(
            val location: ModelYamlValue,
            val import: String? = null,
            val offset: Vector = Vector(0.0, 0.0, 0.0),
            val rotation: Vector = Vector(0.0, 0.0, 0.0),
            val inline: PartDefinition? = null
        )

        private fun entry(value: ModelYamlValue): PartEntry {
            val fields = value.mapping()
            if ("import" !in fields) return PartEntry(value, inline = concrete(value))
            value.mapping(setOf("import", "offset", "rotation"))
            val name = fields.getValue("import").string()
            reference(name, fields.getValue("import"))
            return PartEntry(fields.getValue("import"), name, vector(fields["offset"]), vector(fields["rotation"]))
        }

        private fun concrete(value: ModelYamlValue): PartDefinition {
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
            val names = HashSet<String>()
            animations.forEachIndexed { index, animation ->
                if (!names.add(animation.name())) {
                    fields.getValue("animations").list()[index].fail("part repeats animation '${animation.name()}'")
                }
            }
            return PartDefinition(
                value, group, vector(fields["offset"]), vector(fields["rotation"]),
                fields["parts"]?.list().orEmpty().map(::entry), scale, appearance, animations
            )
        }

        private fun animation(value: ModelYamlValue, group: Boolean): ModelAnimation {
            val fields = value.mapping(setOf("name", "scenes"))
            val name = value.required(fields, "name").string()
            val scenesValue = value.required(fields, "scenes")
            val scenes = scenesValue.mapping()
            if (scenes.isEmpty()) scenesValue.fail("scenes must not be empty")
            val frames = scenes.entries.mapIndexed { index, (scene, frame) ->
                if (scene.isBlank()) frame.fail("scene name must not be blank")
                val frameFields = frame.mapping(if (group) setOf("offset", "rotation") else setOf("offset", "rotation", "scale", "item"))
                frame.publicValue {
                    ModelAnimation.Frame(
                        index.toDouble(), scene,
                        pose(vector(frameFields["offset"]), vector(frameFields["rotation"]), frame),
                        frameFields["scale"]?.let(::scale) ?: ModelScale.UNIT,
                        frameFields["item"]?.let { appearance(it, false) }
                    )
                }
            }
            return value.publicValue { ModelAnimation(name, frames) }
        }

        private fun appearance(value: ModelYamlValue, base: Boolean): ItemAppearance {
            val allowed = setOf("type", "item-model", "custom-model-data", "brightness") + if (base) setOf("scale") else emptySet()
            val fields = value.mapping(allowed)
            val type = value.required(fields, "type")
            val material = type.publicValue { Material.valueOf(type.string()) }
            val brightness = fields["brightness"]?.let { light ->
                val levels = light.mapping(setOf("block", "sky"))
                light.publicValue { ItemAppearance.Brightness(light.required(levels, "block").integer(), light.required(levels, "sky").integer()) }
            }
            return value.publicValue {
                ItemAppearance(material, fields["item-model"]?.string(), fields["custom-model-data"]?.integer(), brightness)
            }
        }

        private fun vector(value: ModelYamlValue?, default: Double = 0.0): Vector {
            val fields = value?.mapping(setOf("x", "y", "z")).orEmpty()
            return Vector(fields["x"]?.number() ?: default, fields["y"]?.number() ?: default, fields["z"]?.number() ?: default)
        }

        private fun scale(value: ModelYamlValue): ModelScale {
            val vector = vector(value, 1.0)
            return value.publicValue { ModelScale(vector.x, vector.y, vector.z) }
        }

        private fun pose(offset: Vector, rotation: Vector, location: ModelYamlValue): ModelPose = location.publicValue {
            val quaternion = Quaterniond().rotateXYZ(Math.toRadians(rotation.x), Math.toRadians(rotation.y), Math.toRadians(rotation.z))
            ModelPose(offset.x, offset.y, offset.z, quaternion.x, quaternion.y, quaternion.z, quaternion.w)
        }

        private fun shared(name: String, location: ModelYamlValue): ModelPart {
            expanded[name]?.let { return it }
            val definition = definitions[name] ?: location.fail("missing shared part '$name'")
            if (!active.add(name)) location.fail("cyclic part reference: ${(active.toList() + name).joinToString(" -> ")}")
            try {
                return build(definition).also { expanded[name] = it }
            } finally {
                active.remove(name)
            }
        }

        private fun expand(entry: PartEntry): ModelPart {
            entry.inline?.let { return build(it) } // Inline offsets/angles belong to the definition exactly once.
            val name = requireNotNull(entry.import)
            val part = shared(name, entry.location)
            val definition = definitions.getValue(name)
            if (entry.offset == Vector(0.0, 0.0, 0.0) && entry.rotation == Vector(0.0, 0.0, 0.0)) return part
            val pose = pose(definition.offset + entry.offset, definition.rotation + entry.rotation, entry.location)
            return entry.location.publicValue {
                when (part) {
                    is ModelPart.Group -> ModelPart.Group(pose, part.children(), part.animations())
                    is ModelPart.Item -> ModelPart.Item(pose, part.scale(), part.appearance(), part.animations())
                }
            }
        }

        private fun build(definition: PartDefinition): ModelPart = definition.location.publicValue {
            val pose = pose(definition.offset, definition.rotation, definition.location)
            if (definition.group) ModelPart.Group(pose, definition.children.map(::expand), definition.animations)
            else ModelPart.Item(pose, definition.scale, requireNotNull(definition.appearance), definition.animations)
        }

        private fun reference(name: String, value: ModelYamlValue) {
            if (!localPath(name)) {
                value.fail("invalid local model/part path '$name'")
            }
        }

        private fun localPath(name: String): Boolean =
            name.split('/').all { PACKAGE_NAME.matches(it) && it != "." && it != ".." }

        private fun relativeFile(path: Path): String = root.relativize(path).joinToString("/")

        private fun read(file: String): ModelYamlValue {
            val source = ModelYamlSource(packageName, file)
            val path = inside(root.resolve(file), source, "$")
            if (!Files.isRegularFile(path)) source.fail("$", "expected a regular .yml file")
            return ModelPackageYaml.read(io(source, "$") { Files.readString(path) }, source)
        }

        private fun inside(path: Path, source: ModelYamlSource, field: String): Path {
            val real = io(source, field) { path.toRealPath() }
            if (!real.startsWith(root)) source.fail(field, "path escapes the package directory")
            return real
        }

        private fun <T> io(source: ModelYamlSource, field: String, operation: () -> T): T = try {
            operation()
        } catch (error: IOException) {
            source.fail(field, "cannot read package path: ${error.message}", error)
        } catch (error: SecurityException) {
            source.fail(field, "cannot access package path: ${error.message}", error)
        }
    }

    private val PACKAGE_NAME = Regex("[a-z0-9._-]+")
}
