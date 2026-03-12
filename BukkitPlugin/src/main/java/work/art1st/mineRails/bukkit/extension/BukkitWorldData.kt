package work.art1st.mineRails.bukkit.extension

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.render.chunk.RenderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.plugin.ExtensionData

class BukkitWorldData (
    val renderChunkManager: RenderChunkManager,
    val bukkitWorld: org.bukkit.World
)

@OptIn(ExtensionData::class)
val World.bukkitWorldData: BukkitWorldData
    get() = getExtensionData(BukkitPlugin)

val World.renderChunkManager: RenderChunkManager
    get() = bukkitWorldData.renderChunkManager