package work.art1st.javarailways.bukkit.debug

import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.simpleato.api.ato.AtoStallReportJson
import java.io.File

object AtoStallDiagnostics {
    @Synchronized
    fun write(pluginDataFolder: File, report: AtoStallReport): File {
        val directory = File(pluginDataFolder, "debug")
        check(directory.isDirectory || directory.mkdirs()) {
            "cannot create ATO diagnostics directory: ${directory.absolutePath}"
        }
        val safeWorldId = report.worldId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(
            directory,
            "ato-stall-$safeWorldId-${report.occurredAtEpochMillis}.json"
        )
        output.writeText(AtoStallReportJson.encode(report), Charsets.UTF_8)
        return output
    }
}
