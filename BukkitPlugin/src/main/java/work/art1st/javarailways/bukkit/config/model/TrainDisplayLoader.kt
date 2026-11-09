package work.art1st.javarailways.bukkit.config.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Runs only while preparing a package; no service access or deferred filesystem references. */
internal fun loadTrainDisplays(packageDir: File): TrainDisplayResources {
    val root = packageDir.toPath().toRealPath()
    val configuration = root.resolve("displays.yml")
    if (!Files.exists(configuration, LinkOption.NOFOLLOW_LINKS)) return TrainDisplayResources()
    val subject = "train displays ${configuration}"
    try {
        require(Files.isRegularFile(configuration) && Files.isReadable(configuration)) { "$subject must be a readable regular file" }
        require(configuration.toRealPath().startsWith(root)) { "$subject resolves outside package ${packageDir.path}" }
        val map = loadYamlMap(configuration.toFile(), subject, rejectDuplicateKeys = true)
        map.requireOnlyKeys(setOf("config-version", "templates", "defaults"), subject)
        require(map.requireInt("config-version", subject) == 1) { "$subject.config-version must be 1" }
        val definitions = requireNotNull(map.optionalMap("templates", subject)) { "$subject is missing required key 'templates'" }
        val templates = definitions.mapValues { (id, value) ->
            val templateSubject = "$subject.templates.$id"
            requireDisplayIdentifier(id, "$templateSubject ID")
            val entry = (value as? Map<*, *>)?.toStringKeyMap(templateSubject)
                ?: throw IllegalArgumentException("$templateSubject must be a map")
            entry.requireOnlyKeys(setOf("file"), templateSubject)
            val path = entry.requireString("file", templateSubject)
            requireDisplayXmlPath(path, "$templateSubject.file")
            val file = root.resolve(path)
            require(Files.isRegularFile(file) && Files.isReadable(file)) { "$templateSubject.file '$path' must be a readable regular file" }
            val real = file.toRealPath()
            require(real.startsWith(root)) { "$templateSubject.file '$path' resolves outside package ${packageDir.path}" }
            val source = try { Files.readString(real, Charsets.UTF_8) } catch (failure: Exception) {
                throw IllegalArgumentException("$templateSubject.file '$path' cannot be read as UTF-8", failure)
            }
            TrainDisplayTemplate(path, source)
        }
        val defaults = map.optionalMap("defaults", subject).orEmpty().mapValues { (category, value) ->
            requireDisplayIdentifier(category, "$subject.defaults category '$category'")
            value as? String ?: throw IllegalArgumentException("$subject.defaults.$category must be a template ID string")
        }
        return TrainDisplayResources(templates, defaults)
    } catch (failure: Exception) {
        throw IllegalArgumentException("Failed to load $subject: ${failure.message}", failure)
    }
}
