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
    fun `Chinese and English catalogs contain the same keys`() {
        assertEquals(RailWandI18n.englishKeys(), RailWandI18n.simplifiedChineseKeys())
        assertTrue(RailWandI18n.englishKeys().isNotEmpty())
    }
}
