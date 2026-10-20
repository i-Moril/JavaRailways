package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.extension.train.CarExtension

class SimulationControlProfilesTest {
    @Test
    fun `mixed template captures exact car package scripts actions and control rather than template package`() {
        val first = source("first", 20_000.0)
        val second = source("second", 35_000.0)
        val template = source("template", 99_000.0)
        val version = TrainResourceVersion(template,
            mapOf("first/car" to model("first/car"), "second/car" to model("second/car")),
            mapOf("first/car" to first, "second/car" to second), listOf("first/car", "second/car"))
        val profile = profileFromTemplate("template", version)
        assertEquals(listOf(20_000.0, 35_000.0), profile.carControls.map { it.mass })
        assertEquals(listOf(first.behavior, second.behavior), profile.cars.map { it.behavior.script })
        assertEquals(listOf(setOf("first-action"), setOf("second-action")), profile.cars.map { it.behavior.actions.keys })
        assertTrue(profile.cars.none { it.installedReversed })
        assertSame(template.control, profile.trainControl)
    }

    @Test
    fun `world car snapshot stays pinned while follow package captures current exact car source`() {
        val pinned = source("source", 20_000.0)
        val reloaded = source("source", 40_000.0).copy(
            behavior = TrainBehaviorScript("behavior.js", "const version = 'reloaded';"))
        val oldVersion = TrainResourceVersion(pinned, mapOf("source/car" to model("source/car")),
            mapOf("source/car" to pinned), listOf("source/car"))
        val newVersion = oldVersion.copy(carPackages = mapOf("source/car" to reloaded))
        val extension = CarExtension().also { it.bindCreatedResources("template", "source/car", oldVersion) }
        val snapshot = extension.captureSimulationBehavior(TrainModelUpdatePolicy.SNAPSHOT,
            { error("already pinned package must be used") }, { error("already pinned model must be used") },
            { error("snapshot must not resolve live packages") })
        val following = extension.captureSimulationBehavior(TrainModelUpdatePolicy.FOLLOW_PACKAGE,
            { error("follow must not resolve pinned packages") }, { error("follow must not resolve pinned models") },
            { assertEquals("template", it); newVersion })
        assertEquals(pinned.behavior, snapshot.script)
        assertEquals(reloaded.behavior, following.script)
        assertNotSame(pinned.actions, snapshot.actions)
        assertEquals(pinned.actions, snapshot.actions)
        assertNotSame(pinned.actions.values.single().sounds, snapshot.actions.values.single().sounds)
        assertEquals(pinned.behavior, snapshot.script, "later captures cannot mutate detached values")
    }

    @Test
    fun `template missing an exact per car package fails without borrowing template actions`() {
        val template = source("template", 20_000.0)
        val version = TrainResourceVersion(template, mapOf("missing/car" to model("missing/car")),
            emptyMap(), listOf("missing/car"))
        assertThrows(NoSuchElementException::class.java) { profileFromTemplate("template", version) }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["package", "model"])
    fun `missing pinned behavior inputs fail without falling back to live packages`(missing: String) {
        val world = work.art1st.javarailways.core.physics.train.createTestWorld()
        try {
            val rail = work.art1st.javarailways.core.physics.train.createStraightRailFromSegments(world,
                work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator.buildStraight(100.0, 1))
            val car = work.art1st.javarailways.core.physics.train.createTrainWithConfig("capture", rail, world, listOf(10.0)).cars.single()
            val extension = CarExtension()
            extension.initialize(car, mapOf(work.art1st.javarailways.bukkit.BukkitPlugin.EXTENSION_ID to
                work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState("source/car",
                    packageSnapshotHash = "package-hash", modelSnapshotHash = "model-hash")))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                extension.captureSimulationBehavior(TrainModelUpdatePolicy.SNAPSHOT,
                    { if (missing == "package") null else source("source", 20_000.0) },
                    { null }, { error("missing pinned input must not fall back to loaded packages") })
            }
            assertTrue(failure.message!!.contains(if (missing == "package") "package-hash" else "model-hash"))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun model(name: String) = SavedCarModelTree("model-$name", CarDefinition(name, length = 10.0), emptyMap())
    private fun source(name: String, mass: Double) = SavedTrainPackageSnapshot.build(name,
        mapOf("$name-action" to TrainActionConfig("$name-action", sounds = listOf(TrainActionSoundConfig("$name.sound", at = 0.25)))),
        TrainBehaviorScript("$name.js", "const source = '$name';"), TrainControlConfig(),
        mapOf("$name/car" to CarControlConfig(mass = mass)))
}
