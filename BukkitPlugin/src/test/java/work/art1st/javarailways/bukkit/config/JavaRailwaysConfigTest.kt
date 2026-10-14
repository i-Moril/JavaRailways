package work.art1st.javarailways.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class JavaRailwaysConfigTest {
    @Test
    fun `bundled config defaults to entitylib display renderer`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/config.yml"))
        val yaml = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

        val config = JavaRailwaysConfig.fromYaml(yaml)
        assertEquals(DisplayRendererBackend.ENTITYLIB, config.displayRenderer.backend)
        assertEquals(true, config.timetableHttpBridge.enabled)
        assertEquals(TimetableWebUiMode.MANAGED, config.timetableWebUi.mode)
        assertEquals("127.0.0.1", config.timetableHttpBridge.bindAddress)
        assertEquals(5.0, config.atoSafetyDistanceMeters)
    }

    @Test
    fun `missing ATO safety distance defaults to five meters`() {
        assertEquals(5.0, JavaRailwaysConfig.fromYaml(validYaml()).atoSafetyDistanceMeters)
    }

    @Test
    fun `ATO safety distance accepts explicit nonnegative numbers`() {
        listOf(0.0, 7.5, 9).forEach { distance ->
            val yaml = validYaml().apply { set("ato.safety-distance-meters", distance) }
            assertEquals(distance.toDouble(), JavaRailwaysConfig.fromYaml(yaml).atoSafetyDistanceMeters)
        }
    }

    @Test
    fun `ATO safety distance rejects negative nonfinite and nonnumeric values`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            "5.0", true, listOf(5.0)).forEach { invalid ->
            val yaml = validYaml().apply { set("ato.safety-distance-meters", invalid) }
            val error = assertThrows(IllegalArgumentException::class.java) {
                JavaRailwaysConfig.fromYaml(yaml)
            }
            assertTrue(error.message.orEmpty().contains("ato.safety-distance-meters"))
        }
    }

    @Test
    fun `ATO settings must be a section when present`() {
        listOf("5.0", true, 5.0, listOf(5.0)).forEach { invalid ->
            val yaml = validYaml().apply { set("ato", invalid) }
            val error = assertThrows(IllegalArgumentException::class.java) {
                JavaRailwaysConfig.fromYaml(yaml)
            }
            assertTrue(error.message.orEmpty().contains("ato"))
        }
    }

    @Test
    fun `parses train key bind distance and native display renderer`() {
        val yaml = validYaml()
        yaml.set("train-model-update-policy", "snapshot")
        yaml.set("train-key-bind-distance", 24.5)
        yaml.set("train-debug-log", true)

        val config = JavaRailwaysConfig.fromYaml(yaml)

        assertEquals(TrainModelUpdatePolicy.SNAPSHOT, config.trainModelUpdatePolicy)
        assertEquals(24.5, config.trainKeyBindDistance)
        assertEquals(true, config.trainDebugLog)
        assertEquals(DisplayRendererBackend.NATIVE, config.displayRenderer.backend)
        assertEquals(PlayerDisplayDistanceDefault.PLAYER_VIEW_DISTANCE, config.displayRenderer.playerDistance.default)
        assertEquals(512.0, config.displayRenderer.playerDistance.maxBlocks)
        assertEquals(true, config.timetableHttpBridge.enabled)
        assertEquals(TimetableWebUiMode.MANAGED, config.timetableWebUi.mode)
        assertEquals("JAVARAILWAYS_TIMETABLE_HTTP_TOKEN", config.timetableHttpBridge.bearerTokenEnvironmentVariable)
    }

    @Test
    fun `parses entitylib display renderer`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "entitylib")
        yaml.set("display-renderer.player-distance.max-blocks", 768.0)

        val config = JavaRailwaysConfig.fromYaml(yaml)

        assertEquals(DisplayRendererBackend.ENTITYLIB, config.displayRenderer.backend)
        assertEquals(768.0, config.displayRenderer.playerDistance.maxBlocks)
    }

    @Test
    fun `unknown display renderer fails explicitly`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "automatic")

        assertThrows(IllegalArgumentException::class.java) {
            JavaRailwaysConfig.fromYaml(yaml)
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

        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(zero) }
        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(excessive) }
    }

    @Test
    fun `timetable http bridge requires loopback bind and sane limits`() {
        val publicBind = validYaml().apply { set("timetable-http-bridge.bind-address", "0.0.0.0") }
        val badTokenEnv = validYaml().apply { set("timetable-http-bridge.bearer-token-env", "bad-name") }
        val badLimit = validYaml().apply { set("timetable-http-bridge.body-limit-bytes", 0) }

        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(publicBind) }
        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(badTokenEnv) }
        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(badLimit) }
    }

    @Test
    fun `config requires webui and bridge sections`() {
        val missingWebUi = validYaml().apply { set("timetable-webui", null) }
        val missingBridge = validYaml().apply { set("timetable-http-bridge", null) }

        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(missingWebUi) }
        assertThrows(IllegalArgumentException::class.java) { JavaRailwaysConfig.fromYaml(missingBridge) }
    }

    @Test
    fun `webui supports external and disabled process modes`() {
        val external = validYaml().apply { set("timetable-webui.mode", "external") }
        val disabled = validYaml().apply {
            set("timetable-webui.mode", "disabled")
            set("timetable-http-bridge.enabled", false)
        }

        assertEquals(TimetableWebUiMode.EXTERNAL, JavaRailwaysConfig.fromYaml(external).timetableWebUi.mode)
        assertEquals(TimetableWebUiMode.DISABLED, JavaRailwaysConfig.fromYaml(disabled).timetableWebUi.mode)
    }

    @Test
    fun `WebUI permits explicitly configured remote HTTP listener`() {
        val yaml = validYaml().apply {
            set("timetable-webui.bind-address", "0.0.0.0")
            set("timetable-webui.public-url", "http://rail.example.com:8080")
        }

        val config = JavaRailwaysConfig.fromYaml(yaml).timetableWebUi
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals("http://rail.example.com:8080", config.publicUrl)
    }

    @Test
    fun `retired physics keys fail with explicit migration boundary`() {
        val yaml = validYaml().apply { set("physics-worker-threads", 4) }

        assertThrows(IllegalArgumentException::class.java) {
            JavaRailwaysConfig.fromYaml(yaml)
        }
    }

    @Test
    fun `missing required keys now fails explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            JavaRailwaysConfig.fromYaml(YamlConfiguration())
        }
    }

    private fun validYaml(): YamlConfiguration = YamlConfiguration().apply {
        set("train-model-update-policy", "follow-package")
        set("train-key-bind-distance", 20.0)
        set("train-debug-log", false)
        set("display-renderer.backend", "native")
        set("display-renderer.player-distance.default", "player-view-distance")
        set("display-renderer.player-distance.max-blocks", 512.0)
        set("timetable-webui.mode", "managed")
        set("timetable-webui.bind-address", "127.0.0.1")
        set("timetable-webui.port", 8080)
        set("timetable-webui.public-url", "http://127.0.0.1:8080")
        set("timetable-webui.shutdown-timeout-millis", 5000)
        set("timetable-http-bridge.enabled", true)
        set("timetable-http-bridge.bind-address", "127.0.0.1")
        set("timetable-http-bridge.port", 8765)
        set("timetable-http-bridge.bearer-token-env", "JAVARAILWAYS_TIMETABLE_HTTP_TOKEN")
        set("timetable-http-bridge.body-limit-bytes", 1048576)
        set("timetable-http-bridge.timeout-millis", 5000)
    }
}
