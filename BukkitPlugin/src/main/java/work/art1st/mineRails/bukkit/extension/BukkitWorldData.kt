package work.art1st.mineRails.bukkit.extension

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.render.chunk.RenderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.plugin.ExtensionData

class BukkitWorldData (
    val renderChunkManager: RenderChunkManager,
    val bukkitWorld: org.bukkit.World,
    /** Captured on construction so worker diagnostics never call Bukkit World methods. */
    val worldId: java.util.UUID = bukkitWorld.uid
)

@OptIn(ExtensionData::class)
val World.bukkitWorldDataOrNull: BukkitWorldData?
    get() {
        val plugin = BukkitPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

@OptIn(ExtensionData::class)
val World.bukkitWorldData: BukkitWorldData
    get() {
        val plugin = BukkitPlugin.instanceOrNull()
            ?: throw IllegalStateException("BukkitPlugin.INSTANCE 尚未初始化；请在访问 world.bukkitWorldData 前先初始化 BukkitPlugin")
        return getExtensionDataOrNull(plugin)
            ?: throw IllegalStateException("BukkitWorldData 未注册到当前 world")
    }

val World.renderChunkManager: RenderChunkManager
    get() = bukkitWorldData.renderChunkManager