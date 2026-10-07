package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.simplesignals.presentation.ModelPackageYaml
import work.art1st.javarailways.simplesignals.presentation.ModelYamlSource
import work.art1st.javarailways.simplesignals.presentation.ModelYamlValue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/** Low-frequency package discovery and preparation. The caller owns scheduling and publication. */
object SignalPackLoader {
    fun load(directory: Path): SignalPackCatalog = Load(directory).load()

    private class Load(private val directory: Path) {
        private lateinit var root: Path

        fun load(): SignalPackCatalog {
            val rootSource = ModelYamlSource("signal-packs", "pack.yml")
            root = io(rootSource, "$") { directory.toRealPath() }
            if (!Files.isDirectory(root)) rootSource.fail("$", "signal pack root must be a directory")

            val candidates = children().map(::readCandidate)
            val candidatesByName = LinkedHashMap<String, Candidate>()
            for (candidate in candidates) {
                val previous = candidatesByName.putIfAbsent(candidate.name, candidate)
                if (previous != null) candidate.source.fail("$.name", "duplicate package name '${candidate.name}'")
            }

            val models = LinkedHashMap<String, SignalPackModel>()
            val logics = LinkedHashMap<String, SignalPackLogic>()
            for (candidate in candidates) {
                candidate.models.forEach { (id, model) ->
                    if (models.putIfAbsent(id, model) != null) candidate.source.fail("$.models", "duplicate model export '$id'")
                }
                candidate.logics.forEach { (id, logic) ->
                    if (logics.putIfAbsent(id, logic) != null) candidate.source.fail("$.logics", "duplicate logic export '$id'")
                }
            }

            // Resolve only after every manifest and export has been parsed. Directory order is irrelevant.
            val combinations = LinkedHashMap<String, SignalPackCombination>()
            val resolvedPackages = LinkedHashMap<String, SignalPack>()
            for (candidate in candidates) {
                val local = LinkedHashMap<String, SignalPackCombination>()
                for (pending in candidate.combinations) {
                    val model = models[pending.model]
                        ?: wrongKindOrUnknown(candidate, pending.location, pending.model, "model", logics)
                    val logic = logics[pending.logic]
                        ?: wrongKindOrUnknown(candidate, pending.location, pending.logic, "logic", models)
                    val combination = SignalPackCombination(pending.id, pending.displayName, model, logic)
                    if (combinations.putIfAbsent(pending.id, combination) != null) {
                        pending.location.fail("duplicate combination ID '${pending.id}'")
                    }
                    local[pending.id] = combination
                }
                resolvedPackages[candidate.name] = SignalPack(
                    candidate.name,
                    candidate.directory,
                    candidate.models,
                    candidate.logics,
                    local
                )
            }
            return SignalPackCatalog(resolvedPackages, models, logics, combinations)
        }

        private fun children(): List<Path> = io(ModelYamlSource("signal-packs", "pack.yml"), "$") {
            Files.newDirectoryStream(root).use { entries ->
                entries.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
            }
        }

        private fun readCandidate(path: Path): Candidate {
            val directoryName = path.fileName?.toString() ?: "signal-pack"
            val initialSource = ModelYamlSource(directoryName, "pack.yml")
            val realDirectory = insideRoot(path, initialSource, "$")
            if (!Files.isDirectory(realDirectory)) initialSource.fail("$", "package must be a directory")
            val manifestPath = safeFile(realDirectory, "pack.yml", initialSource, "$")
            val manifest = ModelPackageYaml.read(io(initialSource, "$") { Files.readString(manifestPath) }, initialSource)
            val fields = manifest.mapping(setOf("config-version", "name", "models", "logics", "combinations"))
            val version = manifest.required(fields, "config-version")
            if (version.integer() != 1) version.fail("only config-version 1 is supported")
            val nameValue = manifest.required(fields, "name")
            val name = nameValue.string()
            if (!NAMESPACE.matches(name)) nameValue.fail("invalid package name '$name'")
            val source = ModelYamlSource(name, "pack.yml")
            safeDirectory(realDirectory, "model", source)
            safeDirectory(realDirectory, "logic", source)

            val modelsValue = manifest.required(fields, "models")
            val models = LinkedHashMap<String, SignalPackModel>()
            for ((localId, fileValue) in modelsValue.mapping()) {
                validateLocalId(localId, fileValue)
                val file = safeExportFile(realDirectory, "model", fileValue.string(), fileValue, null)
                val id = "$name:$localId"
                if (models.putIfAbsent(id, SignalPackModel(id, file)) != null) fileValue.fail("duplicate model export '$id'")
            }

            val logicsValue = manifest.required(fields, "logics")
            val logics = LinkedHashMap<String, SignalPackLogic>()
            for ((localId, value) in logicsValue.mapping()) {
                validateLocalId(localId, value)
                val logicFields = value.mapping(setOf("file", "scan-distance-metres"))
                val fileValue = value.required(logicFields, "file")
                val file = safeExportFile(realDirectory, "logic", fileValue.string(), fileValue, ".js")
                val sourceText = io(fileValue.source, fileValue.path) { Files.readString(file) }
                val distanceValue = value.required(logicFields, "scan-distance-metres")
                val distance = distanceValue.number()
                if (distance <= 0.0) distanceValue.fail("scan-distance-metres must be finite and positive")
                val id = "$name:$localId"
                if (logics.putIfAbsent(id, SignalPackLogic(id, file, sourceText, distance)) != null) {
                    value.fail("duplicate logic export '$id'")
                }
            }

            val combinationsValue = manifest.required(fields, "combinations")
            val combinations = ArrayList<PendingCombination>()
            val combinationIds = HashSet<String>()
            for ((localId, value) in combinationsValue.mapping()) {
                validateLocalId(localId, value)
                val combinationFields = value.mapping(setOf("display-name", "model", "logic"))
                val displayName = value.required(combinationFields, "display-name").string()
                val model = namespacedId(value.required(combinationFields, "model"))
                val logic = namespacedId(value.required(combinationFields, "logic"))
                val id = "$name:$localId"
                if (!combinationIds.add(id)) value.fail("duplicate combination ID '$id'")
                combinations += PendingCombination(id, displayName, model, logic, value)
            }
            return Candidate(name, realDirectory, source, models, logics, combinations)
        }

        private fun validateLocalId(id: String, location: ModelYamlValue) {
            if (!LOCAL_ID.matches(id)) location.fail("invalid local ID '$id'")
        }

        private fun namespacedId(value: ModelYamlValue): String = value.string().also {
            val separator = it.indexOf(':')
            if (separator <= 0 || separator != it.lastIndexOf(':') ||
                !NAMESPACE.matches(it.substring(0, separator)) || !LOCAL_ID.matches(it.substring(separator + 1))) {
                value.fail("expected a namespaced ID such as package:export")
            }
        }

        private fun safeExportFile(
            packageRoot: Path,
            childDirectory: String,
            relative: String,
            location: ModelYamlValue,
            extension: String?
        ): Path {
            if (!RELATIVE_FILE.matches(relative) || relative.split('/').any { it == "." || it == ".." }) {
                location.fail("invalid relative file '$relative'")
            }
            if (extension != null && !relative.endsWith(extension)) location.fail("logic file must end with '$extension'")
            val base = insidePackage(packageRoot.resolve(childDirectory), packageRoot, location)
            if (!Files.isDirectory(base)) location.fail("$childDirectory path must be a directory")
            val requested = try {
                Paths.get(relative)
            } catch (error: InvalidPathException) {
                location.source.fail(location.path, "invalid relative file '$relative'", error)
            }
            if (requested.isAbsolute) location.fail("file must be relative to $childDirectory/")
            val file = insidePackage(base.resolve(requested), packageRoot, location)
            if (!file.startsWith(base)) location.fail("path escapes $childDirectory/")
            if (!Files.isRegularFile(file)) location.fail("expected a regular file")
            return file
        }

        private fun safeFile(packageRoot: Path, relative: String, source: ModelYamlSource, field: String): Path {
            val file = insidePackage(packageRoot.resolve(relative), packageRoot, ModelYamlValue(relative, source, field))
            if (!Files.isRegularFile(file)) source.fail(field, "expected a regular $relative file")
            return file
        }

        private fun safeDirectory(packageRoot: Path, relative: String, source: ModelYamlSource): Path {
            val field = "$.$relative"
            val requested = packageRoot.resolve(relative)
            if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) source.fail(field, "$relative path must be a directory")
            val child = insidePackage(requested, packageRoot, ModelYamlValue(relative, source, field))
            if (!Files.isDirectory(child)) source.fail(field, "$relative path must be a directory")
            return child
        }

        private fun insideRoot(path: Path, source: ModelYamlSource, field: String): Path {
            val real = io(source, field) { path.toRealPath() }
            if (!real.startsWith(root)) source.fail(field, "path escapes the signal pack root")
            return real
        }

        private fun insidePackage(path: Path, packageRoot: Path, location: ModelYamlValue): Path {
            val real = io(location.source, location.path) { path.toRealPath() }
            if (!real.startsWith(packageRoot)) location.fail("path escapes the package directory")
            return real
        }

        private fun <T> wrongKindOrUnknown(
            candidate: Candidate,
            location: ModelYamlValue,
            id: String,
            expected: String,
            otherKind: Map<String, T>
        ): Nothing {
            val reason = if (id in otherKind) "reference '$id' is not a $expected export" else "unknown $expected export '$id'"
            candidate.source.fail(location.path, reason)
        }

        private fun <T> io(source: ModelYamlSource, field: String, operation: () -> T): T = try {
            operation()
        } catch (error: IOException) {
            source.fail(field, "cannot read package path: ${error.message}", error)
        } catch (error: SecurityException) {
            source.fail(field, "cannot access package path: ${error.message}", error)
        }
    }

    private data class Candidate(
        val name: String,
        val directory: Path,
        val source: ModelYamlSource,
        val models: Map<String, SignalPackModel>,
        val logics: Map<String, SignalPackLogic>,
        val combinations: List<PendingCombination>
    )

    private data class PendingCombination(
        val id: String,
        val displayName: String,
        val model: String,
        val logic: String,
        val location: ModelYamlValue
    )

    private val NAMESPACE = Regex("[a-z0-9._-]+")
    private val LOCAL_ID = Regex("[a-z0-9._-]+(?:/[a-z0-9._-]+)*")
    private val RELATIVE_FILE = Regex("[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)*")
}
