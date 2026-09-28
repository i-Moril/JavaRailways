@file:UseSerializers(work.art1st.javarailways.bukkit.render.persistence.BukkitItemStackSerializer::class)

package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.RailDisplayType
import work.art1st.javarailways.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.javarailways.bukkit.extension.rail.RailElementTemplateSnapshot
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

@Serializable
internal data class RailRenderMetaFileV2(
    val version: Int,
    val templates: List<RailRenderTemplateV2>,
    val rails: List<RailRenderMetaEntryV2>
)

@Serializable
internal data class RailRenderMetaEntryV2(
    val railId: String,
    val templateId: String,
    val segments: List<RailRenderSegmentV2>
)

@Serializable
internal data class RailRenderSegmentV2(
    val arcStart: Double,
    val arcEnd: Double,
    val pointArcs: List<Double>
)

@Serializable
internal data class RailRenderTemplateV2(
    val templateId: String,
    val repeatLength: Double,
    val elements: List<RailRenderTemplateElementV2>
)

@Serializable
internal data class RailRenderTemplateElementV2(
    val type: String,
    val itemStack: ItemStack? = null,
    val blockMaterial: String? = null,
    val brightnessBlock: Int? = null,
    val brightnessSky: Int? = null,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val rotationX: Double = 0.0,
    val rotationY: Double = 0.0,
    val rotationZ: Double = 0.0,
    val scaleX: Double,
    val scaleY: Double,
    val scaleZ: Double,
    val lightProbeOffsetX: Double = 0.0,
    val lightProbeOffsetY: Double = 0.0,
    val lightProbeOffsetZ: Double = 0.0
)

data class PersistedRailRenderMeta(
    val railId: UUID,
    val segments: List<RailArcSegmentMeta>,
    val templateSnapshot: RailTemplateSnapshot
)

object RailRenderMetaStore {
    private const val CURRENT_VERSION = 2

    internal data class FileIdentity(
        val exists: Boolean,
        val lastModified: Long = 0L,
        val length: Long = 0L
    )

    private data class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val records: List<PersistedRailRenderMeta>,
        val corrupted: Boolean = false,
        val errorMessage: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }
    private val cache: MutableMap<UUID, CacheEntry> = ConcurrentHashMap()
    private val writers: MutableMap<UUID, Semaphore> = ConcurrentHashMap()

    private fun file(worldId: UUID, dataFolder: File = BukkitPlugin.INSTANCE.dataFolder): File =
        File(dataFolder, "worlds/$worldId-rail-render.json").also {
            it.parentFile?.mkdirs()
        }

    private fun file(world: World): File = file(world.uid)

    fun decoratedRailIds(world: World): Set<UUID> =
        (cache[world.uid]?.records ?: load(world)).mapTo(linkedSetOf()) { it.railId }

    @Synchronized
    fun load(world: World): List<PersistedRailRenderMeta> = load(world.uid, world.name)

    /** File/cache-only snapshot entry for RailWand asynchronous preparation. */
    @Synchronized
    fun load(worldId: UUID, worldName: String = worldId.toString()): List<PersistedRailRenderMeta> {
        return load(worldId, worldName, file(worldId))
    }

    private fun load(worldId: UUID, worldName: String, f: File): List<PersistedRailRenderMeta> {
        if (!f.exists()) {
            cache.remove(worldId)
            return emptyList()
        }

        val lastModified = f.lastModified()
        val length = f.length()
        cache[worldId]?.takeIf { it.lastModified == lastModified && it.length == length }?.let {
            return it.records
        }

        val staleRecords = cache[worldId]?.takeIf { !it.corrupted }?.records.orEmpty()
        if (!f.canRead()) {
            markCorrupted(worldId, worldName, f, lastModified, length, staleRecords, "File is not readable")
            return staleRecords
        }

        return try {
            val records = parseFile(f)
            cache[worldId] = CacheEntry(lastModified = lastModified, length = length, records = records)
            records
        } catch (t: Throwable) {
            markCorrupted(worldId, worldName, f, lastModified, length, staleRecords, "${t::class.simpleName}: ${t.message ?: ""}")
            staleRecords
        }
    }

    fun saveAll(world: World, records: List<PersistedRailRenderMeta>) {
        updateAll(world) { records }
    }

    private fun updateAll(
        world: World,
        transform: (List<PersistedRailRenderMeta>) -> List<PersistedRailRenderMeta>
    ) {
        val writer = writers.computeIfAbsent(world.uid) { Semaphore(1) }
        check(writer.tryAcquire()) { "Rail render metadata already has a prepared write for world ${world.uid}" }
        var temp: File? = null
        try {
            val target = file(world)
            val prepared = synchronized(this) {
                if (!ensureWritable(world.uid, world.name, target)) null else {
                    val current = load(world.uid, world.name, target)
                    target.identity() to transform(current).toList()
                }
            } ?: return
            val (expectedIdentity, nextRecords) = prepared
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            temp = tempFile
            tempFile.writeText(encode(nextRecords))
            synchronized(this) {
                check(target.identity() == expectedIdentity) {
                    "Rail render metadata changed while the replacement was encoded: ${target.path}"
                }
                atomicReplace(tempFile, target)
                cache[world.uid] = CacheEntry(target.lastModified(), target.length(), nextRecords)
            }
        } finally {
            temp?.delete()
            writer.release()
        }
    }

    /**
     * Prepares one whole-world render sidecar away from the Bukkit owner thread. The returned
     * value keeps the per-world writer slot until it is closed, so publication and rollback are
     * both short, deterministic file replacements rather than another full encode.
     */
    internal fun prepareSave(
        worldId: UUID,
        worldName: String,
        expectedRecords: List<PersistedRailRenderMeta>,
        records: List<PersistedRailRenderMeta>
    ): PreparedSave = prepareSave(worldId, worldName, expectedRecords, records, BukkitPlugin.INSTANCE.dataFolder)

    internal fun prepareSave(
        worldId: UUID,
        worldName: String,
        expectedRecords: List<PersistedRailRenderMeta>,
        records: List<PersistedRailRenderMeta>,
        dataFolder: File
    ): PreparedSave {
        val writer = writers.computeIfAbsent(worldId) { Semaphore(1) }
        writer.acquire()
        var staged: File? = null
        var rollback: File? = null
        try {
            val target = file(worldId, dataFolder)
            val (expectedIdentity, previousRecords) = synchronized(this) {
                val currentRecords = load(worldId, worldName, target)
                check(currentRecords == expectedRecords) {
                    "Rail render metadata changed while the replacement was being prepared: ${target.path}"
                }
                val identity = target.identity()
                val entry = cache[worldId]
                check(entry?.corrupted != true) {
                    "Refusing to overwrite corrupted rail render metadata for world '$worldName' at ${target.path}. " +
                        (entry?.errorMessage ?: "")
                }
                identity to currentRecords.toList()
            }
            staged = Files.createTempFile(target.parentFile.toPath(), "${target.name}.", ".staged").toFile()
            staged.writeText(encode(records))
            if (expectedIdentity.exists) {
                rollback = Files.createTempFile(target.parentFile.toPath(), "${target.name}.", ".rollback").toFile()
                Files.copy(
                    target.toPath(),
                    rollback.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
            }
            return PreparedSave(
                worldId = worldId,
                target = target,
                staged = staged,
                rollback = rollback,
                expectedIdentity = expectedIdentity,
                previousRecords = previousRecords,
                records = records.toList(),
                writer = writer
            )
        } catch (failure: Throwable) {
            staged?.delete()
            rollback?.delete()
            writer.release()
            throw failure
        }
    }

    internal class PreparedSave internal constructor(
        private val worldId: UUID,
        private val target: File,
        private val staged: File,
        private val rollback: File?,
        private val expectedIdentity: FileIdentity,
        private val previousRecords: List<PersistedRailRenderMeta>,
        private val records: List<PersistedRailRenderMeta>,
        private val writer: Semaphore
    ) : AutoCloseable {
        private var publishedIdentity: FileIdentity? = null
        private var rolledBack = false
        private var closed = false

        fun commitPrimary() = synchronized(RailRenderMetaStore) {
            check(!closed) { "Prepared rail render metadata write is closed" }
            check(publishedIdentity == null) { "Prepared rail render metadata write was already published" }
            check(target.identity() == expectedIdentity) {
                "Rail render metadata changed while the replacement was prepared: ${target.path}"
            }
            atomicReplace(staged, target)
            val identity = target.identity()
            publishedIdentity = identity
            cache[worldId] = CacheEntry(identity.lastModified, identity.length, records)
        }

        fun rollbackPrimary() = synchronized(RailRenderMetaStore) {
            check(!closed) { "Prepared rail render metadata write is closed" }
            if (rolledBack || publishedIdentity == null) return@synchronized
            check(target.identity() == publishedIdentity) {
                "Published rail render metadata changed before rollback: ${target.path}"
            }
            if (expectedIdentity.exists) {
                atomicReplace(requireNotNull(rollback), target)
                val identity = target.identity()
                cache[worldId] = CacheEntry(identity.lastModified, identity.length, previousRecords)
            } else {
                Files.deleteIfExists(target.toPath())
                cache.remove(worldId)
            }
            rolledBack = true
        }

        override fun close() = synchronized(RailRenderMetaStore) {
            if (closed) return@synchronized
            closed = true
            try {
                staged.delete()
                rollback?.delete()
            } finally {
                writer.release()
            }
        }
    }

    fun upsertRail(world: World, meta: PersistedRailRenderMeta) {
        upsertRails(world, listOf(meta))
    }

    fun upsertRails(world: World, metas: Collection<PersistedRailRenderMeta>) {
        if (metas.isEmpty()) return
        updateAll(world) { current ->
            current.associateBy { it.railId }.toMutableMap().also { existing ->
                metas.forEach { meta -> existing[meta.railId] = meta }
            }.values.toList()
        }
    }

    fun removeRail(world: World, railId: UUID) {
        updateAll(world) { current -> current.filterNot { it.railId == railId } }
    }

    private fun parseFile(file: File): List<PersistedRailRenderMeta> {
        val decoded = json.decodeFromString<RailRenderMetaFileV2>(file.readText())
        require(decoded.version == CURRENT_VERSION) {
            "Unsupported rail render metadata version: ${decoded.version}"
        }
        val templatesById = decoded.templates.associate { template ->
            template.templateId to RailTemplateSnapshot(
                templateId = template.templateId,
                repeatLength = template.repeatLength,
                elements = template.elements.mapIndexed { index, element ->
                    val type = try {
                        RailDisplayType.valueOf(element.type)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException(
                            "Template '${template.templateId}' element #${index + 1} has unknown type '${element.type}'",
                            e
                        )
                    }
                    RailElementTemplateSnapshot(
                        type = type,
                        itemStack = element.itemStack,
                        blockMaterial = element.blockMaterial,
                        brightnessBlock = element.brightnessBlock,
                        brightnessSky = element.brightnessSky,
                        offsetX = element.offsetX,
                        offsetY = element.offsetY,
                        offsetZ = element.offsetZ,
                        rotationX = element.rotationX,
                        rotationY = element.rotationY,
                        rotationZ = element.rotationZ,
                        scaleX = element.scaleX,
                        scaleY = element.scaleY,
                        scaleZ = element.scaleZ,
                        lightProbeOffsetX = element.lightProbeOffsetX,
                        lightProbeOffsetY = element.lightProbeOffsetY,
                        lightProbeOffsetZ = element.lightProbeOffsetZ
                    )
                }
            )
        }
        return decoded.rails.mapIndexed { index, entry ->
            val railId = try {
                UUID.fromString(entry.railId)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Rail entry #${index + 1} has invalid railId '${entry.railId}'", e)
            }
            PersistedRailRenderMeta(
                railId = railId,
                segments = entry.segments.map { seg ->
                    RailArcSegmentMeta(
                        arcStart = seg.arcStart,
                        arcEnd = seg.arcEnd,
                        pointArcs = seg.pointArcs
                    )
                },
                templateSnapshot = templatesById[entry.templateId]
                    ?: throw IllegalArgumentException(
                        "Rail entry #${index + 1} references missing template '${entry.templateId}'"
                    )
            )
        }
    }

    private fun encode(records: List<PersistedRailRenderMeta>): String {
        val templates = LinkedHashMap<String, RailTemplateSnapshot>()
        records.forEach { record ->
            templates[record.templateSnapshot.templateId] = record.templateSnapshot
        }
        return json.encodeToString(RailRenderMetaFileV2(
            version = CURRENT_VERSION,
            templates = templates.values.map { template ->
                RailRenderTemplateV2(
                    templateId = template.templateId,
                    repeatLength = template.repeatLength,
                    elements = template.elements.map { element ->
                        RailRenderTemplateElementV2(
                            type = element.type.name,
                            itemStack = element.itemStack,
                            blockMaterial = element.blockMaterial,
                            brightnessBlock = element.brightnessBlock,
                            brightnessSky = element.brightnessSky,
                            offsetX = element.offsetX,
                            offsetY = element.offsetY,
                            offsetZ = element.offsetZ,
                            rotationX = element.rotationX,
                            rotationY = element.rotationY,
                            rotationZ = element.rotationZ,
                            scaleX = element.scaleX,
                            scaleY = element.scaleY,
                            scaleZ = element.scaleZ,
                            lightProbeOffsetX = element.lightProbeOffsetX,
                            lightProbeOffsetY = element.lightProbeOffsetY,
                            lightProbeOffsetZ = element.lightProbeOffsetZ
                        )
                    }
                )
            },
            rails = records.map { record ->
                RailRenderMetaEntryV2(
                    railId = record.railId.toString(),
                    templateId = record.templateSnapshot.templateId,
                    segments = record.segments.map { seg ->
                        RailRenderSegmentV2(seg.arcStart, seg.arcEnd, seg.pointArcs)
                    }
                )
            }
        ))
    }

    private fun ensureWritable(worldId: UUID, worldName: String, f: File): Boolean {
        if (!f.exists()) {
            cache.remove(worldId)
            return true
        }

        val lastModified = f.lastModified()
        val length = f.length()
        val entry = cache[worldId]?.takeIf { it.lastModified == lastModified && it.length == length } ?: run {
            load(worldId, worldName, f)
            cache[worldId]?.takeIf { it.lastModified == lastModified && it.length == length }
        }

        if (entry?.corrupted == true) {
            BukkitPlugin.INSTANCE.logger.severe(
                "Refusing to overwrite corrupted rail render metadata for world '$worldName' at ${f.path}. ${entry.errorMessage ?: ""}"
            )
            return false
        }
        return true
    }

    private fun markCorrupted(
        worldId: UUID,
        worldName: String,
        file: File,
        lastModified: Long,
        length: Long,
        staleRecords: List<PersistedRailRenderMeta>,
        detail: String
    ) {
        val message = "Failed to parse rail render metadata for world '$worldName' at ${file.path}. $detail"
        BukkitPlugin.INSTANCE.logger.severe(message)
        cache[worldId] = CacheEntry(
            lastModified = lastModified,
            length = length,
            records = staleRecords,
            corrupted = true,
            errorMessage = detail
        )
    }

    private fun File.identity(): FileIdentity = if (exists()) {
        FileIdentity(exists = true, lastModified = lastModified(), length = length())
    } else {
        FileIdentity(exists = false)
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
