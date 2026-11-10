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

    @Test fun `registration retirement cancels late result and waits for visual cleanup after provider callback exits`() {
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
            assertFalse(retired.toCompletableFuture().isDone, "retirement must await the real worker callback exit")
            release.countDown()
            retired.toCompletableFuture().get(2, TimeUnit.SECONDS)
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

    @Test fun `session opens once coalesces samples and publishes a completed draw before latest callback finishes`() {
        val service = DefaultPartExtensionService()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val nextEntered = CountDownLatch(1); val nextRelease = CountDownLatch(1)
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val ticks = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val registration = service.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession {
                opens.incrementAndGet()
                return object : PartExtensionRenderSession {
                    override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
                        ticks += input.elapsedTicks
                        if (ticks.size == 1) { entered.countDown(); awaitIgnoringInterrupt(release) }
                        else { nextEntered.countDown(); awaitIgnoringInterrupt(nextRelease) }
                        return PartExtensionFrame(listOf(PartExtensionText(Component.text(input.elapsedTicks))))
                    }
                    override fun close() { closes.incrementAndGet() }
                }
            }
        })
        val attachment = service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        try {
            attachment.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            attachment.request(PartExtensionRenderInput(elapsedTicks = 10))
            attachment.request(PartExtensionRenderInput(elapsedTicks = 20))
            release.countDown(); assertTrue(nextEntered.await(2, TimeUnit.SECONDS))
            assertEquals(Component.text(0L), awaitFrame(attachment).texts.single().text)
            assertEquals(listOf(0L, 20L), ticks)
            val close = attachment.closeAsync().toCompletableFuture()
            assertFalse(close.isDone)
            nextRelease.countDown(); close.get(2, TimeUnit.SECONDS)
            assertNull(attachment.poll())
            assertEquals(1, opens.get()); assertEquals(1, closes.get())
        } finally { release.countDown(); nextRelease.countDown(); registration.close(); service.close() }
    }

    @Test fun `late open is closed after callback exit and shutdown drains idle session closes`() {
        val service = DefaultPartExtensionService()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val registration = service.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession {
                entered.countDown(); awaitIgnoringInterrupt(release)
                return object : PartExtensionRenderSession {
                    override fun draw(input: PartExtensionRenderInput) = error("retired open must never draw")
                    override fun close() { closed.incrementAndGet() }
                }
            }
        })
        val attachment = service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        attachment.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val shutdown = service.closeAsync().toCompletableFuture()
        assertFalse(shutdown.isDone)
        release.countDown(); shutdown.get(2, TimeUnit.SECONDS)
        assertEquals(1, closed.get()); assertNull(attachment.poll())
        registration.close()

        val idleService = DefaultPartExtensionService()
        val idleClosed = java.util.concurrent.atomic.AtomicInteger()
        idleService.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext) = object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() { idleClosed.incrementAndGet() }
            }
        })
        val idle = idleService.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        idle.start(); awaitFrame(idle)
        idleService.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
        assertEquals(1, idleClosed.get())
    }

    @Test fun `service shutdown retains registration retirement while later idle sessions still need worker close`() {
        val service = DefaultPartExtensionService()
        val pausedVisualRetirement = CountDownLatch(1)
        val releaseVisualRetirement = CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val registration = service.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext) = object : PartExtensionRenderSession {
                override fun draw(input: PartExtensionRenderInput) = PartExtensionFrame.EMPTY
                override fun close() { closes.incrementAndGet() }
            }
        })
        val first = service.createAttachment(TYPE, context()) {
            pausedVisualRetirement.countDown()
            awaitIgnoringInterrupt(releaseVisualRetirement)
            CompletableFuture.completedFuture(null)
        }!!
        val second = service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        first.start(); second.start()
        awaitFrame(first); awaitFrame(second)
        val closer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val registrationClose = closer.submit<java.util.concurrent.CompletionStage<Void>> { registration.closeAsync() }
            assertTrue(pausedVisualRetirement.await(2, TimeUnit.SECONDS))
            val shutdown = service.closeAsync().toCompletableFuture()
            assertFalse(shutdown.isDone, "removed registration must remain owned by service shutdown")
            val executor = DefaultPartExtensionService::class.java.getDeclaredField("workers")
                .also { it.isAccessible = true }.get(service) as ThreadPoolExecutor
            assertFalse(executor.isShutdown, "later idle session close must still be schedulable")
            releaseVisualRetirement.countDown()
            registrationClose.get(2, TimeUnit.SECONDS).toCompletableFuture().get(2, TimeUnit.SECONDS)
            shutdown.get(2, TimeUnit.SECONDS)
            assertEquals(2, closes.get())
            assertTrue(executor.isShutdown)
        } finally {
            releaseVisualRetirement.countDown()
            closer.shutdownNow()
            service.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `unavailable session never reopens and input owns nested JSON independently`() {
        val nested = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("name" to kotlinx.serialization.json.JsonPrimitive("captured"))
        val input = PartExtensionRenderInput(JsonObject(mapOf("train" to JsonObject(nested))))
        nested["name"] = kotlinx.serialization.json.JsonPrimitive("changed")
        assertEquals("{\"train\":{\"name\":\"captured\"}}", input.data.toString())
        assertThrows(IllegalArgumentException::class.java) { PartExtensionRenderInput(elapsedTicks = -1) }
        val service = DefaultPartExtensionService()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        service.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession? { opens.incrementAndGet(); return null }
        })
        val attachment = service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        try {
            attachment.start()
            awaitCondition { opens.get() == 1 && !attachment.canRender }
            repeat(10) { attachment.request(PartExtensionRenderInput(elapsedTicks = it.toLong())) }
            attachment.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS)
            assertEquals(1, opens.get())
        } finally { service.close() }
    }

    @Test fun `draw failures report once per episode then recover on later input without reopening`() {
        val reports = java.util.concurrent.atomic.AtomicInteger()
        val draws = java.util.concurrent.atomic.AtomicInteger()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val service = DefaultPartExtensionService { _, _ -> reports.incrementAndGet(); Unit }
        service.register(owner(), object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession {
                opens.incrementAndGet()
                return object : PartExtensionRenderSession {
                    override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
                        draws.incrementAndGet()
                        if (input.elapsedTicks != 20L) error("draw failed")
                        return PartExtensionFrame.EMPTY
                    }
                    override fun close() = Unit
                }
            }
        })
        val attachment = service.createAttachment(TYPE, context()) { CompletableFuture.completedFuture(null) }!!
        try {
            attachment.start(); awaitCondition { reports.get() == 1 }
            attachment.request(PartExtensionRenderInput(elapsedTicks = 10)); awaitCondition { draws.get() == 2 }
            attachment.request(PartExtensionRenderInput(elapsedTicks = 20)); awaitFrame(attachment)
            assertEquals(1, reports.get())
            attachment.request(PartExtensionRenderInput(elapsedTicks = 30)); awaitCondition { reports.get() == 2 }
            assertEquals(1, opens.get())
        } finally { attachment.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS); service.close() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition(), "worker did not reach expected state")
    }

    private fun context() = PartExtensionRenderContext("car-source", "pinned-hash",
        PartExtensionPart("foreign/install", 1, JsonObject(emptyMap())), PartExtensionResources(files = mapOf("screen.xml" to "pinned XML")))
    private fun renderer(prepare: (PartExtensionRenderContext) -> PartExtensionFrame) = object : PartExtensionRenderer {
        override val typeId = TYPE
        override fun validate(context: PartExtensionPackage) = Unit
        override fun open(context: PartExtensionRenderContext) = object : PartExtensionRenderSession {
            override fun draw(input: PartExtensionRenderInput) = prepare.invoke(context)
            override fun close() = Unit
        }
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
