@file:UseSerializers(work.art1st.mineRails.bukkit.render.persistence.BukkitItemStackSerializer::class)

package work.art1st.mineRails.bukkit.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.RailDisplayType
import work.art1st.mineRails.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.mineRails.bukkit.extension.rail.RailElementTemplateSnapshot
import work.art1st.mineRails.bukkit.extension.rail.RailTemplateSnapshot
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class RailRenderMetaFileV1(
    val version: Int = 2,
    val templates: List<RailRenderTemplateV1> = emptyList(),
    val rails: List<RailRenderMetaEntryV1> = emptyList()
)

@Serializable
private data class RailRenderMetaEntryV1(
    val railId: String,
    val templateId: String? = null,
    val segments: List<RailRenderSegmentV1>
)

@Serializable
private data class RailRenderSegmentV1(
    val arcStart: Double,
    val arcEnd: Double,
    val pointArcs: List<Double> = emptyList()
)

@Serializable
private data class RailRenderTemplateV1(
    val templateId: String,
    val repeatLength: Double,
    val elements: List<RailRenderTemplateElementV1>
)

@Serializable
private data class RailRenderTemplateElementV1(
    val type: String,
    val itemStack: ItemStack? = null,
    val blockMaterial: String? = null,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val rotationX: Double = 0.0,
    val rotationY: Double = 0.0,
    val rotationZ: Double = 0.0,
    val scaleX: Double,
    val scaleY: Double,
    val scaleZ: Double
)

data class PersistedRailRenderMeta(
    val railId: UUID,
    val segments: List<RailArcSegmentMeta>,
    val templateSnapshot: RailTemplateSnapshot? = null
)

object RailRenderMetaStore {
    private data class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val records: List<PersistedRailRenderMeta>,
        val corrupted: Boolean = false,
        val errorMessage: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val cache: MutableMap<UUID, CacheEntry> = ConcurrentHashMap()

    private fun file(world: World): File =
        File(BukkitPlugin.INSTANCE.dataFolder, "worlds/${world.uid}-rail-render.json").also {
            it.parentFile?.mkdirs()
        }

    fun decoratedRailIds(world: World): Set<UUID> =
        (cache[world.uid]?.records ?: load(world)).mapTo(linkedSetOf()) { it.railId }

    @Synchronized
    fun load(world: World): List<PersistedRailRenderMeta> {
        val f = file(world)
        if (!f.exists()) {
            cache.remove(world.uid)
            return emptyList()
        }

        val lastModified = f.lastModified()
        val length = f.length()
        cache[world.uid]?.takeIf { it.lastModified == lastModified && it.length == length }?.let {
            return it.records
        }

        val staleRecords = cache[world.uid]?.takeIf { !it.corrupted }?.records.orEmpty()
        if (!f.canRead()) {
            markCorrupted(world, f, lastModified, length, staleRecords, "File is not readable")
            return staleRecords
        }

        return try {
            val records = parseFile(f)
            cache[world.uid] = CacheEntry(lastModified = lastModified, length = length, records = records)
            records
        } catch (t: Throwable) {
            markCorrupted(world, f, lastModified, length, staleRecords, "${t::class.simpleName}: ${t.message ?: ""}")
            staleRecords
        }
    }

    @Synchronized
    fun saveAll(world: World, records: List<PersistedRailRenderMeta>) {
        if (!ensureWritable(world)) return

        val templates = LinkedHashMap<String, RailTemplateSnapshot>()
        records.forEach { record ->
            record.templateSnapshot?.let { templates[it.templateId] = it }
        }
        val encoded = RailRenderMetaFileV1(
            templates = templates.values.map { template ->
                RailRenderTemplateV1(
                    templateId = template.templateId,
                    repeatLength = template.repeatLength,
                    elements = template.elements.map { element ->
                        RailRenderTemplateElementV1(
                            type = element.type.name,
                            itemStack = element.itemStack,
                            blockMaterial = element.blockMaterial,
                            offsetX = element.offsetX,
                            offsetY = element.offsetY,
                            offsetZ = element.offsetZ,
                            rotationX = element.rotationX,
                            rotationY = element.rotationY,
                            rotationZ = element.rotationZ,
                            scaleX = element.scaleX,
                            scaleY = element.scaleY,
                            scaleZ = element.scaleZ
                        )
                    }
                )
            },
            rails = records.map { record ->
                RailRenderMetaEntryV1(
                    railId = record.railId.toString(),
                    templateId = record.templateSnapshot?.templateId,
                    segments = record.segments.map { seg ->
                        RailRenderSegmentV1(seg.arcStart, seg.arcEnd, seg.pointArcs)
                    }
                )
            }
        )
        val target = file(world)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(json.encodeToString(encoded))
        try {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        cache[world.uid] = CacheEntry(target.lastModified(), target.length(), records.toList())
    }

    fun upsertRail(world: World, meta: PersistedRailRenderMeta) {
        upsertRails(world, listOf(meta))
    }

    @Synchronized
    fun upsertRails(world: World, metas: Collection<PersistedRailRenderMeta>) {
        if (metas.isEmpty()) return
        if (!ensureWritable(world)) return
        val existing = load(world).associateBy { it.railId }.toMutableMap()
        metas.forEach { meta -> existing[meta.railId] = meta }
        saveAll(world, existing.values.toList())
    }

    @Synchronized
    fun removeRail(world: World, railId: UUID) {
        if (!ensureWritable(world)) return
        val existing = load(world).filterNot { it.railId == railId }
        saveAll(world, existing)
    }

    private fun parseFile(file: File): List<PersistedRailRenderMeta> {
        val decoded = json.decodeFromString<RailRenderMetaFileV1>(file.readText())
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
                        offsetX = element.offsetX,
                        offsetY = element.offsetY,
                        offsetZ = element.offsetZ,
                        rotationX = element.rotationX,
                        rotationY = element.rotationY,
                        rotationZ = element.rotationZ,
                        scaleX = element.scaleX,
                        scaleY = element.scaleY,
                        scaleZ = element.scaleZ
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
                templateSnapshot = entry.templateId?.let { templateId ->
                    templatesById[templateId]
                        ?: throw IllegalArgumentException(
                            "Rail entry #${index + 1} references missing template '$templateId'"
                        )
                }
            )
        }
    }

    private fun ensureWritable(world: World): Boolean {
        val f = file(world)
        if (!f.exists()) {
            cache.remove(world.uid)
            return true
        }

        val lastModified = f.lastModified()
        val length = f.length()
        val entry = cache[world.uid]?.takeIf { it.lastModified == lastModified && it.length == length } ?: run {
            load(world)
            cache[world.uid]?.takeIf { it.lastModified == lastModified && it.length == length }
        }

        if (entry?.corrupted == true) {
            BukkitPlugin.INSTANCE.logger.severe(
                "Refusing to overwrite corrupted rail render metadata for world '${world.name}' at ${f.path}. ${entry.errorMessage ?: ""}"
            )
            return false
        }
        return true
    }

    private fun markCorrupted(
        world: World,
        file: File,
        lastModified: Long,
        length: Long,
        staleRecords: List<PersistedRailRenderMeta>,
        detail: String
    ) {
        val message = "Failed to parse rail render metadata for world '${world.name}' at ${file.path}. $detail"
        BukkitPlugin.INSTANCE.logger.severe(message)
        cache[world.uid] = CacheEntry(
            lastModified = lastModified,
            length = length,
            records = staleRecords,
            corrupted = true,
            errorMessage = detail
        )
    }
}
