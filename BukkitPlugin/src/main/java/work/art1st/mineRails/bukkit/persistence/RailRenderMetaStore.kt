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
import java.util.UUID

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
    val pointIndices: List<Int> = emptyList()
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
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun file(world: World): File =
        File(BukkitPlugin.INSTANCE.dataFolder, "worlds/${world.uid}-rail-render.json").also {
            it.parentFile?.mkdirs()
        }

    fun load(world: World): List<PersistedRailRenderMeta> {
        val f = file(world)
        if (!f.exists() || !f.canRead()) return emptyList()
        return try {
            val decoded = json.decodeFromString<RailRenderMetaFileV1>(f.readText())
            val templatesById = decoded.templates.associate { template ->
                template.templateId to RailTemplateSnapshot(
                    templateId = template.templateId,
                    repeatLength = template.repeatLength,
                    elements = template.elements.map { element ->
                        RailElementTemplateSnapshot(
                            type = runCatching { RailDisplayType.valueOf(element.type) }.getOrElse { RailDisplayType.ITEM },
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
            decoded.rails.mapNotNull { entry ->
                runCatching {
                    PersistedRailRenderMeta(
                        railId = UUID.fromString(entry.railId),
                        segments = entry.segments.map { seg ->
                            RailArcSegmentMeta(
                                arcStart = seg.arcStart,
                                arcEnd = seg.arcEnd,
                                pointIndices = seg.pointIndices
                            )
                        },
                        templateSnapshot = entry.templateId?.let { templatesById[it] }
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(world: World, records: List<PersistedRailRenderMeta>) {
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
                        RailRenderSegmentV1(seg.arcStart, seg.arcEnd, seg.pointIndices)
                    }
                )
            }
        )
        file(world).writeText(json.encodeToString(encoded))
    }

    fun upsertRail(world: World, meta: PersistedRailRenderMeta) {
        val existing = load(world).associateBy { it.railId }.toMutableMap()
        existing[meta.railId] = meta
        saveAll(world, existing.values.toList())
    }

    fun removeRail(world: World, railId: UUID) {
        val existing = load(world).filterNot { it.railId == railId }
        saveAll(world, existing)
    }
}
