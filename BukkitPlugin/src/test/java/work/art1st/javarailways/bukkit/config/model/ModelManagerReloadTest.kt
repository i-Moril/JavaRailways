package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModelManagerReloadTest {
    @TempDir
    lateinit var dataFolder: File

    @Test
    fun `failed reload leaves published registries unchanged`() {
        val manager = ModelManager(dataFolder)
        val old = trainPackage("old")
        manager.trainPackages["old"] = old

        File(dataFolder, "trains/broken").apply {
            mkdirs()
            resolve("manifest.yml").writeText("config-version: 999\nname: broken\ntype: train\ncomposition: []\n")
        }

        assertThrows<IllegalArgumentException> { manager.loadAll() }
        assertSame(old, manager.trainPackages["old"])
    }

    @Test
    fun `qualified behavior and actions stay within the requested package`() {
        val firstHorn = TrainActionConfig("horn", displayName = "First horn")
        val secondHorn = TrainActionConfig("horn", displayName = "Second horn")
        val bell = TrainActionConfig("bell")
        val first = trainPackage("first", listOf(firstHorn), "function horn() { return 'first'; }")
        val second = trainPackage("second", listOf(secondHorn, bell), "function horn() { return 'second'; }")
        val empty = trainPackage("empty")
        val manager = ModelManager(dataFolder).apply {
            trainPackages.putAll(listOf(first, second, empty).associateBy { it.manifest.name })
        }

        assertSame(firstHorn, manager.getTrainAction("first", "horn"))
        assertSame(secondHorn, manager.getTrainAction("second", "horn"))
        assertSame(bell, manager.getTrainAction("second", "bell"))
        assertNull(manager.getTrainAction("first", "bell"))
        assertSame(first.actions, manager.getTrainActions("first"))
        assertSame(second.actions, manager.getTrainActions("second"))
        assertSame(first.behavior, manager.getTrainBehavior("first"))
        assertSame(second.behavior, manager.getTrainBehavior("second"))

        for (packageName in listOf("empty", "missing")) {
            assertNull(manager.getTrainAction(packageName, "horn"))
            assertEquals(emptyMap<String, TrainActionConfig>(), manager.getTrainActions(packageName))
            assertNull(manager.getTrainBehavior(packageName))
        }
    }

    @Test
    fun `unqualified lookup preserves global search and action aggregation`() {
        val firstHorn = TrainActionConfig("horn", displayName = "First horn")
        val secondHorn = TrainActionConfig("horn", displayName = "Second horn")
        val bell = TrainActionConfig("bell")
        val first = trainPackage("first", listOf(firstHorn), "function horn() { return 'first'; }")
        val second = trainPackage("second", listOf(secondHorn, bell), "function horn() { return 'second'; }")
        val manager = ModelManager(dataFolder).apply {
            trainPackages.putAll(listOf(trainPackage("empty"), first, second).associateBy { it.manifest.name })
        }

        assertSame(firstHorn, manager.getTrainAction(null, "horn"))
        assertSame(bell, manager.getTrainAction(null, "bell"))
        assertNull(manager.getTrainAction(null, "missing"))
        assertEquals(mapOf("horn" to secondHorn, "bell" to bell), manager.getTrainActions(null))
        assertSame(first.behavior, manager.getTrainBehavior(null))
    }

    private fun trainPackage(
        name: String,
        actions: List<TrainActionConfig> = emptyList(),
        behaviorSource: String? = null
    ): LoadedTrainPackage = LoadedTrainPackage(
        manifest = PackageManifest(name, PackageType.TRAIN, PackageManifest.CONFIG_VERSION, composition = emptyList()),
        partRegistry = PartRegistry(File(dataFolder, "$name-parts")),
        cars = emptyMap(),
        train = TrainDefinition(name, name, emptyList()),
        actions = actions.associateBy { it.name },
        behavior = behaviorSource?.let { TrainBehaviorScript("behavior.js", it) },
        control = TrainControlConfig()
    )
}
