package work.art1st.mineRails.bukkit.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.bukkit.World as BukkitWorld
import org.joml.Vector3d
import work.art1st.mineRails.basic.curve.*
import java.io.File

/**
 * 锚点持久化存储（JSON 文件，按世界存储）。
 *
 * 文件路径: `plugins/MineRails/anchors/<world-uuid>.json`
 */
class PersistentAnchorStore {
    companion object {
        private const val ANCHORS_DIR = "anchors"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    /** 已加载的世界锚点缓存 */
    private val worldAnchors = mutableMapOf<String, MutableMap<String, SavedAnchor>>()

    private var dataFolder: File? = null

    fun init(pluginDataFolder: File) {
        dataFolder = pluginDataFolder
    }

    // ═══════════════════════════════════════════
    // CRUD
    // ═══════════════════════════════════════════

    fun add(world: BukkitWorld, anchor: Anchor): Anchor {
        val saved = toSaved(anchor, world.uid.toString())
        getOrLoad(world)[saved.id] = saved
        save(world)
        return anchor
    }

    fun get(world: BukkitWorld, id: String): Anchor? {
        return getOrLoad(world)[id]?.let { fromSaved(it) }
    }

    fun getAll(world: BukkitWorld): List<Anchor> {
        return getOrLoad(world).values.map { fromSaved(it) }
    }

    fun remove(world: BukkitWorld, id: String) {
        getOrLoad(world).remove(id)
        save(world)
    }

    fun update(world: BukkitWorld, anchor: Anchor) {
        val saved = toSaved(anchor, world.uid.toString())
        getOrLoad(world)[saved.id] = saved
        save(world)
    }

    fun clear(world: BukkitWorld) {
        getOrLoad(world).clear()
        save(world)
    }

    fun getIds(world: BukkitWorld): Set<String> = getOrLoad(world).keys

    // ═══════════════════════════════════════════
    // I/O
    // ═══════════════════════════════════════════

    private fun getOrLoad(world: BukkitWorld): MutableMap<String, SavedAnchor> {
        val worldId = world.uid.toString()
        return worldAnchors.getOrPut(worldId) { load(world) }
    }

    private fun load(world: BukkitWorld): MutableMap<String, SavedAnchor> {
        val file = getFile(world)
        if (!file.exists()) return mutableMapOf()
        return try {
            val saved = json.decodeFromString<SavedAnchorFile>(file.readText())
            val map = mutableMapOf<String, SavedAnchor>()
            saved.anchors.forEach { map[it.id] = it }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save(world: BukkitWorld) {
        val dir = getFile(world).parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
        val anchors = getOrLoad(world).values.toList()
        val file = SavedAnchorFile(anchors = anchors)
        getFile(world).writeText(json.encodeToString(file))
    }

    private fun getFile(world: BukkitWorld): File {
        val folder = dataFolder ?: File(".")
        return File(folder, "$ANCHORS_DIR/${world.uid}.json")
    }

    // ═══════════════════════════════════════════
    // 转换
    // ═══════════════════════════════════════════

    private fun toSaved(anchor: Anchor, worldId: String): SavedAnchor {
        return SavedAnchor(
            id = anchor.id,
            position = SavedVec3d(anchor.position.x(), anchor.position.y(), anchor.position.z()),
            tangent = SavedVec3d(anchor.tangent.x(), anchor.tangent.y(), anchor.tangent.z()),
            worldId = worldId,
            metadata = anchor.metadata
        )
    }

    private fun fromSaved(saved: SavedAnchor): Anchor {
        return Anchor(
            id = saved.id,
            position = Vector3d(saved.position.x, saved.position.y, saved.position.z),
            tangent = Vector3d(saved.tangent.x, saved.tangent.y, saved.tangent.z),
            worldId = saved.worldId,
            metadata = saved.metadata
        )
    }

    fun toAnchor(saved: SavedAnchor): Anchor = fromSaved(saved)
}
