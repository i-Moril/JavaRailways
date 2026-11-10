package work.art1st.javarailways.decorations.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class DisplayDistanceSettingsTest {
    @Test fun `preferences are per viewer and reset quit or disable restores configured default`() {
        val settings = DisplayDistanceSettings(24.0, 96.0)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        settings.set(first, 0.0)
        settings.set(second, 96.0)
        assertEquals(0.0, settings.distanceFor(first))
        assertEquals(96.0, settings.distanceFor(second))
        settings.reset(first)
        assertEquals(24.0, settings.distanceFor(first))
        assertEquals(96.0, settings.distanceFor(second))
        settings.clear()
        assertEquals(24.0, settings.distanceFor(second))
    }

    @Test fun `invalid limits or preferences never replace a valid preference`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 4097.0, 0.0).forEach { max ->
            assertThrows<IllegalArgumentException> { DisplayDistanceSettings(0.0, max) }
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 129.0).forEach { default ->
            assertThrows<IllegalArgumentException> { DisplayDistanceSettings(default, 128.0) }
        }
        val settings = DisplayDistanceSettings(0.0, 4096.0)
        val viewer = UUID.randomUUID()
        settings.set(viewer, 4096.0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 4096.1).forEach { invalid ->
            assertThrows<IllegalArgumentException> { settings.set(viewer, invalid) }
            assertEquals(4096.0, settings.distanceFor(viewer))
        }
    }

    @Test fun `configuration rejects unknown duplicate coercible and non finite values`() {
        fun config(default: String = "32.0", max: String = "128.0") =
            "displays:\n  interior-distance:\n    default-blocks: $default\n    max-blocks: $max\n"
        assertEquals(32.0, DisplayDistanceConfig.parse(config()).defaultBlocks)
        listOf("\"32\"", "true", ".nan", ".inf", "null", "[]").forEach {
            assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse(config(default = it)) }
        }
        listOf("\"128\"", ".inf", "0", "4097").forEach {
            assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse(config(max = it)) }
        }
        assertThrows<Exception> { DisplayDistanceConfig.parse(config() + "    default-blocks: 64\n") }
        assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse(config() + "    typo: 1\n") }
        assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse(config() + "unknown: {}\n") }
        assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse("displays: {}") }
        assertThrows<IllegalArgumentException> { DisplayDistanceConfig.parse("[]") }
    }
}
