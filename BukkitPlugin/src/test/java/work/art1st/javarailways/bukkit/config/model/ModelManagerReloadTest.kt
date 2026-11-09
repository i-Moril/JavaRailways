package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `bad type retains its last good catalog while another type updates`() {
        val first = writePackage("first", speed = 20.0)
        writePackage("second", speed = 21.0)
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val oldFirst = manager.trainPackages.getValue("first")
        val oldVersion = manager.resourceVersion("first")
        first.resolve("control.yml").writeText("config-version: 999")
        writePackage("second", speed = 30.0)

        val prepared = manager.prepareReload()
        assertEquals(setOf("first"), prepared.failures.keys)
        assertEquals(setOf("second"), prepared.versions.keys)
        assertEquals(21.0, manager.getTrainControl("second").maxSpeed)
        val result = manager.publishReload(prepared)

        assertEquals(setOf("first"), result.failures.keys)
        assertEquals(result.failures, manager.lastReloadFailures)
        assertSame(oldFirst, manager.trainPackages["first"])
        assertSame(oldFirst.train, manager.getTrain("first"))
        assertSame(oldVersion, manager.resourceVersion("first"))
        assertEquals(30.0, manager.getTrainControl("second").maxSpeed)
        assertEquals(oldVersion.packageSnapshot, manager.createTrainPackageSnapshot("first"))
    }

    @Test
    fun `reload rejection keeps that whole type on its old prepared version`() {
        writePackage("first", speed = 20.0)
        writePackage("second", speed = 21.0)
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val old = manager.resourceVersion("first")
        writePackage("first", speed = 30.0)
        writePackage("second", speed = 31.0)
        val prepared = manager.prepareReload()
        val failure = IllegalStateException("active controller rejected")
        manager.publishReload(prepared, mapOf("first" to failure))

        assertSame(old, manager.resourceVersion("first"))
        assertEquals(20.0, manager.getTrainControl("first").maxSpeed)
        assertEquals(31.0, manager.resourceVersion("second").packageSnapshot.control.maxSpeed)
        assertSame(failure, manager.lastReloadFailures["first"])
        assertThrows<IllegalStateException> { manager.publishReload(prepared) }
    }

    @Test
    fun `broken source maps to its previous manifest name rather than directory name`() {
        val dir = writePackage("actual-type", directory = "distribution-directory")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val old = manager.resourceVersion("actual-type")
        dir.resolve("manifest.yml").writeText("config-version: 999\nname: actual-type\ntype: train\ncomposition: []")
        var result = manager.publishReload(manager.prepareReload())
        assertEquals(setOf("actual-type"), result.failures.keys)
        assertSame(old, manager.resourceVersion("actual-type"))

        dir.deleteRecursively()
        result = manager.publishReload(manager.prepareReload())
        assertEquals(setOf("actual-type"), result.failures.keys)
        assertSame(old, manager.resourceVersion("actual-type"))
    }

    @Test
    fun `all model and control snapshots use qualified car keys including external composition`() {
        writePackage("first", composition = listOf("first/head", "second/head"))
        writePackage("second")
        val manager = ModelManager(dataFolder)
        val prepared = manager.prepareReload()
        val version = prepared.versions.getValue("first")
        assertEquals(setOf("first/head", "second/head"), version.models.keys)
        assertEquals("fleet/head", prepared.trainPackages.getValue("first").cars.getValue("head").name)
        assertEquals("first/head", prepared.trainPackages.getValue("first").cars.getValue("head").resourceName)
        assertEquals(version.models.keys, version.packageSnapshot.carControls.keys)
        assertEquals("first", version.carPackages.getValue("first/head").packageName)
        assertEquals("second", version.carPackages.getValue("second/head").packageName)
        assertEquals(setOf("second/head"), version.carPackages.getValue("second/head").carControls.keys)
        assertThrows<UnsupportedOperationException> { (version.models as MutableMap).clear() }
        assertEquals(emptyMap<String, LoadedTrainPackage>(), manager.trainPackages)
        manager.publishReload(prepared)
        assertSame(version, manager.resourceVersion("first"))
    }

    @Test
    fun `candidate cross package wiring never mutates retained published registries`() {
        val first = writePackage("first")
        val second = writePackage("second")
        first.resolve("cars/fleet/head.yml").apply { writeText(readText().replace("body-parts: []", "body-parts: [{import: second/shape}]")) }
        second.resolve("parts/second").mkdirs()
        second.resolve("parts/second/shape.yml").writeText("config-version: 1\nname: shape\ntype: dummy\n")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val oldFirst = manager.trainPackages.getValue("first")
        val oldFallback = oldFirst.partRegistry.fallbackRegistries
        val oldPart = manager.resourceVersion("first").models.getValue("first/head").parts.getValue("second/shape")
        second.resolve("parts/second/shape.yml").writeText("config-version: 1\nname: shape\ntype: dummy\noffset: {x: 5.0}\n")
        val prepared = manager.prepareReload()
        val newPart = prepared.versions.getValue("first").models.getValue("first/head").parts.getValue("second/shape")
        assertEquals(0.0, oldPart.offset.x)
        assertEquals(5.0, newPart.offset.x)
        assertSame(oldFallback, oldFirst.partRegistry.fallbackRegistries)
        assertSame(manager.trainPackages.getValue("second").partRegistry, oldFallback["second"])
    }

    @Test
    fun `retained type creates from captured composition even when external source geometry updates`() {
        val first = writePackage("first", composition = listOf("second/head"))
        val second = writePackage("second")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val retained = manager.resourceVersion("first")
        first.resolve("control.yml").writeText("invalid: true")
        val car = second.resolve("cars/fleet/head.yml")
        car.writeText(car.readText().replace("length: 10.0", "length: 15.0").replace("mass: 40000", "mass: 50000"))
        manager.publishReload(manager.prepareReload())
        assertSame(retained, manager.resourceVersion("first"))
        assertEquals(listOf("second/head"), retained.composition)
        assertEquals(10.0, retained.models.getValue(retained.composition.single()).car.length)
        assertEquals(40000.0, retained.carPackages.getValue("second/head").carControls.getValue("second/head").mass)
        assertEquals(15.0, manager.resourceVersion("second").models.getValue("second/head").car.length)
    }

    @Test
    fun `cyclic model reload is rejected before publishing and preserves last good version`() {
        val directory = writePackage("first")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val oldPackage = manager.trainPackages.getValue("first")
        val oldVersion = manager.resourceVersion("first")
        directory.resolve("parts/fleet").mkdirs()
        directory.resolve("parts/fleet/a.yml").writeText(
            "config-version: 1\nname: a\ntype: dummy\nparts: [{import: b}]\n")
        directory.resolve("parts/fleet/b.yml").writeText(
            "config-version: 1\nname: b\ntype: dummy\nparts: [{import: a}]\n")
        directory.resolve("cars/fleet/head.yml").apply {
            writeText(readText().replace("body-parts: []", "body-parts: [{import: a}]"))
        }
        val prepared = manager.prepareReload()
        assertTrue(prepared.failures.getValue("first").message.orEmpty().contains("fleet/a -> fleet/b -> fleet/a"))
        assertTrue("first" !in prepared.versions)
        assertSame(oldPackage, manager.trainPackages["first"])
        manager.publishReload(prepared)
        assertSame(oldPackage, manager.trainPackages["first"])
        assertSame(oldVersion, manager.resourceVersion("first"))
        assertThrows<IllegalArgumentException> { ModelManager(dataFolder).loadAll() }
    }

    @Test
    fun `inline name reuse cannot hide a model reference cycle during reload`() {
        val directory = writePackage("first")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val oldVersion = manager.resourceVersion("first")
        directory.resolve("parts/fleet").mkdirs()
        directory.resolve("parts/fleet/b.yml").writeText(
            "config-version: 1\nname: b\ntype: dummy\n")
        directory.resolve("parts/fleet/a.yml").writeText("""
            config-version: 1
            name: a
            type: dummy
            parts:
              - config-version: 1
                name: b
                type: dummy
                parts: [{import: a}]
        """.trimIndent())
        directory.resolve("cars/fleet/head.yml").apply {
            writeText(readText().replace("body-parts: []", "body-parts: [{import: b}, {import: a}]"))
        }

        val prepared = manager.prepareReload()
        assertTrue(prepared.failures.getValue("first").message.orEmpty()
            .contains("fleet/a -> fleet/b -> fleet/a"))
        assertTrue("first" !in prepared.versions)
        manager.publishReload(prepared)
        assertSame(oldVersion, manager.resourceVersion("first"))
        assertThrows<IllegalArgumentException> { ModelManager(dataFolder).loadAll() }
    }

    @Test
    fun `display reload publishes captured raw sources while failures retain old catalog independently`() {
        val first = writePackage("first")
        val second = writePackage("second")
        writeDisplays(first, "first old")
        writeDisplays(second, "second old")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val old = manager.resourceVersion("first")
        first.resolve("display.xml").writeText("first new <script>raw</script>")
        val prepared = manager.prepareReload()
        val updated = prepared.versions.getValue("first")
        assertEquals("first old", old.packageSnapshot.displays.templates.getValue("main").source)
        assertEquals("first new <script>raw</script>", updated.packageSnapshot.displays.templates.getValue("main").source)
        assertTrue(old.packageSnapshot.hash != updated.packageSnapshot.hash)
        assertSame(old, manager.resourceVersion("first"))
        manager.publishReload(prepared)
        assertSame(updated, manager.resourceVersion("first"))
        // Both missing references and missing declared XML reject the candidate, never erase old resources.
        for (badConfig in listOf("config-version: 1\ntemplates: {}\ndefaults: {cabin: missing}\n",
            "config-version: 1\ntemplates: {main: {file: missing.xml}}\n")) {
            first.resolve("displays.yml").writeText(badConfig)
            val secondText = "second update $badConfig"
            second.resolve("display.xml").writeText(secondText)
            val candidate = manager.prepareReload()
            assertEquals(setOf("first"), candidate.failures.keys)
            manager.publishReload(candidate)
            assertSame(updated, manager.resourceVersion("first"))
            assertEquals(secondText, manager.resourceVersion("second").packageSnapshot.displays.templates.getValue("main").source)
            assertEquals("first old", old.packageSnapshot.displays.templates.getValue("main").source)
        }
    }

    @Test
    fun `external source cars capture their own package displays rather than composition defaults`() {
        val first = writePackage("first", composition = listOf("first/head", "second/head"))
        val second = writePackage("second")
        writeDisplays(first, "composition XML", "first-category")
        writeDisplays(second, "source XML", "second-category")
        val manager = ModelManager(dataFolder)
        manager.loadAll()
        val version = manager.resourceVersion("first")
        val main = version.packageSnapshot.displays
        val firstCar = version.carPackages.getValue("first/head").displays
        val secondCar = version.carPackages.getValue("second/head").displays
        assertEquals("composition XML", main.templates.getValue("main").source)
        assertEquals(main, firstCar)
        assertEquals("source XML", secondCar.templates.getValue("main").source)
        assertEquals(mapOf("second-category" to "main"), secondCar.defaults)
        assertTrue("first-category" !in secondCar.defaults)
        second.resolve("display.xml").writeText("new source XML")
        manager.publishReload(manager.prepareReload())
        assertEquals("source XML", secondCar.templates.getValue("main").source)
        assertEquals("new source XML", manager.resourceVersion("first").carPackages.getValue("second/head").displays.templates.getValue("main").source)
    }

    private fun writeDisplays(directory: File, source: String, category: String = "cabin") {
        directory.resolve("display.xml").writeText(source)
        directory.resolve("displays.yml").writeText("config-version: 1\ntemplates: {main: {file: display.xml}}\ndefaults: {$category: main}\n")
    }

    private fun writePackage(name: String, speed: Double = 20.0, directory: String = name,
        composition: List<String> = listOf("$name/head")): File {
        val dir = File(dataFolder, "trains/$directory").apply { mkdirs() }
        dir.resolve("manifest.yml").writeText("config-version: 2\nname: $name\ntype: train\ncomposition: [${composition.joinToString(", ")}]\n")
        dir.resolve("control.yml").writeText("""
            config-version: 1
            max-speed: $speed
            curve-speed-limit-offset: 0.0
            controller: {power-notches: [P1], brake-notches: [B1]}
            traction: {notch-scales: {P1: 1.0}}
            brake: {apply-time: 1.0, release-time: 1.0, notch-scales: {B1: 1.0}}
            resistance: {a: 0.0, b: 0.0, c: 0.0}
            adhesion: {coefficient: 0.2}
            jerk: {traction: 1.0, brake: 1.0}
        """.trimIndent())
        dir.resolve("cars/fleet").mkdirs()
        dir.resolve("cars/fleet/head.yml").writeText("""
            config-version: 1
            name: head
            length: 10.0
            floor-height: 1.0
            connector-length-a: 0.1
            connector-length-b: 0.1
            control:
              mass: 40000
              rotating-mass-factor: 1.1
              motor: {enabled: true, max-force: 80000, max-power: 1000000, adhesion-mass-ratio: 1.0}
              brake: {max-force: 50000, regenerative-force: 10000, adhesion-mass-ratio: 1.0}
            body-parts: []
            bogies: []
        """.trimIndent())
        return dir
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
