package work.art1st.mineRails.bukkit.debug

import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * 临时诊断日志：记录每 tick 的物理状态和渲染更新状态。
 * 输出到 plugins/MineRails/debug/train_debug.csv，便于用 Python/SQLite 后处理。
 */
object TrainDebugLog {
    private const val FLUSH_EVERY_ROWS = 256

    /**
     * Disabled by default because this is per-tick diagnostics and synchronous disk IO on the
     * server thread can quickly produce tens of MB of CSV and TPS stalls. Enable only when
     * actively collecting a short trace:
     *   -Dminerails.trainDebugLog=true or MINERAILS_TRAIN_DEBUG_LOG=true
     */
    @Volatile
    var enabled: Boolean = java.lang.Boolean.getBoolean("minerails.trainDebugLog") ||
        System.getenv("MINERAILS_TRAIN_DEBUG_LOG")?.equals("true", ignoreCase = true) == true

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var headerWritten = false
    private var rowsSinceFlush = 0

    private fun writer(): BufferedWriter {
        val existing = writer
        if (existing != null) return existing

        val dir = File(BukkitPlugin.INSTANCE.dataFolder, "debug").also { it.mkdirs() }
        val file = File(dir, "train_debug.csv")
        val append = file.exists() && file.length() > 0
        val created = BufferedWriter(FileWriter(file, true))
        writer = created
        headerWritten = append
        if (!headerWritten) {
            created.write(
                listOf(
                    "time_ms", "event", "world", "train_id", "car_index", "car_id",
                    "speed", "derailed", "controller",
                    "head_rail", "head_arc", "head_x", "head_y", "head_z",
                    "car_x", "car_y", "car_z",
                    "dir_x", "dir_y", "dir_z",
                    "normal_x", "normal_y", "normal_z",
                    "bogie_a_x", "bogie_a_y", "bogie_a_z",
                    "bogie_b_x", "bogie_b_y", "bogie_b_z",
                    "bogie_a_dir_x", "bogie_a_dir_y", "bogie_a_dir_z",
                    "bogie_b_dir_x", "bogie_b_dir_y", "bogie_b_dir_z",
                    "render_pitch", "render_yaw", "render_roll",
                    "render_qx", "render_qy", "render_qz", "render_qw",
                    "note"
                ).joinToString(",")
            )
            created.newLine()
            headerWritten = true
        }
        return created
    }

    fun logRow(values: List<Any?>) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            val w = writer()
            w.write(values.joinToString(",") { csv(it) })
            w.newLine()
            rowsSinceFlush++
            if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                w.flush()
                rowsSinceFlush = 0
            }
        }
    }

    fun close() {
        synchronized(lock) {
            writer?.flush()
            writer?.close()
            writer = null
            rowsSinceFlush = 0
        }
    }

    fun euler(rotation: Quaterniondc): Vector3d = rotation.getEulerAnglesXYZ(Vector3d())

    private fun csv(value: Any?): String {
        val s = when (value) {
            null -> ""
            is Double -> String.format(Locale.US, "%.9f", value)
            is Float -> String.format(Locale.US, "%.9f", value.toDouble())
            is Vector3dc -> "${fmt(value.x())};${fmt(value.y())};${fmt(value.z())}"
            else -> value.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.9f", v)
}
