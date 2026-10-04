package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlockerKind
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import java.util.Locale
import java.util.Properties

class TurnoutWandI18nTest {
    @Test
    fun `both catalogs cover every status and keep English fallback`() {
        for (status in ManualTurnoutSelectionStatus.entries) {
            val result = ManualTurnoutSelectionResult(status)
            val english = TurnoutWandI18n.result(Locale.US, result)
            val chinese = TurnoutWandI18n.result(Locale.SIMPLIFIED_CHINESE, result)
            assertFalse(english.startsWith("result."))
            assertFalse(chinese.startsWith("result."))
            assertNotEquals(english, chinese)
            assertEquals(english, TurnoutWandI18n.result(Locale.JAPAN, result))
        }
        fun keys(locale: String) = Properties().also { properties ->
            javaClass.getResourceAsStream("/i18n/turnout_wand_$locale.properties")!!.use { properties.load(it) }
        }.stringPropertyNames()
        assertEquals(keys("en_US"), keys("zh_CN"))
    }

    @Test
    fun `refusal identifies trains or resource owners with bounded diagnostics`() {
        val occupied = ManualTurnoutSelectionResult(ManualTurnoutSelectionStatus.PHYSICALLY_OCCUPIED,
            blockingTrainIds = listOf("train-a", "train-b", "train-c", "train-d"))
        val text = TurnoutWandI18n.result(Locale.US, occupied)
        assertTrue(text.contains("train-a"))
        assertTrue(text.contains("train-c"))
        assertFalse(text.contains("train-d"))
        assertTrue(text.contains("…"))
        val conflict = ManualTurnoutSelectionResult(ManualTurnoutSelectionStatus.RESOURCE_CONFLICT,
            blockers = listOf(InfrastructureBlocker(InfrastructureBlockerKind.EDIT_PERMIT,
                InfrastructureOwner("test:edit", 3), listOf(StableInfrastructureResource("test:turnout", "one")))))
        assertTrue(TurnoutWandI18n.result(Locale.SIMPLIFIED_CHINESE, conflict).contains("test:edit@3"))
    }
}
