package work.art1st.javarailways.bukkit.ui.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogRendererNumberTextTest {
    @Test
    fun `tiny finite values use a bounded round-trip representation`() {
        val values = listOf(1.0e-40, -1.0e-200, Double.MIN_VALUE)

        values.forEach { value ->
            val rendered = formatDialogNumberText(value)

            assertTrue(rendered.length <= DIALOG_NUMBER_TEXT_MAX_LENGTH, rendered)
            assertEquals(value, rendered.toDouble())
        }
    }

    @Test
    fun `ordinary values keep concise plain decimal formatting`() {
        assertEquals("80", formatDialogNumberText(80.0))
        assertEquals("0.125", formatDialogNumberText(0.125))
    }
}
