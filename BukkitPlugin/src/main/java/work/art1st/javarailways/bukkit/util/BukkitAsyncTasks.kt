package work.art1st.javarailways.bukkit.util

import org.bukkit.entity.Entity
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.core.util.ThreadGuard
import java.util.logging.Level

object BukkitAsyncTasks {
    /**
     * Runs value-only work on the Paper/Folia async scheduler and routes completion through the
     * scheduler that follows [completionEntity]. Bukkit callbacks are never sent to a global
     * compatibility main-thread task.
     */
    fun <T> supply(
        plugin: BukkitPlugin,
        completionEntity: Entity,
        operation: String,
        supplier: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { error ->
            plugin.logger.log(Level.SEVERE, "Async operation '$operation' failed", error)
        },
        onRetired: () -> Unit = {}
    ) {
        plugin.platformDispatcher.runAsync {
            val outcome = runCatching {
                ThreadGuard.ensureOffMainThread(operation)
                supplier()
            }
            plugin.platformDispatcher.runForEntity(
                entity = completionEntity,
                action = {
                    outcome.fold(
                        onSuccess = onSuccess,
                        onFailure = onFailure
                    )
                },
                retired = onRetired
            )
        }
    }
}
