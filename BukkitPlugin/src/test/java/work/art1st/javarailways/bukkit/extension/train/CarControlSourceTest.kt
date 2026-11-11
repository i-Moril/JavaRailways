package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts
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
    fun `mixed source snapshot facts follow actual cars after structural split`() = withCars { cars ->
        val first = snapshot("first", 1_000.0, 8.0)
        val second = snapshot("second", 2_000.0, 12.0)
        val sources = listOf(first, second).associateBy { it.hash }
        val bindings = cars.mapIndexed { index, car ->
            val source = listOf(first, second)[index]
            car.id to extension(car, "${source.packageName}/head", source.hash)
        }.toMap()
        fun facts(selected: List<Car>) = TrainCandidateFacts.fromCars(selected.associate { car ->
            car.id to requireNotNull(bindings.getValue(car.id).resolveCandidateFacts(
                TrainModelUpdatePolicy.SNAPSHOT, sources::get) { error("Pinned facts cannot read live packages") })
        })
        val combined = facts(cars)
        assertNull(combined.modelPackageId)
        assertEquals(8.0, combined.maximumSpeedMetersPerSecond)
        val train = cars.first().train as work.art1st.javarailways.core.physics.train.CoreTrain
        val world = train.world as work.art1st.javarailways.core.physics.CoreWorld
        val pending = train.splitBefore(cars.last().id, "fact-split")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        val separated = pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("first", facts(train.cars).modelPackageId)
        assertEquals(8.0, facts(train.cars).maximumSpeedMetersPerSecond)
        assertEquals("second", facts(separated.cars).modelPackageId)
        assertEquals(12.0, facts(separated.cars).maximumSpeedMetersPerSecond)
        assertEquals(combined.carFacts, facts(train.cars).carFacts + facts(separated.cars).carFacts)
    }

    @Test
    fun `missing creation snapshot rejects lookup instead of borrowing current controls`() = withCars { cars ->
        val car = extension(cars[0], "second/head")
        val load: (String) -> SavedTrainPackageSnapshot? = { error("Read-only lookup must not pin a source") }
        assertThrows<IllegalArgumentException> {
            car.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT, load) {
                error("Missing creation snapshot must not borrow current controls")
            }
        }
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

    @Test
    fun `creation binding preserves template identity and source control without reading live package`() = withCars { cars ->
        val source = snapshot("external", 8_000.0)
        val model = work.art1st.javarailways.bukkit.config.model.SavedCarModelTree.build(
            work.art1st.javarailways.bukkit.config.model.CarDefinition("external/head"),
            work.art1st.javarailways.bukkit.config.model.SnapshotPartRegistry(emptyMap()))
        val version = work.art1st.javarailways.bukkit.config.model.TrainResourceVersion(
            snapshot("template", 4_000.0), mapOf("external/head" to model), mapOf("external/head" to source), listOf("external/head"))
        val binding = extension(cars.first(), "external/head")
        binding.bindCreatedResources("template", "external/head", version)
        assertEquals("template", binding.resourceType)
        assertEquals("external/head", binding.carModelName)
        val profile = binding.resolveCarControl(TrainModelUpdatePolicy.SNAPSHOT,
            { error("Created binding already owns its captured source snapshot") },
            { error("Creation-time control must not follow live packages") })
        assertEquals(8_000.0, profile!!.mass)
        val pinnedFacts = binding.resolveCandidateFacts(TrainModelUpdatePolicy.SNAPSHOT,
            { error("Pinned candidate query already owns source snapshot") }, { error("Do not read live source") })
        assertEquals("external", pinnedFacts!!.modelPackageId)
        val changed = source.copy(control = TrainControlConfig(maxSpeed = 7.0))
        val liveFacts = binding.resolveCandidateFacts(TrainModelUpdatePolicy.FOLLOW_PACKAGE,
            { error("Live candidate query cannot read pinned source") }) { version.copy(carPackages = mapOf("external/head" to changed)) }
        assertEquals(7.0, liveFacts!!.maximumSpeedMetersPerSecond)

    }

    private fun extension(car: Car, name: String, hash: String? = null) = CarExtension().also {
        it.initialize(car, mapOf("BUKKIT" to SavedCarRenderState(carModelName = name, packageSnapshotHash = hash)))
    }

    private fun snapshot(name: String, mass: Double, maximumSpeed: Double = TrainControlConfig().maxSpeed) = SavedTrainPackageSnapshot.build(
        packageName = name,
        actions = emptyMap(),
        behavior = null,
        control = TrainControlConfig(maxSpeed = maximumSpeed),
        carControls = mapOf("$name/head" to CarControlConfig(mass = mass))
    )

    private fun withCars(action: (List<Car>) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
                    val train = createTrainWithConfig("sources", rail, world, listOf(10.0, 10.0))
                    action(train.cars)
                }
            }
        }
    }
}
