package work.art1st.javarailways.bukkit.api.internal

import kotlinx.serialization.json.*
import org.bukkit.plugin.Plugin
import org.bukkit.event.server.PluginDisableEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.api.model.extension.*
import work.art1st.javarailways.bukkit.config.model.*
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultPartExtensionServiceTest {
    @TempDir lateinit var directory: File

    @Test fun `missing provider emits one diagnostic per package type while preserving raw data`() {
        val diagnostics = mutableListOf<String>()
        val service = DefaultPartExtensionService(reportDiagnostic = diagnostics::add)
        val manager = ModelManager(directory, service)
        writePackage("first", "kept"); manager.loadAll()
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.single().contains("unknown:part"))
        assertEquals("kept", manager.resourceVersion("first").packageSnapshot.extensions.getValue(TYPE).files.getValue("value.txt"))
        service.close()
    }

    @Test fun `late provider reports each invalid package without deleting data and validates future reload candidates`() {
        val reports = mutableListOf<String>()
        val service = DefaultPartExtensionService { subject, _ -> reports += subject }
        val manager = ModelManager(directory, service)
        val file = writePackage("first", "bad")
        writePackage("second", "bad")
        manager.loadAll() // No provider: the opaque payload and raw resources remain usable.
        val baseline = manager.resourceVersion("first")
        val calls = mutableListOf<PartExtensionPackage>()
        val registration = service.register(owner(), validator { context ->
            calls += context
            require(context.resources!!.files.getValue("value.txt") == "good") { "resource rejected" }
        })
        assertEquals(setOf("first", "second"), registration.existingPackageFailures.keys)
        assertEquals(2, reports.size)
        assertSame(baseline, manager.resourceVersion("first"))
        assertEquals("bad", baseline.packageSnapshot.extensions.getValue(TYPE).files.getValue("value.txt"))
        assertTrue(calls.all { it.parts.isNotEmpty() })
        val rejected = manager.prepareReload()
        assertEquals(setOf("first", "second"), rejected.failures.keys)
        manager.publishReload(rejected)
        assertSame(baseline, manager.resourceVersion("first"))
        file.resolve("addon/value.txt").writeText("good")
        val prepared = manager.prepareReload()
        assertEquals(setOf("second"), prepared.failures.keys)
        manager.publishReload(prepared)
        assertEquals("good", manager.resourceVersion("first").packageSnapshot.extensions.getValue(TYPE).files.getValue("value.txt"))
        assertEquals("bad", baseline.packageSnapshot.extensions.getValue(TYPE).files.getValue("value.txt"))
        registration.close(); service.close()
    }

    @Test fun `duplicate registration rejects while close and disabled owner withdraw exact callbacks`() = BukkitServiceTestSupport.withServer<Unit> {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        writePackage("first", "raw"); manager.loadAll()
        val firstOwner = owner()
        var calls = 0
        val first = service.register(firstOwner, validator { calls++ })
        assertEquals(1, calls)
        assertThrows<IllegalArgumentException> { service.register(owner(), validator {}) }
        first.close()
        service.validatePackage(manager.trainPackages.getValue("first"))
        assertEquals(1, calls)
        val nextOwner = owner()
        val replacement = service.register(nextOwner, validator { calls++ })
        first.close() // An old registration cannot revoke its replacement.
        service.validatePackage(manager.trainPackages.getValue("first"))
        assertEquals(3, calls)
        PartExtensionOwnerLifecycleListener(service).onPluginDisable(PluginDisableEvent(nextOwner))
        service.validatePackage(manager.trainPackages.getValue("first"))
        assertEquals(3, calls)
        replacement.close(); service.close()
        assertThrows<IllegalStateException> { service.register(owner(), validator {}) }
        assertThrows<IllegalArgumentException> { DefaultPartExtensionService().register(owner { false }, validator {}) }
    }

    @Test fun `registration or retirement during prepare rejects stale publication without callback under model lock`() {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        writePackage("first", "raw"); manager.loadAll()
        val baseline = manager.resourceVersion("first")
        val pending = manager.prepareReload()
        val registration = service.register(owner(), validator {})
        assertThrows<IllegalStateException> { manager.publishReload(pending) }
        assertSame(baseline, manager.resourceVersion("first"))
        val underProvider = manager.prepareReload()
        registration.close()
        assertThrows<IllegalStateException> { manager.publishReload(underProvider) }
        service.close()
    }

    @Test fun `callbacks do not hold registry lock and owner retirement prevents later invocation`() {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        writePackage("first", "raw"); manager.loadAll()
        val pluginOwner = owner()
        val executor = Executors.newSingleThreadExecutor()
        var calls = 0
        try {
            service.register(pluginOwner, validator {
                calls++
                executor.submit { service.unregisterOwner(pluginOwner) }.get(2, TimeUnit.SECONDS)
            })
            service.validatePackage(manager.trainPackages.getValue("first"))
            assertEquals(1, calls)
        } finally { executor.shutdownNow(); service.close() }
    }

    @Test fun `same name distinct inline extension occurrences and exact package resources reach provider`() {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        val first = PartDefinition("test/same", PartType.EXTENSION, extension = PartExtensionPayload(TYPE, config = buildJsonObject { put("value", "first") }))
        val second = first.copy(extension = PartExtensionPayload(TYPE, config = buildJsonObject { put("value", "second") }))
        val car = CarDefinition("test/car", bodyParts = listOf(PartRefEntry(first.name, inline = first), PartRefEntry(second.name, inline = second)))
        manager.trainPackages["test"] = LoadedTrainPackage(
            PackageManifest("test", PackageType.TRAIN, composition = emptyList()), PartRegistry(File(directory, "parts")),
            mapOf("car" to car), TrainDefinition("test", "test", emptyList()), control = TrainControlConfig(),
            extensions = mapOf(TYPE to PartExtensionResources(files = mapOf("source.xml" to "own package"))))
        val registration = service.register(owner(), validator {
            assertEquals(listOf("first", "second"), it.parts.map { part -> part.config.getValue("value").jsonPrimitive.content })
            assertEquals("own package", it.resources!!.files.getValue("source.xml"))
            assertThrows<UnsupportedOperationException> { (it.parts as java.util.List<*>).clear() }
            assertThrows<UnsupportedOperationException> { (it.parts[0].config as java.util.Map<*, *>).clear() }
        })
        assertTrue(registration.existingPackageFailures.isEmpty())
        registration.close(); service.close()
    }

    @Test fun `validation ignores unused native missing and cyclic named imports`() {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        val pkg = writePackage("first", "kept")
        pkg.resolve("parts/unused.yml").writeText("config-version: 1\nname: unused\ntype: dummy\nparts: [{import: missing}]\n")
        pkg.resolve("parts/cycle.yml").writeText("config-version: 1\nname: cycle\ntype: dummy\nparts: [{import: cycle}]\n")
        manager.loadAll()
        var calls = 0
        service.register(owner(), validator { calls++ })
        assertEquals(1, calls)
        assertTrue(manager.prepareReload().failures.isEmpty())
        service.close()
    }

    @Test fun `disabled owner observed during lookup never receives callback and public contexts detach mutable JSON`() {
        val service = DefaultPartExtensionService()
        val manager = ModelManager(directory, service)
        writePackage("first", "raw"); manager.loadAll()
        var enabled = true
        var calls = 0
        val pluginOwner = owner { enabled }
        service.register(pluginOwner, validator { calls++ })
        enabled = false
        service.validatePackage(manager.trainPackages.getValue("first"))
        assertEquals(1, calls)
        val source = mutableMapOf<String, JsonElement>("value" to JsonArray(mutableListOf(JsonPrimitive("kept"))))
        val part = PartExtensionPart("name", 1, JsonObject(source))
        val parts = mutableListOf(part)
        val context = PartExtensionPackage("pkg", parts, null)
        source.clear(); parts.clear()
        assertEquals("kept", context.parts.single().config.getValue("value").jsonArray.single().jsonPrimitive.content)
        assertThrows<UnsupportedOperationException> { (context.parts.single().config.getValue("value").jsonArray as java.util.List<*>).clear() }
        service.close()
    }

    private fun validator(block: (PartExtensionPackage) -> Unit) = object : PartExtensionProvider {
        override val typeId = TYPE
        override fun validate(context: PartExtensionPackage) = block(context)
    }

    private fun owner(enabled: () -> Boolean = { true }): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { proxy, method, args -> when (method.name) {
            "isEnabled" -> enabled()
            "getName" -> "TestAddon"
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "TestAddon"
            else -> null
        } } as Plugin

    private fun writePackage(name: String, raw: String): File = File(directory, "trains/$name").apply {
        mkdirs()
        resolve("manifest.yml").writeText("config-version: 2\nname: $name\ntype: train\ncomposition: []\nextensions: {unknown:part: {directory: addon}}\n")
        resolve("addon").mkdir(); resolve("addon/value.txt").writeText(raw)
        resolve("parts").mkdir(); resolve("parts/lcd.yml").writeText("config-version: 1\nname: lcd\ntype: unknown:part\nconfig: {raw: [1, unknown]}\n")
        resolve("control.yml").writeText("""
            config-version: 1
            max-speed: 20
            controller: {power-notches: [P1], brake-notches: [B1]}
            traction: {notch-scales: {P1: 1.0}}
            brake: {apply-time: 1.0, release-time: 1.0, notch-scales: {B1: 1.0}}
            resistance: {a: 0.0, b: 0.0, c: 0.0}
            adhesion: {coefficient: 0.2}
            jerk: {traction: 1.0, brake: 1.0}
        """.trimIndent())
    }

    private companion object { const val TYPE = "unknown:part" }
}
