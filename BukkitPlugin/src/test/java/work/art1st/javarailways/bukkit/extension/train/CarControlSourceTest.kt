package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.io.File

class CarControlSourceTest {
    @TempDir
    lateinit var dataFolder: File

    @Test
    fun `same basename cars use their own pinned profiles instead of changed live packages`() = withCars { cars ->
        val first = snapshot("first", 1_000.0)
        val second = snapshot("second", 2_000.0)
        for (snapshot in listOf(first, second)) {
            TrainPackageSnapshotStore.snapshotFile(dataFolder, snapshot.hash).apply {
                parentFile.mkdirs()
                writeText(Json.encodeToString(snapshot))
            }
        }
        val extensions = listOf(first, second).mapIndexed { index, snapshot ->
            extension(cars[index], "${snapshot.packageName}/head", snapshot.hash)
        }
        val filesBefore = dataFolder.walkTopDown().filter(File::isFile).associate { it.path to it.readText() }
        val lookups = mutableListOf<String>()
        val load: (String) -> SavedTrainPackageSnapshot? = { hash ->
            lookups += hash
            TrainPackageSnapshotStore.load(dataFolder, hash) { error(it) }
        }
        extensions.forEachIndexed { index, car ->
            val profile = car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT, load) {
                error("Pinned profile must not read live model '$it'")
            }
            assertEquals(if (index == 0) 1_000.0 else 2_000.0, profile!!.mass)
        }
        assertEquals(listOf(first.hash, second.hash), lookups)
        val changedLiveProfile = CarControlConfig(mass = 9_000.0)
        assertSame(changedLiveProfile, extensions[1].resolveCarControl(TrainModelUpdatePolicy.FOLLOW_PACKAGE,
            { error("Follow-package must not load pinned data") }) { name ->
            assertEquals("second/head", name)
            changedLiveProfile
        })
        assertEquals(filesBefore, dataFolder.walkTopDown().filter(File::isFile).associate { it.path to it.readText() })
    }

    @Test
    fun `unpinned source lookup does not pin hash or create a snapshot directory`() = withCars { cars ->
        val car = extension(cars[0], "second/head")
        val load: (String) -> SavedTrainPackageSnapshot? = { error("Read-only lookup must not pin a source") }
        var current = CarControlConfig(mass = 1_000.0)
        val live: (String) -> CarControlConfig? = { name ->
            assertEquals("second/head", name)
            current
        }
        assertSame(current, car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT, load, live))
        current = CarControlConfig(mass = 2_000.0)
        assertSame(current, car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT, load, live))
        assertFalse(File(dataFolder, "train-package-snapshots").exists())
    }

    @Test
    fun `missing pinned file or profile fails without creating directories or borrowing live controls`() = withCars { cars ->
        val car = extension(cars[0], "first/head", "missing-hash")
        val file = TrainPackageSnapshotStore.snapshotFile(dataFolder, "missing-hash")
        assertFalse(file.parentFile.exists())
        val missingFile = assertThrows<IllegalArgumentException> {
            car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT,
                { hash -> TrainPackageSnapshotStore.load(dataFolder, hash) { error(it) } },
                { error("Must not borrow live controls") })
        }
        assertEquals("Pinned car package snapshot 'missing-hash' is unavailable", missingFile.message)
        assertFalse(file.parentFile.exists())

        val noHead = snapshot("first", 1_000.0).copy(carControls = emptyMap())
        val missingProfile = assertThrows<IllegalArgumentException> {
            car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT, { noHead },
                { error("Must not borrow live controls") })
        }
        assertEquals("Pinned car package snapshot 'missing-hash' has no control profile for 'first/head'", missingProfile.message)
        assertFalse(file.parentFile.exists())
    }

    private fun extension(car: Car, name: String, hash: String? = null) = CarExtension().also {
        it.initialize(car, mapOf("BUKKIT" to SavedCarRenderState(carModelName = name, packageSnapshotHash = hash)))
    }

    private fun snapshot(name: String, mass: Double) = SavedTrainPackageSnapshot.build(
        packageName = name,
        actions = emptyMap(),
        behavior = null,
        control = TrainControlConfig(),
        carControls = mapOf("head" to CarControlConfig(mass = mass))
    )

    private fun withCars(action: (List<Car>) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
                    val train = createTrainWithConfig("sources", rail, world, listOf(10.0, 10.0))
                    action(train.cars)
                }
            }
        }
    }
}
