package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.Server
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.IconFrame
import simpledisplays.api.TextDraw
import net.kyori.adventure.text.Component
import net.kyori.adventure.key.Key
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import work.art1st.javarailways.decorations.display.DisplayPartConfig
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPart
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.decorations.display.DisplayResources
import work.art1st.javarailways.decorations.display.DisplayTemplateSource
import java.lang.reflect.Proxy
import java.util.logging.Logger

class OptionalSimpleDisplaysTest {
    @Test
    fun `enabled provider discovers exact shared API registration and host close releases it`() {
        val fixture = Fixture()
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val binding = optional.connection as SimpleDisplaysBinding
        assertSame(fixture.service, binding.service)
        assertEquals(1, fixture.listeners)
        optional.close()
        assertNull(optional.connection)
        assertNull(binding.service)
        optional.close()
    }

    @Test
    fun `provider disable retires retained binding while unrelated disable preserves it`() = BukkitServiceTestSupport.withServer<Unit> {
        val fixture = Fixture()
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val binding = optional.connection as SimpleDisplaysBinding
        val template = Template()
        fixture.compileTemplate = { template }
        optional.validateTemplates("fleet", resources("XML"))
        optional.onPluginDisable(PluginDisableEvent(fixture.host))
        assertSame(binding, optional.connection)
        optional.onPluginDisable(PluginDisableEvent(fixture.owner))
        assertNull(optional.connection)
        assertNull(binding.service)
        assertEquals(1, template.closes)
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `service unregister retires exact service and provider only`() = BukkitServiceTestSupport.withServer<Unit> {
        val fixture = Fixture()
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val binding = optional.connection as SimpleDisplaysBinding
        val template = Template()
        fixture.compileTemplate = { template }
        optional.validateTemplates("fleet", resources("XML"))
        val unrelated = RegisteredServiceProvider(String::class.java, "other", ServicePriority.Normal, fixture.owner)
        optional.onServiceUnregister(ServiceUnregisterEvent(unrelated))
        assertSame(binding, optional.connection)
        val otherApiForSameProvider = RegisteredServiceProvider(Any::class.java,
            fixture.service, ServicePriority.Normal, fixture.owner)
        optional.onServiceUnregister(ServiceUnregisterEvent(otherApiForSameProvider))
        assertSame(binding, optional.connection)
        assertSame(fixture.service, binding.service)
        optional.onServiceUnregister(ServiceUnregisterEvent(fixture.registration!!))
        assertNull(optional.connection)
        assertNull(binding.service)
        assertEquals(1, template.closes)
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `missing registration and wrong owner fail closed without registering lifecycle listener`() {
        for (wrongOwner in listOf(false, true)) {
            val fixture = Fixture()
            fixture.registration = if (wrongOwner) RegisteredServiceProvider(DisplayService::class.java,
                fixture.service, ServicePriority.Normal, fixture.host) else null
            val optional = OptionalSimpleDisplays(fixture.host)
            assertDoesNotThrow { optional.start() }
            assertNull(optional.connection)
            assertEquals(0, fixture.listeners)
        }
    }

    @Test
    fun `absent and disabled provider never attempt to resolve any optional API type`() {
        for (present in listOf(false, true)) {
            val fixture = Fixture(present = present, enabled = false)
            val loader = IsolatedIntegrationLoader(blockApi = true)
            val optional = loader.loadClass(OptionalSimpleDisplays::class.java.name)
                .getConstructor(Plugin::class.java).newInstance(fixture.host)
            optional.javaClass.getMethod("start").invoke(optional)
            optional.javaClass.getMethod("validateTemplates", String::class.java, DisplayResources::class.java)
                .invoke(optional, "absent", resources("invalid but uncompiled XML"))
            val frame = optional.javaClass.getMethod("prepareTemplate", PartExtensionRenderContext::class.java,
                DisplayPartConfig::class.java, String::class.java, DisplayTemplateSource::class.java)
                .invoke(optional, renderContext(), DisplayPartConfig("cabin", true, 2.0, 2.0), "main", DisplayTemplateSource("screen.xml", "XML")) as PartExtensionFrame
            assertTrue(frame.texts.isEmpty())
            assertEquals(0, loader.apiAttempts)
            assertEquals(0, fixture.listeners)
            (optional as AutoCloseable).close()
        }
    }

    @Test
    fun `incompatible installed provider with unavailable API does not prevent host startup`() {
        val fixture = Fixture()
        val loader = IsolatedIntegrationLoader(blockApi = true)
        val optional = loader.loadClass(OptionalSimpleDisplays::class.java.name)
            .getConstructor(Plugin::class.java).newInstance(fixture.host)
        assertDoesNotThrow { optional.javaClass.getMethod("start").invoke(optional) }
        assertTrue(loader.apiAttempts > 0)
        assertEquals(0, fixture.listeners)
        (optional as AutoCloseable).close()
    }

    @Test
    fun `installed API missing compile method is rejected before registry access`() {
        val fixture = Fixture()
        val loader = IsolatedIntegrationLoader(blockApi = false, incompatibleAbi = true)
        val optional = loader.loadClass(OptionalSimpleDisplays::class.java.name)
            .getConstructor(Plugin::class.java).newInstance(fixture.host)
        assertDoesNotThrow { optional.javaClass.getMethod("start").invoke(optional) }
        assertTrue(loader.apiAttempts > 0)
        assertEquals(0, fixture.listeners)
        (optional as AutoCloseable).close()
    }

    @Test
    fun `installed API missing template close declaration is rejected before registry access`() {
        val fixture = Fixture()
        val loader = IsolatedIntegrationLoader(blockApi = false, incompatibleTemplateAbi = true)
        val optional = loader.loadClass(OptionalSimpleDisplays::class.java.name)
            .getConstructor(Plugin::class.java).newInstance(fixture.host)
        assertDoesNotThrow { optional.javaClass.getMethod("start").invoke(optional) }
        assertTrue(loader.apiAttempts > 0)
        assertEquals(0, fixture.listeners)
        (optional as AutoCloseable).close()
    }

    @Test
    fun `connection compiles exact captured sources reuses them across identities and closes on host exit`() {
        val fixture = Fixture()
        val output = mutableListOf<Template>()
        val sources = mutableListOf<String>()
        fixture.compileTemplate = { source -> sources.add(source); Template().also(output::add) }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        optional.validateTemplates("first", resources("XML"))
        optional.validateTemplates("second", DisplayResources(mapOf("another-id" to DisplayTemplateSource("elsewhere.xml", "XML"))))
        optional.validateTemplates("first", resources("changed XML"))
        assertEquals(listOf("XML", "changed XML"), sources)
        optional.close()
        assertTrue(output.all { it.closes == 1 })
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `compiler failure keeps cause package template file context and can be retried`() {
        val fixture = Fixture()
        val original = IllegalArgumentException("invalid embedded script")
        val output = mutableListOf<Template>()
        var failing = true
        fixture.compileTemplate = { source ->
            if (source == "bad" && failing) throw original
            Template().also(output::add)
        }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val displays = DisplayResources(linkedMapOf("a-good" to DisplayTemplateSource("good.xml", "good"), "b-bad" to DisplayTemplateSource("bad.xml", "bad")))
        val failure = assertThrows<IllegalArgumentException> { optional.validateTemplates("fleet", displays) }
        assertSame(original, failure.cause)
        assertTrue(failure.message.orEmpty().contains("fleet"))
        assertTrue(failure.message.orEmpty().contains("b-bad"))
        assertTrue(failure.message.orEmpty().contains("bad.xml"))
        assertTrue(failure.message.orEmpty().contains("invalid embedded script"))
        assertNotNull(optional.connection)
        assertEquals(1, output.size)
        failing = false
        optional.validateTemplates("fleet", displays)
        assertEquals(2, output.size)
        optional.close()
        assertTrue(output.all { it.closes == 1 })
    }

    @Test
    fun `static preparation fits centered physical geometry preserves text and closes its fresh template`() {
        val fixture = Fixture()
        val styled = Component.text("Destination").font(Key.key("custom:font"))
        val created = mutableListOf<Template>()
        val sources = mutableListOf<String>()
        fixture.compileTemplate = { source ->
            sources.add(source)
            Template(width = 256.0, height = 128.0, draws = { values, ticks ->
                assertTrue(values.isEmpty()); assertEquals(0L, ticks)
                listOf(TextDraw(64.0, 16.0, 16.0, styled, false))
            }).also(created::add)
        }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val template = DisplayTemplateSource("captured.xml", "exact captured source")
        val frame = optional.prepareTemplate(renderContext(), DisplayPartConfig("cabin", true, 2.0, 2.0), "main", template)
        val text = frame.texts.single()
        assertEquals(styled, text.text)
        assertEquals(-0.5, text.pose.x(), 1e-12)
        assertEquals(0.25, text.pose.y(), 1e-12)
        assertEquals(0.0, text.pose.z())
        val expectedScale = 16.0 * (2.0 / 256.0) * 40.0 / 9.0
        assertEquals(expectedScale, text.scale.x(), 1e-12)
        assertEquals(expectedScale, text.scale.y(), 1e-12)
        assertEquals(expectedScale, text.scale.z(), 1e-12)
        optional.prepareTemplate(renderContext(), DisplayPartConfig("cabin", false, 4.0, 1.0), "main", template)
        assertEquals(listOf("exact captured source", "exact captured source"), sources)
        assertEquals(2, created.size) // Runtime never borrows the evictable validation cache.
        assertTrue(created.all { it.closes == 1 })
        optional.close()
        assertEquals(styled, frame.texts.single().text) // Detached values outlive library retirement.
        assertThrows<UnsupportedOperationException> { (frame.texts as MutableList).clear() }
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `static prepare failures retain original cause context and suppressed cleanup error`() {
        val fixture = Fixture()
        val drawFailure = IllegalStateException("missing required train data")
        val cleanupFailure = IllegalArgumentException("close failed")
        val compiled = Template(draws = { _, _ -> throw drawFailure }, closeAction = { throw cleanupFailure })
        fixture.compileTemplate = { compiled }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val failure = assertThrows<IllegalArgumentException> {
            optional.prepareTemplate(renderContext(), DisplayPartConfig("cabin", true, 1.0, 1.0), "main", DisplayTemplateSource("captured.xml", "source"))
        }
        assertSame(drawFailure, failure.cause)
        assertEquals(listOf(cleanupFailure), drawFailure.suppressed.toList())
        listOf("fleet", "pinned-hash", "cabin-lcd", "main", "captured.xml", "missing required train data").forEach {
            assertTrue(failure.message.orEmpty().contains(it), failure.message)
        }
        assertEquals(1, compiled.closes)
        optional.close()
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `static prepare closes malformed geometry and successful frame close failures reject publication`() {
        val fixture = Fixture()
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val templates = listOf(
            Template(width = Double.NaN),
            Template(width = 0.0),
            Template(width = Double.MIN_VALUE, height = Double.MIN_VALUE),
            Template(draws = { _, _ -> listOf(TextDraw(Double.NaN, 0.0, 1.0, Component.empty(), false)) }),
            Template(draws = { _, _ -> listOf(TextDraw(0.0, 0.0, -1.0, Component.empty(), false)) }),
            Template(draws = { _, _ -> listOf(TextDraw(Double.MAX_VALUE, 0.0, Double.MAX_VALUE, Component.empty(), false)) }),
            Template(draws = { _, _ -> (0..1024).map { TextDraw(0.0, 0.0, 1.0, Component.empty(), false) } }),
            Template(draws = { _, _ -> emptyList() }, closeAction = { throw IllegalStateException("cannot close") }),
        )
        templates.forEach { template ->
            fixture.compileTemplate = { template }
            assertThrows<IllegalArgumentException> {
                optional.prepareTemplate(renderContext(), DisplayPartConfig("cabin", false, 1.0, 1.0), "main", DisplayTemplateSource("main.xml", "source"))
            }
            assertEquals(1, template.closes)
        }
        optional.close()
    }

    @Test
    fun `connection retirement during worker drawing closes candidate and rejects late publication`() {
        val fixture = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val compiled = Template(draws = { _, _ ->
            entered.countDown(); check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            listOf(TextDraw(0.0, 0.0, 1.0, Component.text("late"), false))
        })
        fixture.compileTemplate = { compiled }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<PartExtensionFrame> {
                optional.prepareTemplate(renderContext(), DisplayPartConfig("cabin", true, 1.0, 1.0), "main", DisplayTemplateSource("main.xml", "source"))
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            optional.close()
            proceed.countDown()
            val failure = assertThrows<java.util.concurrent.ExecutionException> { pending.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalArgumentException)
            assertTrue(failure.cause?.message.orEmpty().contains("retired during static preparation"))
            assertEquals(1, compiled.closes)
            assertEquals(0, fixture.serviceCloses)
        } finally { proceed.countDown(); worker.shutdownNow(); optional.close() }
    }

    private class Template(
        override val width: Double = 1.0,
        override val height: Double = 1.0,
        private val draws: (Map<String, String>, Long) -> List<TextDraw> = { _, _ -> error("Validation must not draw") },
        private val closeAction: () -> Unit = {},
    ) : DisplayTemplate {
        var closes = 0
        override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> = draws(values, elapsedTicks)
        override fun close() { closes++; closeAction() }
    }

    /** Child defines real production integration bytecode and selectively hides/mutates the real API artifact. */
    private class IsolatedIntegrationLoader(private val blockApi: Boolean, private val incompatibleAbi: Boolean = false, private val incompatibleTemplateAbi: Boolean = false) : ClassLoader(OptionalSimpleDisplaysTest::class.java.classLoader) {
        var apiAttempts = 0
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (incompatibleTemplateAbi && name == "simpledisplays.api.DisplayService") {
                return findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                    val bytes = it.readBytes()
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
            if ((incompatibleAbi && name == "simpledisplays.api.DisplayService") ||
                (incompatibleTemplateAbi && name == "simpledisplays.api.DisplayTemplate")) {
                apiAttempts++
                return findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                    // Rename the real artifact's compile method in its UTF-8 constant pool entry.
                    // This is an incompatible binary fixture, not a substitute API implementation.
                    val bytes = it.readBytes()
                    val methodName = (if (incompatibleTemplateAbi) "close" else "compile").toByteArray()
                    val offset = (2..bytes.size - methodName.size).first { index ->
                        bytes[index - 2] == 0.toByte() && bytes[index - 1] == methodName.size.toByte() &&
                            methodName.indices.all { bytes[index + it] == methodName[it] }
                    }
                    bytes[offset + methodName.lastIndex] = 'x'.code.toByte()
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
            if (blockApi && name.startsWith("simpledisplays.api.")) {
                apiAttempts++
                throw ClassNotFoundException("API hidden by fixture: $name")
            }
            if (name.startsWith("work.art1st.javarailways.decorations.integration.simpledisplays.") &&
                !name.contains("Test")) {
                val loaded = findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                    val bytes = it.readBytes()
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                return loaded
            }
            return super.loadClass(name, resolve)
        }
    }

    private class Fixture(present: Boolean = true, enabled: Boolean = true) {
        var listeners = 0
        var serviceCloses = 0
        var compileTemplate: (String) -> DisplayTemplate = { error("Discovery must not compile XML") }
        val service = object : DisplayService {
            override fun compile(xml: String, icons: Map<String, List<IconFrame>>): DisplayTemplate =
                compileTemplate(xml)
            override fun close() { serviceCloses++ }
        }
        val host: Plugin
        val owner: Plugin
        var registration: RegisteredServiceProvider<DisplayService>?
        init {
            lateinit var server: Server
            host = proxy(Plugin::class.java) { name, _ -> when (name) {
                "getName" -> "Decorations"
                "isEnabled" -> true
                "getServer" -> server
                "getLogger" -> Logger.getLogger("optional-simpledisplays-test")
                else -> null
            } }
            owner = proxy(Plugin::class.java) { name, _ -> when (name) {
                "getName" -> "SimpleDisplays"
                "isEnabled" -> enabled
                else -> null
            } }
            registration = RegisteredServiceProvider(DisplayService::class.java, service, ServicePriority.Normal, owner)
            val plugins = proxy(PluginManager::class.java) { name, _ -> when (name) {
                "getPlugin" -> if (present) owner else null
                "registerEvents" -> { listeners++; null }
                else -> error("Unexpected PluginManager call: $name")
            } }
            val services = proxy(ServicesManager::class.java) { name, args -> when (name) {
                "getRegistration" -> { assertSame(DisplayService::class.java, args!![0]); registration }
                else -> error("Unexpected ServicesManager call: $name")
            } }
            server = proxy(Server::class.java) { name, _ -> when (name) {
                "getPluginManager" -> plugins
                "getServicesManager" -> services
                else -> error("Unexpected Server call: $name")
            } }
        }
    }

    private companion object {
        fun renderContext() = PartExtensionRenderContext("fleet", "pinned-hash", PartExtensionPart("cabin-lcd", 1,
            Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":1,"height":1}""").jsonObject), null)
        fun resources(source: String) = DisplayResources(mapOf("main" to DisplayTemplateSource("screen.xml", source)))
        fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                "toString" -> type.simpleName + " fixture"
                else -> call(method.name, args)
            } }
        )
    }
}
