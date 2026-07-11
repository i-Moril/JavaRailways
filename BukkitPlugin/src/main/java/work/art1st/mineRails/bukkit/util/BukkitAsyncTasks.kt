package work.art1st.mineRails.bukkit.util

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.logging.Level

object BukkitAsyncTasks {
    fun <T> supply(
        plugin: JavaPlugin,
        operation: String,
        supplier: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { error ->
            plugin.logger.log(Level.SEVERE, "Async operation '$operation' failed", error)
        }
    ) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                ThreadGuard.ensureOffMainThread(operation)
                val result = supplier()
                plugin.server.scheduler.runTask(plugin, Runnable { onSuccess(result) })
            } catch (error: Throwable) {
                plugin.server.scheduler.runTask(plugin, Runnable { onFailure(error) })
            }
        })
    }
}
