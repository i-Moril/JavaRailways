package work.art1st.mineRails.bukkit.http

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.TimetableWebUiMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Owns only the lifecycle of the isolated WebUI JVM; all communication remains HTTP. */
class TimetableWebUiSidecar(private val plugin: BukkitPlugin) {
    private val config = plugin.mineRailsConfig.timetableWebUi
    private val token: String? = if (config.mode == TimetableWebUiMode.MANAGED) generateToken() else null
    private var process: Process? = null
    @Volatile private var stopping = false

    fun managedBridgeToken(): String? = token

    fun start() {
        if (config.mode != TimetableWebUiMode.MANAGED) return
        check(process == null) { "Timetable WebUI sidecar is already started" }
        val runtimeDirectory = plugin.dataFolder.toPath().toAbsolutePath().resolve("webui")
        Files.createDirectories(runtimeDirectory)
        val jar = runtimeDirectory.resolve("MineRails-WebUI.jar")
        extractSidecar(jar)
        val logDirectory = plugin.dataFolder.toPath().resolve("logs")
        Files.createDirectories(logDirectory)
        val log = logDirectory.resolve("webui.log").toFile()
        val javaExecutable = java.nio.file.Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        ).toString()
        val bridge = plugin.mineRailsConfig.timetableHttpBridge
        val bridgeHost = if (bridge.bindAddress == "::1") "[::1]" else bridge.bindAddress
        val child = ProcessBuilder(javaExecutable, "-jar", jar.toString())
            .directory(runtimeDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply {
                environment()["MINERAILS_BRIDGE_URL"] = "http://$bridgeHost:${bridge.port}/"
                environment()["MINERAILS_BRIDGE_TOKEN"] = requireNotNull(token)
                environment()["MINERAILS_WEB_BIND"] = config.bindAddress
                environment()["MINERAILS_WEB_PORT"] = config.port.toString()
                environment()["MINERAILS_WEB_SECURE_COOKIE"] = config.publicUrl.startsWith("https://").toString()
                environment()["MINERAILS_DRAFT_DIR"] = runtimeDirectory.resolve("drafts").toString()
                environment()["MINERAILS_MAX_REQUEST_BYTES"] = bridge.bodyLimitBytes.toString()
                environment()["MINERAILS_REQUEST_TIMEOUT_MS"] = bridge.timeoutMillis.toString()
            }
            .start()
        process = child
        child.onExit().thenAccept { exited ->
            if (!stopping && process === exited) {
                plugin.logger.warning(
                    "Timetable WebUI sidecar exited unexpectedly with code ${exited.exitValue()}; see ${log.absolutePath}"
                )
            }
        }
        plugin.logger.info("Timetable WebUI sidecar started at http://${config.bindAddress}:${config.port}")
    }

    fun stop() {
        stopping = true
        val child = process ?: return
        process = null
        child.destroy()
        if (!child.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
            plugin.logger.warning("Timetable WebUI did not stop in time; terminating it forcibly")
            child.destroyForcibly()
            child.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun extractSidecar(target: java.nio.file.Path) {
        val resource = plugin.getResource(SIDECAR_RESOURCE)
            ?: throw IllegalStateException("Bundled WebUI sidecar is missing: $SIDECAR_RESOURCE")
        val temporary = target.resolveSibling(".${target.fileName}.tmp")
        resource.use { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val SIDECAR_RESOURCE = "webui/MineRails-WebUI.jar"
    }
}
