package work.art1st.javarailways.bukkit.render.model

import kotlinx.serialization.json.JsonObject
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.extension.*
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.render.backend.*
import work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExtensionPartModelTest {
    @Test fun `late frame mounts on current root with held group transform and replays child before parent passenger packet`() = fixture { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registration = f.register { entered.countDown(); release.await(); f.frame() }
        val group = f.group()
        val composite = f.composite(group)
        try {
            composite.setAnimationScenes(mapOf("position" to "raised"))
            composite.updateState(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)))
            f.driveUntil(composite) { entered.count == 0L }
            composite.showTo(f.player)
            f.calls.clear()
            composite.updateRootPosition(Vector3d(50.0, 0.0, 0.0))
            release.countDown()
            f.awaitApplied(composite, 50.0)
            assertEquals(50.0, f.descriptors.single().location.x)
            assertEquals(5f, f.visuals.single().transformationValue!!.translation.x())
            assertEquals(1f, f.visuals.single().transformationValue!!.translation.y())
            assertFalse(f.calls.contains("text-show"))
            f.dispatcher.drain()
            val childShow = f.calls.indexOf("text-show")
            val parentPacket = f.calls.lastIndexOf("root-passengers")
            assertTrue(childShow >= 0 && parentPacket > childShow)
            val traversed = mutableListOf<AbstractModel>(); composite.forEachModelRecursive(traversed::add)
            assertEquals(listOf(group, f.child), traversed, "generated text must not change behavior animation slot ordering")
            val close = registration.closeAsync()
            assertEquals(1, f.visuals.single().destroys)
            assertEquals(0, f.child.destroyed)
            assertEquals(0, f.root.destroys)
            close.toCompletableFuture().join()
            composite.updateRootPosition(Vector3d(60.0, 0.0, 0.0))
            assertEquals(1, f.descriptors.size)
        } finally { release.countDown(); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `removed viewer membership suppresses queued late show and owner close never removes ordinary children`() = fixture { f ->
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group())
        try {
            composite.showTo(f.player)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            assertTrue(f.dispatcher.actions.isNotEmpty())
            composite.hideFrom(f.player)
            f.calls.clear()
            f.dispatcher.drain()
            assertFalse(f.calls.contains("text-show"))
            registration.closeAsync().toCompletableFuture().join()
            assertEquals(0, f.child.destroyed)
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `same UUID replacement viewer gets a new token and stale player hide cannot revoke it`() = fixture { f ->
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group())
        val replacement = f.viewer(VIEWER)
        try {
            composite.showTo(f.player)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            // Replace membership before the old queued show runs. Direct group hide avoids the
            // pre-existing composite/root UUID audience policy outside this attachment boundary.
            val group = mutableListOf<ExtensionPartModel>()
            composite.forEachModelRecursive { if (it is ExtensionPartModel) group += it }
            group.single().showVisualsTo(replacement)
            val beforeStaleHide = f.calls.toList()
            group.single().hideVisualsFrom(f.player)
            assertEquals(beforeStaleHide, f.calls)
            f.calls.clear()
            f.dispatcher.drain()
            assertFalse(f.calls.contains("text-show"), "old viewer token must not expose anything")
            group.single().showVisualsTo(replacement)
            assertTrue(f.calls.contains("text-show"), "stale hide must leave replacement membership active")
        } finally { registration.close(); composite.destroy().toCompletableFuture().join() }
    }

    @Test fun `destroy before worker completion rejects late frame and replacement requires a new group`() = fixture { f ->
        val oldGroup = f.group() // Provider not installed yet: permanently a normal group for this build.
        val oldComposite = f.composite(oldGroup)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registration = f.register { entered.countDown(); while (true) try { release.await(); break } catch (_: InterruptedException) { }; f.frame() }
        oldComposite.updateState(CompactTransform())
        assertEquals(1L, entered.count)
        oldComposite.destroy().toCompletableFuture().join()
        val composite = f.composite(f.group())
        try {
            composite.updateState(CompactTransform())
            f.driveUntil(composite) { entered.count == 0L }
            val retired = composite.destroy().toCompletableFuture()
            assertFalse(retired.isDone)
            release.countDown()
            retired.get(2, TimeUnit.SECONDS)
            composite.updateRootPosition(Vector3d(99.0, 0.0, 0.0))
            assertTrue(f.descriptors.isEmpty())
        } finally { release.countDown(); registration.close() }
    }

    @Test fun `partial create or mount failure retains group and close waits for real rollback`() {
        for (failMount in listOf(false, true)) fixture { f ->
            val cleanup = CompletableFuture<Void>()
            f.pendingCleanup = cleanup
            f.failCreateAt = if (failMount) -1 else 2
            f.root.failMount = failMount
            val registration = f.register { PartExtensionFrame(listOf(f.text("fixed"), f.text("second"))) }
            val composite = f.composite(f.group())
            try {
                composite.updateState(CompactTransform())
                f.awaitAttempted(composite)
                assertEquals(1, f.visuals.single().destroys)
                val close = registration.closeAsync()
                assertFalse(close.toCompletableFuture().isDone)
                assertEquals(0, f.child.destroyed)
                cleanup.complete(null)
                close.toCompletableFuture().join()
            } finally { cleanup.complete(null); registration.close(); composite.destroy().toCompletableFuture().join() }
        }
    }

    @Test fun `reentrant owner retirement during create cannot finish before its returned visual is rolled back`() = fixture { f ->
        val cleanup = CompletableFuture<Void>(); f.pendingCleanup = cleanup
        val registration = f.register { f.frame() }
        var close: CompletionStage<Void>? = null
        f.onCreate = { close = registration.closeAsync(); assertFalse(close!!.toCompletableFuture().isDone) }
        val composite = f.composite(f.group())
        try {
            composite.updateState(CompactTransform())
            f.awaitAttempted(composite)
            assertEquals(1, f.visuals.single().destroys)
            assertFalse(close!!.toCompletableFuture().isDone)
            cleanup.complete(null)
            close!!.toCompletableFuture().join()
        } finally { cleanup.complete(null); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `source ticks throttle serial redraw and same count updates text and pose without rebuilding leaves`() = fixture { f ->
        val next = java.util.concurrent.atomic.AtomicReference(f.frame())
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 100)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            assertEquals(listOf(0L), f.inputs.map { it.elapsedTicks })
            composite.updateExtensionInput(JsonObject(emptyMap()), 109)
            assertEquals(1, f.inputs.size)
            next.set(PartExtensionFrame(listOf(PartExtensionText(Component.text("updated"), ModelPose(4.0,0.0,0.0,0.0,0.0,0.0,1.0), key = "fixed"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 110)
            f.driveUntil(composite) { f.visuals.single().textUpdates == 1 }
            assertEquals(Component.text("updated"), f.visuals.single().textValue)
            assertEquals(7f, f.visuals.single().transformationValue!!.translation.x())
            assertEquals(1, f.descriptors.size)
            val transforms = f.calls.count { it == "text-transform" }
            composite.updateExtensionInput(JsonObject(emptyMap()), 120)
            f.driveUntil(composite) { f.inputs.size == 3 }
            // Wait until the completed result has been consumed as well.
            repeat(10) { composite.updateRootPosition(Vector3d()); Thread.sleep(2) }
            assertEquals(1, f.visuals.single().textUpdates)
            assertEquals(transforms, f.calls.count { it == "text-transform" })
            assertEquals(listOf(0L,10L,20L), f.inputs.map { it.elapsedTicks })
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `dynamic growth and EMPTY preserve original children and await obsolete visual cleanup`() = fixture { f ->
        val cleanup = CompletableFuture<Void>(); f.pendingCleanup = cleanup
        val next = java.util.concurrent.atomic.AtomicReference(f.frame())
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 0)
            composite.updateState(CompactTransform()); f.awaitApplied(composite)
            f.pendingCleanup = null
            next.set(PartExtensionFrame(listOf(f.text("fixed"), f.text("second"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 10)
            f.driveUntil(composite) { f.descriptors.size == 2 }
            assertEquals(0, f.visuals.first().destroys)
            assertEquals(0, f.child.destroyed)
            next.set(PartExtensionFrame.EMPTY)
            composite.updateExtensionInput(JsonObject(emptyMap()), 20)
            f.driveUntil(composite) { f.visuals.all { it.destroys == 1 } }
            val close = registration.closeAsync().toCompletableFuture()
            assertFalse(close.isDone, "obsolete resources must remain in the retirement barrier")
            cleanup.complete(null); close.get(2, TimeUnit.SECONDS)
            assertEquals(0, f.child.destroyed)
        } finally { cleanup.complete(null); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `replacement creation failure retains previously successful text and later sample can recover`() = fixture { f ->
        val next = java.util.concurrent.atomic.AtomicReference(f.frame())
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 0)
            composite.updateState(CompactTransform()); f.awaitApplied(composite)
            f.failCreateAt = 3
            next.set(PartExtensionFrame(listOf(f.text("fixed"), f.text("second"), f.text("third"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 10)
            f.driveUntil(composite) { f.attempts == 3 }
            assertEquals(0, f.visuals.first().destroys)
            assertEquals(1, f.visuals[1].destroys)
            f.failCreateAt = -1
            composite.updateExtensionInput(JsonObject(emptyMap()), 20)
            f.driveUntil(composite) { f.descriptors.size == 4 }
            assertEquals(0, f.visuals.first().destroys)
            assertEquals(0, f.visuals.last().destroys)
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `context reset retains successful labels and their queued replay while replacement waits fails and recovers`() = fixture { f ->
        val cleanup = CompletableFuture<Void>(); f.pendingCleanup = cleanup
        val fail = java.util.concurrent.atomic.AtomicBoolean(false)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val failed = CountDownLatch(1)
        val next = java.util.concurrent.atomic.AtomicReference(f.frame())
        val registration = f.register {
            if (block.get()) { entered.countDown(); release.await() }
            if (fail.get()) {
                failed.countDown()
                error("new context draw failed")
            }
            next.get()
        }
        val composite = f.composite(f.group())
        try {
            composite.showTo(f.player)
            composite.updateExtensionInput(JsonObject(emptyMap()), 100)
            composite.updateState(CompactTransform()); f.awaitApplied(composite)
            val retained = f.visuals.single()
            composite.invalidateExtensionContext()
            assertEquals(0, retained.destroys)
            f.calls.clear(); f.dispatcher.drain()
            assertTrue(f.calls.contains("text-show"), "queued replay must still expose the retained current entity")
            block.set(true); fail.set(true)
            composite.updateExtensionInput(JsonObject(emptyMap()), 101)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, f.inputs.last().elapsedTicks)
            assertEquals(0, retained.destroys, "waiting for new context must keep the last successful frame")
            release.countDown()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            composite.updateRootPosition(Vector3d())
            assertEquals(0, retained.destroys, "failed new context must keep the last successful frame")
            assertEquals(1, f.descriptors.size)
            fail.set(false); block.set(false); f.pendingCleanup = null
            next.set(PartExtensionFrame(listOf(f.text("fixed", "new context"), f.text("second"))))
            composite.invalidateExtensionContext()
            composite.updateExtensionInput(JsonObject(emptyMap()), 102)
            f.driveUntil(composite) { f.descriptors.size == 2 }
            assertEquals(Component.text("new context"), retained.textValue)
            assertEquals(0, retained.destroys, "replacement must reconcile the existing stable key")
            assertEquals(2L, f.inputs.last().elapsedTicks, "semantic reset preserves original clock origin")
            assertEquals(1, f.opens.get(), "semantic reset must reuse the private compiled session")
            val retired = registration.closeAsync().toCompletableFuture()
            assertEquals(listOf(1, 1), f.visuals.map { it.destroys }, "retirement still immediately removes all generated visuals")
            assertFalse(retired.isDone, "retained frame cleanup remains owned")
            cleanup.complete(null); retired.get(2, TimeUnit.SECONDS)
            assertEquals(0, f.child.destroyed)
        } finally { release.countDown(); cleanup.complete(null); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `context and style refresh reject obsolete completed queued and running frames while preserving successful output`() {
        for (styleChange in listOf(false, true)) fixture { f ->
            val prepared = CountDownLatch(1)
            val oldEntered = CountDownLatch(1); val oldRelease = CountDownLatch(1)
            val currentEntered = CountDownLatch(1); val currentRelease = CountDownLatch(1)
            val draws = java.util.concurrent.atomic.AtomicInteger()
            val registration = f.register {
                when (draws.getAndIncrement()) {
                    0 -> f.frame()
                    1 -> PartExtensionFrame(listOf(f.text("obsolete-completed"))).also { prepared.countDown() }
                    2 -> {
                        oldEntered.countDown(); oldRelease.await()
                        PartExtensionFrame(listOf(f.text("obsolete-running")))
                    }
                    3 -> {
                        currentEntered.countDown(); currentRelease.await()
                        PartExtensionFrame(listOf(f.text("fixed", "current")))
                    }
                    else -> error("Unexpected extra draw")
                }
            }
            val composite = f.composite(f.group())
            try {
                composite.updateExtensionInput(JsonObject(emptyMap()), 100)
                composite.updateState(CompactTransform()); f.awaitApplied(composite)
                f.dispatcher.drain()
                val retained = f.visuals.single()
                composite.updateExtensionInput(JsonObject(emptyMap()), 110)
                assertTrue(prepared.await(2, TimeUnit.SECONDS))
                composite.updateExtensionInput(JsonObject(emptyMap()), 120)
                // This serial callback proves the prior result was published; deliberately do not
                // run a model commit until the completed result has been invalidated.
                assertTrue(oldEntered.await(2, TimeUnit.SECONDS))
                composite.updateExtensionInput(JsonObject(emptyMap()), 130) // Queued obsolete request.
                if (styleChange) {
                    f.renderRevision.incrementAndGet()
                    registration.refreshRendering()
                } else {
                    composite.invalidateExtensionContext()
                    composite.updateExtensionInput(JsonObject(emptyMap()), 131)
                }
                composite.updateRootPosition(Vector3d())
                assertEquals(1, f.descriptors.size, "completed obsolete generation must not replace the retained frame")
                assertEquals(0, retained.destroys)

                oldRelease.countDown()
                assertTrue(currentEntered.await(2, TimeUnit.SECONDS))
                assertEquals(if (styleChange) 30L else 31L, f.inputs.last().elapsedTicks,
                    "the obsolete queued request must be discarded in favor of the current generation")
                composite.updateRootPosition(Vector3d())
                assertEquals(1, f.descriptors.size, "running obsolete generation must not replace the retained frame")
                assertEquals(0, retained.textUpdates)
                assertEquals(0, retained.destroys)

                currentRelease.countDown()
                f.driveUntil(composite) { retained.textUpdates == 1 }
                assertEquals(Component.text("current"), retained.textValue)
                assertEquals(1, f.descriptors.size)
                assertEquals(0, retained.destroys)
                assertEquals(4, draws.get())
            } finally {
                oldRelease.countDown(); currentRelease.countDown()
                composite.destroy().toCompletableFuture().join(); registration.close()
            }
        }
    }

    @Test fun `hidden group warms without drawing then resumes latest source time and settings refresh needs no movement`() = fixture { f ->
        val limit = java.util.concurrent.atomic.AtomicReference(0.0)
        f.audiencePolicy = PartExtensionAudiencePolicy { limit.get() }
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 100)
            composite.updateState(CompactTransform())
            f.driveUntil(composite) { f.opens.get() == 1 }
            f.dispatcher.drain()
            assertTrue(f.inputs.isEmpty(), "warm open must not evaluate default invisible output")
            composite.updateExtensionInput(JsonObject(emptyMap()), 150)
            assertTrue(f.inputs.isEmpty())
            limit.set(1.0)
            registration.refreshAudience(VIEWER)
            f.dispatcher.drain()
            f.awaitApplied(composite)
            assertEquals(50L, f.inputs.last().elapsedTicks)
            f.dispatcher.drain()
            limit.set(0.0)
            registration.refreshAudience(VIEWER)
            f.dispatcher.drain()
            val draws = f.inputs.size
            composite.updateExtensionInput(JsonObject(emptyMap()), 160)
            assertEquals(draws, f.inputs.size)
            limit.set(1.0)
            registration.refreshAudience(VIEWER)
            f.dispatcher.drain()
            f.driveUntil(composite) { f.inputs.size > draws }
            assertEquals(60L, f.inputs.last().elapsedTicks)
        } finally { registration.close(); composite.destroy().toCompletableFuture().join() }
    }

    @Test fun `distance uses screen offset and root only motion refreshes stationary viewer with coalesced tasks`() = fixture { f ->
        f.audiencePolicy = PartExtensionAudiencePolicy { 1.0 }
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group()) // Eye x3, screen x3, car center x0.
        try {
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            f.dispatcher.drain()
            f.calls.clear()
            composite.updateRootPosition(Vector3d(50.0, 0.0, 0.0))
            composite.updateRootPosition(Vector3d(60.0, 0.0, 0.0))
            assertEquals(1, f.dispatcher.actions.size, "one refresh per viewer despite repeated root movement")
            f.dispatcher.drain()
            assertTrue(f.calls.contains("text-hide"))
            f.calls.clear()
            composite.updateRootPosition(Vector3d())
            f.dispatcher.drain()
            assertTrue(f.calls.contains("text-show"), "screen returns to the stationary eye at x3")
        } finally { registration.close(); composite.destroy().toCompletableFuture().join() }
    }

    @Test fun `style revision retains old frame until successful replacement and EMPTY explicitly clears it`() = fixture { f ->
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val next = java.util.concurrent.atomic.AtomicReference(f.frame())
        val registration = f.register {
            if (block.get()) { entered.countDown(); release.await() }
            next.get()
        }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 100)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            val retained = f.visuals.single()
            registration.refreshRendering()
            assertEquals(0, retained.destroys, "same effective revision must keep output")
            block.set(true)
            next.set(PartExtensionFrame(listOf(f.text("fixed", "new style"))))
            f.renderRevision.incrementAndGet()
            registration.refreshRendering()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            composite.updateRootPosition(Vector3d())
            assertEquals(0, retained.destroys, "changed selection must keep output while its replacement is pending")
            assertEquals(0, retained.textUpdates)
            release.countDown()
            f.driveUntil(composite) { retained.textUpdates == 1 }
            assertEquals(Component.text("new style"), retained.textValue)
            assertEquals(1, f.descriptors.size, "successful replacement must reuse matching keys")
            assertEquals(0L, f.inputs.last().elapsedTicks, "style selection preserves source clock")
            assertEquals(1, f.opens.get())

            block.set(false); next.set(PartExtensionFrame.EMPTY)
            f.renderRevision.incrementAndGet()
            registration.refreshRendering()
            f.driveUntil(composite) { retained.destroys == 1 }
            assertEquals(1, f.descriptors.size, "successful EMPTY removes the retained frame without creating visuals")
            assertEquals(0, f.child.destroyed)
        } finally { release.countDown(); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `stable keys preserve visual identities across blink reorder growth and shrink`() = fixture { f ->
        val next = java.util.concurrent.atomic.AtomicReference(PartExtensionFrame(listOf(
            f.text("background"), f.text("label"), f.text("dot", "●"))))
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 0)
            composite.updateState(CompactTransform())
            f.driveUntil(composite) { f.visuals.size == 3 }
            f.dispatcher.drain()
            val original = f.visuals.toList()
            f.calls.clear()
            next.set(PartExtensionFrame(listOf(f.text("background"), f.text("label"), f.text("dot", ""))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 10)
            f.driveUntil(composite) { original[2].textUpdates == 1 }
            f.dispatcher.drain()
            assertEquals(Component.empty(), original[2].textValue)
            assertEquals(listOf(0, 0, 1), original.map { it.textUpdates })
            assertTrue(f.calls.none { it.startsWith("text-") || it == "root-mount" || it == "root-passengers" },
                "blink only changes the dot metadata; no mount, show, transform or destruction: ${f.calls}")

            next.set(PartExtensionFrame(listOf(f.text("dot", ""), f.text("new"), f.text("label"), f.text("background"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 20)
            f.driveUntil(composite) { f.visuals.size == 4 }
            f.dispatcher.drain()
            assertEquals(listOf(0, 0, 0), original.map { it.destroys })
            assertEquals(1, f.calls.count { it == "text-show" }, "only the new visual is published")
            val addition = f.visuals.last()
            f.calls.clear()

            next.set(PartExtensionFrame(listOf(f.text("label"), f.text("dot", ""), f.text("new"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 30)
            f.driveUntil(composite) { original[0].destroys == 1 }
            f.dispatcher.drain()
            assertEquals(4, f.visuals.size)
            assertEquals(listOf(1, 0, 0), original.map { it.destroys })
            assertEquals(0, addition.destroys)
            assertEquals(listOf(0, 0, 1), original.map { it.textUpdates })
            assertFalse(f.calls.contains("root-mount"))
            assertFalse(f.calls.contains("text-show"))
            assertFalse(f.calls.contains("text-transform"))
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `failed metadata mutation rolls back reused texts and retains successful frame while addition cleanup completes`() = fixture { f ->
        val next = java.util.concurrent.atomic.AtomicReference(PartExtensionFrame(listOf(f.text("first"), f.text("second"))))
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        val cleanup = CompletableFuture<Void>()
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 0)
            composite.updateState(CompactTransform())
            f.driveUntil(composite) { f.visuals.size == 2 }
            f.dispatcher.drain()
            val first = f.visuals[0]; val second = f.visuals[1]
            second.failTextOnce = true
            f.pendingCleanup = cleanup
            next.set(PartExtensionFrame(listOf(f.text("first", "changed"), f.text("second", "changed"), f.text("new"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 10)
            f.driveUntil(composite) { f.visuals.size == 3 && f.visuals.last().destroys == 1 }
            assertEquals(Component.text("first"), first.textValue, "successful earlier mutations are rolled back")
            assertEquals(0, first.destroys)
            assertEquals(0, second.destroys)
            val closed = registration.closeAsync().toCompletableFuture()
            assertFalse(closed.isDone, "rollback cleanup remains in the real retirement barrier")
            cleanup.complete(null)
            closed.get(2, TimeUnit.SECONDS)
        } finally { cleanup.complete(null); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `queued addition replay survives a newer frame retaining the same element`() = fixture { f ->
        val next = java.util.concurrent.atomic.AtomicReference(PartExtensionFrame(listOf(f.text("retained", "old"))))
        val registration = f.register { next.get() }
        val composite = f.composite(f.group())
        try {
            composite.updateExtensionInput(JsonObject(emptyMap()), 0)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            val retained = f.visuals.single()
            assertFalse(f.calls.contains("text-show"))
            assertTrue(f.dispatcher.actions.isNotEmpty())

            next.set(PartExtensionFrame(listOf(f.text("other"), f.text("retained", "latest"))))
            composite.updateExtensionInput(JsonObject(emptyMap()), 10)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (f.visuals.size < 2 && System.nanoTime() < deadline) {
                composite.updateRootPosition(Vector3d())
                Thread.sleep(2) // Deliberately leave the first frame's player replay queued.
            }
            assertEquals(2, f.visuals.size)
            assertEquals(Component.text("latest"), retained.textValue)
            assertFalse(f.calls.contains("text-show"))
            f.calls.clear()
            f.dispatcher.drain()
            assertEquals(2, f.calls.count { it == "text-show" }, "both retained pending and new elements must be shown")
            assertEquals(0, retained.destroys)
            assertEquals(Component.text("latest"), retained.textValue)
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `frame rejects duplicate and invalid stable keys before any rendering`() {
        assertThrows(IllegalArgumentException::class.java) { PartExtensionText(Component.empty(), key = " ") }
        assertThrows(IllegalArgumentException::class.java) { PartExtensionText(Component.empty(), key = "x".repeat(1025)) }
        val text = PartExtensionText(Component.empty(), key = "same")
        assertThrows(IllegalArgumentException::class.java) { PartExtensionFrame(listOf(text, text)) }
        assertEquals(1024, PartExtensionText(Component.empty(), key = "x".repeat(1024)).key.length)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally {
            f.service.close()
            f.world.physicsCoordinator.close()
            (f.world.tickManager as AutoCloseable).close()
        }
    }

    private class Fixture {
        val world = createTestWorld()
        val service = DefaultPartExtensionService()
        val calls = mutableListOf<String>()
        val descriptors = mutableListOf<DisplayVisualDescriptor>()
        val visuals = mutableListOf<Visual>()
        val inputs = java.util.concurrent.CopyOnWriteArrayList<PartExtensionRenderInput>()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val renderRevision = java.util.concurrent.atomic.AtomicLong()
        val dispatcher = QueuedDispatcher()
        val bundles = TrainPacketBundleCoordinator { _, _ -> }
        val worldId = UUID.randomUUID()
        val bukkitWorld = proxy(World::class.java) { name, _ -> if (name == "getUID") worldId else error("Unexpected world call $name") }
        var eyeX = 3.0
        var audiencePolicy = PartExtensionAudiencePolicy.UNRESTRICTED
        fun viewer(id: UUID) = proxy(Player::class.java) { name, _ -> when (name) {
            "getUniqueId" -> id
            "getEyeLocation" -> Location(bukkitWorld, eyeX, 0.0, 0.0)
            else -> error(name)
        } }
        val player = viewer(VIEWER)
        val root = Visual("root", calls)
        val child = Child(world, calls)
        var pendingCleanup: CompletableFuture<Void>? = null
        var failCreateAt = -1
        var attempts = 0
        var onCreate: (() -> Unit)? = null
        val backend = object : DisplayRenderBackend {
            override fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual {
                attempts++
                if (attempts == failCreateAt) error("creation failed")
                descriptors += descriptor
                onCreate?.invoke()
                return Visual("text", calls, pendingCleanup).also(visuals::add)
            }
        }
        fun register(prepare: () -> PartExtensionFrame) = service.register(proxy(Plugin::class.java) { name, _ -> when (name) {
            "isEnabled" -> true; "getName" -> "StaticTest"; else -> null
        } }, object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession {
                opens.incrementAndGet()
                return object : PartExtensionRenderSession {
                    override val audiencePolicy = this@Fixture.audiencePolicy
                    override val renderRevision: Long get() = this@Fixture.renderRevision.get()
                    override fun draw(input: PartExtensionRenderInput): PartExtensionFrame { inputs += input; return prepare.invoke() }
                    override fun close() = Unit
                }
            }
        })
        fun text(key: String, value: String = key) = PartExtensionText(Component.text(value),
            ModelPose(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), key = key)
        fun frame() = PartExtensionFrame(listOf(text("fixed")))
        fun group() = ExtensionPartModel(world, ModelState(CompactTransform(position = Vector3d(3.0, 0.0, 0.0))),
            listOf(child), listOf(PartAnimationConfig("position", keyframes = listOf(
                PartAnimationKeyframeConfig(0.0, "base"), PartAnimationKeyframeConfig(1.0, "raised", offset = Vec3Config(y = 1.0)))))
                .toRuntimeAnimationMap(), TYPE,
            PartExtensionRenderContext("car-source", "pinned", PartExtensionPart("foreign/install", 1, JsonObject(emptyMap())), null),
            service, backend, dispatcher, bundles, 2f)
        fun composite(group: ExtensionPartModel, show: Boolean = true) =
            CompositeModel(world, listOf(group), root, bukkitWorld).also { if (show) it.showTo(player) }
        fun awaitApplied(composite: CompositeModel, x: Double = 0.0) = drive(composite, x) { descriptors.isNotEmpty() }
        fun awaitAttempted(composite: CompositeModel) = drive(composite, 0.0) { attempts > 0 }
        fun driveUntil(composite: CompositeModel, done: () -> Boolean) = drive(composite, 0.0, done)
        private fun drive(composite: CompositeModel, x: Double, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!done() && System.nanoTime() < deadline) {
                composite.updateRootPosition(Vector3d(x, 0.0, 0.0))
                // Resolve warm-open/distance refresh before drawing, but leave a completed frame's
                // queued audience replay for each test to inspect and drain explicitly.
                if (!done()) dispatcher.drain()
                Thread.sleep(2)
            }
            assertTrue(done(), "worker frame was not consumed by owned root commit")
        }
    }

    private class Child(world: work.art1st.javarailways.core.api.physics.World, private val calls: MutableList<String>) :
        AbstractModel(world, ModelState(CompactTransform()), emptyList(), emptyMap()) {
        var destroyed = 0
        override fun collectDestruction(steps: DestructionSteps) { destroyed++ }
        override fun showVisualsTo(player: Player) { calls += "ordinary-show" }
        override fun hideVisualsFrom(player: Player) { calls += "ordinary-hide" }
    }

    private class Visual(val name: String, val calls: MutableList<String>, val pending: CompletableFuture<Void>? = null) : DisplayVisual {
        var destroys = 0
        var failMount = false
        var transformationValue: Transformation? = null
        override fun showTo(player: Player) { calls += "$name-show" }
        override fun hideFrom(player: Player) { calls += "$name-hide" }
        override fun teleport(location: Location) { calls += "$name-move" }
        override fun setTransformation(transformation: Transformation) { transformationValue = transformation; calls += "$name-transform" }
        override fun setItemStack(itemStack: ItemStack) = error("Not an item test")
        var textValue: Component? = null
        var textUpdates = 0
        var failTextOnce = false
        override fun setText(text: Component) {
            if (failTextOnce) { failTextOnce = false; error("text mutation failed") }
            textValue = text; textUpdates++
        }
        override fun setBrightness(blockLight: Int, skyLight: Int) = Unit
        override fun clearBrightness() = Unit
        override fun addPassenger(passenger: DisplayVisual): Boolean { calls += "$name-mount"; return !failMount }
        override fun refreshPassengersFor(player: Player) { calls += "$name-passengers" }
        override fun destroy(): CompletionStage<Void> { destroys++; return pending ?: CompletableFuture.completedFuture(null) }
    }
    private class QueuedDispatcher : PlatformDispatcher by EntityCleanupDispatcher() {
        val actions = mutableListOf<() -> Unit>()
        override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean { actions += action; return true }
        fun drain() { val pending = actions.toList(); actions.clear(); pending.forEach { it() } }
    }
    private companion object {
        const val TYPE = "test:static"
        val VIEWER: UUID = UUID.randomUUID()
        fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "hashCode" -> System.identityHashCode(instance); "equals" -> instance === args?.get(0)
                "toString" -> type.simpleName; else -> call(method.name, args)
            } })
    }
}
