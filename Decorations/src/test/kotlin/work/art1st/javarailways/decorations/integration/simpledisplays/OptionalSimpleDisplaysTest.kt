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

    private class Template : DisplayTemplate {
        var closes = 0
        override val width = 1.0
        override val height = 1.0
        override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> = error("Validation must not draw")
        override fun close() { closes++ }
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
