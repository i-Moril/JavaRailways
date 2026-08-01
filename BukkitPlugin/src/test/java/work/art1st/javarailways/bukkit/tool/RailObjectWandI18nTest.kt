package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class RailObjectWandI18nTest {
    @Test
    fun `catalog resolves waypoint UI for Chinese and English locales`() {
        assertEquals(
            "不限制（遵循轨道规则）",
            RailObjectWandI18n.translate(Locale.SIMPLIFIED_CHINESE, "waypoint.approach.any")
        )
        assertEquals(
            "Unrestricted (follow rail rules)",
            RailObjectWandI18n.translate(Locale.US, "waypoint.approach.any")
        )
    }

    @Test
    fun `unknown locales use English fallback`() {
        assertEquals(
            "Waypoint type",
            RailObjectWandI18n.translate(Locale.JAPAN, "waypoint.type.label")
        )
    }
}
