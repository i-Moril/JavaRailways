package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.TimetableWebUiMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Owns only the lifecycle of the isolated WebUI JVM; all communication remains HTTP. */
class TimetableWebUiSidecar(private val plugin: BukkitPlugin) {
    private val config = plugin.javaRailwaysConfig.timetableWebUi
    private val token: String? = if (config.mode == TimetableWebUiMode.MANAGED) generateToken() else null
    private val logger = plugin.logger
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false

    fun managedBridgeToken(): String? = token

    @Synchronized
    fun start() {
        if (config.mode != TimetableWebUiMode.MANAGED) return
        check(process == null) { "Timetable WebUI sidecar is already started" }
        val runtimeDirectory = plugin.dataFolder.toPath().toAbsolutePath().resolve("webui")
        Files.createDirectories(runtimeDirectory)
        val jar = runtimeDirectory.resolve("JavaRailways-WebUI.jar")
        extractSidecar(jar)
        val logDirectory = plugin.dataFolder.toPath().resolve("logs")
        Files.createDirectories(logDirectory)
        val log = logDirectory.resolve("webui.log").toFile()
        val javaExecutable = java.nio.file.Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        ).toString()
        val bridge = plugin.javaRailwaysConfig.timetableHttpBridge
        val bridgeHost = if (bridge.bindAddress == "::1") "[::1]" else bridge.bindAddress
        val loopbackHosts = setOf("127.0.0.1", "localhost", "::1")
        if (config.bindAddress !in loopbackHosts) {
            logger.warning(
                "Timetable WebUI is listening on non-loopback address '${config.bindAddress}'; " +
                    "login and session traffic may be exposed without HTTPS"
            )
        }
        val publicUri = URI.create(config.publicUrl)
        if (publicUri.scheme == "http" && publicUri.host !in loopbackHosts) {
            logger.warning("Timetable WebUI public URL uses plaintext HTTP: ${config.publicUrl}")
        }
        stopping = false
        val child = ProcessBuilder(javaExecutable, "-jar", jar.toString())
            .directory(runtimeDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply {
                environment()["JAVARAILWAYS_BRIDGE_URL"] = "http://$bridgeHost:${bridge.port}/"
                environment()["JAVARAILWAYS_BRIDGE_TOKEN"] = requireNotNull(token)
                environment()["JAVARAILWAYS_WEB_BIND"] = config.bindAddress
                environment()["JAVARAILWAYS_WEB_PORT"] = config.port.toString()
                environment()["JAVARAILWAYS_WEB_SECURE_COOKIE"] = config.publicUrl.startsWith("https://").toString()
                environment()["JAVARAILWAYS_DRAFT_DIR"] = runtimeDirectory.resolve("drafts").toString()
                environment()["JAVARAILWAYS_MAX_REQUEST_BYTES"] = bridge.bodyLimitBytes.toString()
                environment()["JAVARAILWAYS_REQUEST_TIMEOUT_MS"] = bridge.timeoutMillis.toString()
            }
            .start()
        process = child
        child.onExit().thenAccept { exited ->
            if (!stopping && process === exited) {
                logger.warning(
                    "Timetable WebUI sidecar exited unexpectedly with code ${exited.exitValue()}; see ${log.absolutePath}"
                )
            }
        }
        logger.info("Timetable WebUI sidecar started at http://${config.bindAddress}:${config.port}")
    }

    @Synchronized
    fun stop() {
        stopping = true
        val child = process ?: return
        try {
            if (child.isAlive) {
                child.destroy()
                if (!child.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    logger.warning("Timetable WebUI did not stop in time; terminating PID ${child.pid()} forcibly")
                    child.destroyForcibly()
                    if (!child.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS) && child.isAlive) {
                        error("Timetable WebUI sidecar PID ${child.pid()} did not exit after forced termination")
                    }
                }
            }
            // Retain ownership on every failure so a live child cannot be mistaken for a stopped one.
            check(!child.isAlive) { "Timetable WebUI sidecar PID ${child.pid()} is still alive after shutdown" }
            process = null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(
                "Interrupted while stopping Timetable WebUI sidecar PID ${child.pid()}; exit is unconfirmed",
                interrupted
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Failed to stop Timetable WebUI sidecar PID ${child.pid()}: ${failure.message}",
                failure
            )
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
        const val SIDECAR_RESOURCE = "webui/JavaRailways-WebUI.jar"
    }
}
