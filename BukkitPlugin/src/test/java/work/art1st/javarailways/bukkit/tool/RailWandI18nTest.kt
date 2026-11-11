package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class RailWandI18nTest {
    @Test
    fun `catalog resolves RailWand settings for Chinese and English locales`() {
        assertEquals(
            "启用外轨超高",
            RailWandI18n.translate(Locale.SIMPLIFIED_CHINESE, "settings.superelevation.enabled.label")
        )
        assertEquals(
            "Enable superelevation",
            RailWandI18n.translate(Locale.US, "settings.superelevation.enabled.label")
        )
    }

    @Test
    fun `unknown locales use English fallback`() {
        assertEquals(
            "Create mode",
            RailWandI18n.translate(Locale.JAPAN, "mode.create.name")
        )
    }

    @Test
    fun `dynamic turnout options are localized`() {
        val reference = RailWandI18n.reference("turnout.option", 9, 300, 50)

        assertEquals("9号 (R300m / 50km/h)", RailWandI18n.resolve(Locale.SIMPLIFIED_CHINESE, reference))
        assertEquals("No. 9 (R300m / 50km/h)", RailWandI18n.resolve(Locale.US, reference))
    }

    @Test
    fun `rail selection text contains geometry but not storage identity`() {
        assertEquals("已选择轨道，SUBGRADE 80 km/h，CURVE 65 km/h，脱轨 100 km/h，半径 300 m，单线",
            RailWandI18n.format(Locale.SIMPLIFIED_CHINESE, "message.rail_selected",
                "80 km/h", "65 km/h", "100 km/h", "300 m", "单线"))
        assertEquals("Selected rail, SUBGRADE 80 km/h, CURVE 65 km/h, derail 100 km/h, radius 300 m, single",
            RailWandI18n.format(Locale.US, "message.rail_selected",
                "80 km/h", "65 km/h", "100 km/h", "300 m", "single"))
        assertEquals("Selected rail; left-click again to clear all decorations",
            RailWandI18n.format(Locale.US, "message.decorate_delete_selected"))
    }

    @Test
    fun `derived anchor names hide rail and split UUIDs without changing authored names`() {
        val id = "12345678-1234-1234-1234-123456789abc"
        assertEquals("轨道端点 A", RailWandI18n.anchorName(Locale.SIMPLIFIED_CHINESE, "rail:$id:A"))
        assertEquals("Rail endpoint B", RailWandI18n.anchorName(Locale.US, "rail:$id:B"))
        assertEquals("Split point", RailWandI18n.anchorName(Locale.US, "split:$id"))
        assertEquals("Station throat", RailWandI18n.anchorName(Locale.US, "Station throat"))
    }

    @Test
    fun `Chinese and English catalogs contain the same keys`() {
        assertEquals(RailWandI18n.englishKeys(), RailWandI18n.simplifiedChineseKeys())
        assertTrue(RailWandI18n.englishKeys().isNotEmpty())
    }
}
