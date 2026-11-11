package work.art1st.javarailways.decorations.display

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Enable-time snapshot only. Mounted sessions never consult these files again. */
object DisplayStyleBundles {
    private const val MAX_FILES = 256
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024

    fun load(directory: Path): DisplayResources {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return DisplayResources()
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Display style directory must be an ordinary directory: $directory" }
        val templates = linkedMapOf<String, DisplayTemplateSource>()
        var fileCount = 0
        var totalBytes = 0
        Files.list(directory).use { children ->
            children.sorted().forEach { bundle ->
                require(Files.isDirectory(bundle, NOFOLLOW_LINKS)) { "Display style bundle must be an ordinary directory: $bundle" }
                val name = bundle.fileName.toString()
                require(Regex("[a-z0-9][a-z0-9_-]*").matches(name)) { "Invalid display style bundle name '$name'" }
                val files = linkedMapOf<String, String>()
                Files.walk(bundle, 16).use { entries ->
                    var entryCount = 0
                    entries.forEach entry@{ file ->
                        require(++entryCount <= 1024) { "Display style bundle '$name' contains too many entries" }
                        require(!Files.isSymbolicLink(file)) { "Display style bundle '$name' contains a symbolic link: $file" }
                        if (Files.isDirectory(file, NOFOLLOW_LINKS)) return@entry
                        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Display style bundle '$name' contains a non-regular file: $file" }
                        val relative = bundle.relativize(file).joinToString("/")
                        if (relative != "displays.yml" && !relative.endsWith(".xml")) return@entry
                        require(++fileCount <= MAX_FILES) { "Display style bundles exceed $MAX_FILES source files" }
                        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES + 1) }
                        require(bytes.size <= MAX_FILE_BYTES) { "Display style source is larger than $MAX_FILE_BYTES bytes: $file" }
                        totalBytes += bytes.size
                        require(totalBytes <= MAX_TOTAL_BYTES) { "Display style bundles exceed $MAX_TOTAL_BYTES source bytes" }
                        files[relative] = try {
                            StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString()
                        } catch (failure: java.nio.charset.CharacterCodingException) {
                            throw IllegalArgumentException("Display style source is not valid UTF-8: $file", failure)
                        }
                    }
                }
                require("displays.yml" in files) { "Display style bundle '$name' is missing displays.yml" }
                val resources = DisplayResourceParser.parse(files, "external style bundle '$name'")
                require(resources.defaults.isEmpty()) { "External display style bundle '$name' cannot declare category defaults" }
                resources.templates.forEach { (id, source) ->
                    require(id.startsWith("$name.") && id.length > name.length + 1) {
                        "External display template '$id' must start with '$name.'"
                    }
                    require(templates.putIfAbsent(id, source) == null) { "Duplicate external display template '$id'" }
                }
            }
        }
        return DisplayResources(templates)
    }

    fun merge(local: DisplayResources?, external: DisplayResources, subject: String): DisplayResources? {
        if (external.templates.isEmpty()) return local
        val collisions = local?.templates?.keys.orEmpty().intersect(external.templates.keys)
        require(collisions.isEmpty()) { "$subject duplicates external display template IDs ${collisions.sorted()}" }
        return DisplayResources(external.templates + local?.templates.orEmpty(), local?.defaults.orEmpty())
    }
}
