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
    @Test
    fun `capability queries normalize scenes and reject unknown animation or scene`() {
        val capabilities = mapOf("lamp" to setOf("head", "tail"), "motion" to emptySet())
        assertTrue(capabilities.supportsAnimation("lamp", null))
        assertTrue(capabilities.supportsAnimation("lamp", " HEAD "))
        assertFalse(capabilities.supportsAnimation("lamp", "off"))
        assertFalse(capabilities.supportsAnimation("missing", null))
        assertTrue(capabilities.supportsAnimation("motion", null))
        assertFalse(capabilities.supportsAnimation("motion", "head"))
    }

    @Test
    fun `real s100 package reverses every car without requesting another cars end lights`() {
        val loaded = requireNotNull(PackageLoader(java.io.File("../models/s100")).loadTrainPackage())
        assertEquals(setOf("head", "head_tail", "motor", "motor_tail", "passenger", "trailer"), loaded.cars.keys)
        for ((modelName, model) in loaded.cars) {
            val capabilities = SavedCarModelTree.build(model, loaded.partRegistry).captureAnimationCapabilities()
            val effects = mutableListOf<Pair<String, String?>>()
            val results = mutableListOf<Result<Unit>>()
            CarBehavior({ loaded.behavior }, { loaded.actions }, { name, scene, _ ->
                if (capabilities.supportsAnimation(name, scene)) {
                    effects.add(name to scene)
                    java.util.concurrent.CompletableFuture.completedFuture(Unit)
                } else java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("animation unavailable"))
            }, { _, _, _ -> fail<Unit>("light actions must not play sound") },
                { _, _ -> error("light actions must not schedule delayed effects") }, {},
                { throw it }, capabilities::supportsAnimation, { sides -> CarAnimationPlayback.DoorClosePlan(sides.flatten(), emptySet(), 0L) }, { capabilities }).use { behavior ->
                val lightNames = capabilities.keys.filter { it.endsWith("_end_light") }.toSet()
                val metadata = behavior.metadata(false)
                assertTrue(effects.isEmpty(), "metadata must not start effects: $modelName")
                assertEquals(lightNames.isNotEmpty(), metadata.any { it.name == "lights-refresh" }, modelName)
                assertEquals(lightNames.isNotEmpty(), metadata.any { it.name == "end-lights-off" }, modelName)
                for (tail in listOf(false, true)) {
                    effects.clear()
                    results.clear()
                    assertEquals(work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult.Accepted,
                        behavior.call("onReverse", emptyList(), tail, results::add, optional = true), modelName)
                    assertTrue(results.single().isSuccess, modelName)
                    assertEquals(lightNames, effects.map { it.first }.toSet(), modelName)
                    effects.forEach { (name, scene) ->
                        assertEquals(if ((name == "front_end_light") xor tail) "head" else "tail", scene, modelName)
                    }
                }
                if (lightNames.isNotEmpty()) {
                    effects.clear()
                    results.clear()
                    behavior.action("end-lights-off", false, results::add)
                    assertTrue(results.single().isSuccess, modelName)
                    assertEquals(lightNames.map { it to "off" }.toSet(), effects.toSet(), modelName)
                }
                effects.clear()
                results.clear()
                behavior.action("power-off", false, results::add)
                assertTrue(results.single().isSuccess, modelName)
                assertEquals((lightNames + "cabin_lights").map { it to "off" }.toSet(), effects.toSet(), modelName)
                // Calling a real, but unsupported, raw action remains a genuine rejection.
                val unsupported = if ("front_end_light" !in lightNames) "front-light-head" else "rear-light-head"
                results.clear()
                assertTrue(behavior.action(unsupported, false, results::add) is
                    work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult.Rejected, modelName)
                assertTrue(results.single().isFailure, modelName)
            }
        }
    }

}
