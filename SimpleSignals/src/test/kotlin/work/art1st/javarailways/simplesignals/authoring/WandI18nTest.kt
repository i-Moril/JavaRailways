package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

internal class WandI18nTest {
    @Test fun `naming mode has localized item status and instructions`() {
        assertEquals("命名", WandText.key("section.mode.NAME").render(Locale.CHINA))
        assertEquals("name", WandText.key("section.mode.NAME").render(Locale.US))
        assertEquals("SimpleSignals 边界命名工具", WandText.key("section.name.NAME").render(Locale.CHINA))
        assertEquals("SimpleSignals boundary naming wand", WandText.key("section.name.NAME").render(Locale.US))
    }

    @Test fun `Chinese resolves nested feedback while unknown locale falls back to English`() {
        val text = WandText.key("block.created", WandText.diagnostic("player:platform_%s"))
        assertEquals("已创建闭塞：player:platform_%s", text.render(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Created block: player:platform_%s", text.render(Locale.JAPAN))
        assertEquals("backend diagnostic", WandText.diagnostic("backend diagnostic").render(Locale.CHINA))
    }
}
