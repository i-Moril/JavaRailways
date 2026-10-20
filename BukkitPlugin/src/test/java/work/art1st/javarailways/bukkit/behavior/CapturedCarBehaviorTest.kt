package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.model.*

class CapturedCarBehaviorTest {
    @Test
    fun `capabilities use actual recursive body bogie wheel conversion with nonempty duplicate resolution`() {
        val door = PartDefinition("other/door", PartType.DUMMY, animations = listOf(
            PartAnimationConfig("door", keyframes = listOf(PartAnimationKeyframeConfig(0.0, "closed"), PartAnimationKeyframeConfig(1.0, "open"))),
            PartAnimationConfig("empty"),
            PartAnimationConfig("duplicate", commands = listOf(PartAnimationCommandConfig(0.0, scene = "old"))),
            PartAnimationConfig("duplicate", commands = listOf(PartAnimationCommandConfig(0.0, scene = "new")))
        ))
        val body = PartDefinition("train/body", PartType.DUMMY, parts = listOf(PartRefEntry.import("other/door")))
        val bogie = PartDefinition("train/bogie", PartType.DUMMY, animations = listOf(
            PartAnimationConfig("suspension", commands = listOf(PartAnimationCommandConfig(1.0)))
        ))
        val wheel = PartDefinition("train/wheel", PartType.DUMMY, animations = listOf(
            PartAnimationConfig("wheel", commands = listOf(PartAnimationCommandConfig(1.0, scene = "run")))
        ))
        val car = CarDefinition("train/car", bodyParts = listOf(PartRefEntry.import("train/body")), bogies = listOf(
            BogieDefinition(parts = listOf(PartRefEntry.import("train/bogie")), wheelParts = listOf(PartRefEntry.import("train/wheel")))
        ))
        val snapshot = SavedCarModelTree("fixture", car, listOf(door, body, bogie, wheel).associateBy { it.name })
        assertEquals(mapOf("door" to setOf("closed", "open"), "duplicate" to setOf("new"),
            "suspension" to emptySet<String>(), "wheel" to setOf("run")), snapshot.captureAnimationCapabilities())
    }
}
