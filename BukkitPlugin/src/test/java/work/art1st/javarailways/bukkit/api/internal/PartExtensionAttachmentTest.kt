package work.art1st.javarailways.bukkit.api.internal

import kotlinx.serialization.json.JsonObject
import net.kyori.adventure.text.Component
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.extension.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor

class PartExtensionAttachmentTest {
    @Test fun `runtime preparation executes once on host worker with exact detached source context`() {
        val service = DefaultPartExtensionService()
        val prepared = CountDownLatch(1)
        val caller = Thread.currentThread()
        var worker: Thread? = null
        var received: PartExtensionRenderContext? = null
        val registration = service.register(owner(), renderer { context ->
            received = context; worker = Thread.currentThread(); prepared.countDown()
            PartExtensionFrame(listOf(PartExtensionText(Component.text("prepared"))))
        })
        val context = context()
        val attachment = service.createAttachment(TYPE, context) { CompletableFuture.completedFuture(null) }!!
        try {
            attachment.start(); attachment.start()
            assertTrue(prepared.await(2, TimeUnit.SECONDS))
            val frame = awaitFrame(attachment)
            assertNotSame(caller, worker)
            assertTrue(worker!!.name.startsWith("JavaRailways-part-prepare-"))
            assertSame(context, received)
            assertEquals("car-source", received!!.packageName)
            assertEquals("pinned-hash", received!!.packageHash)
            assertEquals("pinned XML", received!!.resources!!.files.getValue("screen.xml"))
            assertEquals(Component.text("prepared"), frame.texts.single().text)
            assertNull(attachment.poll())
        } finally { attachment.closeAsync().toCompletableFuture().join(); registration.close(); service.close() }
    }

    @Test fun `registration retirement cancels late result and waits for visual cleanup without waiting for provider`() {
        val service = DefaultPartExtensionService()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanup = CompletableFuture<Void>()
        val registration = service.register(owner(), renderer {
            entered.countDown(); awaitIgnoringInterrupt(release)
            PartExtensionFrame(listOf(PartExtensionText(Component.text("late"))))
        })
        val attachment = service.createAttachment(TYPE, context()) { cleanup }!!
        try {
            attachment.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val retired = registration.closeAsync()
            assertFalse(attachment.isActive)
            assertFalse(retired.toCompletableFuture().isDone)
            assertSame(retired, registration.closeAsync())
            cleanup.complete(null)
            retired.toCompletableFuture().join()
            assertNull(attachment.poll())
        } finally { release.countDown(); service.close() }
    }

    @Test fun `retiring queued attachment removes it rather than keeping canceled history`() {
        val service = DefaultPartExtensionService()
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val registration = service.register(owner(), renderer {
            entered.countDown(); awaitIgnoringInterrupt(release); PartExtensionFrame.EMPTY
        })
        val attachments = (1..3).map { service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!! }
        try {
            attachments.take(2).forEach { it.start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            attachments[2].start()
            val executor = DefaultPartExtensionService::class.java.getDeclaredField("workers").also { it.isAccessible = true }.get(service) as ThreadPoolExecutor
            assertEquals(1, executor.queue.size)
            attachments[2].closeAsync().toCompletableFuture().join()
            assertEquals(0, executor.queue.size)
        } finally { registration.close(); release.countDown(); service.close() }
    }

    @Test fun `metadata only and missing providers allocate no attachment while preparation failures remain contextual`() {
        val reports = mutableListOf<String>()
        val service = DefaultPartExtensionService { subject, _ -> synchronized(reports) { reports += subject } }
        assertNull(service.createAttachment(TYPE, context()) { error("No visuals") })
        val metadata = service.register(owner(), object : PartExtensionProvider {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
        })
        assertNull(service.createAttachment(TYPE, context()) { error("No visuals") })
        metadata.close()
        val reported = CountDownLatch(1)
        val failedService = DefaultPartExtensionService { subject, _ -> synchronized(reports) { reports += subject }; reported.countDown() }
        val registration = failedService.register(owner(), renderer { error("bad XML") })
        val attachment = failedService.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        try {
            attachment.start(); assertTrue(reported.await(2, TimeUnit.SECONDS))
            assertNull(attachment.poll())
            assertTrue(reports.single().contains("car-source"))
            assertTrue(reports.single().contains("foreign/install"))
            attachment.start()
            assertEquals(1, reports.size)
        } finally { registration.close(); failedService.close(); service.close() }
    }

    private fun context() = PartExtensionRenderContext("car-source", "pinned-hash",
        PartExtensionPart("foreign/install", 1, JsonObject(emptyMap())), PartExtensionResources(files = mapOf("screen.xml" to "pinned XML")))
    private fun renderer(prepare: (PartExtensionRenderContext) -> PartExtensionFrame) = object : PartExtensionRenderer {
        override val typeId = TYPE
        override fun validate(context: PartExtensionPackage) = Unit
        override fun prepare(context: PartExtensionRenderContext) = prepare.invoke(context)
    }
    private fun owner(): Plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) {
        proxy, method, args -> when (method.name) {
            "isEnabled" -> true; "getName" -> "AttachmentTest"; "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0); else -> null
        }
    } as Plugin
    private fun awaitFrame(attachment: PartExtensionAttachment): PartExtensionFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) { attachment.poll()?.let { return it }; Thread.sleep(2) }
        error("Preparation result did not arrive")
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }
    private companion object { const val TYPE = "test:static" }
}
