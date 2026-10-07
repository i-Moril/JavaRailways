package work.art1st.javarailways.simplesignals.presentation

import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetImportMode
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompilation
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlCompiler
import work.art1st.javarailways.bukkit.api.model.asset.ModelAssetYamlSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Legacy SimpleSignals file discovery; model-language compilation is owned by BukkitPlugin. */
internal object ModelPackageLoader {
    fun load(directory: Path): LoadedModelPackage = Load(directory).load()

    private class Load(private val directory: Path) {
        private var packageName = directory.fileName?.toString() ?: "model-package"
        private lateinit var root: Path

        fun load(): LoadedModelPackage {
            root = io(ModelYamlSource(packageName, "manifest.yml"), "$") { directory.toRealPath() }
            if (!Files.isDirectory(root)) {
                ModelYamlSource(packageName, "manifest.yml").fail("$", "package must be a directory")
            }
            val manifest = readYaml("manifest.yml")
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
            val modelNames = linkedSetOf<String>()
            names.forEach { name ->
                val text = name.string()
                reference(text, name)
                if (!modelNames.add(text)) name.fail("duplicate model name '$text'")
            }

            val sharedParts = linkedMapOf<String, ModelAssetYamlSource>()
            val partsDirectory = root.resolve("parts")
            if (Files.exists(partsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                scanParts(partsDirectory, hashSetOf(), sharedParts)
            }
            val models = linkedMapOf<String, ModelAssetYamlSource>()
            modelNames.forEach { name -> models[name] = readSource("models/$name.yml") }
            val compiled = try {
                ModelAssetYamlCompiler.compile(
                    ModelAssetYamlCompilation(packageName, models, sharedParts, ModelAssetImportMode.SHARED_PARTS)
                )
            } catch (error: IllegalArgumentException) {
                throw ModelPackageException(error.message ?: "model asset compilation failed", error)
            }
            return LoadedModelPackage(
                packageName,
                displayName,
                modelNames.associate { name ->
                    val id = "$packageName:$name"
                    id to LoadedPackageModel(id, compiled.getValue(name))
                }
            )
        }

        private fun scanParts(
            directory: Path,
            directories: MutableSet<Path>,
            result: MutableMap<String, ModelAssetYamlSource>
        ) {
            val relative = relativeFile(directory)
            val source = ModelYamlSource(packageName, relative)
            val real = inside(directory, source, "$")
            if (!Files.isDirectory(real)) source.fail("$", "parts path must be a directory")
            if (!directories.add(real)) source.fail("$", "cyclic directory symlink")
            try {
                io(source, "$") {
                    Files.newDirectoryStream(directory).use { entries ->
                        entries.sortedBy { it.fileName.toString() }.forEach { entry ->
                            val entrySource = ModelYamlSource(packageName, relativeFile(entry))
                            val entryReal = inside(entry, entrySource, "$")
                            if (Files.isDirectory(entryReal)) {
                                scanParts(entry, directories, result)
                            } else if (entry.fileName.toString().endsWith(".yml")) {
                                val name = root.resolve("parts").relativize(entry)
                                    .joinToString("/").removeSuffix(".yml")
                                if (!localPath(name)) entrySource.fail("$", "invalid local part path '$name'")
                                result[name] = readSource("parts/$name.yml")
                            }
                        }
                    }
                }
            } finally {
                directories.remove(real)
            }
        }

        private fun readYaml(file: String): ModelYamlValue {
            val source = ModelYamlSource(packageName, file)
            return ModelPackageYaml.read(readText(file, source), source)
        }

        private fun readSource(file: String): ModelAssetYamlSource {
            val source = ModelYamlSource(packageName, file)
            return ModelAssetYamlSource(file, readText(file, source))
        }

        private fun readText(file: String, source: ModelYamlSource): String {
            val path = inside(root.resolve(file), source, "$")
            if (!Files.isRegularFile(path)) source.fail("$", "expected a regular .yml file")
            return io(source, "$") { Files.readString(path) }
        }

        private fun reference(name: String, value: ModelYamlValue) {
            if (!localPath(name)) value.fail("invalid local model/part path '$name'")
        }

        private fun localPath(name: String): Boolean =
            name.split('/').all { PACKAGE_NAME.matches(it) && it != "." && it != ".." }

        private fun relativeFile(path: Path): String = root.relativize(path).joinToString("/")

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
