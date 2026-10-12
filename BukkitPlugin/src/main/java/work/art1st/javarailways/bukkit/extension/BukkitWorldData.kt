package work.art1st.javarailways.bukkit.extension

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.plugin.ExtensionData

class BukkitWorldData (
    val renderChunkManager: RenderChunkManager,
    val bukkitWorld: org.bukkit.World,
    /** Captured on construction so worker diagnostics never call Bukkit World methods. */
    val worldId: java.util.UUID = bukkitWorld.uid
) {
    private val modelOperations = WorldModelOperations()

    internal fun <T> trackModelOperation(future: java.util.concurrent.CompletableFuture<T>) = modelOperations.track(future)

    internal fun closeModelOperations() = modelOperations.close()
}

/** World-lifetime fence also covers callbacks queued before a car runtime is entered. */
internal class WorldModelOperations : AutoCloseable {
    private val modelOperations = linkedSetOf<java.util.concurrent.CompletableFuture<*>>()
    private var modelOperationsClosed = false

    @Synchronized
    fun <T> track(future: java.util.concurrent.CompletableFuture<T>): java.util.concurrent.CompletableFuture<T> {
        if (modelOperationsClosed) future.completeExceptionally(IllegalStateException("railway world is unloading"))
        else {
            modelOperations.add(future)
            future.whenComplete { _, _ -> synchronized(this) { modelOperations.remove(future) } }
        }
        return future
    }

    @Synchronized
    override fun close() {
        modelOperationsClosed = true
        modelOperations.toList().forEach { it.completeExceptionally(IllegalStateException("railway world unloaded")) }
    }
}

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
