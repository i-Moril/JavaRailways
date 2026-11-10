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
import simpledisplays.api.DisplayContext
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
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
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
            val session = optional.javaClass.getMethod("openTemplate", PartExtensionRenderContext::class.java,
                DisplayPartConfig::class.java, String::class.java, DisplayTemplateSource::class.java)
                .invoke(optional, renderContext(), DisplayPartConfig("cabin", true, 2.0, 2.0), "main", DisplayTemplateSource("screen.xml", "XML")) as PartExtensionRenderSession?
            assertNull(session)
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
    fun `session compiles once draws structured data and time repeatedly and preserves physical fitting`() {
        val fixture = Fixture()
        val styled = Component.text("Destination").font(Key.key("custom:font"))
        val contexts = mutableListOf<DisplayContext>()
        val compiled = Template(width = 256.0, height = 128.0, contexts = { ctx ->
            contexts.add(ctx)
            listOf(TextDraw(64.0, 16.0, 16.0, styled, false))
        })
        val sources = mutableListOf<String>()
        fixture.compileTemplate = { sources.add(it); compiled }
        val optional = OptionalSimpleDisplays(fixture.host)
        optional.start()
        val session = requireNotNull(optional.openTemplate(renderContext(), DisplayPartConfig("cabin", true, 2.0, 2.0), "main", DisplayTemplateSource("captured.xml", "pinned source")))
        val first = Json.parseToJsonElement("""{"train":{"name":"First","speedMetersPerSecond":12.5,"type":null},"car":{"index":0},"trainName":"First","flags":[true,false]}""").jsonObject
        val second = Json.parseToJsonElement("""{"train":{"name":"Second"},"trainName":"Second"}""").jsonObject
        val frame = session.draw(PartExtensionRenderInput(first, 0))
        session.draw(PartExtensionRenderInput(second, 20))
        assertEquals(listOf("pinned source"), sources)
        assertEquals(listOf(0L,20L), contexts.map { it.elapsedTicks })
        assertEquals("First", (contexts[0].data["train"] as Map<*, *>)["name"])
        assertEquals(12.5, (contexts[0].data["train"] as Map<*, *>)["speedMetersPerSecond"])
        assertNull((contexts[0].data["train"] as Map<*, *>)["type"])
        assertEquals(listOf(true,false), contexts[0].data["flags"])
        assertEquals("Second", contexts[1].data["trainName"])
        assertNull(contexts[0].placeholderResolver)
        val text = frame.texts.single()
        assertEquals(styled, text.text)
        assertEquals(-0.5, text.pose.x(), 1e-12); assertEquals(0.25, text.pose.y(), 1e-12)
        val expected = 16.0 * (2.0 / 256.0) * 40.0 / 9.0
        assertEquals(expected,text.scale.x(),1e-12); assertEquals(expected,text.scale.y(),1e-12); assertEquals(expected,text.scale.z(),1e-12)
        assertEquals(0, compiled.closes)
        session.close(); session.close()
        assertEquals(1, compiled.closes)
        assertTrue(session.draw(PartExtensionRenderInput()).texts.isEmpty())
        optional.close()
        assertEquals(styled, frame.texts.single().text)
        assertEquals(0, fixture.serviceCloses)
    }

    @Test
    fun `failed draw retains reusable session and original contextual cause`() {
        val fixture = Fixture()
        val original = IllegalArgumentException("missing train name")
        var tries = 0
        val compiled = Template(contexts = {
            if (++tries == 1) throw original
            listOf(TextDraw(0.0,0.0,1.0,Component.text("recovered"),false))
        })
        fixture.compileTemplate = { compiled }
        val optional = OptionalSimpleDisplays(fixture.host); optional.start()
        val session = requireNotNull(optional.openTemplate(renderContext(), DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("captured.xml","source")))
        val failure = assertThrows<IllegalArgumentException> { session.draw(PartExtensionRenderInput()) }
        assertSame(original, failure.cause)
        listOf("fleet","pinned-hash","cabin-lcd","main","captured.xml","missing train name").forEach { assertTrue(failure.message.orEmpty().contains(it)) }
        assertEquals(0,compiled.closes)
        assertEquals(Component.text("recovered"),session.draw(PartExtensionRenderInput(elapsedTicks=10)).texts.single().text)
        session.close(); optional.close(); assertEquals(1,compiled.closes)
    }

    @Test
    fun `invalid geometry rejects a frame and idle close reports its terminal cleanup failure`() {
        val fixture = Fixture(); val optional = OptionalSimpleDisplays(fixture.host); optional.start()
        val templates = listOf(
            Template(width=Double.NaN), Template(width=0.0), Template(width=Double.MIN_VALUE,height=Double.MIN_VALUE),
            Template(contexts={listOf(TextDraw(Double.NaN,0.0,1.0,Component.empty(),false))}),
            Template(contexts={listOf(TextDraw(0.0,0.0,-1.0,Component.empty(),false))}),
            Template(contexts={(0..1024).map{TextDraw(0.0,0.0,1.0,Component.empty(),false)}}),
        )
        templates.forEach { compiled ->
            fixture.compileTemplate={compiled}
            val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
            assertThrows<IllegalArgumentException>{session.draw(PartExtensionRenderInput())}
            assertEquals(0,compiled.closes);session.close();assertEquals(1,compiled.closes)
        }
        val cleanup=IllegalStateException("cannot close")
        val compiled=Template(contexts={emptyList()},closeAction={throw cleanup})
        fixture.compileTemplate={compiled}
        val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
        session.draw(PartExtensionRenderInput())
        assertSame(cleanup,assertThrows<IllegalStateException>{session.close()})
        assertSame(cleanup,assertThrows<IllegalStateException>{session.close()})
        assertEquals(1,compiled.closes);optional.close()
    }

    @Test
    fun `connection retirement during drawing defers cleanup and suppresses late output`() {
        val fixture=Fixture();val entered=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
        val compiled=Template(contexts={entered.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS));listOf(TextDraw(0.0,0.0,1.0,Component.text("late"),false))})
        fixture.compileTemplate={compiled};val optional=OptionalSimpleDisplays(fixture.host);optional.start()
        val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",true,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending=worker.submit<PartExtensionFrame>{session.draw(PartExtensionRenderInput())}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));optional.close();assertEquals(0,compiled.closes)
            proceed.countDown();assertTrue(pending.get(5,java.util.concurrent.TimeUnit.SECONDS).texts.isEmpty())
            session.close();assertEquals(1,compiled.closes);assertEquals(0,fixture.serviceCloses)
        }finally{proceed.countDown();worker.shutdownNow();optional.close()}
    }

    @Test
    fun `late open after connection retirement closes new template without a session`() {
        val fixture=Fixture();val entered=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
        val compiled=Template();fixture.compileTemplate={entered.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS));compiled}
        val optional=OptionalSimpleDisplays(fixture.host);optional.start();val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending=worker.submit<PartExtensionRenderSession?>{optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source"))}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));optional.close();proceed.countDown()
            assertNull(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,compiled.closes)
        }finally{proceed.countDown();worker.shutdownNow();optional.close()}
    }

    @Test
    fun `retired draw failure retains deferred cleanup suppression and later close observes failure`() {
        val fixture=Fixture();val optional=OptionalSimpleDisplays(fixture.host);optional.start()
        val original=IllegalArgumentException("draw failed");val cleanup=IllegalStateException("cleanup failed")
        val compiled=Template(contexts={optional.close();throw original},closeAction={throw cleanup})
        fixture.compileTemplate={compiled}
        val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
        val failure=assertThrows<IllegalArgumentException>{session.draw(PartExtensionRenderInput())}
        assertSame(original,failure.cause);assertEquals(listOf(cleanup),original.suppressed.toList())
        assertSame(cleanup,assertThrows<IllegalStateException>{session.close()});assertEquals(1,compiled.closes)
    }

    @Test
    fun `host close observes cleanup already owned by concurrent idle connection retirement`() {
        val fixture=Fixture();val optional=OptionalSimpleDisplays(fixture.host);optional.start()
        val entered=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
        val cleanup=IllegalStateException("idle cleanup failed")
        val compiled=Template(closeAction={entered.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS));throw cleanup})
        fixture.compileTemplate={compiled}
        val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
        val worker=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val retirement=worker.submit { optional.close() }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val hostClose=worker.submit { session.close() }
            proceed.countDown()
            assertSame(cleanup,assertThrows<java.util.concurrent.ExecutionException>{retirement.get(5,java.util.concurrent.TimeUnit.SECONDS)}.cause)
            assertSame(cleanup,assertThrows<java.util.concurrent.ExecutionException>{hostClose.get(5,java.util.concurrent.TimeUnit.SECONDS)}.cause)
            assertEquals(1,compiled.closes)
        }finally{proceed.countDown();worker.shutdownNow();optional.close()}
    }

    @Test
    fun `host session close during draw defers private template destruction until invocation exits`() {
        val fixture=Fixture();val entered=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
        val compiled=Template(contexts={entered.countDown();check(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS));emptyList()})
        fixture.compileTemplate={compiled};val optional=OptionalSimpleDisplays(fixture.host);optional.start()
        val session=requireNotNull(optional.openTemplate(renderContext(),DisplayPartConfig("cabin",false,1.0,1.0),"main",DisplayTemplateSource("main.xml","source")))
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending=worker.submit<PartExtensionFrame>{session.draw(PartExtensionRenderInput())}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));session.close();assertEquals(0,compiled.closes)
            proceed.countDown();pending.get(5,java.util.concurrent.TimeUnit.SECONDS);session.close();assertEquals(1,compiled.closes)
        }finally{proceed.countDown();worker.shutdownNow();optional.close()}
    }

    private class Template(
        override val width: Double = 1.0,
        override val height: Double = 1.0,
        private val draws: (Map<String, String>, Long) -> List<TextDraw> = { _, _ -> error("Validation must not draw") },
        private val contexts: ((DisplayContext) -> List<TextDraw>)? = null,
        private val closeAction: () -> Unit = {},
    ) : DisplayTemplate {
        var closes = 0
        override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> = draws(values, elapsedTicks)
        override fun draw(context: DisplayContext): List<TextDraw> = contexts?.invoke(context) ?: draws(context.data.filterValues { it is String }.mapValues { it.value as String },context.elapsedTicks)
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
