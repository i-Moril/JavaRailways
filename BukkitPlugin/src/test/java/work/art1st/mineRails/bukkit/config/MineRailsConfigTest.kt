package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class MineRailsConfigTest {
    @Test
    fun `bundled config defaults to entitylib display renderer`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/config.yml"))
        val yaml = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

        val config = MineRailsConfig.fromYaml(yaml)
        assertEquals(DisplayRendererBackend.ENTITYLIB, config.displayRenderer.backend)
        assertEquals(true, config.timetableHttpBridge.enabled)
        assertEquals(TimetableWebUiMode.MANAGED, config.timetableWebUi.mode)
        assertEquals("127.0.0.1", config.timetableHttpBridge.bindAddress)
    }

    @Test
    fun `parses train key bind distance and native display renderer`() {
        val yaml = validYaml()
        yaml.set("train-model-update-policy", "snapshot")
        yaml.set("train-key-bind-distance", 24.5)
        yaml.set("train-debug-log", true)
        yaml.set("physics-worker-threads", 3)
        yaml.set("overspeed-derail-enabled", true)
        yaml.set("derail-lateral-acceleration-mps2", 3.5)
        yaml.set("overspeed-tolerance-kmh", 6.0)
        yaml.set("overspeed-grace-ticks", 4)

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(TrainModelUpdatePolicy.SNAPSHOT, config.trainModelUpdatePolicy)
        assertEquals(24.5, config.trainKeyBindDistance)
        assertEquals(true, config.trainDebugLog)
        assertEquals(3, config.physicsWorkerThreads)
        assertEquals(true, config.overspeedDerailEnabled)
        assertEquals(3.5, config.derailLateralAccelerationMetersPerSecondSquared)
        assertEquals(6.0, config.overspeedToleranceKmH)
        assertEquals(4, config.overspeedGraceTicks)
        assertEquals(DisplayRendererBackend.NATIVE, config.displayRenderer.backend)
        assertEquals(PlayerDisplayDistanceDefault.PLAYER_VIEW_DISTANCE, config.displayRenderer.playerDistance.default)
        assertEquals(512.0, config.displayRenderer.playerDistance.maxBlocks)
        assertEquals(true, config.timetableHttpBridge.enabled)
        assertEquals(TimetableWebUiMode.MANAGED, config.timetableWebUi.mode)
        assertEquals("MINERAILS_TIMETABLE_HTTP_TOKEN", config.timetableHttpBridge.bearerTokenEnvironmentVariable)
    }

    @Test
    fun `parses and validates turnout safety defaults`() {
        val yaml = validYaml().apply {
            set("turnout.default-protection-distance-meters", 24.0)
            set("turnout.default-locking-distance-meters", 6.0)
            set("turnout.decision-lead-ticks", 5)
            set("turnout.allocation-retry-ticks", 7)
        }

        val config = MineRailsConfig.fromYaml(yaml).turnoutSafety

        assertEquals(24.0, config.defaultProtectionDistanceMeters)
        assertEquals(6.0, config.defaultLockingDistanceMeters)
        assertEquals(5, config.decisionLeadTicks)
        assertEquals(7, config.allocationRetryTicks)
        val invalid = validYaml().apply {
            set("turnout.default-protection-distance-meters", 4.0)
            set("turnout.default-locking-distance-meters", 5.0)
        }
        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(invalid) }
    }

    @Test
    fun `parses entitylib display renderer`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "entitylib")
        yaml.set("display-renderer.player-distance.max-blocks", 768.0)

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(DisplayRendererBackend.ENTITYLIB, config.displayRenderer.backend)
        assertEquals(768.0, config.displayRenderer.playerDistance.maxBlocks)
    }

    @Test
    fun `unknown display renderer fails explicitly`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "automatic")

        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(yaml)
        }
    }

    @Test
    fun `display renderer max distance must be within supported range`() {
        val zero = validYaml().apply {
            set("display-renderer.player-distance.max-blocks", 0.0)
        }
        val excessive = validYaml().apply {
            set("display-renderer.player-distance.max-blocks", 4097.0)
        }

        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(zero) }
        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(excessive) }
    }

    @Test
    fun `timetable http bridge requires loopback bind and sane limits`() {
        val publicBind = validYaml().apply { set("timetable-http-bridge.bind-address", "0.0.0.0") }
        val badTokenEnv = validYaml().apply { set("timetable-http-bridge.bearer-token-env", "bad-name") }
        val badLimit = validYaml().apply { set("timetable-http-bridge.body-limit-bytes", 0) }

        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(publicBind) }
        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(badTokenEnv) }
        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(badLimit) }
    }

    @Test
    fun `legacy config without webui keys defaults to managed sidecar`() {
        val yaml = validYaml().apply {
            set("timetable-webui", null)
            set("timetable-http-bridge", null)
        }

        val config = MineRailsConfig.fromYaml(yaml)
        assertEquals(TimetableWebUiMode.MANAGED, config.timetableWebUi.mode)
        assertEquals(true, config.timetableHttpBridge.enabled)
        assertEquals(8080, config.timetableWebUi.port)
    }

    @Test
    fun `webui supports external and disabled process modes`() {
        val external = validYaml().apply { set("timetable-webui.mode", "external") }
        val disabled = validYaml().apply {
            set("timetable-webui.mode", "disabled")
            set("timetable-http-bridge.enabled", false)
        }

        assertEquals(TimetableWebUiMode.EXTERNAL, MineRailsConfig.fromYaml(external).timetableWebUi.mode)
        assertEquals(TimetableWebUiMode.DISABLED, MineRailsConfig.fromYaml(disabled).timetableWebUi.mode)
    }

    @Test
    fun `WebUI permits explicitly configured remote HTTP listener`() {
        val yaml = validYaml().apply {
            set("timetable-webui.bind-address", "0.0.0.0")
            set("timetable-webui.public-url", "http://rail.example.com:8080")
        }

        val config = MineRailsConfig.fromYaml(yaml).timetableWebUi
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals("http://rail.example.com:8080", config.publicUrl)
    }

    @Test
    fun `physics worker threads must be positive`() {
        val yaml = validYaml()
        yaml.set("physics-worker-threads", 0)

        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(yaml)
        }
    }

    @Test
    fun `missing required keys now fails explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(YamlConfiguration())
        }
    }

    private fun validYaml(): YamlConfiguration = YamlConfiguration().apply {
        set("train-model-update-policy", "follow-package")
        set("train-key-bind-distance", 20.0)
        set("train-debug-log", false)
        set("physics-worker-threads", 4)
        set("overspeed-derail-enabled", false)
        set("derail-lateral-acceleration-mps2", 3.0)
        set("overspeed-tolerance-kmh", 5.0)
        set("overspeed-grace-ticks", 5)
        set("display-renderer.backend", "native")
        set("display-renderer.player-distance.default", "player-view-distance")
        set("display-renderer.player-distance.max-blocks", 512.0)
        set("timetable-webui.mode", "managed")
        set("timetable-webui.bind-address", "127.0.0.1")
        set("timetable-webui.port", 8080)
        set("timetable-webui.shutdown-timeout-millis", 5000)
        set("timetable-http-bridge.enabled", true)
        set("timetable-http-bridge.bind-address", "127.0.0.1")
        set("timetable-http-bridge.port", 8765)
        set("timetable-http-bridge.bearer-token-env", "MINERAILS_TIMETABLE_HTTP_TOKEN")
        set("timetable-http-bridge.body-limit-bytes", 1048576)
        set("timetable-http-bridge.timeout-millis", 5000)
    }
}
