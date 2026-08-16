package work.art1st.javarailways.bukkit.config.model

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
        val old = LoadedTrainPackage(
            manifest = PackageManifest("old", PackageType.TRAIN, PackageManifest.CONFIG_VERSION, composition = emptyList()),
            partRegistry = PartRegistry(File(dataFolder, "old-parts")),
            cars = emptyMap(),
            train = TrainDefinition("old", "old", emptyList()),
            control = TrainControlConfig()
        )
        manager.trainPackages["old"] = old

        File(dataFolder, "trains/broken").apply {
            mkdirs()
            resolve("manifest.yml").writeText("config-version: 999\nname: broken\ntype: train\ncomposition: []\n")
        }

        assertThrows<IllegalArgumentException> { manager.loadAll() }
        assertSame(old, manager.trainPackages["old"])
    }
}
