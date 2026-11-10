package work.art1st.javarailways.bukkit.config.model

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources
import work.art1st.javarailways.bukkit.api.model.extension.immutableExtensionResources
import work.art1st.javarailways.bukkit.api.model.extension.requireExtensionResourcePath
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.TreeMap

/** Explicit resource-directory declaration, independent of any installed addon. */
data class PackageExtensionDeclaration(val directory: String, val schemaVersion: Int = 1) {
    init {
        requireExtensionResourcePath(directory)
        require(schemaVersion > 0) { "Extension resource schema-version must be positive" }
    }
}

/** Captures every regular UTF-8 file, including safe symlinks, with cycle and real-path containment checks. */
internal fun capturePackageExtensions(packageDir: File, declarations: Map<String, PackageExtensionDeclaration>): Map<String, PartExtensionResources> {
    val root = packageDir.toPath().toRealPath()
    return immutableExtensionResources(declarations.mapValues { (typeId, declaration) ->
        val files = TreeMap<String, String>()
        val activeDirectories = mutableSetOf<Path>()
        fun visit(path: Path, relative: String) {
            require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Extension '$typeId' path '$path' is missing" }
            val real = try { path.toRealPath() } catch (failure: Exception) {
                throw IllegalArgumentException("Extension '$typeId' path '$path' has a broken or cyclic link", failure)
            }
            require(real.startsWith(root)) { "Extension '$typeId' path '$path' resolves outside package ${packageDir.path}" }
            when {
                Files.isDirectory(real) -> {
                    require(activeDirectories.add(real)) { "Extension '$typeId' directory '$path' contains a symbolic-link cycle" }
                    try {
                        Files.newDirectoryStream(path).use { entries ->
                            entries.toList().sortedBy { it.fileName.toString() }.forEach { child ->
                                val name = child.fileName.toString()
                                visit(child, if (relative.isEmpty()) name else "$relative/$name")
                            }
                        }
                    } finally { activeDirectories.remove(real) }
                }
                Files.isRegularFile(real) -> {
                    requireExtensionResourcePath(relative)
                    files[relative] = try { Files.readString(real, Charsets.UTF_8) } catch (failure: Exception) {
                        throw IllegalArgumentException("Extension '$typeId' file '$path' cannot be read as UTF-8", failure)
                    }
                }
                else -> throw IllegalArgumentException("Extension '$typeId' path '$path' must be a regular file or directory")
            }
        }
        var component = root
        for (segment in declaration.directory.split('/')) {
            component = component.resolve(segment)
            val realComponent = try { component.toRealPath() } catch (failure: Exception) {
                throw IllegalArgumentException("Extension '$typeId' directory component '$component' cannot be resolved", failure)
            }
            require(realComponent.startsWith(root)) { "Extension '$typeId' directory component '$component' resolves outside package ${packageDir.path}" }
        }
        val directory = root.resolve(declaration.directory)
        require(Files.isDirectory(directory)) { "Extension '$typeId' directory '$directory' must exist and be a directory" }
        visit(directory, "")
        PartExtensionResources(declaration.schemaVersion, files)
    })
}
