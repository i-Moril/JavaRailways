package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.simplesignals.presentation.ModelPackageYaml
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetImportMode
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompilation
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompiler
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlSource
import work.art1st.javarailways.simplesignals.presentation.ModelYamlSource
import work.art1st.javarailways.simplesignals.presentation.ModelYamlValue
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

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
                candidate.models.forEach { (modelId, model) ->
                    if (models.putIfAbsent(modelId, model) != null) {
                        candidate.source.fail("$.name", "duplicate model ID '$modelId'")
                    }
                }
                candidate.logics.forEach { (logicId, logic) ->
                    if (logics.putIfAbsent(logicId, logic) != null) {
                        candidate.source.fail("$.name", "duplicate logic ID '$logicId'")
                    }
                }
            }

            // Resolve signal files only after every package's model and logic files are known.
            val signals = LinkedHashMap<String, SignalPackSignal>()
            val resolvedPackages = LinkedHashMap<String, SignalPack>()
            for (candidate in candidates) {
                val localSignals = LinkedHashMap<String, SignalPackSignal>()
                for (pending in candidate.signals) {
                    val model = models[pending.modelId]
                        ?: wrongKindOrUnknown(pending.modelLocation, pending.modelId, "model", logics)
                    val logic = logics[pending.logicId]
                        ?: wrongKindOrUnknown(pending.logicLocation, pending.logicId, "logic", models)
                    val signal = SignalPackSignal(
                        pending.signalType,
                        pending.file,
                        pending.displayName,
                        model,
                        logic
                    )
                    if (signals.putIfAbsent(pending.signalType, signal) != null) {
                        pending.location.fail("duplicate signal type '${pending.signalType}'")
                    }
                    localSignals[pending.signalType] = signal
                }
                resolvedPackages[candidate.name] = SignalPack(
                    candidate.name,
                    candidate.displayName,
                    candidate.directory,
                    candidate.models,
                    candidate.logics,
                    localSignals
                )
            }
            return SignalPackCatalog(resolvedPackages, models, logics, signals)
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
            val fields = manifest.mapping(setOf("config-version", "name", "display-name"))
            val version = manifest.required(fields, "config-version")
            if (version.integer() != 1) version.fail("only config-version 1 is supported")
            val nameValue = manifest.required(fields, "name")
            val name = nameValue.string()
            if (!validNamespace(name)) nameValue.fail("invalid package name '$name'")
            val source = ModelYamlSource(name, "pack.yml")
            val displayName = fields["display-name"]?.string()

            val modelDirectory = safeDirectory(realDirectory, "model", source)
            val logicDirectory = safeDirectory(realDirectory, "logic", source)
            val signalDirectory = safeDirectory(realDirectory, "signal", source)
            val models = readModels(name, realDirectory, modelDirectory)
            val logics = readLogics(name, realDirectory, logicDirectory)
            val signals = readSignals(name, realDirectory, signalDirectory)
            return Candidate(name, displayName, realDirectory, source, models, logics, signals)
        }

        private fun readModels(
            packageName: String,
            packageRoot: Path,
            directory: Path
        ): Map<String, SignalPackModel> {
            val files = discoveredFiles(packageName, packageRoot, directory, setOf(".yml"))
            val sources = files.associate { file ->
                file.localId to ModelAssetYamlSource(
                    relative(packageRoot, file.file),
                    io(file.source, "$") { Files.readString(file.file) }
                )
            }
            val materialized = ModelAssetYamlCompiler.compile(
                ModelAssetYamlCompilation(packageName, sources, emptyMap(), ModelAssetImportMode.MODELS)
            )
            return files.associate { file ->
                val modelId = "$packageName:${file.localId}"
                modelId to SignalPackModel(
                    modelId,
                    file.file,
                    materialized.getValue(file.localId)
                )
            }
        }

        private fun readLogics(
            packageName: String,
            packageRoot: Path,
            directory: Path
        ): Map<String, SignalPackLogic> {
            val files = discoveredFiles(packageName, packageRoot, directory, setOf(".yml", ".js"))
            val metadata = files.filter { it.extension == ".yml" }.associateBy(DiscoveredFile::localId)
            val scripts = files.filter { it.extension == ".js" }.associateBy(DiscoveredFile::localId)
            val unmatched = (metadata.keys - scripts.keys).firstOrNull()
            if (unmatched != null) metadata.getValue(unmatched).source.fail("$", "missing matching logic source '$unmatched.js'")
            val orphan = (scripts.keys - metadata.keys).firstOrNull()
            if (orphan != null) scripts.getValue(orphan).source.fail("$", "missing matching logic metadata '$orphan.yml'")

            return metadata.toSortedMap().entries.associate { (localId, metadataFile) ->
                val value = ModelPackageYaml.read(
                    io(metadataFile.source, "$") { Files.readString(metadataFile.file) },
                    metadataFile.source
                )
                val fields = value.mapping(setOf("scan-distance-metres", "reference-deceleration-metres-per-second-squared"))
                val distanceValue = fields["scan-distance-metres"]
                val decelerationValue = fields["reference-deceleration-metres-per-second-squared"]
                if ((distanceValue == null) == (decelerationValue == null)) {
                    value.fail("requires exactly one scan-distance-metres or reference-deceleration-metres-per-second-squared")
                }
                val distance = distanceValue?.number()?.also {
                    if (it <= 0.0) distanceValue.fail("scan-distance-metres must be finite and positive")
                }
                val deceleration = decelerationValue?.number()?.also {
                    if (it <= 0.0) decelerationValue.fail("reference-deceleration-metres-per-second-squared must be finite and positive")
                }
                val logicId = "$packageName:$localId"
                val sourceFile = scripts.getValue(localId)
                logicId to SignalPackLogic(
                    logicId,
                    metadataFile.file,
                    sourceFile.file,
                    io(sourceFile.source, "$") { Files.readString(sourceFile.file) },
                    distance,
                    deceleration
                )
            }
        }

        private fun readSignals(
            packageName: String,
            packageRoot: Path,
            directory: Path
        ): List<PendingSignal> = discoveredFiles(packageName, packageRoot, directory, setOf(".yml")).map { file ->
            val value = ModelPackageYaml.read(io(file.source, "$") { Files.readString(file.file) }, file.source)
            val fields = value.mapping(setOf("display-name", "model", "logic"))
            val modelLocation = value.required(fields, "model")
            val logicLocation = value.required(fields, "logic")
            PendingSignal(
                "$packageName:${file.localId}",
                file.file,
                value.required(fields, "display-name").string(),
                namespacedId(modelLocation),
                namespacedId(logicLocation),
                modelLocation,
                logicLocation,
                value
            )
        }

        private fun discoveredFiles(
            packageName: String,
            packageRoot: Path,
            directory: Path,
            allowedExtensions: Set<String>
        ): List<DiscoveredFile> = io(ModelYamlSource(packageName, relative(packageRoot, directory)), "$") {
            Files.walk(directory, FileVisitOption.FOLLOW_LINKS).use { paths ->
                paths.filter { requested ->
                    if (requested == directory) return@filter false
                    val source = ModelYamlSource(packageName, relative(packageRoot, requested))
                    insidePackage(requested, packageRoot, ModelYamlValue(requested.toString(), source, "$"))
                    Files.isRegularFile(requested)
                }.map { requested ->
                    val relative = relative(directory, requested)
                    val source = ModelYamlSource(packageName, relative(packageRoot, requested))
                    val file = insidePackage(requested, packageRoot, ModelYamlValue(relative, source, "$"))
                    val extension = allowedExtensions.singleOrNull { relative.endsWith(it) }
                        ?: source.fail("$", "file extension must be one of ${allowedExtensions.sorted().joinToString()}")
                    val localId = relative.removeSuffix(extension)
                    if (!validLocalId(localId)) source.fail("$", "invalid local ID '$localId'")
                    DiscoveredFile(localId, extension, file, source)
                }.sorted(compareBy(DiscoveredFile::localId, DiscoveredFile::extension)).toList()
            }
        }

        private fun namespacedId(value: ModelYamlValue): String = value.string().also {
            val separator = it.indexOf(':')
            if (separator <= 0 || separator != it.lastIndexOf(':') ||
                !validNamespace(it.substring(0, separator)) || !validLocalId(it.substring(separator + 1))) {
                value.fail("expected a namespaced ID such as package:export")
            }
        }

        private fun safeFile(packageRoot: Path, relative: String, source: ModelYamlSource, field: String): Path {
            val file = insidePackage(packageRoot.resolve(relative), packageRoot, ModelYamlValue(relative, source, field))
            if (!Files.isRegularFile(file)) source.fail(field, "expected a regular $relative file")
            return file
        }

        private fun safeDirectory(packageRoot: Path, relative: String, source: ModelYamlSource): Path {
            val field = "$.${relative}"
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
            location: ModelYamlValue,
            id: String,
            expected: String,
            otherKind: Map<String, T>
        ): Nothing {
            val reason = if (id in otherKind) "reference '$id' is not a $expected ID" else "unknown $expected ID '$id'"
            location.fail(reason)
        }

        private fun relative(base: Path, child: Path): String =
            base.relativize(child).joinToString("/") { it.toString() }

        private fun <T> io(source: ModelYamlSource, field: String, operation: () -> T): T = try {
            operation()
        } catch (error: IOException) {
            source.fail(field, "cannot read package path: ${error.message}", error)
        } catch (error: UncheckedIOException) {
            source.fail(field, "cannot read package path: ${error.cause?.message ?: error.message}", error)
        } catch (error: SecurityException) {
            source.fail(field, "cannot access package path: ${error.message}", error)
        }
    }

    private data class Candidate(
        val name: String,
        val displayName: String?,
        val directory: Path,
        val source: ModelYamlSource,
        val models: Map<String, SignalPackModel>,
        val logics: Map<String, SignalPackLogic>,
        val signals: List<PendingSignal>
    )

    private data class PendingSignal(
        val signalType: String,
        val file: Path,
        val displayName: String,
        val modelId: String,
        val logicId: String,
        val modelLocation: ModelYamlValue,
        val logicLocation: ModelYamlValue,
        val location: ModelYamlValue
    )

    private data class DiscoveredFile(
        val localId: String,
        val extension: String,
        val file: Path,
        val source: ModelYamlSource
    )

    private val NAMESPACE = Regex("[a-z0-9._-]+")
    private val LOCAL_ID = Regex("[a-z0-9._-]+(?:/[a-z0-9._-]+)*")

    private fun validNamespace(value: String): Boolean = NAMESPACE.matches(value) && value != "." && value != ".."

    private fun validLocalId(value: String): Boolean =
        LOCAL_ID.matches(value) && value.split('/').none { it == "." || it == ".." }
}
