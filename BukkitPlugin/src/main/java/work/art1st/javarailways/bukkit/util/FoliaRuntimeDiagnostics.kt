package work.art1st.javarailways.bukkit.util

import java.util.logging.Logger

/** Version-specific Folia internals are optional diagnostics, never a scheduling decision. */
internal object FoliaRuntimeDiagnostics {
    fun warnIfSingleWorker(logger: Logger, gentleUnloadAvailable: Boolean = false, readCount: () -> Int? = ::readTickWorkerCount) {
        val count = runCatching(readCount).getOrNull()
        when {
            count == 1 -> logger.warning(
                "Folia has only one tick worker thread. Synchronous JavaRailways hot-disable " +
                    "cleanup may time out while waiting for other regions. " +
                    if (gentleUnloadAvailable) "Use the two-request PlugManX GentleUnload flow; direct disable remains unsafe."
                    else "Configure threaded-regions.threads above 1 before using plugin hot reload."
            )
            count == null || count < 1 -> logger.warning(
                "Could not determine Folia's actual tick worker count; " +
                    "single-worker hot-disable cleanup safety could not be checked."
            )
        }
    }

    private fun readTickWorkerCount(): Int? {
        val regions = Class.forName("io.papermc.paper.threadedregions.TickRegions")
        val scheduler = regions.getMethod("getScheduler").invoke(null) ?: return null
        return readSchedulerWorkerCount(scheduler)
    }

    internal fun readSchedulerWorkerCount(scheduler: Any): Int? {
        val getter = scheduler.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name == "getTotalThreadCount"
        } ?: return null
        return (getter.invoke(scheduler) as? Number)?.toInt()?.takeIf { it > 0 }
    }
}
