package work.art1st.javarailways.simplesignals.persistence

import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Worker-only transport for independent current-schema documents, never formal world files. */
internal class SimpleSignalsDefinitionDrafts(dataFolder: Path) {
    private val directory = dataFolder.toAbsolutePath().normalize().resolve("drafts")

    fun export(stem: String, definitions: SimpleSignalsWorldDefinitions) {
        val target = path(stem, createDirectory = true)
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        Files.newByteChannel(target, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
            Channels.newWriter(channel, Charsets.UTF_8).use { it.write(encoded) }
        }
    }

    fun read(stem: String, worldId: UUID): SimpleSignalsWorldDefinitions {
        val target = path(stem)
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Draft is missing or is not a regular file: $stem" }
        return Files.newByteChannel(target, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newReader(channel, Charsets.UTF_8).use {
                SimpleSignalsWorldDefinitionsCodec.decode(it.readText(), worldId)
            }
        }
    }

    private fun path(stem: String, createDirectory: Boolean = false): Path {
        require(validStem(stem)) { "Draft must be a simple filename stem (letters, digits, _ or -)" }
        // Reject symlinked parents as well as the file; drafts cannot alias worlds/primary/backup.
        var ancestor: Path? = directory
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor)) { "Draft directory must not contain symbolic links" }
            ancestor = ancestor.parent
        }
        if (createDirectory) Files.createDirectories(directory)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Draft directory is not a directory" }
        val target = directory.resolve("$stem.json")
        require(!Files.isSymbolicLink(target)) { "Draft must not be a symbolic link" }
        return target
    }

    companion object {
        fun validStem(stem: String): Boolean = stem.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
    }
}

/** Whole document omission means deletion. Duplicates reject before building ID maps. */
internal fun definitionDraftPatch(
    current: SimpleSignalsWorldDefinitions,
    draft: SimpleSignalsWorldDefinitions
): SimpleSignalsDefinitionPatch {
    require(current.worldId == draft.worldId) { "Draft world does not match current definitions" }
    fun <T> diff(old: List<T>, candidate: List<T>, id: (T) -> String): Pair<List<T>, List<String>> {
        require(candidate.map(id).distinct().size == candidate.size) { "Draft contains duplicate definition IDs" }
        val previous = old.associateBy(id)
        val next = candidate.associateBy(id)
        return candidate.filter { previous[id(it)] != it } to previous.keys.filter { it !in next }
    }
    val regions = diff(current.regions, draft.regions) { it.regionId }
    val tables = diff(current.routeTables, draft.routeTables) { it.tableId }
    val blocks = diff(current.fixedBlocks, draft.fixedBlocks) { it.blockId }
    val fixedSignals = diff(current.fixedBlockSignals, draft.fixedBlockSignals) { it.signalId }
    val protectionSignals = diff(current.protectionSignals, draft.protectionSignals) { it.signalId }
    val indicators = diff(current.routeIndicators, draft.routeIndicators) { it.indicatorId }
    return SimpleSignalsDefinitionPatch(regions.first, regions.second, tables.first, tables.second,
        blocks.first, blocks.second, fixedSignals.first, fixedSignals.second,
        protectionSignals.first, protectionSignals.second, indicators.first, indicators.second)
}

internal fun definitionDraftSummary(current: SimpleSignalsWorldDefinitions, draft: SimpleSignalsWorldDefinitions): String {
    fun ids(value: SimpleSignalsWorldDefinitions): Map<String, Any> = buildMap {
        value.regions.forEach { put("region/${it.regionId}", it) }
        value.routeTables.forEach { put("table/${it.tableId}", it) }
        value.fixedBlocks.forEach { put("block/${it.blockId}", it) }
        value.fixedBlockSignals.forEach { put("fixed-signal/${it.signalId}", it) }
        value.protectionSignals.forEach { put("protection-signal/${it.signalId}", it) }
        value.routeIndicators.forEach { put("indicator/${it.indicatorId}", it) }
    }
    val old = ids(current)
    val next = ids(draft)
    return "create=${next.keys.count { it !in old }}, update=${next.keys.count { it in old && old[it] != next[it] }}, " +
        "delete=${old.keys.count { it !in next }} (omitted existing IDs are deleted)"
}
