package work.art1st.mineRails.bukkit.debug

import work.art1st.mineRails.basic.api.depot.DepotResetReport
import work.art1st.mineRails.basic.api.depot.DepotResetReportJson
import java.io.File

object DepotResetDiagnostics {
    @Synchronized
    fun write(pluginDataFolder: File, report: DepotResetReport): File {
        val directory = File(pluginDataFolder, "debug")
        check(directory.isDirectory || directory.mkdirs()) {
            "cannot create Depot Reset diagnostics directory: ${directory.absolutePath}"
        }
        val safeWorldId = report.worldId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(
            directory,
            "depot-reset-$safeWorldId-${report.occurredAtEpochMillis}.json"
        )
        output.writeText(DepotResetReportJson.encode(report), Charsets.UTF_8)
        return output
    }
}
